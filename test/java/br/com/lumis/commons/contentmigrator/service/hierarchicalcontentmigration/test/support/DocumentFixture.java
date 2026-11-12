package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture.Work;
import lumis.content.ContentManagerFactory;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.service.document.DocumentConfig;
import lumis.service.document.DocumentManagerFactory;

/**
 * Creates documents and folders in {@value #DOCUMENT_SERVICE_ID} instances through the
 * {@code default} content table source and removes them at the end.
 *
 * <p>Same lifecycle as {@link MediaFixture}: every operation runs in its own committed transaction,
 * {@link #cleanup()} removes in the inverse order of creation (children before folders) and must be
 * called before {@link PortalFixture#cleanup()}. Reading {@code lum_File} and stored bytes is done
 * with the {@link MediaFixture} static helpers, which do not depend on the repository type.
 */
public class DocumentFixture
{
	/** Document repository service. */
	public static final String DOCUMENT_SERVICE_ID = "lumis.service.document";

	/** Dependency type of a content service on its document repository. */
	public static final String DOCUMENT_REPOSITORY_DEPENDENCY = "document_repository";

	/** Content table source of the document service, rows of {@code lum_Document}. */
	public static final String DOCUMENT_SOURCE_ID = "default";

	private final Deque<Work<Void>> cleaners = new ArrayDeque<>();

	/** Adds tracked hierarchical content with a document link and optional HTML references. */
	public String addHierarchicalContent(
			PortalFixture portal, ServiceInstanceConfig instance, String title, String parentContentId, String documentId, String html) throws Exception
	{
		String contentId = portal.addHierarchicalContent(instance, title, parentContentId, true);
		Map<String, Object> data = new HashMap<>();
		data.put("type", documentId == null ? 4 : 3);
		data.put("linkDocumentId", documentId);
		data.put("linkOpeningType", 0);
		data.put("linkContent", html);
		MediaFixture.updateHierarchicalContent(instance, contentId, data);
		return contentId;
	}

	/**
	 * Adds a folder ({@code lum_Document.type} 1) to the given document instance.
	 *
	 * @param parentFolderId the parent folder's document id, or null for the root.
	 * @return the folder's document id.
	 */
	public String addFolder(ServiceInstanceConfig documentInstance, String parentFolderId, String title) throws Exception
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", title);
		data.put("type", DocumentConfig.TYPE_FOLDER);
		data.put("parentFolder", parentFolderId);
		data.put("inheritPublishTo", Boolean.TRUE);
		String folderId = PortalFixture.inTransaction((session, transaction) -> addData(session, transaction, documentInstance.getId(), data));
		track(documentInstance.getId(), folderId);
		return folderId;
	}

	/**
	 * Adds a document ({@code lum_Document.type} 0) whose file has the given name, content type and
	 * bytes.
	 *
	 * @param parentFolderId the parent folder's document id, or null for the root.
	 * @return the document id.
	 */
	public String addDocument(
			ServiceInstanceConfig documentInstance,
			String parentFolderId,
			String title,
			String description,
			String fileName,
			String contentType,
			byte[] bytes) throws Exception
	{
		Path tempDir = Files.createTempDirectory("contentmigrator-document-");
		try
		{
			Path tempFile = Files.write(tempDir.resolve(fileName), bytes);
			Map<String, Object> data = new HashMap<>();
			data.put("title", title);
			data.put("description", description);
			data.put("type", DocumentConfig.TYPE_DOCUMENT);
			data.put("parentFolder", parentFolderId);
			data.put("inheritPublishTo", Boolean.TRUE);
			data.put("documentFile", MediaFixture.fileData(fileName, contentType, tempFile.toFile()));
			String documentId = PortalFixture.inTransaction((session, transaction) ->
			{
				String itemId = addData(session, transaction, documentInstance.getId(), data);
				linkFileToContentLocale(session, transaction, itemId);
				return itemId;
			});
			track(documentInstance.getId(), documentId);
			return documentId;
		}
		finally
		{
			// FileManager copies the file only after the commit
			MediaFixture.deleteRecursively(tempDir);
		}
	}

	/**
	 * Adds a row to the {@value #DOCUMENT_SOURCE_ID} source of a document instance, in the given
	 * transaction, as the given session. Used by the fixture and by clone code under test.
	 *
	 * @return the item id, which is the {@code lum_Document.id}.
	 */
	public static String addData(SessionConfig session, ITransaction transaction, String documentInstanceId, Map<String, Object> data) throws Exception
	{
		if (session.getLocale() == null)
		{
			session.setLocale(MediaFixture.LOCALE);
		}
		return documentSource(session, transaction, documentInstanceId).addData(data).getItemId();
	}

	/**
	 * Sets {@code lum_File.contentLocaleId} of the file of a document added with a
	 * {@link MediaFixture#fileData(String, String, java.io.File)} value, which the content metadata
	 * leaves null. Call in the transaction of the add.
	 */
	public static void linkFileToContentLocale(SessionConfig session, ITransaction transaction, String documentId) throws Exception
	{
		DocumentConfig document = getDocument(session, transaction, documentId);
		String contentLocaleId = ContentManagerFactory.getContentManager().getContentVersionByItemId(documentId).getContentLocale().getId();
		ManagerFactory.getFileManager().setFileContentLocaleId(document.getDocumentFile(), contentLocaleId);
	}

	/**
	 * Registers a document created outside the fixture (for example, a clone) for removal by
	 * {@link #cleanup()}. Documents already removed are skipped.
	 */
	public void track(String documentInstanceId, String documentId)
	{
		cleaners.push((session, transaction) ->
		{
			if (getDocument(session, transaction, documentId) != null)
			{
				deleteDocument(session, transaction, documentInstanceId, documentId);
			}
			return null;
		});
	}

	/**
	 * Deletes a document or an empty folder through the source, which runs the native hooks: the
	 * {@code file} field removes its {@code lum_File} and stored file.
	 */
	public static void deleteDocument(ServiceInstanceConfig documentInstance, String documentId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			deleteDocument(session, transaction, documentInstance.getId(), documentId);
			return null;
		});
	}

	private static void deleteDocument(SessionConfig session, ITransaction transaction, String documentInstanceId, String documentId) throws Exception
	{
		documentSource(session, transaction, documentInstanceId).deleteDataByItemIds(Collections.singleton(documentId));
	}

	private static ContentTableSource documentSource(SessionConfig session, ITransaction transaction, String documentInstanceId) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(documentInstanceId, transaction);
		return (ContentTableSource) SourceFactory.createSource(DOCUMENT_SOURCE_ID, new SourceContext(session, instance, transaction));
	}

	/**
	 * Returns the {@code lum_Document} row with the given id, or null. {@code IDocumentManager.get}
	 * throws for a missing id, despite its javadoc. Call inside a transaction.
	 */
	public static DocumentConfig getDocument(SessionConfig session, ITransaction transaction, String documentId) throws Exception
	{
		try
		{
			return DocumentManagerFactory.getDocumentManager().get(session, documentId, transaction);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
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
			System.out.println("[DocumentFixture] " + PortalFixture.KEEP_TEST_DATA + "=true: keeping " + cleaners.size() + " documents");
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
				System.err.println("[DocumentFixture] cleanup step failed: " + e);
				e.printStackTrace();
			}
		}
		return failures;
	}
}
