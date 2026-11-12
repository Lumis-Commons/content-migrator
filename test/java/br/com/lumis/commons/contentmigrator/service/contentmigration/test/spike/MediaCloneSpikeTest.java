package br.com.lumis.commons.contentmigrator.service.contentmigration.test.spike;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.filesystem.PortalBaseFolder;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.service.media.Media;

/**
 * Spike of the media clone between two media repositories (docs/architecture/media-migration.md):
 * the clone gets its own {@code lum_File}, with the same bytes, name and content type, and
 * deleting the clone through the native source hooks keeps the origin file.
 *
 * <p>{@link #cloneMedia(String, ServiceInstanceConfig, String)} is the algorithm the media
 * handler is to follow: copy the stored file to a temporary file, add the media to the
 * destination source with a {@code FileDataType.Data} carrying the origin name and content type,
 * set {@code lum_File.contentLocaleId} of the new file, and delete the temporary file only after the
 * commit.
 */
public class MediaCloneSpikeTest
{
	private static final String FILE_NAME = "contentmigrator spike imagem.png";

	private static final String CONTENT_TYPE = "image/png";

	private PortalFixture fixture;

	private MediaFixture media;

	@Before
	public void setUp()
	{
		fixture = new PortalFixture();
		media = new MediaFixture();
	}

	@After
	public void tearDown()
	{
		TestCleanup.assertNoFailures(media::cleanup, fixture::cleanup);
	}

	@Test
	public void clonedMediaHasIndependentFileAndDeletingItKeepsTheOrigin() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		String originFolderId = media.addFolder(origin, null, "pasta");
		String destFolderId = media.addFolder(dest, null, "pasta");
		byte[] bytes = png();
		String originMediaId = media.addMedia(origin, originFolderId, "imagem", FILE_NAME, CONTENT_TYPE, bytes);

		String cloneId = cloneMedia(originMediaId, dest, destFolderId);
		media.track(dest.getId(), cloneId);

		String[] files = PortalFixture.inTransaction((session, transaction) ->
		{
			Media originMedia = MediaFixture.getMedia(originMediaId);
			Media clone = MediaFixture.getMedia(cloneId);
			assertNotNull("clone not found", clone);
			assertEquals(Media.TYPE_MEDIA, clone.getType());
			assertEquals(originMedia.getTitle(), clone.getTitle());
			assertEquals(destFolderId, clone.getParentFolder());
			assertEquals(originFolderId, originMedia.getParentFolder());
			assertNotEquals("clone shares the origin fileId", originMedia.getMediaFile(), clone.getMediaFile());

			FileConfig originFile = MediaFixture.getFile(session, transaction, originMedia.getMediaFile());
			FileConfig cloneFile = MediaFixture.getFile(session, transaction, clone.getMediaFile());
			assertNotNull("clone lum_File not found", cloneFile);
			assertEquals(FILE_NAME, originFile.getName());
			assertEquals(originFile.getName(), cloneFile.getName());
			assertEquals(CONTENT_TYPE, originFile.getContentType());
			assertEquals(originFile.getContentType(), cloneFile.getContentType());
			assertEquals(originFile.getFileSize(), cloneFile.getFileSize());
			assertEquals(origin.getId(), originFile.getServiceInstanceId());
			assertEquals(dest.getId(), cloneFile.getServiceInstanceId());
			assertNotEquals(originFile.getFullPath(), cloneFile.getFullPath());
			assertArrayEquals(bytes, MediaFixture.readStoredFile(originFile.getFullPath()));
			assertArrayEquals(bytes, MediaFixture.readStoredFile(cloneFile.getFullPath()));

			// the clone is a content of the destination, and its lum_File points to its locale
			ContentLocale cloneLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(cloneId).getContentLocale();
			assertEquals(dest.getId(), cloneLocale.getContent().getServiceInstanceId());
			assertEquals(cloneLocale.getId(), cloneFile.getContentLocaleId());
			return new String[]{originFile.getId(), cloneFile.getId(), cloneFile.getFullPath()};
		});
		String originFileId = files[0];
		String cloneFileId = files[1];
		String cloneFullPath = files[2];

		MediaFixture.deleteMedia(dest, cloneId);

		PortalFixture.inTransaction((session, transaction) ->
		{
			assertNull("clone not deleted", MediaFixture.getMedia(cloneId));
			assertNull("clone lum_File not deleted", MediaFixture.getFile(session, transaction, cloneFileId));
			assertFalse("clone stored file not deleted", MediaFixture.storedFileExists(cloneFullPath));

			Media originMedia = MediaFixture.getMedia(originMediaId);
			assertNotNull("origin media deleted with the clone", originMedia);
			assertEquals(originFileId, originMedia.getMediaFile());
			FileConfig originFile = MediaFixture.getFile(session, transaction, originFileId);
			assertNotNull("origin lum_File deleted with the clone", originFile);
			assertTrue("origin stored file deleted with the clone", MediaFixture.storedFileExists(originFile.getFullPath()));
			assertArrayEquals(bytes, MediaFixture.readStoredFile(originFile.getFullPath()));
			return null;
		});
	}

	/**
	 * Clones a media into the given folder of another media instance, with an independent
	 * {@code lum_File}.
	 *
	 * @return the clone's media id.
	 */
	static String cloneMedia(String originMediaId, ServiceInstanceConfig destInstance, String destFolderId) throws Exception
	{
		Path tempDir = Files.createTempDirectory("contentmigrator-media-clone-");
		try
		{
			return PortalFixture.inTransaction((session, transaction) ->
			{
				Media originMedia = MediaFixture.getMedia(originMediaId);
				FileConfig originFile = MediaFixture.getFile(session, transaction, originMedia.getMediaFile());

				// same physical file name as the origin, in a directory of its own
				String storedName = originFile.getFullPath().substring(originFile.getFullPath().lastIndexOf('/') + 1);
				Path copy = tempDir.resolve(storedName);
				try (InputStream in = PortalBaseFolder.DATA.toPortalFilePath(originFile.getFullPath()).toFile().getInputStream())
				{
					Files.copy(in, copy);
				}

				Map<String, Object> data = new HashMap<>();
				data.put("title", originMedia.getTitle());
				data.put("legend", originMedia.getLegend());
				data.put("credits", originMedia.getCredits());
				data.put("type", Media.TYPE_MEDIA);
				data.put("parentFolder", destFolderId);
				data.put("inheritPublishTo", originMedia.getInheritPublishTo());
				data.put("mediaFile", MediaFixture.fileData(originFile.getName(), originFile.getContentType(), copy.toFile()));
				String cloneId = MediaFixture.addData(session, transaction, destInstance.getId(), data);
				MediaFixture.linkFileToContentLocale(cloneId);
				return cloneId;
			});
		}
		finally
		{
			// FileManager copies the temporary file in afterCommit
			MediaFixture.deleteRecursively(tempDir);
		}
	}

	/**
	 * Returns a small PNG image.
	 */
	private static byte[] png() throws Exception
	{
		BufferedImage image = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < image.getWidth(); x++)
		{
			for (int y = 0; y < image.getHeight(); y++)
			{
				image.setRGB(x, y, new Color(x * 15, y * 30, 128).getRGB());
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
