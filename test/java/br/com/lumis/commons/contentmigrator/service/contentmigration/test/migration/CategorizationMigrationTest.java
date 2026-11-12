package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.CategorizationFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.service.categorization.CategorizationUtil;

/** Integration coverage for categorization term paths, associations and repository maps. */
public class CategorizationMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final Locale EN_US = new Locale("en", "US");

	private static final String CATEGORIZATION = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY;

	private static final String DEPENDENCY_CONTEXT = "br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyContext";

	private static final String DEPENDENCY_HANDLER = "br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandler";

	private static final String HANDLER_REGISTRY = "br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandlerRegistry";

	private PortalFixture portal;

	private ContentMetadataFixture contents;

	private CategorizationFixture terms;

	private ChannelConfig channel;

	private ServiceInstanceConfig originNews;

	private ServiceInstanceConfig destinationNews;

	private ServiceInstanceConfig originRepository;

	private ServiceInstanceConfig destinationRepository;

	private String originRootId;

	private String originBlueId;

	private String originGreenId;

	private String originNewsItemId;

	private String destinationNewsItemId;

	private final List<String> migrationIds = new ArrayList<>();

	private final List<ServiceInstanceConfig[]> instancePairs = new ArrayList<>();

	private final List<String> manualItemMapIds = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		contents = new ContentMetadataFixture();
		terms = new CategorizationFixture();
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
			trackClonedTerms();
			cleanup.run(contents::cleanup, terms::cleanup);
			deleteMigrationData();
		}
		else
		{
			cleanup.run(contents::cleanup, terms::cleanup);
		}
		cleanup.run(portal::cleanup);
		cleanup.assertNoFailures();
	}

	@Test
	public void resolvesPublishedAndActiveTermsByPathReusesDestinationAndRetriesMapConflict() throws Exception
	{
		createPair(false);
		createTerms();
		String destinationRootId = terms.addTerm(destinationRepository, null, "Root");
		String destinationBlueId = terms.addTerm(destinationRepository, destinationRootId, "Blue");

		originNewsItemId = contents
				.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, ContentMetadataFixture.news("categorized", "introduction", "body"), null, null, null);
		addCategorizationTerm(originNewsItemId, originBlueId);
		String activeOriginItemId = ContentMetadataFixture.updateNews(
				UserConfig.USER_ADMINISTRATOR_ID,
				originNews,
				originNewsItemId,
				ContentMetadataFixture.news("active", "active introduction", "active body"),
				null,
				null);
		replaceCategorizationTerms(activeOriginItemId, originGreenId);
		setPublishedVersion(activeOriginItemId, originNewsItemId);

		System.setProperty(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE, "true");
		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage") + "\n" + run.get("errorStack"), "FINISHED", run.get("status"));
		assertFalse("unique-map fault injection was consumed", Boolean.getBoolean(RepositoryCloner.PROPERTY_FAULT_UNIQUE_MAP_ONCE));
		destinationNewsItemId = mappedContentItem(originNewsItemId);
		contents.track(destinationNews.getId(), destinationNewsItemId);

		assertEquals("origin published term", Arrays.asList("Root/Blue"), termPathsFor(activeOriginItemId, true));
		assertEquals("origin active term", Arrays.asList("Root/Green"), termPathsFor(activeOriginItemId, false));
		assertEquals("published term is remapped by its path", Arrays.asList("Root/Blue"), termPathsFor(destinationNewsItemId, true));
		assertEquals("active term is remapped by its path", Arrays.asList("Root/Green"), termPathsFor(destinationNewsItemId, false));
		assertEquals("existing Blue term is reused", destinationBlueId, onlyTermForVersion(destinationNewsItemId, true));
		assertNotEquals("destination terms have separate ids", originBlueId, onlyTermForVersion(destinationNewsItemId, true));

		List<TermMap> maps = termMaps();
		assertEquals("root, Blue and Green have one map each", 3, maps.size());
		TermMap rootMap = mapByOrigin(maps, originRootId);
		TermMap blueMap = mapByOrigin(maps, originBlueId);
		TermMap greenMap = mapByOrigin(maps, originGreenId);
		assertEquals(destinationRootId, rootMap.destinationId);
		assertEquals(destinationBlueId, blueMap.destinationId);
		assertFalse("root path match is reused", rootMap.destinationCreated);
		assertFalse("Blue path match is reused", blueMap.destinationCreated);
		assertTrue("missing Green path is created", greenMap.destinationCreated);
		assertEquals("TERM is the map kind", "TERM", greenMap.kind);
		assertEquals("categorizationTerm is the mapped source", "categorizationTerm", greenMap.sourceId);
		assertEquals("a retried unique insert produces one row for Green", 1, countOrigin(maps, originGreenId));
		assertEquals(destinationRepository.getId(), contentServiceInstance(greenMap.destinationId));

		assertTrue("a term with children is referenced", isCloneReferenced(destinationRootId));
		assertTrue("a term associated to a migrated content is referenced", isCloneReferenced(greenMap.destinationId));
		String unusedId = terms.addTerm(destinationRepository, null, "Unused");
		assertFalse("a term without associations or children is not referenced", isCloneReferenced(unusedId));
	}

	@Test
	public void addsEachTermLocaleToTheSameDestinationTerm() throws Exception
	{
		createPair(false);
		originRootId = terms.addTerm(originRepository, null, "Raiz");
		originBlueId = terms.addTerm(originRepository, originRootId, "Azul");
		terms.addTermLocale(originRepository, originRootId, null, EN_US, "Root");
		terms.addTermLocale(originRepository, originBlueId, originRootId, EN_US, "Blue");
		originNewsItemId = contents
				.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, ContentMetadataFixture.news("bilingual", "introduction", "body"), null, null, null);
		addCategorizationTerm(originNewsItemId, originBlueId);
		Map<String, Object> english = ContentMetadataFixture.news("bilingual en", "introduction en", "body en");
		english.put("language", EN_US);
		english.put("language.isDefaultLocale", Boolean.FALSE);
		english.put("language.contentId", contentIdOf(originNewsItemId));
		String englishItemId = contents.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, english, null, null, null);
		addCategorizationTerm(englishItemId, originBlueId);

		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage") + "\n" + run.get("errorStack"), "FINISHED", run.get("status"));
		destinationNewsItemId = mappedContentItem(originNewsItemId);
		String destinationEnglishItemId = mappedContentItem(englishItemId);
		contents.track(destinationNews.getId(), destinationNewsItemId);
		contents.track(destinationNews.getId(), destinationEnglishItemId);

		String destinationBlueId = onlyTermForVersion(destinationNewsItemId, false);
		assertEquals("both locales of the news use one destination term", destinationBlueId, onlyTermForVersion(destinationEnglishItemId, false));
		assertEquals("the destination term has both locales", 2, localeCount(destinationBlueId));
		assertEquals(Arrays.asList("Raiz", "Azul"), termPath(destinationBlueId, ContentMetadataFixture.LOCALE));
		assertEquals(Arrays.asList("Root", "Blue"), termPath(destinationBlueId, EN_US));

		List<TermMap> maps = termMaps();
		assertEquals("each term locale has one map", 4, maps.size());
		for (String originId : new String[]{originRootId, originBlueId})
		{
			List<String> destinations = new ArrayList<>();
			for (TermMap map : maps)
			{
				if (originId.equals(map.originId))
				{
					assertTrue("each locale was created by the migration", map.destinationCreated);
					destinations.add(map.destinationId);
				}
			}
			assertEquals("both locales of " + originId + " are mapped", 2, destinations.size());
			assertEquals("both locales of " + originId + " map to one term", destinations.get(0), destinations.get(1));
		}
	}

	@Test
	public void keepsTermsWhenTheOriginHasNoCategorizationRepository() throws Exception
	{
		createPair(false);
		originRootId = terms.addTerm(originRepository, null, "Unbound root");
		originNewsItemId = contents
				.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, ContentMetadataFixture.news("unbound repository", "introduction", "body"), null, null, null);
		addCategorizationTerm(originNewsItemId, originRootId);
		// the association remains when the repository is unbound from the origin after categorizing
		removeDependency(originNews, CATEGORIZATION);

		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage") + "\n" + run.get("errorStack"), "FINISHED", run.get("status"));
		destinationNewsItemId = mappedContentItem(originNewsItemId);
		contents.track(destinationNews.getId(), destinationNewsItemId);
		assertEquals("the term of another repository is kept", originRootId, onlyTermForVersion(destinationNewsItemId, false));
		assertTrue("no term was cloned", termMaps().isEmpty());
		assertTrue(
				"the kept term is a warning of the item",
				contentWarnings(originNewsItemId).contains("Categorization term " + originRootId + " belongs to another repository"));
	}

	@Test
	public void keepsTermAssociationsWhenBothInstancesShareTheRepository() throws Exception
	{
		createPair(true);
		originRootId = terms.addTerm(originRepository, null, "Shared root");
		originNewsItemId = contents
				.addNews(UserConfig.USER_ADMINISTRATOR_ID, originNews, ContentMetadataFixture.news("same repository", "introduction", "body"), null, null, null);
		addCategorizationTerm(originNewsItemId, originRootId);

		Map<String, Object> run = runMigration(null);
		assertEquals("Migration error: " + run.get("errorMessage"), "FINISHED", run.get("status"));
		destinationNewsItemId = mappedContentItem(originNewsItemId);
		contents.track(destinationNews.getId(), destinationNewsItemId);
		assertEquals(originRootId, onlyTermForVersion(destinationNewsItemId, false));
		assertTrue("same repository creates no TERM map rows", termMaps().isEmpty());
	}

	@Test
	public void deleteCloneDoesNotDeleteAnotherOriginRepositoryMapOrTerm() throws Exception
	{
		createPair(false);
		ServiceInstanceConfig otherOriginRepository = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		String destinationTermName = "Shared destination term";
		String otherSourceTermId = terms.addTerm(otherOriginRepository, null, destinationTermName);
		String currentSourceTermId = terms.addTerm(originRepository, null, destinationTermName);
		String destinationTermId = terms.addTerm(destinationRepository, null, destinationTermName);
		String otherMapId = insertTermMap(otherOriginRepository, destinationRepository, otherSourceTermId, destinationTermId, true);
		String currentMapId = insertTermMap(originRepository, destinationRepository, currentSourceTermId, destinationTermId, false);

		deleteClone(originRepository, destinationRepository, destinationTermId);

		assertFalse(
				"the term owned by the other origin pair remains",
				PortalFixture.isNotFound((session, transaction) -> ContentManagerFactory.getContentManager().getContent(destinationTermId)));
		assertFalse("the map being undone is removed", itemMapExists(currentMapId));
		assertTrue("the other origin pair's created map remains", itemMapExists(otherMapId));
	}

	private void createPair(boolean sharedRepository) throws Exception
	{
		originRepository = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		destinationRepository = sharedRepository ? originRepository : portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		originNews = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		destinationNews = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		portal.setDependency(originRepository, originNews, CATEGORIZATION);
		portal.setDependency(destinationRepository, destinationNews, CATEGORIZATION);
		instancePairs.add(new ServiceInstanceConfig[]{originNews, destinationNews});
	}

	private void createTerms() throws Exception
	{
		originRootId = terms.addTerm(originRepository, null, "Root");
		originBlueId = terms.addTerm(originRepository, originRootId, "Blue");
		originGreenId = terms.addTerm(originRepository, originRootId, "Green");
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

	private void replaceCategorizationTerms(String itemId, String termContentId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			for (ContentAssociation association : new ArrayList<>(version.getContentAssociations()))
			{
				if (ContentAssociation.TYPE_CATEGORIZATION_TERM.equals(association.getType()))
				{
					association.delete();
				}
			}
			ContentAssociation association = version.addContentAssociation();
			association.setType(ContentAssociation.TYPE_CATEGORIZATION_TERM);
			association.setAssociatedContent(ContentManagerFactory.getContentManager().getContent(termContentId));
			return null;
		});
	}

	private void setPublishedVersion(String activeItemId, String publishedItemId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentMetadataFixture.contentLocale(activeItemId);
			ContentVersion published = ContentManagerFactory.getContentManager().getContentVersionByItemId(publishedItemId);
			locale.setPublishedVersion(published);
			return null;
		});
	}

	private List<String> termPathsFor(String itemId, boolean published) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			ContentLocale locale = ContentMetadataFixture.contentLocale(itemId);
			ContentVersion version = published ? locale.getPublishedVersion() : locale.getActiveVersion();
			List<String> paths = new ArrayList<>();
			for (String termId : categorizationTermIds(version))
			{
				paths.add(String.join("/", CategorizationUtil.getTermPathNames(termId)));
			}
			return paths;
		});
	}

	private List<String> categorizationTermIds(ContentVersion version)
	{
		List<String> ids = new ArrayList<>();
		for (ContentAssociation association : version.getContentAssociations())
		{
			if (ContentAssociation.TYPE_CATEGORIZATION_TERM.equals(association.getType()))
			{
				ids.add(association.getAssociatedContent().getId());
			}
		}
		return ids;
	}

	private static List<String> termPath(String termContentId, Locale locale) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(locale);
			return CategorizationUtil.getTermPathNames(termContentId);
		});
	}

	private static int localeCount(String contentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> ContentManagerFactory.getContentManager().getContent(contentId).getContentLocales().size());
	}

	private static String contentIdOf(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> ContentMetadataFixture.contentLocale(itemId).getContent().getId());
	}

	/**
	 * Calls the registered categorization handler, through the module class loader, to tell whether
	 * a locale of the given destination term is referenced.
	 */
	private boolean isCloneReferenced(String termContentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Class<?> contextType = ModuleBridge.load(DEPENDENCY_CONTEXT);
			Object context = ModuleBridge.newDependencyContext(
					scopeValues(session, transaction, originNews.getId(), destinationNews.getId()),
					originRepository.getId(),
					destinationRepository.getId());
			Object handler = ModuleBridge.invokeStatic(HANDLER_REGISTRY, "getHandler", CATEGORIZATION);
			Method method = ModuleBridge.load(DEPENDENCY_HANDLER).getMethod("isCloneReferenced", contextType, String.class);
			String localeId = ContentManagerFactory.getContentManager().getContent(termContentId).getContentLocales().iterator().next().getId();
			return (Boolean) method.invoke(handler, context, localeId);
		});
	}

	private String insertTermMap(
			ServiceInstanceConfig sourceRepository,
			ServiceInstanceConfig destinationRepository,
			String sourceTermId,
			String destinationTermId,
			boolean destCreated) throws Exception
	{
		String mapId = lumis.util.PortalUtil.generateNewGuid();
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale sourceLocale = ContentManagerFactory.getContentManager().getContent(sourceTermId).getContentLocales().iterator().next();
			ContentLocale destinationLocale = ContentManagerFactory.getContentManager().getContent(destinationTermId).getContentLocales().iterator().next();
			Map<String, Object> values = new HashMap<>();
			values.put("id", mapId);
			values.put("kind", "TERM");
			values.put("serviceId", CategorizationFixture.SERVICE_ID);
			values.put("sourceId", CategorizationFixture.SOURCE_ID);
			values.put("locale", sourceLocale.getLocale().toString());
			values.put("warnings", "");
			values.put("destCreated", destCreated);
			values.put("originServiceInstanceId", sourceRepository.getId());
			values.put("originContentId", sourceTermId);
			values.put("originContentLocaleId", sourceLocale.getId());
			values.put("originItemId", sourceLocale.getActiveVersion().getItemId());
			values.put("destServiceInstanceId", destinationRepository.getId());
			values.put("destContentId", destinationTermId);
			values.put("destContentLocaleId", destinationLocale.getId());
			values.put("destItemId", destinationLocale.getActiveVersion().getItemId());
			ModuleBridge.insertItemMap(connection(transaction), values);
			return null;
		});
		manualItemMapIds.add(mapId);
		return mapId;
	}

	private void deleteClone(ServiceInstanceConfig sourceRepository, ServiceInstanceConfig destinationRepository, String destinationTermId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Class<?> contextType = ModuleBridge.load(DEPENDENCY_CONTEXT);
			Object context = ModuleBridge.newDependencyContext(
					scopeValues(session, transaction, originNews.getId(), destinationNews.getId()),
					sourceRepository.getId(),
					destinationRepository.getId());
			Object handler = ModuleBridge.invokeStatic(HANDLER_REGISTRY, "getHandler", CATEGORIZATION);
			Method method = ModuleBridge.load(DEPENDENCY_HANDLER).getMethod("deleteClone", contextType, String.class);
			String localeId = ContentManagerFactory.getContentManager().getContent(destinationTermId).getContentLocales().iterator().next().getId();
			method.invoke(handler, context, localeId);
			return null;
		});
	}

	private boolean itemMapExists(String mapId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("select count(*) from lum_cmig_MigItemMap where id = ?"))
			{
				statement.setString(1, mapId);
				try (ResultSet rs = statement.executeQuery())
				{
					rs.next();
					return rs.getInt(1) == 1;
				}
			}
		});
	}

	/** Removes the dependency of the given type of the instance. */
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

	private String contentWarnings(String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("select warnings from lum_cmig_MigItemMap where kind = 'CONTENT' and originItemId = ?"))
			{
				statement.setString(1, originItemId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("content map exists", rs.next());
					return String.valueOf(rs.getString(1));
				}
			}
		});
	}

	private String onlyTermForVersion(String itemId, boolean published) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentMetadataFixture.contentLocale(itemId);
			ContentVersion version = published ? locale.getPublishedVersion() : locale.getActiveVersion();
			List<String> ids = categorizationTermIds(version);
			assertEquals("one categorization association", 1, ids.size());
			return ids.get(0);
		});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runMigration(String updateMode) throws Exception
	{
		String key = originNews.getId() + ":" + destinationNews.getId();
		String migrationId = migrationByPair.get(key);
		if (migrationId == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-categorization-migration");
			create.put("originServiceInstanceId", originNews.getId());
			create.put("destServiceInstanceId", destinationNews.getId());
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

	private String mappedContentItem(String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			String localeId = ContentMetadataFixture.contentLocale(originItemId).getId();
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select destItemId from lum_cmig_MigItemMap where kind = 'CONTENT' " + "and originServiceInstanceId = ? and destServiceInstanceId = ? "
							+ "and originContentLocaleId = ?"))
			{
				statement.setString(1, originNews.getId());
				statement.setString(2, destinationNews.getId());
				statement.setString(3, localeId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("content map exists", rs.next());
					return rs.getString(1);
				}
			}
		});
	}

	private List<TermMap> termMaps() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<TermMap> maps = new ArrayList<>();
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select kind, sourceId, originItemId, destItemId, destCreated from lum_cmig_MigItemMap "
							+ "where originServiceInstanceId = ? and destServiceInstanceId = ? and kind = 'TERM'"))
			{
				statement.setString(1, originRepository.getId());
				statement.setString(2, destinationRepository.getId());
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						maps.add(new TermMap(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)));
					}
				}
			}
			return maps;
		});
	}

	private String contentServiceInstance(String contentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> ContentManagerFactory.getContentManager().getContent(contentId).getServiceInstanceId());
	}

	private void trackClonedTerms() throws Exception
	{
		boolean repositoriesMissing = originRepository == null || destinationRepository == null;
		if (repositoriesMissing || originRepository.equals(destinationRepository))
		{
			return;
		}
		List<TermMap> maps = termMaps();
		for (String originId : new String[]{originRootId, originBlueId, originGreenId})
		{
			if (originId != null)
			{
				for (TermMap map : maps)
				{
					if (originId.equals(map.originId))
					{
						terms.track(destinationRepository.getId(), map.destinationId);
					}
				}
			}
		}
	}

	private void deleteMigrationData() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = connection(transaction);
			for (String mapId : manualItemMapIds)
			{
				update(connection, "delete from lum_cmig_MigItemMap where id = ?", mapId);
			}
			for (ServiceInstanceConfig[] pair : instancePairs)
			{
				// @formatter:off
				update(connection, "delete from lum_cmig_MigItemMap where originServiceInstanceId = ? "
						+ "and destServiceInstanceId = ?", pair[0].getId(), pair[1].getId());
				// @formatter:on
				update(
						connection,
						"delete from lum_cmig_MigRepoMap where originRepoInstanceId = ? " + "and destRepoInstanceId = ?",
						originRepository.getId(),
						destinationRepository.getId());
			}
			for (String migrationId : migrationIds)
			{
				update(connection, "delete from lum_cmig_Migration where id = ?", migrationId);
			}
			update(connection, "update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null");
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

	private static TermMap mapByOrigin(List<TermMap> maps, String originId)
	{
		for (TermMap map : maps)
		{
			if (originId.equals(map.originId))
			{
				return map;
			}
		}
		throw new AssertionError("No TERM map for origin term " + originId);
	}

	private static int countOrigin(List<TermMap> maps, String originId)
	{
		int count = 0;
		for (TermMap map : maps)
		{
			if (originId.equals(map.originId))
			{
				count++;
			}
		}
		return count;
	}

	private static final class TermMap
	{
		final String kind;

		final String sourceId;

		final String originId;

		final String destinationId;

		final boolean destinationCreated;

		TermMap(String kind, String sourceId, String originId, String destinationId, boolean destinationCreated)
		{
			this.kind = kind;
			this.sourceId = sourceId;
			this.originId = originId;
			this.destinationId = destinationId;
			this.destinationCreated = destinationCreated;
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
