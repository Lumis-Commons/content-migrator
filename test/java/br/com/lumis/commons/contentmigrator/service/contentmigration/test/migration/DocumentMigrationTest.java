package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.DocumentFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.file.FileConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.service.document.DocumentConfig;

/** Integration coverage for document repository maps, files and document references. */
public class DocumentMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final String DOCUMENT_REPOSITORY = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY;

	private static final String HIERARCHICAL_SERVICE_ID = "lumis.service.hierarchicalcontent";

	private static final byte[] ORIGINAL_BYTES = new byte[]{3, 7, 11, 15, 19, 23};

	private static final byte[] UPDATED_BYTES = new byte[]{2, 4, 6, 8, 10, 12, 14};

	/**
	 * A public file path whose escape {@code %zz} is not valid percent-encoding. It goes in the text
	 * of the html, because HtmlDataType rejects a malformed url only in link attributes.
	 */
	private static final String MALFORMED_PUBLIC_PATH = "/data/files/missing%zz.png";

	private static final String DEPENDENCY_CONTEXT = "br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyContext";

	private static final String DEPENDENCY_HANDLER = "br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandler";

	private static final String HANDLER_REGISTRY = "br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandlerRegistry";

	private PortalFixture portal;

	private ContentMetadataFixture contents;

	private DocumentFixture documents;

	private ChannelConfig channel;

	private ServiceInstanceConfig originRepository;

	private ServiceInstanceConfig destinationRepository;

	private ServiceInstanceConfig originNews;

	private ServiceInstanceConfig destinationNews;

	private ServiceInstanceConfig originHierarchy;

	private ServiceInstanceConfig destinationHierarchy;

	private String originRootFolderId;

	private String originNestedFolderId;

	private String originDocumentId;

	private String originNewsItemId;

	private String originHierarchyItemId;

	private String destinationNewsItemId;

	private String destinationHierarchyItemId;

	private final List<String> migrationIds = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		contents = new ContentMetadataFixture();
		documents = new DocumentFixture();
		channel = portal.createChannel();
		resetLock();
	}

	@After
	public void tearDown() throws Exception
	{
		TestCleanup cleanup = new TestCleanup();
		for (String migrationId : migrationIds)
		{
			ModuleBridge.api("stop", migrationId);
			ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
		}
		System.clearProperty(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE);
		if (!PortalFixture.keepTestData())
		{
			trackDestinationDocuments();
			deleteMigratedContents();
			deleteOriginHierarchy();
			cleanup.run(contents::cleanup, documents::cleanup);
			deleteMigrationData();
		}
		else
		{
			cleanup.run(contents::cleanup, documents::cleanup);
		}
		cleanup.run(portal::cleanup);
		cleanup.assertNoFailures();
	}

	@Test
	public void clonesSharedDocumentOnceRewritesReferencesRetriesAndUpdatesChangedBytes() throws Exception
	{
		createInstances();
		createOriginDocument();
		String sourceFileId = documentFile(originDocumentId).getId();
		String href = "<p><a href=\"/lumis/portal/file/fileDownload.jsp?fileId=" + sourceFileId + "&amp;inline=true\">document</a></p><p>" + MALFORMED_PUBLIC_PATH
				+ "</p>";
		Map<String, Object> newsData = ContentMetadataFixture.news("document migration", "introduction", href);
		originNewsItemId = contents.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, newsData, null, null, null);
		originHierarchyItemId = addHierarchicalLink(originHierarchy, originDocumentId, href);

		// Matching destination paths must be reused and mapped as unowned folders.
		String destinationRoot = documents.addFolder(destinationRepository, null, "documents root");
		String destinationNested = documents.addFolder(destinationRepository, destinationRoot, "nested");

		System.setProperty(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE, "true");
		Map<String, Object> newsRun = runMigration(originNews, destinationNews, null);
		assertEquals("News migration error: " + newsRun.get("errorMessage"), "FINISHED", newsRun.get("status"));
		assertFalse("unique-map fault injection was consumed", Boolean.getBoolean(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE));
		destinationNewsItemId = mappedContentItem(originNews, destinationNews, originNewsItemId);

		Map<String, Object> hierarchyRun = runMigration(originHierarchy, destinationHierarchy, null);
		assertEquals("Hierarchy migration error: " + hierarchyRun.get("errorMessage"), "FINISHED", hierarchyRun.get("status"));
		destinationHierarchyItemId = mappedContentItem(originHierarchy, destinationHierarchy, originHierarchyItemId);

		List<MapRow> maps = repositoryMaps();
		MapRow documentMap = onlyMap(maps, "DOCUMENT");
		assertEquals("root and nested folders are mapped once", 2, count(maps, "FOLDER"));
		assertEquals(destinationRoot, mapByOriginItem(maps, originRootFolderId).destItemId);
		assertEquals(destinationNested, mapByOriginItem(maps, originNestedFolderId).destItemId);
		assertFalse("reused root is not owned by this migration", mapByOriginItem(maps, originRootFolderId).destCreated);
		assertFalse("reused nested folder is not owned by this migration", mapByOriginItem(maps, originNestedFolderId).destCreated);
		assertEquals("one document map serves both content services", 1, countOriginRows(maps, "DOCUMENT", originDocumentId));
		assertNotNull("file fingerprint is persisted", documentMap.fingerprint);
		assertTrue(documentMap.fingerprint.matches("sha256:[0-9a-f]{64}:\\d+"));

		DocumentClone clone = inspectClone(documentMap.destItemId);
		assertNotEquals("the clone has a new document id", originDocumentId, clone.documentId);
		assertNotEquals("the clone has an independent file id", clone.originFileId, clone.destFileId);
		assertEquals("document title is preserved", "migration document", clone.title);
		assertEquals("document description is preserved", "document description", clone.description);
		assertEquals("file name is preserved", "shared document.txt", clone.fileName);
		assertEquals("content type is preserved", "text/plain", clone.contentType);
		assertEquals(destinationRepository.getId(), clone.repositoryId);
		assertEquals(destinationNested, clone.parentFolderId);
		assertArrayEquals(ORIGINAL_BYTES, clone.bytes);

		assertEquals(documentMap.destItemId, hierarchyDocumentField(destinationHierarchy, destinationHierarchyItemId));
		String destinationHierarchyHtml = hierarchicalHtml(destinationHierarchy, destinationHierarchyItemId);
		assertTrue("hierarchical HTML references the destination file", destinationHierarchyHtml.contains(clone.destFileId));
		assertFalse("a relative download link in hierarchical HTML gets no prefix", destinationHierarchyHtml.contains("null/lumis/portal"));
		String destinationNewsHtml = newsHtml(destinationNews, destinationNewsItemId);
		assertTrue("news HTML references the destination file", destinationNewsHtml.contains(clone.destFileId));
		assertFalse("news HTML no longer references the source file", destinationNewsHtml.contains(sourceFileId));
		assertFalse("a relative download link in news HTML gets no prefix", destinationNewsHtml.contains("null/lumis/portal"));
		assertTrue("a public file path that cannot be decoded is kept", destinationNewsHtml.contains(MALFORMED_PUBLIC_PATH));
		assertTrue("the nested folder holds the document clone", isCloneReferenced(destinationNested));
		assertFalse("a news is not a referenced document clone", isCloneReferenced(originNewsItemId));

		String priorDestinationFileId = clone.destFileId;
		String priorFingerprint = documentMap.fingerprint;
		assertEquals("origin update retains its file id", sourceFileId, replaceOriginFile(UPDATED_BYTES));
		Map<String, Object> updateRun = runMigration(originNews, destinationNews, "UPDATE");
		assertEquals("Update error: " + updateRun.get("errorMessage"), "FINISHED", updateRun.get("status"));
		MapRow updatedMap = onlyMap(repositoryMaps(), "DOCUMENT");
		DocumentClone updatedClone = inspectClone(updatedMap.destItemId);
		assertEquals("destination update retains its file id", priorDestinationFileId, updatedClone.destFileId);
		assertNotEquals("same origin file id gets a new fingerprint", priorFingerprint, updatedMap.fingerprint);
		assertArrayEquals(UPDATED_BYTES, updatedClone.bytes);
	}

	@Test
	public void keepsHtmlLinksWhenTheOriginHasNoDocumentRepository() throws Exception
	{
		createInstances();
		createOriginDocument();
		removeDependency(originNews, DOCUMENT_REPOSITORY);
		FileConfig file = documentFile(originDocumentId);
		String publicPath = file.getFullPath().substring("files/".length()).replace(" ", "%20");
		String html = "<p><a href=\"/lumis/portal/file/fileDownload.jsp?fileId=" + file.getId() + "&amp;inline=true\">document</a><img src=\"/data/files/" + publicPath
				+ "\" /></p>";
		Map<String, Object> newsData = ContentMetadataFixture.news("no origin repository", "introduction", html);
		originNewsItemId = contents.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, newsData, null, null, null);
		Map<String, Object> run = runMigration(originNews, destinationNews, null);
		assertEquals("Migration error: " + run.get("errorMessage"), "FINISHED", run.get("status"));
		destinationNewsItemId = mappedContentItem(originNews, destinationNews, originNewsItemId);
		assertEquals("links to documents of another repository are kept", newsHtml(originNews, originNewsItemId), newsHtml(destinationNews, destinationNewsItemId));
		assertTrue("no document is cloned", repositoryMaps().isEmpty());
	}

	private void createInstances() throws Exception
	{
		originRepository = portal.createServiceInstance(channel, DocumentFixture.DOCUMENT_SERVICE_ID);
		destinationRepository = portal.createServiceInstance(channel, DocumentFixture.DOCUMENT_SERVICE_ID);
		originNews = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		destinationNews = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		originHierarchy = portal.createServiceInstance(channel, HIERARCHICAL_SERVICE_ID);
		destinationHierarchy = portal.createServiceInstance(channel, HIERARCHICAL_SERVICE_ID);
		for (ServiceInstanceConfig content : new ServiceInstanceConfig[]{originNews, originHierarchy})
		{
			portal.setDependency(originRepository, content, DOCUMENT_REPOSITORY);
		}
		portal.setDependency(destinationRepository, destinationNews, DOCUMENT_REPOSITORY);
		portal.setDependency(destinationRepository, destinationHierarchy, DOCUMENT_REPOSITORY);
	}

	private void createOriginDocument() throws Exception
	{
		originRootFolderId = documents.addFolder(originRepository, null, "documents root");
		originNestedFolderId = documents.addFolder(originRepository, originRootFolderId, "nested");
		originDocumentId = documents
				.addDocument(originRepository, originNestedFolderId, "migration document", "document description", "shared document.txt", "text/plain", ORIGINAL_BYTES);
	}

	private String addHierarchicalLink(ServiceInstanceConfig instance, String documentId, String html) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			Map<String, Object> data = new HashMap<>();
			data.put("title", "document link");
			data.put("introduction", "document migration");
			data.put("type", 3);
			data.put("position", 0);
			data.put("hidden", false);
			data.put("parentContentId", null);
			data.put("linkDocumentId", documentId);
			data.put("linkOpeningType", 0);
			data.put("linkContent", html);
			return source(session, transaction, instance).addData(data).getItemId();
		});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runMigration(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String updateMode) throws Exception
	{
		String key = origin.getId() + ":" + destination.getId();
		String migrationId = migrationByPair.get(key);
		if (migrationId == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-document-migration");
			create.put("originServiceInstanceId", origin.getId());
			create.put("destServiceInstanceId", destination.getId());
			Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
			migrationId = (String) migration.get("id");
			migrationIds.add(migrationId);
			migrationByPair.put(key, migrationId);
		}
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		if (updateMode != null)
		{
			start.put("updateMode", updateMode);
		}
		ModuleBridge.api("start", start);
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
	}

	private String mappedContentItem(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			String localeId = lumis.content.ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId).getContentLocale().getId();
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select destItemId from lum_cmig_MigItemMap where kind = 'CONTENT' " + "and originServiceInstanceId = ? and destServiceInstanceId = ? "
							+ "and originContentLocaleId = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				statement.setString(3, localeId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("content map exists for " + originItemId, rs.next());
					return rs.getString(1);
				}
			}
		});
	}

	private List<MapRow> repositoryMaps() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<MapRow> rows = new ArrayList<>();
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select kind, originItemId, destItemId, destCreated, originFileFingerprint " + "from lum_cmig_MigItemMap where originServiceInstanceId = ? "
							+ "and destServiceInstanceId = ? and kind in ('DOCUMENT','FOLDER')"))
			{
				statement.setString(1, originRepository.getId());
				statement.setString(2, destinationRepository.getId());
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						rows.add(new MapRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)));
					}
				}
			}
			return rows;
		});
	}

	private DocumentClone inspectClone(String documentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			DocumentConfig origin = DocumentFixture.getDocument(session, transaction, originDocumentId);
			DocumentConfig destination = DocumentFixture.getDocument(session, transaction, documentId);
			FileConfig sourceFile = MediaFixture.getFile(session, transaction, origin.getDocumentFile());
			FileConfig destFile = MediaFixture.getFile(session, transaction, destination.getDocumentFile());
			return new DocumentClone(
					documentId,
					destination.getTitle(),
					destination.getDescription(),
					destination.getParentFolder(),
					sourceFile.getId(),
					destFile.getId(),
					destFile.getName(),
					destFile.getContentType(),
					destFile.getServiceInstanceId(),
					MediaFixture.readStoredFile(destFile.getFullPath()));
		});
	}

	private String hierarchyDocumentField(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Object value = source(session, transaction, instance).readData(itemId).get(0).get("linkDocumentId");
			if (value instanceof lumis.service.document.field.DocumentDataType.Data)
			{
				return ((lumis.service.document.field.DocumentDataType.Data) value).getDocumentId();
			}
			return value == null ? null : value.toString();
		});
	}

	private String hierarchicalHtml(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> source(session, transaction, instance).readData(itemId).get(0).get("linkContent").toString());
	}

	private String newsHtml(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture.inTransaction(
				(session, transaction) -> ContentMetadataFixture.newsSource(session, transaction, instance.getId()).readData(itemId).get(0).get("content").toString());
	}

	/**
	 * Calls the registered document handler, through the module class loader, to tell whether the
	 * content locale of the given destination item is referenced.
	 */
	private boolean isCloneReferenced(String destItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Class<?> contextType = ModuleBridge.load(DEPENDENCY_CONTEXT);
			Object context = ModuleBridge.newDependencyContext(
					scopeValues(session, transaction, originNews.getId(), destinationNews.getId()),
					originRepository.getId(),
					destinationRepository.getId());
			Object handler = ModuleBridge.invokeStatic(HANDLER_REGISTRY, "getHandler", DOCUMENT_REPOSITORY);
			Method method = ModuleBridge.load(DEPENDENCY_HANDLER).getMethod("isCloneReferenced", contextType, String.class);
			return (Boolean) method.invoke(handler, context, ContentMetadataFixture.contentLocale(destItemId).getId());
		});
	}

	/** Removes the dependency of the given type that the portal may have created for the instance. */
	private static void removeDependency(ServiceInstanceConfig dependent, String dependencyType) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(dependent.getId());
			removal.setDependencyType(dependencyType);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
	}

	private FileConfig documentFile(String documentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			DocumentConfig document = DocumentFixture.getDocument(session, transaction, documentId);
			return MediaFixture.getFile(session, transaction, document.getDocumentFile());
		});
	}

	private String replaceOriginFile(byte[] bytes) throws Exception
	{
		DocumentConfig before = PortalFixture.inTransaction((session, transaction) -> DocumentFixture.getDocument(session, transaction, originDocumentId));
		FileConfig oldFile = documentFile(originDocumentId);
		Path tempDir = Files.createTempDirectory("contentmigrator-document-update-");
		try
		{
			File tempFile = Files.write(tempDir.resolve(oldFile.getName()), bytes).toFile();
			return PortalFixture.inTransaction((session, transaction) ->
			{
				session.setLocale(ContentMetadataFixture.LOCALE);
				ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(originRepository.getId(), transaction);
				ContentTableSource source = source(session, transaction, instance);
				Map<String, Object> data = new HashMap<>();
				data.put("id", originDocumentId);
				data.put("title", before.getTitle());
				data.put("description", before.getDescription());
				data.put("type", before.getType());
				data.put("parentFolder", before.getParentFolder());
				data.put("inheritPublishTo", before.isInheritPublishTo());
				data.put("publishToPrincipals", "");
				data.put("documentFile", MediaFixture.fileData(oldFile.getName(), oldFile.getContentType(), tempFile));
				source.updateData(data);
				DocumentConfig updated = DocumentFixture.getDocument(session, transaction, originDocumentId);
				String contentLocaleId = ContentMetadataFixture.contentLocale(originDocumentId).getId();
				ManagerFactory.getFileManager().setFileContentLocaleId(updated.getDocumentFile(), contentLocaleId);
				return updated.getDocumentFile();
			});
		}
		finally
		{
			MediaFixture.deleteRecursively(tempDir);
		}
	}

	private String destinationMappedId(String originId) throws Exception
	{
		return mapByOriginItem(repositoryMaps(), originId).destItemId;
	}

	private void trackDestinationDocuments() throws Exception
	{
		if (originDocumentId == null || repositoryMaps().isEmpty())
		{
			return;
		}
		String root = destinationMappedId(originRootFolderId);
		String nested = destinationMappedId(originNestedFolderId);
		String document = destinationMappedId(originDocumentId);
		// The fixture removes in reverse registration order: register parents before their children.
		documents.track(destinationRepository.getId(), root);
		documents.track(destinationRepository.getId(), nested);
		documents.track(destinationRepository.getId(), document);
	}

	private void deleteMigratedContents() throws Exception
	{
		if (originNews == null)
		{
			return;
		}
		for (ServiceInstanceConfig[] pair : new ServiceInstanceConfig[][]{{originNews, destinationNews}, {originHierarchy, destinationHierarchy}})
		{
			List<String[]> rows = PortalFixture.inTransaction((session, transaction) -> loadMappedContentRows(transaction, pair));
			for (String[] row : rows)
			{
				deleteItem(pair[1].getId(), row[0], row[1]);
			}
		}
	}

	private List<String[]> loadMappedContentRows(ITransaction transaction, ServiceInstanceConfig[] pair) throws Exception
	{
		List<String[]> values = new ArrayList<>();
		try (PreparedStatement statement = connection(transaction).prepareStatement(
				"select sourceId, destItemId from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ? and kind = 'CONTENT'"))
		{
			statement.setString(1, pair[0].getId());
			statement.setString(2, pair[1].getId());
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					values.add(new String[]{rs.getString(1), rs.getString(2)});
				}
			}
		}
		return values;
	}

	private void deleteOriginHierarchy() throws Exception
	{
		if (originHierarchyItemId != null)
		{
			deleteItem(originHierarchy.getId(), "default", originHierarchyItemId);
		}
	}

	private void deleteMigrationData() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = connection(transaction);
			for (ServiceInstanceConfig[] pair : new ServiceInstanceConfig[][]{{originNews, destinationNews}, {originHierarchy, destinationHierarchy}})
			{
				if (pair[0] != null)
				{
					update(
							connection,
							"delete from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ?",
							pair[0].getId(),
							pair[1].getId());
				}
			}
			for (String migrationId : migrationIds)
			{
				update(connection, "delete from lum_cmig_Migration where id = ?", migrationId);
			}
			update(connection, "update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null");
			return null;
		});
	}

	private static ContentTableSource source(lumis.portal.authentication.SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance) throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
	}

	private static void deleteItem(String instanceId, String sourceId, String itemId) throws Exception
	{
		if (itemId == null)
		{
			return;
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			try
			{
				ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(instanceId, transaction);
				((ContentTableSource) SourceFactory.createSource(sourceId, new SourceContext(session, instance, transaction)))
						.deleteDataByItemIds(java.util.Collections.singleton(itemId));
			}
			catch (PortalObjectNotFoundException e)
			{
				// Content or instance was already removed.
			}
			return null;
		});
	}

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}

	private static int update(Connection connection, String sql, Object... parameters) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			for (int index = 0; index < parameters.length; index++)
			{
				statement.setObject(index + 1, parameters[index]);
			}
			return statement.executeUpdate();
		}
	}

	private static void resetLock() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			// @formatter:off
			update(connection(transaction),
					"update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null");
			// @formatter:on
			return null;
		});
	}

	private static MapRow onlyMap(List<MapRow> maps, String kind)
	{
		List<MapRow> found = new ArrayList<>();
		for (MapRow row : maps)
		{
			if (kind.equals(row.kind))
			{
				found.add(row);
			}
		}
		assertEquals("expected one " + kind + " map", 1, found.size());
		return found.get(0);
	}

	private static int count(List<MapRow> maps, String kind)
	{
		int count = 0;
		for (MapRow row : maps)
		{
			if (kind.equals(row.kind))
			{
				count++;
			}
		}
		return count;
	}

	private static int countOriginRows(List<MapRow> maps, String kind, String originId)
	{
		int count = 0;
		for (MapRow row : maps)
		{
			if (kind.equals(row.kind) && originId.equals(row.originItemId))
			{
				count++;
			}
		}
		return count;
	}

	private static MapRow mapByOriginItem(List<MapRow> maps, String originId)
	{
		for (MapRow row : maps)
		{
			if (originId.equals(row.originItemId))
			{
				return row;
			}
		}
		throw new AssertionError("No repository map for origin item " + originId);
	}

	private static final class MapRow
	{
		final String kind;

		final String originItemId;

		final String destItemId;

		final boolean destCreated;

		final String fingerprint;

		MapRow(String kind, String originItemId, String destItemId, boolean destCreated, String fingerprint)
		{
			this.kind = kind;
			this.originItemId = originItemId;
			this.destItemId = destItemId;
			this.destCreated = destCreated;
			this.fingerprint = fingerprint;
		}
	}

	private static final class DocumentClone
	{
		final String documentId;

		final String title;

		final String description;

		final String parentFolderId;

		final String originFileId;

		final String destFileId;

		final String fileName;

		final String contentType;

		final String repositoryId;

		final byte[] bytes;

		DocumentClone(
				String documentId,
				String title,
				String description,
				String parentFolderId,
				String originFileId,
				String destFileId,
				String fileName,
				String contentType,
				String repositoryId,
				byte[] bytes)
		{
			this.documentId = documentId;
			this.title = title;
			this.description = description;
			this.parentFolderId = parentFolderId;
			this.originFileId = originFileId;
			this.destFileId = destFileId;
			this.fileName = fileName;
			this.contentType = contentType;
			this.repositoryId = repositoryId;
			this.bytes = bytes;
		}
	}

	/** Returns the values of the migration scope of a dependency context between two news instances. */
	private static Map<String, Object> scopeValues(SessionConfig session, ITransaction transaction, String originInstanceId, String destinationInstanceId)
			throws Exception
	{
		Map<String, Object> values = new HashMap<>();
		values.put("session", session);
		values.put("transaction", transaction);
		values.put("connection", ((ITransactionJdbc) transaction).getConnection());
		values.put("serviceId", PortalFixture.NEWS_SERVICE_ID);
		values.put("originServiceInstanceId", originInstanceId);
		values.put("destinationServiceInstanceId", destinationInstanceId);
		return values;
	}
}
