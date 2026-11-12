package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.config.ExecutionSettings;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.CategorizationFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.service.media.Media;

/** Integration coverage for deletion ownership and restartable, reference-safe undo. */
public class DeleteUndoMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final String MEDIA_REPOSITORY = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY;

	private static final String CATEGORY_REPOSITORY = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY;

	private static final String HIERARCHICAL_SERVICE_ID = "lumis.service.hierarchicalcontent";

	private PortalFixture portal;

	private ContentMetadataFixture contents;

	private MediaFixture media;

	private CategorizationFixture terms;

	private ChannelConfig channel;

	private final List<String> migrationIds = new ArrayList<>();

	private final List<ServiceInstanceConfig[]> newsPairs = new ArrayList<>();

	private final List<ServiceInstanceConfig[]> allContentPairs = new ArrayList<>();

	private final List<ServiceInstanceConfig[]> repositoryPairs = new ArrayList<>();

	private final List<String[]> nativeHierarchyItems = new ArrayList<>();

	private final List<String[]> destinationHierarchyItems = new ArrayList<>();

	private final List<String[]> nativeMediaItems = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		contents = new ContentMetadataFixture();
		media = new MediaFixture();
		terms = new CategorizationFixture();
		channel = portal.createChannel();
		resetLock();
	}

	@After
	public void tearDown() throws Exception
	{
		TestCleanup cleanup = new TestCleanup();
		System.clearProperty(ExecutionSettings.PROPERTY_ITEM_DELAY_MILLIS);
		for (String migrationId : migrationIds)
		{
			try
			{
				ModuleBridge.api("stop", migrationId);
				ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
			}
			catch (Exception e)
			{
				if (!"NOT_FOUND".equals(ModuleBridge.errorCode(e)))
				{
					throw e;
				}
			}
		}

		if (!PortalFixture.keepTestData())
		{
			trackMappedNews();
			cleanup.run(contents::cleanup);
			cleanupHierarchyItems();
			cleanupNativeMediaItems();
			trackCreatedMediaClones();
			cleanup.run(media::cleanup);
			trackCreatedTermClones();
			cleanup.run(terms::cleanup);
			deleteMigrationData();
		}
		else
		{
			cleanup.run(contents::cleanup, media::cleanup, terms::cleanup);
		}
		cleanup.run(portal::cleanup);
		cleanup.assertNoFailures();
	}

	@Test
	public void deleteKeepsSharedRepositoryMapsUntilLastMigration() throws Exception
	{
		ServiceInstanceConfig originMedia = portal.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig destinationMedia = portal.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		repositoryPairs.add(new ServiceInstanceConfig[]{originMedia, destinationMedia});
		String sourceMediaId = media.addMedia(originMedia, null, "shared media", "shared.png", "image/png", new byte[]{1, 2, 3, 4});

		ServiceInstanceConfig[] first = createNewsPair(originMedia, destinationMedia);
		ServiceInstanceConfig[] second = createNewsPair(originMedia, destinationMedia);
		String firstOriginItem = addNews(first[0], "first", sourceMediaId);
		String secondOriginItem = addNews(second[0], "second", sourceMediaId);
		String firstMigration = createMigration(first[0], first[1]);
		String secondMigration = createMigration(second[0], second[1]);
		assertEquals("FINISHED", runMigration(firstMigration).get("status"));
		assertEquals("FINISHED", runMigration(secondMigration).get("status"));

		Map<String, Object> cloneMap = itemMap(originMedia.getId(), destinationMedia.getId(), "MEDIA", sourceMediaId);
		String destinationMediaId = (String) cloneMap.get("destItemId");
		assertNotNull("both migrations use the same repository clone", destinationMediaId);
		media.track(destinationMedia.getId(), destinationMediaId);
		contents.track(first[1].getId(), mappedContentItem(first[0], first[1], firstOriginItem));
		contents.track(second[1].getId(), mappedContentItem(second[0], second[1], secondOriginItem));
		assertEquals(1, countRepoMaps(originMedia.getId(), destinationMedia.getId()));

		ModuleBridge.api("delete", firstMigration);
		assertEquals("first migration content maps are removed", 0, countContentMaps(first[0].getId(), first[1].getId()));
		assertEquals("first destination content remains", 1, contentCount(first[1].getId()));
		assertEquals("shared media clone map remains", 1, countItemMaps(originMedia.getId(), destinationMedia.getId(), "MEDIA"));
		assertEquals("shared repository pair remains", 1, countRepoMaps(originMedia.getId(), destinationMedia.getId()));
		assertNotNull("destination media remains", getMedia(destinationMediaId));

		ModuleBridge.api("delete", secondMigration);
		assertEquals("last migration content maps are removed", 0, countContentMaps(second[0].getId(), second[1].getId()));
		assertEquals("second destination content also remains", 1, contentCount(second[1].getId()));
		assertEquals("last delete removes repository clone maps", 0, countItemMaps(originMedia.getId(), destinationMedia.getId(), "MEDIA"));
		assertEquals("last delete removes the repository pair map", 0, countRepoMaps(originMedia.getId(), destinationMedia.getId()));
		assertNotNull("delete never deletes destination content", getMedia(destinationMediaId));
	}

	@Test
	public void undoRemovesUnusedClonesAndKeepsReusedAndReferencedDependencies() throws Exception
	{
		ServiceInstanceConfig originMedia = portal.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig destinationMedia = portal.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig originCategories = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		ServiceInstanceConfig destinationCategories = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		repositoryPairs.add(new ServiceInstanceConfig[]{originMedia, destinationMedia});
		repositoryPairs.add(new ServiceInstanceConfig[]{originCategories, destinationCategories});
		ServiceInstanceConfig[] news = createNewsPair(originMedia, destinationMedia);
		portal.setDependency(originCategories, news[0], CATEGORY_REPOSITORY);
		portal.setDependency(destinationCategories, news[1], CATEGORY_REPOSITORY);

		String originRoot = media.addFolder(originMedia, null, "reused folder");
		String sourceReferencedMedia = media.addMedia(originMedia, originRoot, "referenced", "referenced.png", "image/png", new byte[]{5, 4, 3, 2, 1});
		String originNewFolder = media.addFolder(originMedia, null, "created folder");
		String sourceUnusedMedia = media.addMedia(originMedia, originNewFolder, "unused", "unused.png", "image/png", new byte[]{8, 7, 6, 5});
		String destinationReusedFolder = media.addFolder(destinationMedia, null, "reused folder");

		String originTermRoot = terms.addTerm(originCategories, null, "Root");
		String originExistingTerm = terms.addTerm(originCategories, originTermRoot, "Existing");
		String originCreatedTerm = terms.addTerm(originCategories, originTermRoot, "Created");
		String destinationTermRoot = terms.addTerm(destinationCategories, null, "Root");
		String destinationExistingTerm = terms.addTerm(destinationCategories, destinationTermRoot, "Existing");

		String firstOriginItem = addNews(news[0], "referenced media", sourceReferencedMedia);
		addCategorizationTerm(firstOriginItem, originExistingTerm);
		String secondOriginItem = addNews(news[0], "unused media", sourceUnusedMedia);
		addCategorizationTerm(secondOriginItem, originCreatedTerm);
		String migrationId = createMigration(news[0], news[1]);
		Map<String, Object> migrated = runMigration(migrationId);
		assertEquals("migration error: " + migrated.get("errorMessage"), "FINISHED", migrated.get("status"));

		Map<String, Object> referencedMap = itemMap(originMedia.getId(), destinationMedia.getId(), "MEDIA", sourceReferencedMedia);
		Map<String, Object> unusedMap = itemMap(originMedia.getId(), destinationMedia.getId(), "MEDIA", sourceUnusedMedia);
		Map<String, Object> reusedFolderMap = itemMap(originMedia.getId(), destinationMedia.getId(), "FOLDER", originRoot);
		Map<String, Object> createdFolderMap = itemMap(originMedia.getId(), destinationMedia.getId(), "FOLDER", originNewFolder);
		assertFalse("matching destination folder is reused", (Boolean) reusedFolderMap.get("destCreated"));
		assertEquals(destinationReusedFolder, reusedFolderMap.get("destItemId"));
		assertTrue("missing destination folder is owned", (Boolean) createdFolderMap.get("destCreated"));
		String referencedCloneId = (String) referencedMap.get("destItemId");
		String unusedCloneId = (String) unusedMap.get("destItemId");
		String createdFolderId = (String) createdFolderMap.get("destItemId");
		String unusedFileId = getMedia(unusedCloneId).getMediaFile();
		String unusedFilePath = PortalFixture.inTransaction((session, transaction) -> MediaFixture.getFile(session, transaction, unusedFileId).getFullPath());
		contents.track(news[1].getId(), mappedContentItem(news[0], news[1], firstOriginItem));
		contents.track(news[1].getId(), mappedContentItem(news[0], news[1], secondOriginItem));
		trackCreatedMediaClones();

		String nativeNews = addNews(news[1], "native reference", referencedCloneId);
		addCategorizationTerm(nativeNews, destinationExistingTerm);
		String nativeMedia = media.addMedia(destinationMedia, createdFolderId, "native in clone folder", "native.png", "image/png", new byte[]{3, 1, 4, 1, 5});
		nativeMediaItems.add(new String[]{destinationMedia.getId(), nativeMedia});
		Map<String, Object> createdTermMap = itemMap(originCategories.getId(), destinationCategories.getId(), "TERM", originCreatedTerm);
		String createdTermContentId = (String) createdTermMap.get("destItemId");

		Map<String, Object> undo = requestUndo(migrationId, null);
		Map<String, Object> finalState = await(migrationId);
		assertEquals("undo error: " + finalState.get("errorMessage"), "FINISHED", finalState.get("status"));
		assertEquals("all migrated news mappings are removed", 0, countContentMaps(news[0].getId(), news[1].getId()));
		assertEquals("native news remains", 1, contentCount(news[1].getId()));
		assertEquals("referenced media clone is retained", referencedCloneId, getMedia(referencedCloneId).getId());
		assertNotNull("native media keeps its cloned parent folder", getMedia(createdFolderId));
		assertNotNull("preexisting destination folder remains", getMedia(destinationReusedFolder));
		assertNull("unreferenced clone is deleted", getMedia(unusedCloneId));
		assertFalse("native file hook removes the clone file", MediaFixture.storedFileExists(unusedFilePath));
		assertEquals("referenced media keeps its map with a warning", 1, countItemMapsByOriginItem(originMedia.getId(), destinationMedia.getId(), sourceReferencedMedia));
		assertEquals("unreferenced media map is removed", 0, countItemMapsByOriginItem(originMedia.getId(), destinationMedia.getId(), sourceUnusedMedia));
		assertEquals("reused folder map is released", 0, countItemMapsByOriginItem(originMedia.getId(), destinationMedia.getId(), originRoot));
		assertEquals("folder protected by native child keeps its map", 1, countItemMapsByOriginItem(originMedia.getId(), destinationMedia.getId(), originNewFolder));
		assertTrue(
				"referenced media map records why it was retained",
				String.valueOf(itemMap(originMedia.getId(), destinationMedia.getId(), "MEDIA", sourceReferencedMedia).get("warnings")).contains("still referenced"));
		assertTrue(
				"folder map records its native child reference",
				String.valueOf(itemMap(originMedia.getId(), destinationMedia.getId(), "FOLDER", originNewFolder).get("warnings")).contains("still referenced"));
		assertTrue("created term is removed after its migrated association is deleted", !contentExists(createdTermContentId));
		assertTrue("reused destination term remains", contentExists(destinationExistingTerm));
		assertTrue("reused destination term's parent remains", contentExists(destinationTermRoot));
		assertEquals("reused term map is released", 0, countItemMapsByOriginItem(originCategories.getId(), destinationCategories.getId(), originExistingTerm));
		assertEquals("undo operation is recorded", "UNDO", undo.get("operation"));
	}

	@Test
	public void undoStopsChildFirstAndCanRetryWhileKeepingNativeParentReference() throws Exception
	{
		ServiceInstanceConfig origin = portal.createServiceInstance(channel, HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig destination = portal.createServiceInstance(channel, HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig[] pair = new ServiceInstanceConfig[]{origin, destination};
		allContentPairs.add(pair);
		String originParentItem = addHierarchical(origin, "source parent", null);
		String originParentContent = contentId(originParentItem);
		String originChildItem = addHierarchical(origin, "source child", originParentContent);
		String migrationId = createMigration(origin, destination);
		Map<String, Object> run = runMigration(migrationId);
		assertEquals("FINISHED", run.get("status"));
		String destinationParentItem = mappedContentItem(origin, destination, originParentItem);
		String destinationChildItem = mappedContentItem(origin, destination, originChildItem);
		destinationHierarchyItems.add(new String[]{destination.getId(), "default", destinationParentItem});
		destinationHierarchyItems.add(new String[]{destination.getId(), "default", destinationChildItem});
		String destinationParentContent = contentId(destinationParentItem);
		String nativeChildItem = addHierarchical(destination, "native child", destinationParentContent);
		nativeHierarchyItems.add(new String[]{destination.getId(), "default", nativeChildItem});
		addStandardAssociation(nativeChildItem, destinationParentContent);

		System.setProperty(ExecutionSettings.PROPERTY_ITEM_DELAY_MILLIS, "1500");
		Map<String, Object> running = requestUndo(migrationId, null);
		assertTrue("undo was submitted", "RUNNING".equals(running.get("status")) || "FINISHED".equals(running.get("status")));
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		assertApiError(MigrationApiCode.LOCKED, () -> ModuleBridge.api("start", start));
		assertApiError(MigrationApiCode.LOCKED, () -> ModuleBridge.api("delete", migrationId));
		ModuleBridge.api("stop", migrationId);
		Map<String, Object> stopped = await(migrationId);
		assertEquals("stop ends the in-flight item cleanly", "STOPPED", stopped.get("status"));
		assertNull("a requested stop is not an error", stopped.get("errorType"));
		assertNull("a requested stop has no error message", stopped.get("errorMessage"));
		assertFalse("mapped child is removed before parent", contentExistsByItem(destinationChildItem));
		assertTrue("parent is still present while native child refers to it", contentExistsByItem(destinationParentItem));

		System.clearProperty(ExecutionSettings.PROPERTY_ITEM_DELAY_MILLIS);
		Map<String, Object> retried = requestUndo(migrationId, null);
		Map<String, Object> finalState = await(migrationId);
		assertEquals("retry finishes without an error", "FINISHED", finalState.get("status"));
		assertNull(finalState.get("errorType"));
		assertNull(finalState.get("errorMessage"));
		assertTrue("native child remains", contentExistsByItem(nativeChildItem));
		assertTrue("parent remains referenced", contentExistsByItem(destinationParentItem));
		Map<String, Object> parentMap = contentMap(origin, destination, originParentItem);
		assertNotNull("referenced parent map is retained", parentMap);
		assertTrue("retained content records a warning", String.valueOf(parentMap.get("warnings")).contains("still referenced"));
		assertTrue("repeated undo is accepted", "RUNNING".equals(retried.get("status")) || "FINISHED".equals(retried.get("status")));
	}

	private ServiceInstanceConfig[] createNewsPair(ServiceInstanceConfig originMedia, ServiceInstanceConfig destinationMedia) throws Exception
	{
		ServiceInstanceConfig origin = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		ServiceInstanceConfig destination = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		ServiceInstanceConfig[] pair = new ServiceInstanceConfig[]{origin, destination};
		newsPairs.add(pair);
		allContentPairs.add(pair);
		portal.setDependency(originMedia, origin, MEDIA_REPOSITORY);
		portal.setDependency(destinationMedia, destination, MEDIA_REPOSITORY);
		return pair;
	}

	private String addNews(ServiceInstanceConfig instance, String title, String mediaId) throws Exception
	{
		Map<String, Object> data = ContentMetadataFixture.news(title, "introduction " + title, "body " + title);
		if (mediaId != null)
		{
			data.put("introductionImage", mediaId);
			data.put("contentImage", mediaId);
			data.put("socialImage", mediaId);
		}
		return contents.addNews(UserConfig.USER_ADMINISTRATOR_ID, instance, data, null, null, null);
	}

	private void addCategorizationTerm(String itemId, String termContentId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			ContentAssociation association = version.addContentAssociation();
			association.setType(ContentAssociation.TYPE_CATEGORIZATION_TERM);
			association.setAssociatedContent(ContentManagerFactory.getContentManager().getContent(termContentId));
			return null;
		});
	}

	private String addHierarchical(ServiceInstanceConfig instance, String title, String parentContentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			Map<String, Object> data = new HashMap<>();
			data.put("title", title);
			data.put("introduction", "introduction " + title);
			data.put("type", 4);
			data.put("position", 0);
			data.put("hidden", false);
			data.put("parentContentId", parentContentId);
			return hierarchicalSource(session, transaction, instance).addData(data).getItemId();
		});
	}

	private void addStandardAssociation(String itemId, String contentId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			ContentAssociation association = version.addContentAssociation();
			association.setType(ContentAssociation.TYPE_STANDARD);
			association.setAssociatedContent(ContentManagerFactory.getContentManager().getContent(contentId));
			return null;
		});
	}

	private ContentTableSource hierarchicalSource(lumis.portal.authentication.SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance)
			throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
	}

	@SuppressWarnings("unchecked")
	private String createMigration(ServiceInstanceConfig origin, ServiceInstanceConfig destination) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-delete-undo");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		Map<String, Object> result = (Map<String, Object>) ModuleBridge.api("create", params);
		String id = (String) result.get("id");
		migrationIds.add(id);
		return id;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runMigration(String migrationId) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("migrationId", migrationId);
		params.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		ModuleBridge.api("start", params);
		return await(migrationId);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> requestUndo(String migrationId, String userId) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("migrationId", migrationId);
		if (userId != null)
		{
			params.put("userId", userId);
		}
		return (Map<String, Object>) ModuleBridge.api("undo", params);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> await(String migrationId) throws Exception
	{
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
	}

	private static void assertApiError(String expected, CheckedAction action) throws Exception
	{
		try
		{
			action.run();
		}
		catch (Exception e)
		{
			assertEquals(expected, ModuleBridge.errorCode(e));
			return;
		}
		throw new AssertionError("Expected MigrationApi error " + expected);
	}

	private Map<String, Object> itemMap(String originRepository, String destinationRepository, String kind, String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			// @formatter:off
			String sql = "select id, destItemId, destContentLocaleId, destContentId, destCreated, warnings "
					+ "from lum_cmig_MigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ? "
					+ "and kind = ? and originItemId = ?";
			// @formatter:on
			try (PreparedStatement statement = connection(transaction).prepareStatement(sql))
			{
				statement.setString(1, originRepository);
				statement.setString(2, destinationRepository);
				statement.setString(3, kind);
				statement.setString(4, originItemId);
				try (ResultSet rs = statement.executeQuery())
				{
					if (!rs.next())
					{
						return null;
					}
					Map<String, Object> row = new HashMap<>();
					row.put("id", rs.getString(1));
					row.put("destItemId", rs.getString(2));
					row.put("destContentLocaleId", rs.getString(3));
					row.put("destContentId", rs.getString(4));
					row.put("destCreated", rs.getBoolean(5));
					row.put("warnings", rs.getString(6));
					return row;
				}
			}
		});
	}

	private Map<String, Object> contentMap(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select id, destItemId, destContentId, destContentLocaleId, warnings from lum_cmig_MigItemMap "
							+ "where kind = 'CONTENT' and originServiceInstanceId = ? and destServiceInstanceId = ? " + "and originItemId = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				statement.setString(3, originItemId);
				try (ResultSet rs = statement.executeQuery())
				{
					if (!rs.next())
					{
						return null;
					}
					Map<String, Object> row = new HashMap<>();
					row.put("id", rs.getString(1));
					row.put("destItemId", rs.getString(2));
					row.put("destContentId", rs.getString(3));
					row.put("destContentLocaleId", rs.getString(4));
					row.put("warnings", rs.getString(5));
					return row;
				}
			}
		});
	}

	private String mappedContentItem(ServiceInstanceConfig origin, ServiceInstanceConfig destination, String originItemId) throws Exception
	{
		Map<String, Object> row = contentMap(origin, destination, originItemId);
		assertNotNull("content map exists for origin item " + originItemId, row);
		return (String) row.get("destItemId");
	}

	private String contentId(String itemId) throws Exception
	{
		return PortalFixture.inTransaction(
				(session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId).getContentLocale().getContent().getId());
	}

	private boolean contentExists(String contentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try
			{
				ContentManagerFactory.getContentManager().getContent(contentId);
				return true;
			}
			catch (PortalObjectNotFoundException e)
			{
				return false;
			}
		});
	}

	private boolean contentExistsByItem(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> contentExistsByItem(session, transaction, itemId));
	}

	private boolean contentExistsByItem(lumis.portal.authentication.SessionConfig session, ITransaction transaction, String itemId) throws Exception
	{
		try
		{
			ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			return true;
		}
		catch (PortalObjectNotFoundException e)
		{
			return false;
		}
	}

	private Media getMedia(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> MediaFixture.getMedia(itemId));
	}

	private int contentCount(String serviceInstanceId) throws Exception
	{
		// @formatter:off
		return queryInt("select count(*) from lum_Content where serviceInstanceId = ? and sourceId = 'default'",
				serviceInstanceId);
		// @formatter:on
	}

	private int countContentMaps(String origin, String destination) throws Exception
	{
		// @formatter:off
		return queryInt("select count(*) from lum_cmig_MigItemMap where kind = 'CONTENT' "
				+ "and originServiceInstanceId = ? and destServiceInstanceId = ?", origin, destination);
		// @formatter:on
	}

	private int countItemMaps(String origin, String destination, String kind) throws Exception
	{
		// @formatter:off
		return queryInt("select count(*) from lum_cmig_MigItemMap where kind = ? "
				+ "and originServiceInstanceId = ? and destServiceInstanceId = ?", kind, origin, destination);
		// @formatter:on
	}

	private int countItemMapsByOriginItem(String origin, String destination, String originItemId) throws Exception
	{
		// @formatter:off
		return queryInt("select count(*) from lum_cmig_MigItemMap where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? and originItemId = ?", origin, destination, originItemId);
		// @formatter:on
	}

	private int countRepoMaps(String origin, String destination) throws Exception
	{
		// @formatter:off
		return queryInt("select count(*) from lum_cmig_MigRepoMap where originRepoInstanceId = ? "
				+ "and destRepoInstanceId = ?", origin, destination);
		// @formatter:on
	}

	private int queryInt(String sql, Object... parameters) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(sql))
			{
				for (int i = 0; i < parameters.length; i++)
				{
					statement.setObject(i + 1, parameters[i]);
				}
				try (ResultSet rs = statement.executeQuery())
				{
					rs.next();
					return rs.getInt(1);
				}
			}
		});
	}

	private void trackMappedNews() throws Exception
	{
		for (ServiceInstanceConfig[] pair : newsPairs)
		{
			PortalFixture.inTransaction((session, transaction) -> trackMappedNews(transaction, pair));
		}
	}

	private Object trackMappedNews(ITransaction transaction, ServiceInstanceConfig[] pair) throws Exception
	{
		try (PreparedStatement statement = connection(transaction).prepareStatement(
				"select destItemId from lum_cmig_MigItemMap where kind = 'CONTENT' " + "and originServiceInstanceId = ? and destServiceInstanceId = ?"))
		{
			statement.setString(1, pair[0].getId());
			statement.setString(2, pair[1].getId());
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					contents.track(pair[1].getId(), rs.getString(1));
				}
			}
		}
		return null;
	}

	private void trackCreatedMediaClones() throws Exception
	{
		for (ServiceInstanceConfig[] pair : repositoryPairs)
		{
			if (PortalFixture.MEDIA_SERVICE_ID.equals(pair[1].getServiceId()))
			{
				PortalFixture.inTransaction((session, transaction) -> trackCreatedMediaClones(transaction, pair));
			}
		}
	}

	private Object trackCreatedMediaClones(ITransaction transaction, ServiceInstanceConfig[] pair) throws Exception
	{
		List<String[]> rows = new ArrayList<>();
		try (PreparedStatement statement = connection(transaction).prepareStatement(
				"select destItemId, kind from lum_cmig_MigItemMap where originServiceInstanceId = ? "
						+ "and destServiceInstanceId = ? and destCreated = ? and kind in ('MEDIA','FOLDER')"))
		{
			statement.setString(1, pair[0].getId());
			statement.setString(2, pair[1].getId());
			statement.setBoolean(3, true);
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					rows.add(new String[]{rs.getString(1), rs.getString(2)});
				}
			}
		}
		Collections.sort(rows, Comparator.comparingInt(row -> mediaDepth(row[0])));
		for (String[] row : rows)
		{
			media.track(pair[1].getId(), row[0]);
		}
		return null;
	}

	private int mediaDepth(String itemId)
	{
		try
		{
			int depth = 0;
			Media item = MediaFixture.getMedia(itemId);
			while (item != null && item.getParentFolder() != null)
			{
				depth++;
				item = MediaFixture.getMedia(item.getParentFolder());
			}
			return depth;
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private void trackCreatedTermClones() throws Exception
	{
		for (ServiceInstanceConfig[] pair : repositoryPairs)
		{
			if (CategorizationFixture.SERVICE_ID.equals(pair[1].getServiceId()))
			{
				PortalFixture.inTransaction((session, transaction) -> trackCreatedTermClones(transaction, pair));
			}
		}
	}

	private Object trackCreatedTermClones(ITransaction transaction, ServiceInstanceConfig[] pair) throws Exception
	{
		try (PreparedStatement statement = connection(transaction).prepareStatement(
				"select destItemId from lum_cmig_MigItemMap where originServiceInstanceId = ? "
						+ "and destServiceInstanceId = ? and kind = 'TERM' and destCreated = ?"))
		{
			statement.setString(1, pair[0].getId());
			statement.setString(2, pair[1].getId());
			statement.setBoolean(3, true);
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					terms.track(pair[1].getId(), rs.getString(1));
				}
			}
		}
		return null;
	}

	private void cleanupHierarchyItems() throws Exception
	{
		List<String[]> all = new ArrayList<>(nativeHierarchyItems);
		List<String[]> migrated = new ArrayList<>(destinationHierarchyItems);
		Collections.reverse(migrated);
		all.addAll(migrated);
		for (String[] entry : all)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				if (contentExistsByItem(session, transaction, entry[2]))
				{
					ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(entry[0], transaction);
					ContentTableSource source = hierarchicalSource(session, transaction, instance);
					source.deleteDataByItemIds(Collections.singleton(entry[2]));
				}
				return null;
			});
		}
	}

	private void cleanupNativeMediaItems() throws Exception
	{
		for (String[] entry : nativeMediaItems)
		{
			if (getMedia(entry[1]) != null)
			{
				ServiceInstanceConfig instance = PortalFixture
						.inTransaction((session, transaction) -> ManagerFactory.getServiceInstanceManager().get(entry[0], transaction));
				MediaFixture.deleteMedia(instance, entry[1]);
			}
		}
	}

	private void deleteMigrationData() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = connection(transaction);
			for (ServiceInstanceConfig[] pair : allContentPairs)
			{
				deletePairMaps(connection, pair[0].getId(), pair[1].getId());
			}
			for (ServiceInstanceConfig[] pair : repositoryPairs)
			{
				deletePairMaps(connection, pair[0].getId(), pair[1].getId());
			}
			for (String migrationId : migrationIds)
			{
				try (PreparedStatement statement = connection.prepareStatement("delete from lum_cmig_Migration where id = ?"))
				{
					statement.setString(1, migrationId);
					statement.executeUpdate();
				}
			}
			try (PreparedStatement statement = connection.prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
			{
				statement.executeUpdate();
			}
			return null;
		});
	}

	private static void deletePairMaps(Connection connection, String origin, String destination) throws Exception
	{
		for (String table : Arrays.asList("lum_cmig_MigItemMap", "lum_cmig_MigRepoMap"))
		{
			// @formatter:off
			String sql = "delete from " + table + ("lum_cmig_MigRepoMap".equals(table)
					? " where originRepoInstanceId = ? and destRepoInstanceId = ?"
					: " where originServiceInstanceId = ? and destServiceInstanceId = ?");
			// @formatter:on
			try (PreparedStatement statement = connection.prepareStatement(sql))
			{
				statement.setString(1, origin);
				statement.setString(2, destination);
				statement.executeUpdate();
			}
		}
	}

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}

	private static void resetLock() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
			{
				statement.executeUpdate();
			}
			return null;
		});
	}

	@FunctionalInterface
	private interface CheckedAction
	{
		void run() throws Exception;
	}

	private static final class MigrationApiCode
	{
		static final String LOCKED = "LOCKED";
	}
}
