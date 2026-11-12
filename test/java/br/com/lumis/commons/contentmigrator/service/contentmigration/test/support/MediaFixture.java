package br.com.lumis.commons.contentmigrator.service.contentmigration.test.support;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture.Work;
import lumis.content.ContentManagerFactory;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.doui.source.field.type.FileDataType;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.filesystem.PortalBaseFolder;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.service.media.Media;
import lumis.service.media.MediaManagerFactory;

/**
 * Creates media and folders in {@value PortalFixture#MEDIA_SERVICE_ID} instances through the
 * {@code default} content table source, reads their {@code lum_File} and stored bytes, and removes
 * them at the end.
 *
 * <p>Every operation runs in its own committed transaction, through
 * {@link PortalFixture#inTransaction(Work)}. Media and folders are removed by {@link #cleanup()} in
 * the inverse order of creation, so children go before their folders; call it before
 * {@link PortalFixture#cleanup()}, which removes the service instances. The system property
 * {@value PortalFixture#KEEP_TEST_DATA} keeps them, as in {@link PortalFixture}.
 */
public class MediaFixture
{
	/** Content table source of the media service, rows of {@code lum_Media}. */
	public static final String MEDIA_SOURCE_ID = "default";

	/** Locale of the media created; the impersonated session has none. */
	public static final Locale LOCALE = new Locale("pt", "BR");

	private final Deque<Work<Void>> cleaners = new ArrayDeque<>();

	/**
	 * Adds a folder ({@code lum_Media.type} 1) to the given media instance.
	 *
	 * @param parentFolderId the parent folder's media id, or null for the root.
	 * @return the folder's media id.
	 */
	public String addFolder(ServiceInstanceConfig mediaInstance, String parentFolderId, String title) throws Exception
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", title);
		data.put("type", Media.TYPE_FOLDER);
		data.put("parentFolder", parentFolderId);
		data.put("inheritPublishTo", Boolean.TRUE);
		String folderId = PortalFixture.inTransaction((session, transaction) -> addData(session, transaction, mediaInstance.getId(), data));
		track(mediaInstance.getId(), folderId);
		return folderId;
	}

	/**
	 * Adds a media ({@code lum_Media.type} 0) whose file has the given name, content type and
	 * bytes.
	 *
	 * @param parentFolderId the parent folder's media id, or null for the root.
	 * @return the media id.
	 */
	public String addMedia(ServiceInstanceConfig mediaInstance, String parentFolderId, String title, String fileName, String contentType, byte[] bytes) throws Exception
	{
		Path tempDir = Files.createTempDirectory("contentmigrator-media-");
		try
		{
			Path tempFile = Files.write(tempDir.resolve(fileName), bytes);
			Map<String, Object> data = new HashMap<>();
			data.put("title", title);
			data.put("type", Media.TYPE_MEDIA);
			data.put("parentFolder", parentFolderId);
			data.put("inheritPublishTo", Boolean.TRUE);
			data.put("mediaFile", fileData(fileName, contentType, tempFile.toFile()));
			String mediaId = PortalFixture.inTransaction((session, transaction) ->
			{
				String itemId = addData(session, transaction, mediaInstance.getId(), data);
				linkFileToContentLocale(itemId);
				return itemId;
			});
			track(mediaInstance.getId(), mediaId);
			return mediaId;
		}
		finally
		{
			// FileManager copies the file only after the commit
			deleteRecursively(tempDir);
		}
	}

	/**
	 * Adds a row to the {@value #MEDIA_SOURCE_ID} source of a media instance, in the given
	 * transaction, as the given session. Used by the fixture and by clone code under test.
	 *
	 * @return the item id, which is the {@code lum_Media.id}.
	 */
	public static String addData(SessionConfig session, ITransaction transaction, String mediaInstanceId, Map<String, Object> data) throws Exception
	{
		if (session.getLocale() == null)
		{
			session.setLocale(LOCALE);
		}
		return mediaSource(session, transaction, mediaInstanceId).addData(data).getItemId();
	}

	/**
	 * Sets {@code lum_File.contentLocaleId} of the file of a media added with a
	 * {@link #fileData(String, String, File)} value. The content metadata only sets it when the
	 * field parameter converts to the new file id, which a {@code FileDataType.Data} without file id
	 * does not. Call in the transaction of the add.
	 */
	public static void linkFileToContentLocale(String mediaId) throws Exception
	{
		Media media = MediaManagerFactory.getMediaManager().get(mediaId);
		String contentLocaleId = ContentManagerFactory.getContentManager().getContentVersionByItemId(mediaId).getContentLocale().getId();
		ManagerFactory.getFileManager().setFileContentLocaleId(media.getMediaFile(), contentLocaleId);
	}

	/**
	 * Returns the value of a {@code file} field for a new {@code lum_File}: name and content type
	 * are kept as given, and {@code file} is copied by {@code FileManager} after the commit.
	 */
	public static FileDataType.Data fileData(String name, String contentType, File file)
	{
		FileDataType.Data fileData = new FileDataType.Data();
		fileData.setName(name);
		fileData.setContentType(contentType);
		fileData.setFullPath(file.getAbsolutePath());
		return fileData;
	}

	/**
	 * Registers a media created outside the fixture (for example, a clone) for removal by
	 * {@link #cleanup()}. Media already removed are skipped.
	 */
	public void track(String mediaInstanceId, String mediaId)
	{
		cleaners.push((session, transaction) ->
		{
			if (getMedia(mediaId) != null)
			{
				deleteMedia(session, transaction, mediaInstanceId, mediaId);
			}
			return null;
		});
	}

	/**
	 * Deletes a media or an empty folder through the source, which runs the native hooks: the
	 * {@code file} field removes its {@code lum_File} and stored file.
	 */
	public static void deleteMedia(ServiceInstanceConfig mediaInstance, String mediaId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			deleteMedia(session, transaction, mediaInstance.getId(), mediaId);
			return null;
		});
	}

	private static void deleteMedia(SessionConfig session, ITransaction transaction, String mediaInstanceId, String mediaId) throws Exception
	{
		mediaSource(session, transaction, mediaInstanceId).deleteDataByItemIds(Collections.singleton(mediaId));
	}

	private static ContentTableSource mediaSource(SessionConfig session, ITransaction transaction, String mediaInstanceId) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(mediaInstanceId, transaction);
		return (ContentTableSource) SourceFactory.createSource(MEDIA_SOURCE_ID, new SourceContext(session, instance, transaction));
	}

	/**
	 * Returns the {@code lum_Media} row with the given id, or null. Call inside a transaction.
	 */
	public static Media getMedia(String mediaId) throws Exception
	{
		try
		{
			return MediaManagerFactory.getMediaManager().get(mediaId);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	/**
	 * Returns the {@code lum_File} row with the given id, or null. Call inside a transaction.
	 */
	public static FileConfig getFile(SessionConfig session, ITransaction transaction, String fileId) throws Exception
	{
		return ManagerFactory.getFileManager().get(session, fileId, transaction);
	}

	/**
	 * Returns whether the stored file of the given {@code lum_File.fullPath} exists.
	 */
	public static boolean storedFileExists(String fullPath) throws IOException
	{
		return PortalBaseFolder.DATA.toPortalFilePath(fullPath).toFile().exists();
	}

	/**
	 * Returns the bytes of the stored file of the given {@code lum_File.fullPath}.
	 */
	public static byte[] readStoredFile(String fullPath) throws IOException
	{
		return PortalBaseFolder.DATA.toPortalFilePath(fullPath).toFile().readBytes();
	}

	/**
	 * Deletes a temporary directory and its files, ignoring failures.
	 */
	public static void deleteRecursively(Path directory)
	{
		try (Stream<Path> paths = Files.walk(directory))
		{
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException e)
		{
			System.err.println("[MediaFixture] could not delete " + directory + ": " + e);
		}
	}

	/**
	 * Deletes everything this fixture created or tracked, in the inverse order of creation, each
	 * step in its own transaction. A failing step is reported and does not stop the following ones.
	 *
	 * @return the number of steps that failed.
	 */
	public int cleanup()
	{
		if (PortalFixture.keepTestData())
		{
			System.out.println("[MediaFixture] " + PortalFixture.KEEP_TEST_DATA + "=true: keeping " + cleaners.size() + " media");
			cleaners.clear();
			return 0;
		}

		int failures = 0;
		while (!cleaners.isEmpty())
		{
			try
			{
				PortalFixture.inTransaction(cleaners.pop());
			}
			catch (Exception e)
			{
				failures++;
				System.err.println("[MediaFixture] cleanup step failed: " + e);
				e.printStackTrace();
			}
		}
		return failures;
	}
}
