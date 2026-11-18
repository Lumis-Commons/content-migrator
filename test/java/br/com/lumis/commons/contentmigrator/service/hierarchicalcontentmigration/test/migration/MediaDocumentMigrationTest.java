package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.DocumentFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.file.FileConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.user.UserConfig;
import lumis.service.document.DocumentConfig;
import lumis.service.media.Media;

/** Repository clones and references in hierarchical image, document and HTML fields. */
public class MediaDocumentMigrationTest
{
	private static final byte[] BYTES = new byte[]{1, 5, 9, 13, 17, 21};
	private static final String MALFORMED_PATH = "/data/files/missing%zz.png";
	private PortalFixture portal;
	private MediaFixture media;
	private DocumentFixture documents;
	private ServiceInstanceConfig origin;
	private ServiceInstanceConfig destination;
	private ServiceInstanceConfig originRepository;
	private ServiceInstanceConfig destinationRepository;
	private String migrationId;
	private String root;
	private String nested;
	private String repositoryItem;
	private boolean document;

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		media = new MediaFixture();
		documents = new DocumentFixture();
		PortalFixture.awaitGlobalLockFree();
	}

	@After
	public void tearDown() throws Exception
	{
		try
		{
			if (migrationId != null)
			{
				ModuleBridge.api("stop", migrationId);
				ModuleBridge.api("awaitTermination", migrationId, "120000");
				if (!PortalFixture.keepTestData())
				{
					ModuleBridge.api("undo", runParams());
					assertEquals("FINISHED", map(ModuleBridge.api("awaitTermination", migrationId, "120000")).get("status"));
					ModuleBridge.api("delete", migrationId);
				}
			}
		}
		finally
		{
			TestCleanup.assertNoFailures(portal::cleanup, media::cleanup, documents::cleanup);
		}
	}

	/**
	 * Verifies that distinct media repositories produce one mapped image clone and two folders,
	 * with the clone reused by the hierarchy and on rerun.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void sharedImageAndHtmlUseOneCloneAcrossTheHierarchyAndOnRerun() throws Exception
	{
		createRepositories(false, false);
		String parent = addContent("parent", null, repositoryItem);
		String child = addContent("child", parent, repositoryItem);
		migrate(parent);
		String clone = assertClone();
		assertEquals("two folders and one image in the destination repository", "3", repositoryItemCount());
		assertReferences(parent, clone, true);
		assertReferences(child, clone, true);
		assertEquals(result(parent, "destContentId"), result(child, "parentContentId"));
		assertStableRerun(parent, child, clone, true);
	}

	@Test
	public void mediaRetriesUniqueMapConflicts() throws Exception
	{
		assertRetry(false);
	}

	@Test
	public void mediaUpdatesChangedBytesWithoutCreatingAnotherClone() throws Exception
	{
		assertFileUpdate(false);
	}

	@Test
	public void mediaReusesSmallestDuplicateFolderAndWarns() throws Exception
	{
		createRepositories(false, false);
		String first = media.addFolder(destinationRepository, null, "root");
		String second = media.addFolder(destinationRepository, null, "root");
		String content = addContent("duplicate folders", null, repositoryItem);
		migrate(content);
		assertEquals(first.compareTo(second) < 0 ? first : second, mapped(root, "destItemId"));
		assertEquals("0", mapped(root, "destCreated"));
		String warnings = value("select warnings from lum_cmig_HMigItemMap where originContentId = ? and kind = 'CONTENT'", content);
		assertTrue(warnings, warnings.contains("Multiple destination media folders named 'root'"));
		assertReferences(content, assertClone(), true);
	}

	@Test
	public void htmlAloneClonesMediaWithoutAnImageField() throws Exception
	{
		createRepositories(false, false);
		String content = addContent("HTML media", null, null);
		migrate(content);
		String clone = assertClone();
		assertReferences(content, clone, false);
		assertStableRerun(content, content, clone, false);
	}

	@Test
	public void mediaReusesMatchingDestinationFolders() throws Exception
	{
		createRepositories(false, false);
		String destRoot = media.addFolder(destinationRepository, null, "root");
		String destNested = media.addFolder(destinationRepository, destRoot, "nested");
		String content = addContent("reused folders", null, repositoryItem);
		migrate(content);
		assertEquals(destRoot, mapped(root, "destItemId"));
		assertEquals(destNested, mapped(nested, "destItemId"));
		assertEquals("0", mapped(root, "destCreated"));
		assertEquals("0", mapped(nested, "destCreated"));
		assertReferences(content, assertClone(), true);
	}

	/**
	 * Verifies that a shared media repository keeps the image and HTML references, its item
	 * count, and the absence of item mappings involving that repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void sameMediaRepositoryKeepsImageAndHtmlWithoutClones() throws Exception
	{
		createRepositories(false, true);
		String content = addContent("same repository", null, repositoryItem);
		String beforeCount = repositoryItemCount();
		migrate(content);
		assertReferences(content, repositoryItem, true);
		assertEquals("shared repository item count", beforeCount, repositoryItemCount());
		assertEquals("item mappings involving the shared repository", "0", value(
				"select count(*) from lum_cmig_HMigItemMap where originServiceInstanceId = ? or destServiceInstanceId = ?",
				originRepository.getId(), originRepository.getId()));
	}

	@Test
	public void htmlWithoutOriginMediaDependencyIsPreserved() throws Exception
	{
		createRepositories(false, false);
		assertHtmlWithoutOriginDependency();
	}

	@Test
	public void sharedDocumentLinkAndHtmlReuseFoldersAndCloneOnRerun() throws Exception
	{
		createRepositories(true, false);
		String destRoot = documents.addFolder(destinationRepository, null, "root");
		String destNested = documents.addFolder(destinationRepository, destRoot, "nested");
		String parent = addContent("document parent", null, repositoryItem);
		String child = addContent("document child", parent, repositoryItem);
		migrate(parent);
		String clone = assertClone();
		assertEquals(destRoot, mapped(root, "destItemId"));
		assertEquals(destNested, mapped(nested, "destItemId"));
		assertEquals("0", mapped(root, "destCreated"));
		assertEquals("0", mapped(nested, "destCreated"));
		assertReferences(parent, clone, true);
		assertReferences(child, clone, true);
		assertEquals("3", result(parent, "type"));
		assertEquals(result(parent, "destContentId"), result(child, "parentContentId"));
		assertStableRerun(parent, child, clone, true);
	}

	@Test
	public void documentRetriesUniqueMapConflicts() throws Exception
	{
		assertRetry(true);
	}

	@Test
	public void documentUpdatesChangedBytesWithoutCreatingAnotherClone() throws Exception
	{
		assertFileUpdate(true);
	}

	@Test
	public void htmlAloneClonesDocumentWithoutADocumentLink() throws Exception
	{
		createRepositories(true, false);
		String content = addContent("HTML document", null, null);
		migrate(content);
		String clone = assertClone();
		assertReferences(content, clone, false);
		assertStableRerun(content, content, clone, false);
	}

	@Test
	public void htmlWithoutOriginDocumentDependencyIsPreserved() throws Exception
	{
		createRepositories(true, false);
		assertHtmlWithoutOriginDependency();
	}

	/**
	 * Verifies that a document link and its HTML references reuse the original document
	 * without adding repository items or mappings.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void sameDocumentRepositoryKeepsDocumentLinkAndHtmlWithoutClones() throws Exception
	{
		createRepositories(true, true);
		String content = addContent("shared repository document link", null, repositoryItem);
		assertSharedDocumentRepositoryReuse(content, true);
	}

	/**
	 * Verifies that HTML alone reuses the original document file in a shared repository
	 * without adding repository items or mappings.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void sameDocumentRepositoryKeepsHtmlWithoutADocumentLinkOrClone() throws Exception
	{
		createRepositories(true, true);
		String content = addContent("shared repository HTML document", null, null);
		assertSharedDocumentRepositoryReuse(content, false);
	}

	/**
	 * Verifies that one migration reuses the shared image and clones the document from a
	 * distinct repository, keeping the same destination content and references on rerun.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void sharedMediaAndDistinctDocumentRepositoriesAreHandledIndependently() throws Exception
	{
		ChannelConfig channel = createRepositories(true, false);
		RepositoryItems sharedMedia = createRepositoryItems(channel, false, true);
		String content = addContent("shared media and distinct documents", null, repositoryItem);
		MediaFixture.setHierarchicalImage(origin, content, sharedMedia.item());
		String mediaCount = repositoryItemCount(sharedMedia.destination());

		migrate(content);
		String clone = assertClone();
		String cloneFileId = file(clone).getId();
		String destinationContent = result(content, "destContentId");
		assertMixedRepositoryReferences(content, sharedMedia, mediaCount, clone);

		run();
		assertEquals(destinationContent, result(content, "destContentId"));
		assertEquals(clone, assertClone());
		assertEquals(cloneFileId, file(clone).getId());
		assertMixedRepositoryReferences(content, sharedMedia, mediaCount, clone);
	}

	/**
	 * Checks both references and verifies that only the document repository gained items.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void assertMixedRepositoryReferences(String content, RepositoryItems sharedMedia, String mediaCount, String clone)
			throws Exception
	{
		assertEquals(sharedMedia.item(), result(content, "image"));
		assertEquals("shared media repository item count", mediaCount, repositoryItemCount(sharedMedia.destination()));
		String mediaRepositoryId = sharedMedia.destination().getId();
		assertEquals("item mappings involving the shared media repository", "0", value(
				"select count(*) from lum_cmig_HMigItemMap where originServiceInstanceId = ? or destServiceInstanceId = ?",
				mediaRepositoryId, mediaRepositoryId));
		assertEquals("two folders and one document in the destination repository", "3", repositoryItemCount());
		assertReferences(content, clone, true);
	}

	/**
	 * Checks destination references and repository state after migrating the selected content.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void assertSharedDocumentRepositoryReuse(String content, boolean direct) throws Exception
	{
		String beforeCount = repositoryItemCount();
		migrate(content);
		assertReferences(content, repositoryItem, direct);
		assertEquals("shared document repository item count", beforeCount, repositoryItemCount());
		assertEquals("item mappings involving the shared document repository", "0", value(
				"select count(*) from lum_cmig_HMigItemMap where originServiceInstanceId = ? or destServiceInstanceId = ?",
				originRepository.getId(), originRepository.getId()));
	}

	/**
	 * Creates the hierarchical instances and the repository items used by the clone assertions.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private ChannelConfig createRepositories(boolean document, boolean sameRepository) throws Exception
	{
		this.document = document;
		ChannelConfig channel = portal.createChannel();
		origin = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		RepositoryItems items = createRepositoryItems(channel, document, sameRepository);
		originRepository = items.origin();
		destinationRepository = items.destination();
		root = items.root();
		nested = items.nested();
		repositoryItem = items.item();
		return channel;
	}

	/**
	 * Attaches one repository type to both hierarchical instances and creates its source items.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private RepositoryItems createRepositoryItems(ChannelConfig channel, boolean isDocument, boolean isShared) throws Exception
	{
		String service = isDocument ? DocumentFixture.DOCUMENT_SERVICE_ID : PortalFixture.MEDIA_SERVICE_ID;
		ServiceInstanceConfig source = portal.createServiceInstance(channel, service);
		ServiceInstanceConfig target = isShared ? source : portal.createServiceInstance(channel, service);
		String dependency = dependencyType(isDocument);
		portal.setDependency(source, origin, dependency);
		portal.setDependency(target, destination, dependency);
		String rootFolder = isDocument ? documents.addFolder(source, null, "root") : media.addFolder(source, null, "root");
		String nestedFolder = isDocument ? documents.addFolder(source, rootFolder, "nested") : media.addFolder(source, rootFolder, "nested");
		String item = isDocument
				? documents.addDocument(source, nestedFolder, "shared document", "description", "shared document.txt", "text/plain", BYTES)
				: media.addMedia(source, nestedFolder, "shared image", "shared image.png", "image/png", BYTES);
		return new RepositoryItems(source, target, rootFolder, nestedFolder, item);
	}

	/**
	 * Returns the dependency type for the requested repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String dependencyType(boolean isDocument)
	{
		return isDocument ? DocumentFixture.DOCUMENT_REPOSITORY_DEPENDENCY : ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY;
	}

	private String addContent(String title, String parent, String reference) throws Exception
	{
		return document ? documents.addHierarchicalContent(portal, origin, title, parent, reference, html())
				: media.addHierarchicalContent(portal, origin, title, parent, reference, html());
	}

	private FileConfig file(String item) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> MediaFixture.getFile(session, transaction,
				document ? DocumentFixture.getDocument(session, transaction, item).getDocumentFile() : MediaFixture.getMedia(item).getMediaFile()));
	}

	private String html() throws Exception
	{
		FileConfig file = file(repositoryItem);
		String path = file.getFullPath().substring("files/".length()).replace(" ", "%20");
		return "<p><a href=\"/lumis/portal/file/fileDownload.jsp?fileId=" + file.getId()
				+ "&amp;inline=true&amp;transform=thumbnail\">download</a><img src=\"/data/files/" + path + "\" /></p><p>" + MALFORMED_PATH + "</p>";
	}

	private void assertHtmlWithoutOriginDependency() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(origin.getId());
			removal.setDependencyType(dependencyType(document));
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
		String content = addContent("missing dependency", null, null);
		migrate(content);
		String sourceItem = PortalFixture.inTransaction((session, transaction) -> ContentManagerFactory.getContentManager().getContent(content)
				.getContentLocale(MediaFixture.LOCALE, false).getActiveVersion().getItemId());
		assertEquals(value("select linkContent from lum_HierarchicalContent where id = ?", sourceItem), result(content, "linkContent"));
		assertEquals("0", repositoryMapCount());
	}

	private String assertClone() throws Exception
	{
		String clone = mapped(repositoryItem, "destItemId");
		assertNotEquals(repositoryItem, clone);
		assertEquals("3", repositoryMapCount());
		assertEquals(document ? "DOCUMENT" : "MEDIA", mapped(repositoryItem, "kind"));
		assertEquals("1", mapped(repositoryItem, "destCreated"));
		assertEquals(destinationRepository.getId(), value("select c.serviceInstanceId from lum_Content c inner join lum_ContentLocale cl on cl.contentId = c.id "
				+ "inner join lum_ContentVersion v on v.contentLocaleId = cl.id where v.itemId = ?", clone));
		String table = document ? "lum_Document" : "lum_Media";
		assertEquals(value("select title from " + table + " where id = ?", repositoryItem), value("select title from " + table + " where id = ?", clone));
		if (document)
		{
			assertEquals(value("select description from lum_Document where id = ?", repositoryItem), value("select description from lum_Document where id = ?", clone));
		}
		assertEquals(mapped(nested, "destItemId"), value("select parentFolder from " + table + " where id = ?", clone));
		assertEquals(mapped(root, "destItemId"), value("select parentFolder from " + table + " where id = ?", mapped(nested, "destItemId")));
		String fingerprint = mapped(repositoryItem, "originFileFingerprint");
		assertNotNull(fingerprint);
		assertTrue(fingerprint, fingerprint.matches("sha256:[0-9a-f]{64}:\\d+"));
		FileConfig source = file(repositoryItem);
		FileConfig target = file(clone);
		assertNotEquals(source.getId(), target.getId());
		assertEquals(destinationRepository.getId(), target.getServiceInstanceId());
		assertEquals(source.getName(), target.getName());
		assertEquals(source.getContentType(), target.getContentType());
		assertArrayEquals(BYTES, MediaFixture.readStoredFile(target.getFullPath()));
		return clone;
	}

	private void assertReferences(String content, String clone, boolean direct) throws Exception
	{
		assertEquals(direct ? clone : null, result(content, document ? "linkDocumentId" : "image"));
		String html = result(content, "linkContent");
		assertTrue(html, html.contains("fileId=" + file(clone).getId()));
		String cloneFileId = file(clone).getId();
		String expectedHref = PortalFixture.inTransaction((session, transaction) ->
				ManagerFactory.getFileManager().getFileHref(session, cloneFileId, transaction).toString());
		assertTrue(html, html.replace("&amp;", "&").contains(expectedHref));
		assertTrue(html, html.contains("inline=true"));
		assertTrue(html, html.contains("transform=thumbnail"));
		assertTrue(html, html.contains(MALFORMED_PATH));
		assertFalse(html, html.contains("null/lumis/portal"));
		if (!clone.equals(repositoryItem))
		{
			assertFalse(html, html.contains(file(repositoryItem).getId()));
			assertFalse(html, html.contains(file(repositoryItem).getFullPath().replace(" ", "%20")));
		}
	}

	private void assertStableRerun(String parent, String child, String clone, boolean direct) throws Exception
	{
		String parentContent = result(parent, "destContentId");
		String childContent = result(child, "destContentId");
		String fileId = file(clone).getId();
		String beforeCount = repositoryItemCount();
		run();
		assertEquals(parentContent, result(parent, "destContentId"));
		assertEquals(childContent, result(child, "destContentId"));
		assertEquals(clone, assertClone());
		assertEquals(fileId, file(clone).getId());
		assertEquals(beforeCount, repositoryItemCount());
		assertReferences(parent, clone, direct);
		assertReferences(child, clone, direct);
	}

	private void assertRetry(boolean document) throws Exception
	{
		createRepositories(document, false);
		String parent = addContent("retry parent", null, repositoryItem);
		String child = addContent("retry child", parent, repositoryItem);
		String faultProperty = (String) ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.RepositoryCloner")
				.getField("PROPERTY_FAULT_UNIQUE_MAP_ONCE").get(null);
		try
		{
			System.setProperty(faultProperty, "true");
			migrate(parent);
			assertFalse("unique-map fault injection was consumed", Boolean.getBoolean(faultProperty));
		}
		finally
		{
			System.clearProperty(faultProperty);
		}
		String clone = assertClone();
		assertReferences(parent, clone, true);
		assertReferences(child, clone, true);
	}

	private void assertFileUpdate(boolean document) throws Exception
	{
		createRepositories(document, false);
		String parent = addContent("update parent", null, repositoryItem);
		String child = addContent("update child", parent, repositoryItem);
		migrate(parent);
		String clone = assertClone();
		String sourceFileId = file(repositoryItem).getId();
		String targetFileId = file(clone).getId();
		String fingerprint = mapped(repositoryItem, "originFileFingerprint");
		String count = repositoryItemCount();
		byte[] updated = new byte[]{2, 6, 10, 14, 18, 22};
		replaceOriginFile(updated);
		assertEquals("source updates retain the file id", sourceFileId, file(repositoryItem).getId());
		run();
		assertEquals(clone, mapped(repositoryItem, "destItemId"));
		assertEquals("3", repositoryMapCount());
		assertEquals(count, repositoryItemCount());
		assertEquals("destination updates retain the file id", targetFileId, file(clone).getId());
		assertNotEquals(fingerprint, mapped(repositoryItem, "originFileFingerprint"));
		assertArrayEquals(updated, MediaFixture.readStoredFile(file(clone).getFullPath()));
		assertReferences(parent, clone, true);
		assertReferences(child, clone, true);
	}

	private void replaceOriginFile(byte[] bytes) throws Exception
	{
		FileConfig oldFile = file(repositoryItem);
		Path tempDir = Files.createTempDirectory("hierarchical-repository-update-");
		try
		{
			Path tempFile = Files.write(tempDir.resolve(oldFile.getName()), bytes);
			PortalFixture.inTransaction((session, transaction) ->
			{
				session.setLocale(MediaFixture.LOCALE);
				Map<String, Object> data = new HashMap<>();
				data.put("id", repositoryItem);
				data.put("publishToPrincipals", "");
				if (document)
				{
					DocumentConfig item = DocumentFixture.getDocument(session, transaction, repositoryItem);
					data.put("title", item.getTitle());
					data.put("description", item.getDescription());
					data.put("type", item.getType());
					data.put("parentFolder", item.getParentFolder());
					data.put("inheritPublishTo", item.isInheritPublishTo());
				}
				else
				{
					Media item = MediaFixture.getMedia(repositoryItem);
					data.put("title", item.getTitle());
					data.put("legend", item.getLegend());
					data.put("credits", item.getCredits());
					data.put("type", item.getType());
					data.put("parentFolder", item.getParentFolder());
					data.put("inheritPublishTo", item.getInheritPublishTo());
				}
				data.put(document ? "documentFile" : "mediaFile", MediaFixture.fileData(oldFile.getName(), oldFile.getContentType(), tempFile.toFile()));
				ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, originRepository, transaction));
				source.updateData(data);
				if (document)
				{
					DocumentFixture.linkFileToContentLocale(session, transaction, repositoryItem);
				}
				else
				{
					MediaFixture.linkFileToContentLocale(repositoryItem);
				}
				return null;
			});
		}
		finally
		{
			MediaFixture.deleteRecursively(tempDir);
		}
	}

	/**
	 * Counts content in the destination repository used by the clone assertions.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String repositoryItemCount() throws Exception
	{
		return repositoryItemCount(destinationRepository);
	}

	/**
	 * Counts items in the specified repository, including folders.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String repositoryItemCount(ServiceInstanceConfig repository) throws Exception
	{
		return value("select count(*) from lum_Content where serviceInstanceId = ?", repository.getId());
	}

	private String repositoryMapCount() throws Exception
	{
		return value("select count(*) from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ? and kind <> 'CONTENT'",
				originRepository.getId(), destinationRepository.getId());
	}

	private String mapped(String item, String field) throws Exception
	{
		return value("select " + field + " from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ? and originItemId = ?",
				originRepository.getId(), destinationRepository.getId(), item);
	}

	private String result(String content, String field) throws Exception
	{
		String alias = "destContentId".equals(field) || "destItemId".equals(field) ? "m" : "h";
		return value("select " + alias + "." + field + " from lum_cmig_HMigItemMap m inner join lum_HierarchicalContent h on h.id = m.destItemId "
				+ "where m.originServiceInstanceId = ? and m.destServiceInstanceId = ? and m.originContentId = ? and m.kind = 'CONTENT'",
				origin.getId(), destination.getId(), content);
	}

	private static String value(String sql, String... parameters) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(sql))
			{
				for (int i = 0; i < parameters.length; i++)
				{
					statement.setString(i + 1, parameters[i]);
				}
				try (ResultSet rows = statement.executeQuery())
				{
					assertTrue("expected a result for " + sql, rows.next());
					String result = rows.getString(1);
					assertFalse("expected one result for " + sql, rows.next());
					return result;
				}
			}
		});
	}

	private void migrate(String selection) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "hierarchical media and documents");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", selection);
		params.put("updateMode", "UPDATE");
		migrationId = (String) map(ModuleBridge.api("create", params)).get("id");
		run();
	}

	private void run() throws Exception
	{
		ModuleBridge.api("start", runParams());
		Map<String, Object> finished = map(ModuleBridge.api("awaitTermination", migrationId, "120000"));
		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
	}

	private Map<String, Object> runParams()
	{
		Map<String, Object> params = new HashMap<>();
		params.put("migrationId", migrationId);
		params.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		return params;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value)
	{
		return (Map<String, Object>) value;
	}

	/**
	 * Repositories and source items configured for one dependency type.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private record RepositoryItems(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String root, String nested, String item)
	{
	}
}
