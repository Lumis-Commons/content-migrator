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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentVersion;
import lumis.content.source.field.MetaDataSourceField.Type;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.file.FileConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;

/** Integration coverage for repository media maps, file copies, references and updates. */
public class MediaMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final String MEDIA_REPOSITORY = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY;

	private static final byte[] ORIGINAL_BYTES = new byte[]{1, 5, 9, 13, 17, 21};

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

	private MediaFixture media;

	private ChannelConfig channel;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig destination;

	private ServiceInstanceConfig originMediaRepository;

	private ServiceInstanceConfig destinationMediaRepository;

	private final List<String> migrationIds = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	private final List<String> originNewsIds = new ArrayList<>();

	private final List<ServiceInstanceConfig[]> contentPairs = new ArrayList<>();

	private String originMediaId;

	private String originFolderId;

	private String originNestedFolderId;

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		contents = new ContentMetadataFixture();
		media = new MediaFixture();
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
			trackDestinationContents();
			cleanup.run(contents::cleanup);
			trackDestinationMedia();
			cleanup.run(media::cleanup);
			deleteMigrationMaps();
			cleanup.run(portal::cleanup);
		}
		else
		{
			cleanup.run(contents::cleanup, media::cleanup, portal::cleanup);
		}
		cleanup.assertNoFailures();
	}

	@Test
	public void clonesSharedMediaOnceRewritesFieldsAndHtmlRetriesAndUpdatesChangedBytes() throws Exception
	{
		createPair(false);
		createOriginMedia("media shared");
		String firstNewsId = addNews("first", originMediaId);
		String secondNewsId = addNews("second", originMediaId);
		originNewsIds.add(firstNewsId);
		originNewsIds.add(secondNewsId);

		System.setProperty(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE, "true");
		Map<String, Object> firstRun = runMigration(null);
		assertEquals("Migration error: " + firstRun.get("errorMessage"), "FINISHED", firstRun.get("status"));
		assertFalse("fault injection was consumed", Boolean.getBoolean(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE));

		List<MapRow> repositoryMaps = repositoryMaps();
		MapRow mediaMap = onlyMap(repositoryMaps, "MEDIA");
		assertEquals("folder map rows", 2, count(repositoryMaps, "FOLDER"));
		assertEquals("the source content locale is mapped only once", 1, countOriginRows(repositoryMaps, "MEDIA", originMediaId));
		MediaClone clone = inspectClone(mediaMap.destItemId);
		assertNotEquals("the clone has a new media id", originMediaId, clone.mediaId);
		assertNotEquals("the clone has an independent file id", clone.originFileId, clone.destFileId);
		assertEquals("source file name", clone.originName, clone.destName);
		assertEquals("source content type", clone.originContentType, clone.destContentType);
		assertEquals(destinationMediaRepository.getId(), clone.destRepositoryId);
		assertArrayEquals(ORIGINAL_BYTES, clone.destinationBytes);
		assertNotNull("source fingerprint is persisted", mediaMap.fingerprint);
		assertTrue(mediaMap.fingerprint.matches("sha256:[0-9a-f]{64}:\\d+"));

		String destNestedFolderId = parentFolderOf(clone.mediaId);
		String destRootFolderId = parentFolderOf(destNestedFolderId);
		assertEquals("nested folder map points to the parent of the media", destNestedFolderId, mapByOriginItem(repositoryMaps, originNestedFolderId).destItemId);
		assertEquals("root folder map points to the nested folder parent", destRootFolderId, mapByOriginItem(repositoryMaps, originFolderId).destItemId);
		assertEquals("folder hierarchy is preserved", destRootFolderId, parentFolderOf(destNestedFolderId));
		assertEquals("created folders are owned by the destination repository", destinationMediaRepository.getId(), serviceInstanceOf(destRootFolderId));

		for (String originNewsId : originNewsIds)
		{
			assertContentReferences(originNewsId, clone.mediaId, clone.destFileId);
		}
		assertTrue("the nested folder clone holds the media clone", isCloneReferenced(destNestedFolderId));
		assertTrue("the media clone is the social image of a migrated news", isCloneReferenced(clone.mediaId));
		assertFalse("a news is not a referenced media clone", isCloneReferenced(firstNewsId));

		String previousDestFileId = clone.destFileId;
		String oldFingerprint = mediaMap.fingerprint;
		String sourceFileId = replaceOriginFile(UPDATED_BYTES);
		assertEquals("source updates retain the file id", clone.originFileId, sourceFileId);
		Map<String, Object> updateRun = runMigration("UPDATE");
		assertEquals("Update error: " + updateRun.get("errorMessage"), "FINISHED", updateRun.get("status"));
		MapRow updatedMap = onlyMap(repositoryMaps(), "MEDIA");
		MediaClone updatedClone = inspectClone(updatedMap.destItemId);
		assertEquals("destination update retains its file id", previousDestFileId, updatedClone.destFileId);
		assertNotEquals("same source file id gets a new fingerprint", oldFingerprint, updatedMap.fingerprint);
		assertArrayEquals(UPDATED_BYTES, updatedClone.destinationBytes);
	}

	@Test
	public void reusesMatchingDestinationFolderPathAndKeepsSameRepositoryReferences() throws Exception
	{
		createPair(false);
		createOriginMedia("media reusable");
		String destRoot = media.addFolder(destinationMediaRepository, null, "media reusable root");
		String destNested = media.addFolder(destinationMediaRepository, destRoot, "nested");
		String newsId = addNews("reuse", originMediaId);
		originNewsIds.add(newsId);
		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage"), "FINISHED", run.get("status"));
		List<MapRow> maps = repositoryMaps();
		assertEquals(destRoot, mapByOriginItem(maps, originFolderId).destItemId);
		assertEquals(destNested, mapByOriginItem(maps, originNestedFolderId).destItemId);
		assertFalse("a reused folder is not owned by the migration", mapByOriginItem(maps, originFolderId).destCreated);
		assertFalse("a reused nested folder is not owned by the migration", mapByOriginItem(maps, originNestedFolderId).destCreated);
		assertEquals(destNested, parentFolderOf(onlyMap(maps, "MEDIA").destItemId));

		// A second pair sharing the exact repository must retain the media id and create no media map.
		ServiceInstanceConfig sameOrigin = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		ServiceInstanceConfig sameDestination = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		portal.setDependency(originMediaRepository, sameOrigin, MEDIA_REPOSITORY);
		portal.setDependency(originMediaRepository, sameDestination, MEDIA_REPOSITORY);
		String sameOriginNewsId = addNews(sameOrigin, "same repository", originMediaId);
		originNewsIds.add(sameOriginNewsId);
		contentPairs.add(new ServiceInstanceConfig[]{sameOrigin, sameDestination});
		ServiceInstanceConfig priorOrigin = origin;
		ServiceInstanceConfig priorDestination = destination;
		origin = sameOrigin;
		destination = sameDestination;
		Map<String, Object> sameRepoRun = runMigration(null);
		assertEquals("Same-repository error: " + sameRepoRun.get("errorMessage"), "FINISHED", sameRepoRun.get("status"));
		String sameDestNewsId = mappedContentItem(sameOriginNewsId, sameOrigin, sameDestination);
		assertEquals(originMediaId, newsMediaField(sameDestination, sameDestNewsId, "contentImage"));
		assertEquals("same repository creates no repository map rows", 0, countMaps(originMediaRepository.getId(), originMediaRepository.getId(), "MEDIA", "FOLDER"));
		contents.track(sameDestination.getId(), sameDestNewsId);
		origin = priorOrigin;
		destination = priorDestination;
	}

	@Test
	public void reusesTheSmallestIdAmongDuplicateDestinationFoldersAndWarns() throws Exception
	{
		createPair(false);
		createOriginMedia("media duplicate");
		String firstRoot = media.addFolder(destinationMediaRepository, null, "media duplicate root");
		String secondRoot = media.addFolder(destinationMediaRepository, null, "media duplicate root");
		String smallestRoot = firstRoot.compareTo(secondRoot) < 0 ? firstRoot : secondRoot;
		String newsId = addNews("duplicate", originMediaId);
		originNewsIds.add(newsId);
		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage"), "FINISHED", run.get("status"));
		assertEquals("the folder with the smallest id is reused", smallestRoot, mapByOriginItem(repositoryMaps(), originFolderId).destItemId);
		assertTrue("duplicate folders are reported", contentWarnings(newsId).contains("Multiple destination media folders named 'media duplicate root'"));
	}

	@Test
	public void keepsHtmlLinksWhenTheOriginHasNoMediaRepository() throws Exception
	{
		createPair(false);
		createOriginMedia("media without origin repository");
		removeDependency(origin, MEDIA_REPOSITORY);
		String html = mediaLinksHtml(originMediaId);
		String newsId = contents.addNews(UserConfig.USER_ADMINISTRATOR_ID, origin, ContentMetadataFixture.news("no origin repository", "intro", html), null, null, null);
		originNewsIds.add(newsId);
		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage"), "FINISHED", run.get("status"));
		String destNewsId = mappedContentItem(newsId, origin, destination);
		assertEquals("links to media of another repository are kept", newsHtml(origin, newsId), newsHtml(destination, destNewsId));
		assertEquals("no media is cloned", 0, countMaps(originMediaRepository.getId(), destinationMediaRepository.getId(), "MEDIA", "FOLDER"));
	}

	private void createPair(boolean sameRepository) throws Exception
	{
		originMediaRepository = portal.createServiceInstance(channel, "lumis.service.media");
		destinationMediaRepository = sameRepository ? originMediaRepository : portal.createServiceInstance(channel, "lumis.service.media");
		origin = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		destination = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		contentPairs.add(new ServiceInstanceConfig[]{origin, destination});
		portal.setDependency(originMediaRepository, origin, MEDIA_REPOSITORY);
		portal.setDependency(destinationMediaRepository, destination, MEDIA_REPOSITORY);
	}

	private void createOriginMedia(String title) throws Exception
	{
		originFolderId = media.addFolder(originMediaRepository, null, title + " root");
		originNestedFolderId = media.addFolder(originMediaRepository, originFolderId, "nested");
		originMediaId = media.addMedia(originMediaRepository, originNestedFolderId, title, "shared image.png", "image/png", ORIGINAL_BYTES);
	}

	private String addNews(String suffix, String mediaId) throws Exception
	{
		return addNews(origin, suffix, mediaId);
	}

	private String addNews(ServiceInstanceConfig instance, String suffix, String mediaId) throws Exception
	{
		Map<String, Object> values = ContentMetadataFixture.news("migration " + suffix, "intro " + suffix, mediaLinksHtml(mediaId));
		values.put("introductionImage", mediaId);
		values.put("contentImage", mediaId);
		values.put("socialImage", mediaId);
		String itemId = contents.addNews(UserConfig.USER_ADMINISTRATOR_ID, instance, values, null, null, null);
		return itemId;
	}

	/**
	 * Returns an html with a relative download link and a public link to the file of the media, and
	 * a public file path whose malformed escape cannot be decoded.
	 */
	private static String mediaLinksHtml(String mediaId) throws Exception
	{
		FileConfig file = PortalFixture
				.inTransaction((session, transaction) -> MediaFixture.getFile(session, transaction, MediaFixture.getMedia(mediaId).getMediaFile()));
		String publicPath = file.getFullPath().substring("files/".length()).replace(" ", "%20");
		return "<p><a href=\"/lumis/portal/file/fileDownload.jsp?fileId=" + file.getId() + "&amp;inline=true&amp;transform=thumbnail\">download</a>"
				+ "<img src=\"/data/files/" + publicPath + "\" /></p><p>" + MALFORMED_PUBLIC_PATH + "</p>";
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runMigration(String updateMode) throws Exception
	{
		String key = origin.getId() + ":" + destination.getId();
		String migrationId = migrationByPair.get(key);
		if (migrationId == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-media-migration");
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

	private void assertContentReferences(String originItemId, String destMediaId, String destFileId) throws Exception
	{
		String destItemId = mappedContentItem(originItemId, origin, destination);
		PortalFixture.inTransaction((session, transaction) ->
		{
			List<lumis.doui.source.ISourceData> rows = ContentMetadataFixture.newsSource(session, transaction, destination.getId()).readData(destItemId);
			assertEquals(1, rows.size());
			assertEquals(destMediaId, mediaId(rows.get(0).get("introductionImage")));
			assertEquals(destMediaId, mediaId(rows.get(0).get("contentImage")));
			String socialImageFieldId = ContentMetadataFixture.newsSource(session, transaction, destination.getId()).getField(Type.SocialImage).getId();
			assertEquals(destMediaId, mediaId(rows.get(0).get(socialImageFieldId)));
			String html = rows.get(0).get("content", String.class);
			assertTrue("HTML fileId is rewritten", html.contains("fileId=" + destFileId));
			assertFalse("a relative download link gets no prefix", html.contains("null/lumis/portal"));
			assertTrue("a public file path that cannot be decoded is kept", html.contains(MALFORMED_PUBLIC_PATH));
			assertFalse("HTML does not retain the source fileId", html.contains("fileId=" + originFileId()));
			assertTrue("inline query parameter is preserved", html.contains("inline=true"));
			assertTrue("transform query parameter is preserved", html.contains("transform=thumbnail"));
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(destItemId);
			assertEquals(destMediaId, version.getSocialImageMediaId());
			return null;
		});
	}

	private String originFileId() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> MediaFixture.getMedia(originMediaId).getMediaFile());
	}

	private String replaceOriginFile(byte[] bytes) throws Exception
	{
		MediaFileInfo info = PortalFixture.inTransaction((session, transaction) ->
		{
			lumis.service.media.Media originMedia = MediaFixture.getMedia(originMediaId);
			FileConfig file = MediaFixture.getFile(session, transaction, originMedia.getMediaFile());
			return new MediaFileInfo(originMedia, file);
		});
		Path tempDir = Files.createTempDirectory("contentmigrator-media-update-");
		try
		{
			File tempFile = Files.write(tempDir.resolve(info.file.getName()), bytes).toFile();
			return PortalFixture.inTransaction((session, transaction) ->
			{
				session.setLocale(ContentMetadataFixture.LOCALE);
				ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(originMediaRepository.getId(), transaction);
				ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
				Map<String, Object> data = new HashMap<>();
				data.put("id", originMediaId);
				data.put("title", info.media.getTitle());
				data.put("legend", info.media.getLegend());
				data.put("credits", info.media.getCredits());
				data.put("type", info.media.getType());
				data.put("parentFolder", info.media.getParentFolder());
				data.put("inheritPublishTo", info.media.getInheritPublishTo());
				data.put("publishToPrincipals", "");
				data.put("mediaFile", MediaFixture.fileData(info.file.getName(), info.file.getContentType(), tempFile));
				source.updateData(data);
				lumis.service.media.Media updated = MediaFixture.getMedia(originMediaId);
				ContentLocaleFileLink.set(updated.getMediaFile(), ContentMetadataFixture.contentLocale(originMediaId).getId());
				return updated.getMediaFile();
			});
		}
		finally
		{
			MediaFixture.deleteRecursively(tempDir);
		}
	}

	private String mappedContentItem(String originItemId, ServiceInstanceConfig from, ServiceInstanceConfig to) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			String localeId = ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId).getContentLocale().getId();
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
					"select destItemId from lum_cmig_MigItemMap where kind = 'CONTENT' " + "and originServiceInstanceId = ? and destServiceInstanceId = ? "
							+ "and originContentLocaleId = ?"))
			{
				statement.setString(1, from.getId());
				statement.setString(2, to.getId());
				statement.setString(3, localeId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("content map exists for " + originItemId, rs.next());
					return rs.getString(1);
				}
			}
		});
	}

	private static String newsHtml(ServiceInstanceConfig instance, String itemId) throws Exception
	{
		return PortalFixture.inTransaction(
				(session, transaction) -> ContentMetadataFixture.newsSource(session, transaction, instance.getId()).readData(itemId).get(0).get("content").toString());
	}

	/** Returns the warnings of the item map of an origin news. */
	private String contentWarnings(String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			String localeId = ContentMetadataFixture.contentLocale(originItemId).getId();
			// @formatter:off
			String sql = "select warnings from lum_cmig_MigItemMap where kind = 'CONTENT' "
					+ "and originServiceInstanceId = ? and destServiceInstanceId = ? and originContentLocaleId = ?";
			// @formatter:on
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(sql))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				statement.setString(3, localeId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("content map exists for " + originItemId, rs.next());
					return String.valueOf(rs.getString(1));
				}
			}
		});
	}

	/**
	 * Calls the registered media handler, through the module class loader, to tell whether the
	 * content locale of the given destination item is referenced.
	 */
	private boolean isCloneReferenced(String destItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Class<?> contextType = ModuleBridge.load(DEPENDENCY_CONTEXT);
			Object context = ModuleBridge.newDependencyContext(
					scopeValues(session, transaction, origin.getId(), destination.getId()),
					originMediaRepository.getId(),
					destinationMediaRepository.getId());
			Object handler = ModuleBridge.invokeStatic(HANDLER_REGISTRY, "getHandler", MEDIA_REPOSITORY);
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

	private String newsMediaField(ServiceInstanceConfig instance, String itemId, String fieldId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Object value = ContentMetadataFixture.newsSource(session, transaction, instance.getId()).readData(itemId).get(0).get(fieldId);
			return mediaId(value);
		});
	}

	private static String mediaId(Object value)
	{
		if (value instanceof lumis.service.media.field.MediaDataType.Data)
		{
			return ((lumis.service.media.field.MediaDataType.Data) value).getMediaId();
		}
		return value instanceof lumis.service.media.Media ? ((lumis.service.media.Media) value).getId() : String.valueOf(value);
	}

	private List<MapRow> repositoryMaps() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<MapRow> result = new ArrayList<>();
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
					"select kind, originItemId, destItemId, destCreated, originFileFingerprint " + "from lum_cmig_MigItemMap where originServiceInstanceId = ? "
							+ "and destServiceInstanceId = ? and kind in ('MEDIA','FOLDER')"))
			{
				statement.setString(1, originMediaRepository.getId());
				statement.setString(2, destinationMediaRepository.getId());
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						result.add(new MapRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)));
					}
				}
			}
			return result;
		});
	}

	private MapRow onlyMap(List<MapRow> maps, String kind)
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

	private int count(List<MapRow> maps, String kind)
	{
		int result = 0;
		for (MapRow row : maps)
		{
			if (kind.equals(row.kind))
			{
				result++;
			}
		}
		return result;
	}

	private int countOriginRows(List<MapRow> maps, String kind, String originId)
	{
		int result = 0;
		for (MapRow row : maps)
		{
			if (kind.equals(row.kind) && originId.equals(row.originItemId))
			{
				result++;
			}
		}
		return result;
	}

	private MapRow mapByOriginItem(List<MapRow> maps, String originId)
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

	private MediaClone inspectClone(String mediaId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			lumis.service.media.Media source = MediaFixture.getMedia(originMediaId);
			lumis.service.media.Media clone = MediaFixture.getMedia(mediaId);
			FileConfig sourceFile = MediaFixture.getFile(session, transaction, source.getMediaFile());
			FileConfig destFile = MediaFixture.getFile(session, transaction, clone.getMediaFile());
			return new MediaClone(
					mediaId,
					sourceFile.getId(),
					destFile.getId(),
					sourceFile.getName(),
					destFile.getName(),
					sourceFile.getContentType(),
					destFile.getContentType(),
					MediaFixture.readStoredFile(destFile.getFullPath()),
					destFile.getServiceInstanceId());
		});
	}

	private String parentFolderOf(String mediaId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> MediaFixture.getMedia(mediaId).getParentFolder());
	}

	private String serviceInstanceOf(String mediaId) throws Exception
	{
		return PortalFixture.inTransaction(
				(session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(mediaId).getContentLocale().getContent()
						.getServiceInstanceId());
	}

	private void trackDestinationContents() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			for (ServiceInstanceConfig[] pair : contentPairs)
			{
				trackDestinationContents(transaction, pair);
			}
			return null;
		});
	}

	private void trackDestinationContents(ITransaction transaction, ServiceInstanceConfig[] pair) throws Exception
	{
		try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
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
	}

	private void trackDestinationMedia() throws Exception
	{
		boolean repositoriesMissing = originMediaRepository == null || destinationMediaRepository == null;
		if (repositoriesMissing || originMediaRepository.getId().equals(destinationMediaRepository.getId()))
		{
			return;
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			List<String[]> entries = new ArrayList<>();
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
					"select destItemId, kind from lum_cmig_MigItemMap where originServiceInstanceId = ? "
							+ "and destServiceInstanceId = ? and kind in ('MEDIA','FOLDER') and destCreated = ?"))
			{
				statement.setString(1, originMediaRepository.getId());
				statement.setString(2, destinationMediaRepository.getId());
				statement.setBoolean(3, true);
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						entries.add(new String[]{rs.getString(1), rs.getString(2)});
					}
				}
			}
			Collections.sort(entries, Comparator.comparingInt(entry -> depth(entry[0])));
			for (String[] entry : entries)
			{
				media.track(destinationMediaRepository.getId(), entry[0]);
			}
			return null;
		});
	}

	private int depth(String mediaId)
	{
		try
		{
			int depth = 0;
			String current = mediaId;
			while (current != null)
			{
				lumis.service.media.Media item = MediaFixture.getMedia(current);
				if (item == null)
				{
					break;
				}
				current = item.getParentFolder();
				if (current != null)
				{
					depth++;
				}
			}
			return depth;
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Could not inspect media folder hierarchy for " + mediaId, e);
		}
	}

	private int countMaps(String originId, String destinationId, String... kinds) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			// @formatter:off
			String sql = "select count(*) from lum_cmig_MigItemMap where originServiceInstanceId = ? "
					+ "and destServiceInstanceId = ? and kind in (?, ?)";
			// @formatter:on
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(sql))
			{
				statement.setString(1, originId);
				statement.setString(2, destinationId);
				statement.setString(3, kinds[0]);
				statement.setString(4, kinds[1]);
				try (ResultSet rs = statement.executeQuery())
				{
					rs.next();
					return rs.getInt(1);
				}
			}
		});
	}

	private void deleteMigrationMaps() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			for (ServiceInstanceConfig[] pair : contentPairs)
			{
				deleteMaps(connection, pair[0].getId(), pair[1].getId());
			}
			boolean hasDistinctRepositories = originMediaRepository != null && destinationMediaRepository != null;
			if (hasDistinctRepositories && !originMediaRepository.getId().equals(destinationMediaRepository.getId()))
			{
				deleteMaps(connection, originMediaRepository.getId(), destinationMediaRepository.getId());
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

	private void deleteMaps(Connection connection, String originId, String destinationId) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement("delete from lum_cmig_MigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ?"))
		{
			statement.setString(1, originId);
			statement.setString(2, destinationId);
			statement.executeUpdate();
		}
	}

	private void resetLock() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
			{
				statement.executeUpdate();
			}
			return null;
		});
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

	private static final class MediaClone
	{
		final String mediaId;

		final String originFileId;

		final String destFileId;

		final String originName;

		final String destName;

		final String originContentType;

		final String destContentType;

		final byte[] destinationBytes;

		final String destRepositoryId;

		MediaClone(
				String mediaId,
				String originFileId,
				String destFileId,
				String originName,
				String destName,
				String originContentType,
				String destContentType,
				byte[] destinationBytes,
				String destRepositoryId)
		{
			this.mediaId = mediaId;
			this.originFileId = originFileId;
			this.destFileId = destFileId;
			this.originName = originName;
			this.destName = destName;
			this.originContentType = originContentType;
			this.destContentType = destContentType;
			this.destinationBytes = destinationBytes;
			this.destRepositoryId = destRepositoryId;
		}
	}

	private static final class ContentLocaleFileLink
	{
		static void set(String fileId, String contentLocaleId) throws Exception
		{
			ManagerFactory.getFileManager().setFileContentLocaleId(fileId, contentLocaleId);
		}
	}

	private static final class MediaFileInfo
	{
		final lumis.service.media.Media media;

		final FileConfig file;

		MediaFileInfo(lumis.service.media.Media media, FileConfig file)
		{
			this.media = media;
			this.file = file;
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
