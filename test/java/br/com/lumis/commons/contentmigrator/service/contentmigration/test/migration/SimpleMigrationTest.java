package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.URL;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentPublication;
import lumis.content.core.ContentVersion;
import lumis.content.core.LockType;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.ISourceData;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.doui.source.field.type.FileDataType;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.file.FileConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;

/** Integration coverage for the first content-copy stage. */
public class SimpleMigrationTest
{
	private static final String HIERARCHICAL_SERVICE_ID = "lumis.service.hierarchicalcontent";

	private static final String USERPOST_SERVICE_ID = "lumis.service.userpost";

	private static final Locale PT_BR = new Locale("pt", "BR");

	private static final Locale EN_US = new Locale("en", "US");

	private static final String AWAIT_MILLIS = "120000";

	private PortalFixture fixture;

	private ChannelConfig channel;

	private final List<String> migrationIds = new ArrayList<>();

	private final List<ServiceInstanceConfig[]> pairs = new ArrayList<>();

	private final List<String[]> originItems = new ArrayList<>();

	private final List<String[]> extraItems = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		channel = fixture.createChannel();
		resetLock();
	}

	@After
	public void tearDown() throws Exception
	{
		for (String id : migrationIds)
		{
			ModuleBridge.api("stop", id);
			ModuleBridge.api("awaitTermination", id, AWAIT_MILLIS);
		}
		if (!PortalFixture.keepTestData())
		{
			for (ServiceInstanceConfig[] pair : pairs)
			{
				deleteMappedCopies(pair[0].getId(), pair[1].getId());
			}
			for (String[] item : originItems)
			{
				deleteItem(item[0], item[1], item[2]);
			}
			for (String[] item : extraItems)
			{
				deleteItem(item[0], item[1], item[2]);
			}
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = connection(transaction);
				for (ServiceInstanceConfig[] pair : pairs)
				{
					update(
							connection,
							"delete from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ?",
							pair[0].getId(),
							pair[1].getId());
				}
				for (String id : migrationIds)
				{
					update(connection, "delete from lum_cmig_Migration where id = ?", id);
				}
				update(connection, "update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null");
				return null;
			});
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void migratesLocalesTogetherAndRemapsHierarchyAndStandardRelations() throws Exception
	{
		ServiceInstanceConfig[] news = pair(PortalFixture.NEWS_SERVICE_ID);
		String ptItem = addNews(news[0], PT_BR, null, true, "pt-br");
		String originContentId = PortalFixture.inTransaction(
				(session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(ptItem).getContentLocale().getContent().getId());
		String enItem = addNews(news[0], EN_US, originContentId, false, "en-us");
		originItems.add(new String[]{news[0].getId(), "default", ptItem});
		originItems.add(new String[]{news[0].getId(), "default", enItem});

		Map<String, Object> newsResult = runMigration(news, null);
		assertEquals("Migration error: " + newsResult.get("errorMessage"), "FINISHED", newsResult.get("status"));
		List<Map<String, Object>> localeMappings = mappings(news[0].getId(), news[1].getId());
		assertEquals(2, localeMappings.size());
		assertEquals("both locales use one destination content", localeMappings.get(0).get("destcontentid"), localeMappings.get(1).get("destcontentid"));
		PortalFixture.inTransaction((session, transaction) ->
		{
			Content destContent = ContentManagerFactory.getContentManager().getContent((String) localeMappings.get(0).get("destcontentid"));
			assertEquals(PT_BR, destContent.getDefaultLocale());
			assertEquals("both language rows were created", 2, destContent.getContentLocales().size());
			return null;
		});

		ServiceInstanceConfig[] hierarchy = pair(HIERARCHICAL_SERVICE_ID);
		String parentItem = addHierarchical(hierarchy[0], "parent", null);
		String parentContentId = PortalFixture.inTransaction(
				(session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(parentItem).getContentLocale().getContent().getId());
		String childItem = addHierarchical(hierarchy[0], "child", parentContentId);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion child = ContentManagerFactory.getContentManager().getContentVersionByItemId(childItem);
			ContentAssociation association = child.addContentAssociation();
			association.setType(ContentAssociation.TYPE_STANDARD);
			association.setAssociatedContent(ContentManagerFactory.getContentManager().getContent(parentContentId));
			return null;
		});
		originItems.add(new String[]{hierarchy[0].getId(), "default", parentItem});
		originItems.add(new String[]{hierarchy[0].getId(), "default", childItem});

		Map<String, Object> hierarchyResult = runMigration(hierarchy, null);
		assertEquals("Migration error: " + hierarchyResult.get("errorMessage"), "FINISHED", hierarchyResult.get("status"));
		Map<String, Map<String, Object>> byOriginItem = new HashMap<>();
		for (Map<String, Object> row : mappings(hierarchy[0].getId(), hierarchy[1].getId()))
		{
			byOriginItem.put((String) row.get("originitemid"), row);
		}
		String destParentContentId = (String) byOriginItem.get(parentItem).get("destcontentid");
		String destChildItemId = (String) byOriginItem.get(childItem).get("destitemid");
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentTableSource source = source(session, transaction, hierarchy[1], "default");
			ISourceData childRow = source.readData(destChildItemId).get(0);
			assertEquals("parent content id was remapped", destParentContentId, childRow.get("parentContentId", String.class));
			ContentVersion childVersion = ContentManagerFactory.getContentManager().getContentVersionByItemId(destChildItemId);
			Collection<ContentAssociation> associations = childVersion.getContentAssociations();
			assertEquals(1, associations.size());
			assertEquals("standard relation was remapped", destParentContentId, associations.iterator().next().getAssociatedContent().getId());
			return null;
		});
	}

	@Test
	public void updateModeReplacesMappedVersionAndRecreatesDeletedDestination() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(PortalFixture.NEWS_SERVICE_ID);
		String originItemId = addNews(pair[0], PT_BR, null, true, "before update");
		originItems.add(new String[]{pair[0].getId(), "default", originItemId});
		Map<String, Object> initialResult = runMigration(pair, null);
		assertEquals("Migration error: " + initialResult.get("errorMessage"), "FINISHED", initialResult.get("status"));
		Map<String, Object> firstMap = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String initialDestItemId = (String) firstMap.get("destitemid");

		String updatedOriginItemId = ContentMetadataFixture.updateNews(
				UserConfig.USER_ADMINISTRATOR_ID,
				pair[0],
				originItemId,
				ContentMetadataFixture.news("after update", "updated intro", "updated body"),
				null,
				null);
		originItems.add(new String[]{pair[0].getId(), "default", updatedOriginItemId});
		Map<String, Object> updateResult = runMigration(pair, "UPDATE");
		assertEquals("Migration error: " + updateResult.get("errorMessage"), "FINISHED", updateResult.get("status"));
		Map<String, Object> updatedMap = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String updatedDestItemId = (String) updatedMap.get("destitemid");
		assertNotEquals("update creates a new version", initialDestItemId, updatedDestItemId);
		Map<String, Object> destRow = PortalFixture
				.inTransaction((session, transaction) -> ContentMetadataFixture.readNews(session, transaction, pair[1].getId(), updatedDestItemId));
		assertEquals("after update", destRow.get("title"));

		deleteItem(pair[1].getId(), "default", updatedDestItemId);
		Map<String, Object> recreatedResult = runMigration(pair, "UPDATE");
		assertEquals("Migration error: " + recreatedResult.get("errorMessage"), "FINISHED", recreatedResult.get("status"));
		Map<String, Object> recreatedMap = mappings(pair[0].getId(), pair[1].getId()).get(0);
		assertNotEquals("missing mapped destination is recreated", updatedDestItemId, recreatedMap.get("destitemid"));

		String currentDestLocaleId = (String) recreatedMap.get("destcontentlocaleid");
		String nextOriginItemId = ContentMetadataFixture
				.updateNews(UserConfig.USER_ADMINISTRATOR_ID, pair[0], updatedOriginItemId, ContentMetadataFixture.news("locked update", "intro", "body"), null, null);
		originItems.add(new String[]{pair[0].getId(), "default", nextOriginItemId});
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getContentManager().lock(currentDestLocaleId, LockType.EXPLICIT);
			return null;
		});
		try
		{
			Map<String, Object> lockedResult = runMigration(pair, "UPDATE");
			assertEquals("STOPPED_WITH_ERROR", lockedResult.get("status"));
			assertTrue(String.valueOf(lockedResult.get("errorMessage")).contains("after update"));
		}
		finally
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				ContentManagerFactory.getContentManager().releaseLock(currentDestLocaleId);
				return null;
			});
		}
	}

	@Test
	public void rejectsLocaleOutsideDestinationChannelAndNamesTheLocale() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(PortalFixture.NEWS_SERVICE_ID);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ChannelConfig destinationChannel = ManagerFactory.getChannelManager().get(session, pair[1].getChannelId(), transaction);
			destinationChannel.setAcceptableLocales(Arrays.asList(PT_BR));
			ManagerFactory.getChannelManager().update(session, destinationChannel, transaction);
			return null;
		});
		String ptItem = addNews(pair[0], PT_BR, null, true, "accepted");
		String originContentId = PortalFixture.inTransaction(
				(session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(ptItem).getContentLocale().getContent().getId());
		String enItem = addNews(pair[0], EN_US, originContentId, false, "rejected");
		originItems.add(new String[]{pair[0].getId(), "default", ptItem});
		originItems.add(new String[]{pair[0].getId(), "default", enItem});

		Map<String, Object> result = runMigration(pair, null);
		assertEquals("STOPPED_WITH_ERROR", result.get("status"));
		assertEquals("LOCALE_NOT_ACCEPTED", result.get("errorType"));
		assertTrue(String.valueOf(result.get("errorMessage")).contains("en_US"));
		assertEquals("blocked source item recorded", enItem, result.get("blockingOriginItemId"));
	}

	@Test
	public void userPostFilesAreCopiedIntoNewDestinationFiles() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(USERPOST_SERVICE_ID);
		byte[] bytes = "content migration file clone ção".getBytes(StandardCharsets.UTF_8);
		Path tempDir = Files.createTempDirectory("contentmigrator-migration-file-");
		String originPostId;
		try
		{
			File file = Files.write(tempDir.resolve("migration.txt"), bytes).toFile();
			originPostId = PortalFixture.inTransaction((session, transaction) ->
			{
				session.setLocale(PT_BR);
				Map<String, Object> data = new HashMap<>();
				data.put("title", "origin post");
				data.put("post", "origin post body");
				data.put("priority", 5);
				data.put("media", MediaFixture.fileData("migration.txt", "text/plain", file));
				return source(session, transaction, pair[0], "post").addData(data).getItemId();
			});
		}
		finally
		{
			MediaFixture.deleteRecursively(tempDir);
		}
		originItems.add(new String[]{pair[0].getId(), "post", originPostId});

		Map<String, Object> result = runMigration(pair, null);
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
		Map<String, Object> map = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String destPostId = (String) map.get("destitemid");
		String[] fileIds = PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = connection(transaction);
			try (PreparedStatement statement = connection.prepareStatement("select media from lum_UPPost where id = ? or id = ? order by id"))
			{
				statement.setString(1, originPostId);
				statement.setString(2, destPostId);
				try (ResultSet rs = statement.executeQuery())
				{
					List<String> ids = new ArrayList<>();
					while (rs.next())
					{
						ids.add(rs.getString(1));
					}
					assertEquals(2, ids.size());
					return ids.toArray(new String[0]);
				}
			}
		});
		FileConfig[] files = PortalFixture.inTransaction(
				(
						session,
						transaction) -> new FileConfig[]{MediaFixture.getFile(session, transaction, fileIds[0]), MediaFixture.getFile(session, transaction, fileIds[1])});
		FileConfig originFile = files[0].getServiceInstanceId().equals(pair[0].getId()) ? files[0] : files[1];
		FileConfig destFile = files[0].getServiceInstanceId().equals(pair[1].getId()) ? files[0] : files[1];
		assertNotEquals(originFile.getId(), destFile.getId());
		assertEquals(pair[1].getId(), destFile.getServiceInstanceId());
		assertEquals(originFile.getName(), destFile.getName());
		assertEquals(originFile.getContentType(), destFile.getContentType());
		assertTrue(Arrays.equals(bytes, MediaFixture.readStoredFile(destFile.getFullPath())));
	}

	@Test
	public void duplicateSlugIsSuffixedAndOriginOnlyMetadataIsNotCopied() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig otherTarget = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		String originItemId = addNews(pair[0], PT_BR, null, true, "origin-slug", "migration-slug", "https://origin.example/news");
		String existingDestItemId = addNews(pair[1], PT_BR, null, true, "dest-slug", "migration-slug", null);
		originItems.add(new String[]{pair[0].getId(), "default", originItemId});
		extraItems.add(new String[]{pair[1].getId(), "default", existingDestItemId});
		PortalFixture.inTransaction((session, transaction) ->
		{
			assertEquals(
					"fixture creates an occupied destination slug",
					"migration-slug",
					ContentManagerFactory.getContentManager().getContentVersionByItemId(existingDestItemId).getSlug());
			return null;
		});
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId).getContentLocale();
			ContentLocale destinationLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(existingDestItemId).getContentLocale();
			originLocale.setPublishedVersion(originLocale.getActiveVersion());
			destinationLocale.setPublishedVersion(destinationLocale.getActiveVersion());
			ContentVersion originVersion = ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId);
			if (originVersion.getCanonicalUrl() == null)
			{
				originVersion.setCanonicalUrl(new URL("https://origin.example/news"));
			}
			for (ContentPublication publication : originVersion.getPublications())
			{
				publication.getServiceInstanceIds().add(otherTarget.getId());
			}
			return null;
		});

		Map<String, Object> result = runMigration(pair, null);
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
		Map<String, Object> map = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String destItemId = (String) map.get("destitemid");
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion destVersion = ContentManagerFactory.getContentManager().getContentVersionByItemId(destItemId);
			assertEquals("duplicate destination slug is suffixed; warnings=" + map.get("warnings"), "migration-slug-1", destVersion.getSlug());
			assertTrue("slug adjustment warning recorded", String.valueOf(map.get("warnings")).contains("migration-slug-1"));
			assertEquals("canonical URL is destination specific", null, destVersion.getCanonicalUrl());
			for (ContentPublication publication : destVersion.getPublications())
			{
				assertEquals("only the destination receives publication", Arrays.asList(pair[1].getId()), new ArrayList<>(publication.getServiceInstanceIds()));
			}
			return null;
		});
	}

	@Test
	public void suffixedSlugCutAtASlashKeepsTheNativeSlugFormat() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(PortalFixture.NEWS_SERVICE_ID);
		// 100 characters, the native maximum, with the slash right where the "-1" suffix cuts the slug
		String slug = "a".repeat(97) + "/bc";
		String originItemId = addNews(pair[0], PT_BR, null, true, "long-slug-origin", slug, null);
		String existingDestItemId = addNews(pair[1], PT_BR, null, true, "long-slug-dest", slug, null);
		originItems.add(new String[]{pair[0].getId(), "default", originItemId});
		extraItems.add(new String[]{pair[1].getId(), "default", existingDestItemId});
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale destinationLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(existingDestItemId).getContentLocale();
			destinationLocale.setPublishedVersion(destinationLocale.getActiveVersion());
			return null;
		});

		Map<String, Object> result = runMigration(pair, null);
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
		String destItemId = (String) mappings(pair[0].getId(), pair[1].getId()).get(0).get("destitemid");
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion destVersion = ContentManagerFactory.getContentManager().getContentVersionByItemId(destItemId);
			assertEquals("the cut drops the trailing slash before the suffix", "a".repeat(97) + "-1", destVersion.getSlug());
			return null;
		});
	}

	@Test
	public void publicationPrincipalsAndTargetsComeFromTheOriginPublication() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig otherTarget = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		String restrictedItemId = addNews(pair[0], PT_BR, null, true, "restricted");
		String elsewhereItemId = addNews(pair[0], PT_BR, null, true, "published elsewhere");
		originItems.add(new String[]{pair[0].getId(), "default", restrictedItemId});
		originItems.add(new String[]{pair[0].getId(), "default", elsewhereItemId});
		PortalFixture.inTransaction((session, transaction) ->
		{
			for (ContentPublication publication : ContentManagerFactory.getContentManager().getContentVersionByItemId(restrictedItemId).getPublications())
			{
				publication.getPrincipalIds().clear();
				publication.getPrincipalIds().add(UserConfig.USER_ADMINISTRATOR_ID);
			}
			for (ContentPublication publication : ContentManagerFactory.getContentManager().getContentVersionByItemId(elsewhereItemId).getPublications())
			{
				publication.getServiceInstanceIds().clear();
				publication.getServiceInstanceIds().add(otherTarget.getId());
			}
			return null;
		});

		Map<String, Object> result = runMigration(pair, null);
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
		Map<String, String> destItemByOriginItem = new HashMap<>();
		for (Map<String, Object> row : mappings(pair[0].getId(), pair[1].getId()))
		{
			destItemByOriginItem.put((String) row.get("originitemid"), (String) row.get("destitemid"));
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion restricted = ContentManagerFactory.getContentManager().getContentVersionByItemId(destItemByOriginItem.get(restrictedItemId));
			for (ContentPublication publication : restricted.getPublications())
			{
				assertEquals("the restriction to a principal is kept", Arrays.asList(UserConfig.USER_ADMINISTRATOR_ID), new ArrayList<>(publication.getPrincipalIds()));
				assertEquals(Arrays.asList(pair[1].getId()), new ArrayList<>(publication.getServiceInstanceIds()));
			}
			ContentVersion elsewhere = ContentManagerFactory.getContentManager().getContentVersionByItemId(destItemByOriginItem.get(elsewhereItemId));
			for (ContentPublication publication : elsewhere.getPublications())
			{
				assertTrue("an origin not published in its own instance is not published in the destination", publication.getServiceInstanceIds().isEmpty());
			}
			return null;
		});
	}

	@Test
	public void sourceWithoutLanguageFieldKeepsTheOriginLocale() throws Exception
	{
		ServiceInstanceConfig[] pair = pair(USERPOST_SERVICE_ID);
		Locale portalDefault = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getLocalizationManager().getDefaultLocale());
		Locale originLocale = PT_BR.equals(portalDefault) ? EN_US : PT_BR;
		String originPostId = PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(originLocale);
			Map<String, Object> data = new HashMap<>();
			data.put("title", "post in " + originLocale);
			data.put("post", "post body");
			data.put("priority", 5);
			return source(session, transaction, pair[0], "post").addData(data).getItemId();
		});
		originItems.add(new String[]{pair[0].getId(), "post", originPostId});

		Map<String, Object> result = runMigration(pair, null);
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
		String destPostId = (String) mappings(pair[0].getId(), pair[1].getId()).get(0).get("destitemid");
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale destLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(destPostId).getContentLocale();
			assertEquals("the destination keeps the origin locale, not the portal default " + portalDefault, originLocale, destLocale.getLocale());
			assertEquals(originLocale, destLocale.getContent().getDefaultLocale());
			return null;
		});
	}

	private ServiceInstanceConfig[] pair(String serviceId) throws Exception
	{
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, serviceId);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, serviceId);
		ServiceInstanceConfig[] pair = {origin, dest};
		pairs.add(pair);
		return pair;
	}

	private String addNews(ServiceInstanceConfig instance, Locale locale, String contentId, boolean defaultLocale, String suffix) throws Exception
	{
		return addNews(instance, locale, contentId, defaultLocale, suffix, null, null);
	}

	private String addNews(ServiceInstanceConfig instance, Locale locale, String contentId, boolean defaultLocale, String suffix, String slug, String canonicalUrl)
			throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(locale);
			Map<String, Object> data = ContentMetadataFixture.news("migration " + suffix, "introduction " + suffix, "body " + suffix);
			data.put("language", locale);
			data.put("language.isDefaultLocale", defaultLocale);
			if (slug != null)
			{
				data.put("slug", slug);
			}
			if (canonicalUrl != null)
			{
				data.put("canonicalUrl", canonicalUrl);
			}
			if (contentId != null)
			{
				data.put("language.contentId", contentId);
			}
			return source(session, transaction, instance, "default").addData(data).getItemId();
		});
	}

	private String addHierarchical(ServiceInstanceConfig instance, String title, String parentContentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(PT_BR);
			Map<String, Object> data = new HashMap<>();
			data.put("title", "migration " + title);
			data.put("introduction", "migration introduction " + title);
			data.put("type", 4);
			data.put("position", 0);
			data.put("hidden", false);
			data.put("parentContentId", parentContentId);
			return source(session, transaction, instance, "default").addData(data).getItemId();
		});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runMigration(ServiceInstanceConfig[] pair, String updateMode) throws Exception
	{
		String key = pair[0].getId() + ":" + pair[1].getId();
		String id = migrationByPair.get(key);
		if (id == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-simple-migration");
			create.put("originServiceInstanceId", pair[0].getId());
			create.put("destServiceInstanceId", pair[1].getId());
			Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
			id = (String) migration.get("id");
			migrationIds.add(id);
			migrationByPair.put(key, id);
		}
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", id);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		if (updateMode != null)
		{
			start.put("updateMode", updateMode);
		}
		ModuleBridge.api("start", start);
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", id, AWAIT_MILLIS);
	}

	private List<Map<String, Object>> mappings(String origin, String dest) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<Map<String, Object>> rows = new ArrayList<>();
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select * from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ? order by originContentLocaleId"))
			{
				statement.setString(1, origin);
				statement.setString(2, dest);
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						rows.add(readRow(rs));
					}
				}
			}
			return rows;
		});
	}

	private static Map<String, Object> readRow(ResultSet rs) throws Exception
	{
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
		{
			row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
		}
		return row;
	}

	private void deleteMappedCopies(String origin, String dest) throws Exception
	{
		for (Map<String, Object> row : mappings(origin, dest))
		{
			deleteItem(dest, (String) row.get("sourceid"), (String) row.get("destitemid"));
		}
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
				source(session, transaction, instance, sourceId).deleteDataByItemIds(Arrays.asList(itemId));
			}
			catch (PortalObjectNotFoundException e)
			{
				// The content or instance has already been removed.
			}
			return null;
		});
	}

	private static ContentTableSource source(lumis.portal.authentication.SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance, String sourceId)
			throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource(sourceId, new SourceContext(session, instance, transaction));
	}

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}

	private static int update(Connection connection, String sql, Object... params) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			for (int i = 0; i < params.length; i++)
			{
				statement.setObject(i + 1, params[i]);
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
}
