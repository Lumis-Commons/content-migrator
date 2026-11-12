package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.CategorizationFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.DocumentFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.FullScenarioFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.WidgetFixture;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.workflow.ContentWorkflowMetaData;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.content.table.ContentTableSource;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.service.pagepersonalization.bean.WidgetInstance;

/** End-to-end coverage for shared dependencies, workflow versions, resume, update and undo. */
public class FullMigrationTest
{
	private static final String AWAIT_MILLIS = "180000";

	private FullScenarioFixture scenario;

	private final List<String> migrationIds = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		scenario = new FullScenarioFixture();
		scenario.create();
		resetLock();
	}

	@After
	public void tearDown() throws Exception
	{
		for (String migrationId : migrationIds)
		{
			ModuleBridge.api("stop", migrationId);
			ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
		}
		boolean hasScenarioData = scenario != null && scenario.originNews != null;
		if (!PortalFixture.keepTestData() && hasScenarioData)
		{
			trackDestinationContents();
			deleteMigrationData();
		}
		if (scenario != null)
		{
			scenario.cleanup();
		}
	}

	@Test
	public void migratesSharedDependenciesAcrossChannelsThenUpdatesAndUndoesNews() throws Exception
	{
		assertEquals("fixture has twenty news content ids", FullScenarioFixture.NEWS_CONTENT_COUNT, scenario.originNewsContentIds.size());
		assertEquals("news content ids are distinct", FullScenarioFixture.NEWS_CONTENT_COUNT, new HashSet<>(scenario.originNewsContentIds).size());

		String newsMigrationId = createMigration(scenario.originNews, scenario.destinationNews);
		Map<String, Object> blocked = startAndAwait(newsMigrationId, null, UserConfig.USER_ADMINISTRATOR_ID);
		assertEquals("WIDGET_MAPPING_REQUIRED", blocked.get("errorType"));
		assertEquals("STOPPED_WITH_ERROR", blocked.get("status"));
		assertEquals("the widget modal resumes the blocked migration", scenario.originWidgetContentId, blocked.get("blockingWidgetContentId"));
		assertEquals("precheck does not copy news before the widget is mapped", 0, countItemMaps(scenario.originNews, scenario.destinationNews, "CONTENT"));
		mapWidgetThroughAdminModal(newsMigrationId);

		Map<String, Object> newsRun = startAndAwait(newsMigrationId, null, UserConfig.USER_ADMINISTRATOR_ID);
		assertFinishedCount(newsRun, FullScenarioFixture.NEWS_CONTENT_COUNT);
		assertEquals(
				"one content map exists for every source news locale",
				FullScenarioFixture.NEWS_CONTENT_COUNT,
				countItemMaps(scenario.originNews, scenario.destinationNews, "CONTENT"));
		assertNewsWorkflowAndDependencies();

		String hierarchyMigrationId = createMigration(scenario.originHierarchy, scenario.destinationHierarchy);
		Map<String, Object> hierarchyRun = startAndAwait(hierarchyMigrationId, null, UserConfig.USER_ADMINISTRATOR_ID);
		assertFinishedCount(hierarchyRun, 2);
		assertHierarchyAndDependencyReuse();
		String destinationHierarchyChildItem = destinationItemFor(scenario.originHierarchy, scenario.destinationHierarchy, scenario.hierarchyChildItemId);

		String previousDestinationActive = destinationItemFor(scenario.originNews, scenario.destinationNews, scenario.originNewsItemIds.get(0));
		String updatedOriginActive = scenario.updateFirstNews();
		Map<String, Object> updateRun = startAndAwait(newsMigrationId, "UPDATE", UserConfig.USER_ADMINISTRATOR_ID);
		assertFinishedCount(updateRun, FullScenarioFixture.NEWS_CONTENT_COUNT);
		String updatedDestinationActive = destinationItemFor(scenario.originNews, scenario.destinationNews, updatedOriginActive);
		assertNotEquals("UPDATE creates the current destination version", previousDestinationActive, updatedDestinationActive);
		Map<String, Object> updatedNews = PortalFixture.inTransaction(
				(session, transaction) -> ContentMetadataFixture.readNews(session, transaction, scenario.destinationNews.getId(), updatedDestinationActive));
		assertEquals("news 00 updated", updatedNews.get("title"));
		assertPublishedAndActiveVersionsMatch(updatedOriginActive, updatedDestinationActive);

		List<String> destinationNewsItems = destinationItems(scenario.originNews, scenario.destinationNews);
		String destinationMediaId = mapDestinationItem(scenario.originMediaRepository, scenario.destinationMediaRepository, "MEDIA", scenario.originMediaId);
		String destinationDocumentId = mapDestinationItem(
				scenario.originDocumentRepository,
				scenario.destinationDocumentRepository,
				"DOCUMENT",
				scenario.originDocumentId);
		String destinationTermId = mapDestinationItem(
				scenario.originCategorizationRepository,
				scenario.destinationCategorizationRepository,
				"TERM",
				scenario.originSharedTermId);

		Map<String, Object> undoRequest = new HashMap<>();
		undoRequest.put("migrationId", newsMigrationId);
		undoRequest.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		ModuleBridge.api("undo", undoRequest);
		Map<String, Object> undo = await(newsMigrationId);
		assertEquals("FINISHED", undo.get("status"));
		assertEquals("UNDO", undo.get("operation"));

		for (String itemId : destinationNewsItems)
		{
			assertFalse("undo removes a destination news item", contentExists(itemId));
		}
		assertTrue("hierarchical child remains after undoing news", contentExists(destinationHierarchyChildItem));
		assertTrue("media clone shared by hierarchy remains", mediaExists(destinationMediaId));
		assertTrue("document clone shared by hierarchy remains", documentExists(destinationDocumentId));
		assertTrue("term clone shared by hierarchy remains", contentIdExists(destinationTermId));
		PortalFixture.inTransaction((session, transaction) ->
		{
			assertNotNull("destination widget remains", WidgetFixture.getWidget(scenario.destinationWidgetContentId));
			return null;
		});
		assertEquals("widget mapping is shared by both migration pairs", scenario.destinationWidgetContentId, widgetMapping());
		assertEquals(
				"hierarchical child still points to the shared document",
				destinationDocumentId,
				hierarchicalDocumentId(
						scenario.destinationHierarchy,
						destinationItemFor(scenario.originHierarchy, scenario.destinationHierarchy, scenario.hierarchyChildItemId)));
	}

	private void assertNewsWorkflowAndDependencies() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentMetadataFixture.contentLocale(scenario.originNewsItemIds.get(0));
			assertNotNull("origin has a published version", originLocale.getPublishedVersion());
			assertNotEquals("origin published and active versions differ", originLocale.getPublishedVersion(), originLocale.getActiveVersion());
			return null;
		});
		String destinationActiveItem = destinationItemFor(scenario.originNews, scenario.destinationNews, scenario.originNewsItemIds.get(0));
		assertPublishedAndActiveVersionsMatch(scenario.originNewsItemIds.get(0), destinationActiveItem);

		String destinationMediaId = mapDestinationItem(scenario.originMediaRepository, scenario.destinationMediaRepository, "MEDIA", scenario.originMediaId);
		String destinationDocumentId = mapDestinationItem(
				scenario.originDocumentRepository,
				scenario.destinationDocumentRepository,
				"DOCUMENT",
				scenario.originDocumentId);
		String destinationTermId = mapDestinationItem(
				scenario.originCategorizationRepository,
				scenario.destinationCategorizationRepository,
				"TERM",
				scenario.originSharedTermId);
		assertEquals("one media clone serves all news and the hierarchy", 1, countItemMaps(scenario.originMediaRepository, scenario.destinationMediaRepository, "MEDIA"));
		assertEquals(
				"one document clone serves all news and the hierarchy",
				1,
				countItemMaps(scenario.originDocumentRepository, scenario.destinationDocumentRepository, "DOCUMENT"));
		assertEquals(
				"root and shared term are each cloned once",
				2,
				countItemMaps(scenario.originCategorizationRepository, scenario.destinationCategorizationRepository, "TERM"));
		assertEquals("destination term path is retained", java.util.Arrays.asList("Full Migration", "Shared"), CategorizationFixture.path(destinationTermId));

		String destinationMediaFileId = mediaFileId(destinationMediaId);
		String destinationDocumentFileId = documentFileId(destinationDocumentId);
		Map<String, Object> contentMap = itemMap(scenario.originNews, scenario.destinationNews, "CONTENT", scenario.originNewsItemIds.get(0));
		String destinationHtml = PortalFixture.inTransaction(
				(session, transaction) -> ContentMetadataFixture.readNews(session, transaction, scenario.destinationNews.getId(), (String) contentMap.get("destItemId"))
						.get("content").toString());
		assertTrue("news HTML points to the cloned document file", destinationHtml.contains(destinationDocumentFileId));
		assertFalse("news HTML no longer points to the source document file", destinationHtml.contains(scenario.originDocumentFileId));
		assertTrue("news HTML points to the cloned media file", destinationHtml.contains(destinationMediaFileId));
		assertFalse("news HTML no longer points to the source media file", destinationHtml.contains(scenario.originMediaFileId));
		assertEquals(destinationMediaId, newsMediaField((String) contentMap.get("destItemId"), "introductionImage"));
		assertEquals(destinationMediaId, newsMediaField((String) contentMap.get("destItemId"), "contentImage"));
		assertEquals(destinationMediaFileId, mediaFileId(destinationMediaId));
		assertTrue("destination document file is independent", !scenario.originDocumentFileId.equals(destinationDocumentFileId));
		assertWidgetInstancesCover(destinationItems(scenario.originNews, scenario.destinationNews));
	}

	private void assertHierarchyAndDependencyReuse() throws Exception
	{
		String destinationParentItem = destinationItemFor(scenario.originHierarchy, scenario.destinationHierarchy, scenario.hierarchyParentItemId);
		String destinationChildItem = destinationItemFor(scenario.originHierarchy, scenario.destinationHierarchy, scenario.hierarchyChildItemId);
		String destinationParentContent = (String) itemMap(scenario.originHierarchy, scenario.destinationHierarchy, "CONTENT", scenario.hierarchyParentItemId)
				.get("destContentId");
		String destinationDocumentId = mapDestinationItem(
				scenario.originDocumentRepository,
				scenario.destinationDocumentRepository,
				"DOCUMENT",
				scenario.originDocumentId);
		String destinationMediaId = mapDestinationItem(scenario.originMediaRepository, scenario.destinationMediaRepository, "MEDIA", scenario.originMediaId);
		String destinationTermId = mapDestinationItem(
				scenario.originCategorizationRepository,
				scenario.destinationCategorizationRepository,
				"TERM",
				scenario.originSharedTermId);

		assertEquals("hierarchical parent is remapped", destinationParentContent, hierarchicalParentId(scenario.destinationHierarchy, destinationChildItem));
		assertEquals("document field is remapped", destinationDocumentId, hierarchicalDocumentId(scenario.destinationHierarchy, destinationChildItem));
		assertEquals(
				"news migration's document clone is reused",
				1,
				countItemMaps(scenario.originDocumentRepository, scenario.destinationDocumentRepository, "DOCUMENT"));
		assertEquals("news migration's media clone is reused", 1, countItemMaps(scenario.originMediaRepository, scenario.destinationMediaRepository, "MEDIA"));
		assertEquals(
				"news migration's term clone is reused",
				2,
				countItemMaps(scenario.originCategorizationRepository, scenario.destinationCategorizationRepository, "TERM"));
		assertEquals("hierarchical term association points at the shared clone", destinationTermId, activeCategorizationTerm(destinationChildItem));
		assertEquals("modal mapping is reused by the hierarchical migration", scenario.destinationWidgetContentId, widgetMapping());

		String hierarchyHtml = hierarchicalHtml(scenario.destinationHierarchy, destinationChildItem);
		assertTrue("hierarchy HTML uses the mapped document file", hierarchyHtml.contains(documentFileId(destinationDocumentId)));
		assertFalse("hierarchy HTML dropped the source document file id", hierarchyHtml.contains(scenario.originDocumentFileId));
		assertTrue("hierarchy HTML uses the mapped media file", hierarchyHtml.contains(mediaFileId(destinationMediaId)));
		assertFalse("hierarchy HTML dropped the source media file id", hierarchyHtml.contains(scenario.originMediaFileId));
		assertWidgetInstancesCover(java.util.Arrays.asList(destinationChildItem));
	}

	private void assertPublishedAndActiveVersionsMatch(String originItemId, String destinationItemId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentMetadataFixture.contentLocale(originItemId);
			ContentLocale destinationLocale = ContentMetadataFixture.contentLocale(destinationItemId);
			assertTrue("published and active versions are copied", destinationLocale.getVersions().size() >= 2);
			assertNotNull(destinationLocale.getPublishedVersion());
			assertNotEquals(destinationLocale.getPublishedVersion(), destinationLocale.getActiveVersion());
			ContentWorkflowMetaData originWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, originLocale);
			ContentWorkflowMetaData destinationWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, destinationLocale);
			assertEquals("workflow state is preserved", originWorkflow.getStateId(), destinationWorkflow.getStateId());
			Map<String, Object> originPublished = ContentMetadataFixture
					.readNews(session, transaction, scenario.originNews.getId(), originLocale.getPublishedVersion().getItemId());
			Map<String, Object> destinationPublished = ContentMetadataFixture
					.readNews(session, transaction, scenario.destinationNews.getId(), destinationLocale.getPublishedVersion().getItemId());
			Map<String, Object> originActive = ContentMetadataFixture
					.readNews(session, transaction, scenario.originNews.getId(), originLocale.getActiveVersion().getItemId());
			Map<String, Object> destinationActive = ContentMetadataFixture
					.readNews(session, transaction, scenario.destinationNews.getId(), destinationLocale.getActiveVersion().getItemId());
			assertEquals(originPublished.get("title"), destinationPublished.get("title"));
			assertEquals(originActive.get("title"), destinationActive.get("title"));
			assertEquals(originPublished.get("introduction"), destinationPublished.get("introduction"));
			assertEquals(originActive.get("introduction"), destinationActive.get("introduction"));
			return null;
		});
	}

	private void assertWidgetInstancesCover(List<String> destinationItemIds) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Set<String> linkedItemIds = new HashSet<>();
			for (WidgetInstance instance : WidgetInstance.findByWidgetContentId(scenario.destinationWidgetContentId))
			{
				linkedItemIds.addAll(WidgetFixture.getInstance(instance.getId()).getFieldItemIds());
			}
			for (String itemId : destinationItemIds)
			{
				assertTrue("destination widget instance is linked to " + itemId, linkedItemIds.contains(itemId));
			}
			return null;
		});
	}

	private void mapWidgetThroughAdminModal(String migrationId) throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		try
		{
			ModuleBridge.invokeStatic(
					"br.com.lumis.commons.contentmigrator.service.contentmigration.processaction.WidgetMappingProcessActionHandler",
					"saveMapping",
					migrationId,
					scenario.destinationWidgetContentId);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	@SuppressWarnings("unchecked")
	private String createMigration(ServiceInstanceConfig origin, ServiceInstanceConfig destination) throws Exception
	{
		String key = origin.getId() + ":" + destination.getId();
		String existing = migrationByPair.get(key);
		if (existing != null)
		{
			return existing;
		}
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-full-migration-" + migrationIds.size());
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		Map<String, Object> result = (Map<String, Object>) ModuleBridge.api("create", params);
		String migrationId = (String) result.get("id");
		migrationIds.add(migrationId);
		migrationByPair.put(key, migrationId);
		return migrationId;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> startAndAwait(String migrationId, String updateMode, String userId) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("migrationId", migrationId);
		params.put("userId", userId);
		if (updateMode != null)
		{
			params.put("updateMode", updateMode);
		}
		ModuleBridge.api("start", params);
		return await(migrationId);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> await(String migrationId) throws Exception
	{
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
	}

	private static void assertFinishedCount(Map<String, Object> result, int expectedCount)
	{
		assertEquals("Migration error: " + result.get("errorMessage") + "\n" + result.get("errorStack"), "FINISHED", result.get("status"));
		assertEquals("MIGRATE", result.get("operation"));
		assertEquals(expectedCount, integerValue(result.get("totalCount")));
		assertEquals(expectedCount, integerValue(result.get("migratedCount")));
	}

	private static int integerValue(Object value)
	{
		return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
	}

	private String destinationItemFor(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String originItemId) throws Exception
	{
		return (String) itemMap(origin, destination, "CONTENT", originItemId).get("destItemId");
	}

	private Map<String, Object> itemMap(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String kind, String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select destItemId, destContentId, destContentLocaleId, destCreated from lum_cmig_MigItemMap "
							+ "where originServiceInstanceId = ? and destServiceInstanceId = ? and kind = ? " + "and originItemId = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				statement.setString(3, kind);
				statement.setString(4, originItemId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("map exists for " + kind + " " + originItemId, rs.next());
					Map<String, Object> row = new HashMap<>();
					row.put("destItemId", rs.getString(1));
					row.put("destContentId", rs.getString(2));
					row.put("destContentLocaleId", rs.getString(3));
					row.put("destCreated", rs.getBoolean(4));
					return row;
				}
			}
		});
	}

	private String mapDestinationItem(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String kind, String originItemId) throws Exception
	{
		return (String) itemMap(origin, destination, kind, originItemId).get("destItemId");
	}

	private int countItemMaps(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String kind) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("select count(*) from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ? and kind = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				statement.setString(3, kind);
				try (ResultSet rs = statement.executeQuery())
				{
					rs.next();
					return rs.getInt(1);
				}
			}
		});
	}

	private List<String> destinationItems(ServiceInstanceConfig origin, ServiceInstanceConfig destination) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> loadDestinationItems(transaction, origin, destination));
	}

	private List<String> loadDestinationItems(ITransaction transaction, ServiceInstanceConfig origin, ServiceInstanceConfig destination) throws Exception
	{
		List<String> items = new ArrayList<>();
		try (PreparedStatement statement = connection(transaction).prepareStatement(
				"select destItemId from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ? and kind = 'CONTENT'"))
		{
			statement.setString(1, origin.getId());
			statement.setString(2, destination.getId());
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					if (rs.getString(1) != null)
					{
						items.add(rs.getString(1));
					}
				}
			}
		}
		return items;
	}

	private String widgetMapping() throws Exception
	{
		return PortalFixture.inTransaction(
				(
						session,
						transaction) -> (String) ModuleBridge.invokeStatic(
								ModuleBridge.WIDGET_MAP_DAO,
								"getWidgetMapping",
								connection(transaction),
								scenario.originWidgetContentId,
								scenario.destinationWidgetRepository.getId()));
	}

	private String mediaFileId(String mediaId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> MediaFixture.getMedia(mediaId).getMediaFile());
	}

	private String documentFileId(String documentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			lumis.service.document.DocumentConfig document = DocumentFixture.getDocument(session, transaction, documentId);
			return MediaFixture.getFile(session, transaction, document.getDocumentFile()).getId();
		});
	}

	private String mediaFullPath(String mediaId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			lumis.service.media.Media media = MediaFixture.getMedia(mediaId);
			return MediaFixture.getFile(session, transaction, media.getMediaFile()).getFullPath();
		});
	}

	private String newsMediaField(String itemId, String fieldId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Object value = ContentMetadataFixture.newsSource(session, transaction, scenario.destinationNews.getId()).readData(itemId).get(0).get(fieldId);
			if (value instanceof lumis.service.media.field.MediaDataType.Data)
			{
				return ((lumis.service.media.field.MediaDataType.Data) value).getMediaId();
			}
			if (value instanceof lumis.service.media.Media)
			{
				return ((lumis.service.media.Media) value).getId();
			}
			return String.valueOf(value);
		});
	}

	private String activeCategorizationTerm(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			for (ContentAssociation association : version.getContentAssociations())
			{
				if (ContentAssociation.TYPE_CATEGORIZATION_TERM.equals(association.getType()))
				{
					return association.getAssociatedContent().getId();
				}
			}
			return null;
		});
	}

	private String hierarchicalParentId(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture
				.inTransaction((session, transaction) -> (String) hierarchicalSource(session, transaction, instance).readData(itemId).get(0).get("parentContentId"));
	}

	private String hierarchicalDocumentId(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Object value = hierarchicalSource(session, transaction, instance).readData(itemId).get(0).get("linkDocumentId");
			if (value instanceof lumis.service.document.field.DocumentDataType.Data)
			{
				return ((lumis.service.document.field.DocumentDataType.Data) value).getDocumentId();
			}
			return value == null ? null : value.toString();
		});
	}

	private String hierarchicalHtml(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture
				.inTransaction((session, transaction) -> hierarchicalSource(session, transaction, instance).readData(itemId).get(0).get("linkContent").toString());
	}

	private ContentTableSource hierarchicalSource(lumis.portal.authentication.SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance)
			throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
	}

	private boolean contentExists(String itemId) throws Exception
	{
		return !PortalFixture.isNotFound((session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId));
	}

	private boolean mediaExists(String mediaId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> MediaFixture.getMedia(mediaId) != null);
	}

	private boolean documentExists(String documentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> DocumentFixture.getDocument(session, transaction, documentId) != null);
	}

	private boolean contentIdExists(String contentId) throws Exception
	{
		return !PortalFixture.isNotFound((session, transaction) -> ContentManagerFactory.getContentManager().getContent(contentId));
	}

	private void trackDestinationContents() throws Exception
	{
		if (scenario == null || scenario.originNews == null)
		{
			return;
		}
		for (ServiceInstanceConfig[] pair : scenario.migrationPairs())
		{
			List<String[]> mapped = PortalFixture.inTransaction((session, transaction) -> loadMappedContentRows(transaction, pair));
			for (String[] row : mapped)
			{
				if (pair[1].getId().equals(scenario.destinationNews.getId()))
				{
					scenario.content.track(pair[1].getId(), row[1]);
				}
				else
				{
					scenario.widgets.track(pair[1].getId(), row[0], row[1]);
				}
			}
		}
	}

	private List<String[]> loadMappedContentRows(ITransaction transaction, ServiceInstanceConfig[] pair) throws Exception
	{
		List<String[]> rows = new ArrayList<>();
		try (PreparedStatement statement = connection(transaction).prepareStatement(
				"select sourceId, destItemId from lum_cmig_MigItemMap where kind = 'CONTENT' " + "and originServiceInstanceId = ? and destServiceInstanceId = ?"))
		{
			statement.setString(1, pair[0].getId());
			statement.setString(2, pair[1].getId());
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					if (rs.getString(2) != null)
					{
						rows.add(new String[]{rs.getString(1), rs.getString(2)});
					}
				}
			}
		}
		return rows;
	}

	private void deleteMigrationData() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = connection(transaction);
			for (ServiceInstanceConfig[] pair : scenario.allMapPairs())
			{
				// @formatter:off
				update(connection, "delete from lum_cmig_MigItemMap where originServiceInstanceId = ? "
						+ "and destServiceInstanceId = ?", pair[0].getId(), pair[1].getId());
				// @formatter:on
				update(connection, "delete from lum_cmig_MigRepoMap where originRepoInstanceId = ? " + "and destRepoInstanceId = ?", pair[0].getId(), pair[1].getId());
			}
			// @formatter:off
			update(connection, "delete from lum_cmig_MigWidgetMap where originWidgetContentId = ? "
					+ "and destRepoInstanceId = ?", scenario.originWidgetContentId,
					scenario.destinationWidgetRepository.getId());
			// @formatter:on
			for (String migrationId : migrationIds)
			{
				update(connection, "delete from lum_cmig_Migration where id = ?", migrationId);
			}
			update(connection, "update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null");
			return null;
		});
	}

	private void resetLock() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			update(connection(transaction), "update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null");
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
}
