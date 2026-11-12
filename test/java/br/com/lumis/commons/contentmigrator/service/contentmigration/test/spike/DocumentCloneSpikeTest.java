package br.com.lumis.commons.contentmigrator.service.contentmigration.test.spike;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.DocumentFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.filesystem.PortalBaseFolder;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.service.document.DocumentConfig;
import lumis.service.document.DocumentManagerFactory;

/**
 * Spike of the document clone between two document repositories
 * (docs/architecture/document-migration.md): the clone gets its own {@code lum_File}, with the same
 * bytes, name and content type, and deleting the clone through the native source hooks keeps the
 * origin file.
 *
 * <p>{@link #cloneDocument(String, ServiceInstanceConfig, String)} is the media clone algorithm
 * with the document differences: {@code description} goes in the {@code addData} itself and there
 * is no {@code legend} nor {@code credits}.
 */
public class DocumentCloneSpikeTest
{
	private static final String FILE_NAME = "contentmigrator spike documento.txt";

	private static final String CONTENT_TYPE = "text/plain";

	private static final String FOLDER_TITLE = "pasta documentos";

	private PortalFixture fixture;

	private DocumentFixture documents;

	@Before
	public void setUp()
	{
		fixture = new PortalFixture();
		documents = new DocumentFixture();
	}

	@After
	public void tearDown()
	{
		TestCleanup.assertNoFailures(documents::cleanup, fixture::cleanup);
	}

	@Test
	public void clonedDocumentHasIndependentFileAndDeletingItKeepsTheOrigin() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, DocumentFixture.DOCUMENT_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, DocumentFixture.DOCUMENT_SERVICE_ID);
		String originFolderId = documents.addFolder(origin, null, FOLDER_TITLE);
		String destFolderId = documents.addFolder(dest, null, FOLDER_TITLE);
		byte[] bytes = ("documento do spike de clone " + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
		String originDocumentId = documents.addDocument(origin, originFolderId, "documento", "descrição do documento", FILE_NAME, CONTENT_TYPE, bytes);

		// folder reuse key of the handler: mapped parent and title, through the document manager
		String foundFolderId = PortalFixture.inTransaction(
				(session, transaction) -> DocumentManagerFactory.getDocumentManager().getFolderIdsByParentAndName(session, dest.getId(), null, FOLDER_TITLE, transaction)
						.iterator().next());
		assertEquals(destFolderId, foundFolderId);

		String cloneId = cloneDocument(originDocumentId, dest, destFolderId);
		documents.track(dest.getId(), cloneId);

		String[] files = PortalFixture.inTransaction((session, transaction) ->
		{
			DocumentConfig originDocument = DocumentFixture.getDocument(session, transaction, originDocumentId);
			DocumentConfig clone = DocumentFixture.getDocument(session, transaction, cloneId);
			assertNotNull("clone not found", clone);
			assertEquals(DocumentConfig.TYPE_DOCUMENT, clone.getType());
			assertEquals(originDocument.getTitle(), clone.getTitle());
			assertEquals("description is a source field, written by addData", originDocument.getDescription(), clone.getDescription());
			assertEquals(destFolderId, clone.getParentFolder());
			assertEquals(originFolderId, originDocument.getParentFolder());
			assertNotEquals("clone shares the origin fileId", originDocument.getDocumentFile(), clone.getDocumentFile());

			FileConfig originFile = MediaFixture.getFile(session, transaction, originDocument.getDocumentFile());
			FileConfig cloneFile = MediaFixture.getFile(session, transaction, clone.getDocumentFile());
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

			ContentLocale cloneLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(cloneId).getContentLocale();
			assertEquals(dest.getId(), cloneLocale.getContent().getServiceInstanceId());
			assertEquals(cloneLocale.getId(), cloneFile.getContentLocaleId());
			return new String[]{originFile.getId(), cloneFile.getId(), cloneFile.getFullPath()};
		});
		String originFileId = files[0];
		String cloneFileId = files[1];
		String cloneFullPath = files[2];

		DocumentFixture.deleteDocument(dest, cloneId);

		PortalFixture.inTransaction((session, transaction) ->
		{
			assertNull("clone not deleted", DocumentFixture.getDocument(session, transaction, cloneId));
			assertNull("clone lum_File not deleted", MediaFixture.getFile(session, transaction, cloneFileId));
			assertFalse("clone stored file not deleted", MediaFixture.storedFileExists(cloneFullPath));

			DocumentConfig originDocument = DocumentFixture.getDocument(session, transaction, originDocumentId);
			assertNotNull("origin document deleted with the clone", originDocument);
			assertEquals(originFileId, originDocument.getDocumentFile());
			FileConfig originFile = MediaFixture.getFile(session, transaction, originFileId);
			assertNotNull("origin lum_File deleted with the clone", originFile);
			assertTrue("origin stored file deleted with the clone", MediaFixture.storedFileExists(originFile.getFullPath()));
			assertArrayEquals(bytes, MediaFixture.readStoredFile(originFile.getFullPath()));
			return null;
		});
	}

	/**
	 * Clones a document into the given folder of another document instance, with an independent
	 * {@code lum_File}.
	 *
	 * @return the clone's document id.
	 */
	static String cloneDocument(String originDocumentId, ServiceInstanceConfig destInstance, String destFolderId) throws Exception
	{
		Path tempDir = Files.createTempDirectory("contentmigrator-document-clone-");
		try
		{
			return PortalFixture.inTransaction((session, transaction) ->
			{
				DocumentConfig originDocument = DocumentFixture.getDocument(session, transaction, originDocumentId);
				FileConfig originFile = MediaFixture.getFile(session, transaction, originDocument.getDocumentFile());

				// same physical file name as the origin, in a directory of its own
				String storedName = originFile.getFullPath().substring(originFile.getFullPath().lastIndexOf('/') + 1);
				Path copy = tempDir.resolve(storedName);
				try (InputStream in = PortalBaseFolder.DATA.toPortalFilePath(originFile.getFullPath()).toFile().getInputStream())
				{
					Files.copy(in, copy);
				}

				Map<String, Object> data = new HashMap<>();
				data.put("title", originDocument.getTitle());
				data.put("description", originDocument.getDescription());
				data.put("type", DocumentConfig.TYPE_DOCUMENT);
				data.put("parentFolder", destFolderId);
				data.put("inheritPublishTo", originDocument.isInheritPublishTo());
				data.put("documentFile", MediaFixture.fileData(originFile.getName(), originFile.getContentType(), copy.toFile()));
				String cloneId = DocumentFixture.addData(session, transaction, destInstance.getId(), data);
				DocumentFixture.linkFileToContentLocale(session, transaction, cloneId);
				return cloneId;
			});
		}
		finally
		{
			// FileManager copies the temporary file in afterCommit
			MediaFixture.deleteRecursively(tempDir);
		}
	}
}
