package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.CategorizationFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentTag;
import lumis.content.core.ContentVersion;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.user.UserConfig;

/** Categorization paths and tags remain associated across a migrated hierarchy. */
public class CategorizationTagsMigrationTest
{
	private static final String CATEGORIZATION = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY;
	private static final String TAG_REPOSITORY = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_TAG_REPOSITORY;
	private static final String TAG_NAME = "hierarchical-migration-tag";
	private PortalFixture portal;
	private CategorizationFixture terms;
	private ChannelConfig channel;
	private ServiceInstanceConfig origin;
	private ServiceInstanceConfig destination;
	private ServiceInstanceConfig originRepository;
	private ServiceInstanceConfig destinationRepository;
	private String migrationId;
	private String faultProperty;

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		terms = new CategorizationFixture();
		PortalFixture.awaitGlobalLockFree();
		channel = portal.createChannel();
		origin = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		faultProperty = (String) ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.RepositoryCloner")
				.getField("PROPERTY_FAULT_UNIQUE_MAP_ONCE").get(null);
	}

	@After
	public void tearDown() throws Exception
	{
		try
		{
			if (faultProperty != null)
			{
				System.clearProperty(faultProperty);
			}
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
			TestCleanup.assertNoFailures(portal::cleanup, terms::cleanup);
		}
	}

	@Test
	public void publishedAndActiveTermsReusePathsAndRetryOneMapConflict() throws Exception
	{
		createRepositories(false);
		String root = terms.addTerm(originRepository, null, "Root");
		String blue = terms.addTerm(originRepository, root, "Blue");
		String green = terms.addTerm(originRepository, root, "Green");
		String destRoot = terms.addTerm(destinationRepository, null, "Root");
		String destBlue = terms.addTerm(destinationRepository, destRoot, "Blue");
		String parent = terms.addHierarchicalContent(portal, origin, "parent", null, blue);
		String child = terms.addHierarchicalContent(portal, origin, "child", parent, green);
		portal.addHierarchicalDraft(origin, parent, null);
		terms.setTerms(parent, ContentMetadataFixture.LOCALE, green);
		try
		{
			System.setProperty(faultProperty, "true");
			migrate(parent);
			assertFalse("unique-map conflict was retried", Boolean.getBoolean(faultProperty));
		}
		finally
		{
			System.clearProperty(faultProperty);
		}
		String destParent = result(parent, "destContentId");
		String destChild = result(child, "destContentId");
		String destGreen = mapped(green, "destItemId");
		assertNotEquals(green, destGreen);
		assertEquals(destRoot, mapped(root, "destItemId"));
		assertEquals(destBlue, mapped(blue, "destItemId"));
		assertEquals("0", mapped(root, "destCreated"));
		assertEquals("0", mapped(blue, "destCreated"));
		assertEquals("1", mapped(green, "destCreated"));
		assertEquals("categorizationTerm", mapped(green, "sourceId"));
		assertEquals("3", termMapCount());
		assertEquals(destBlue, onlyTerm(destParent, ContentMetadataFixture.LOCALE, true));
		assertEquals(destGreen, onlyTerm(destParent, ContentMetadataFixture.LOCALE, false));
		assertEquals(destGreen, onlyTerm(destChild, ContentMetadataFixture.LOCALE, false));
		assertEquals(java.util.Arrays.asList("Root", "Green"), CategorizationFixture.path(destGreen));
		assertEquals(destParent, value("select parentContentId from lum_HierarchicalContent where id = ?", result(child, "destItemId")));
		assertEquals(destinationRepository.getId(), value("select serviceInstanceId from lum_Content where id = ?", destGreen));
		String count = repositoryContentCount(destinationRepository);
		run();
		assertEquals(destParent, result(parent, "destContentId"));
		assertEquals(destChild, result(child, "destContentId"));
		assertEquals(destGreen, mapped(green, "destItemId"));
		assertEquals(count, repositoryContentCount(destinationRepository));
		assertEquals("3", termMapCount());
		assertEquals(destGreen, onlyTerm(destChild, ContentMetadataFixture.LOCALE, false));
	}

	@Test
	public void bothContentLocalesUseOneBilingualDestinationTerm() throws Exception
	{
		createRepositories(false);
		String root = terms.addTerm(originRepository, null, "Raiz");
		String blue = terms.addTerm(originRepository, root, "Azul");
		terms.addTermLocale(originRepository, root, null, Locale.US, "Root");
		terms.addTermLocale(originRepository, blue, root, Locale.US, "Blue");
		String content = terms.addHierarchicalContent(portal, origin, "bilingual", null, blue);
		portal.addHierarchicalTranslation(origin, content);
		terms.setTerms(content, Locale.US, blue);
		migrate(content);
		String destContent = result(content, "destContentId");
		String destBlue = onlyTerm(destContent, ContentMetadataFixture.LOCALE, false);
		assertEquals(destBlue, onlyTerm(destContent, Locale.US, false));
		assertEquals("4", termMapCount());
		PortalFixture.inTransaction((session, transaction) ->
		{
			assertEquals(2, ContentManagerFactory.getContentManager().getContent(destBlue).getContentLocales().size());
			session.setLocale(Locale.US);
			assertEquals(java.util.Arrays.asList("Root", "Blue"), lumis.service.categorization.CategorizationUtil.getTermPathNames(destBlue));
			return null;
		});
		assertEquals(java.util.Arrays.asList("Raiz", "Azul"), CategorizationFixture.path(destBlue));
	}

	@Test
	public void missingOriginRepositoryKeepsAssociatedTerms() throws Exception
	{
		createRepositories(false);
		String term = terms.addTerm(originRepository, null, "Unbound");
		String content = terms.addHierarchicalContent(portal, origin, "unbound", null, term);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(origin.getId());
			removal.setDependencyType(CATEGORIZATION);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
		migrate(content);
		assertEquals(term, onlyTerm(result(content, "destContentId"), ContentMetadataFixture.LOCALE, false));
		assertEquals("0", termMapCount());
		assertTrue(result(content, "warnings").contains("Categorization term " + term));
	}

	@Test
	public void sharedRepositoryPreservesTermsWithoutMaps() throws Exception
	{
		createRepositories(true);
		String term = terms.addTerm(originRepository, null, "Shared");
		String content = terms.addHierarchicalContent(portal, origin, "shared", null, term);
		migrate(content);
		assertEquals(term, onlyTerm(result(content, "destContentId"), ContentMetadataFixture.LOCALE, false));
		assertEquals("0", termMapCount());
	}

	@Test
	public void deletingReusedTermMapPreservesAnotherOriginRepositoryMap() throws Exception
	{
		createRepositories(false);
		ServiceInstanceConfig otherRepository = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		String otherTerm = terms.addTerm(otherRepository, null, "Shared destination term");
		String currentTerm = terms.addTerm(originRepository, null, "Shared destination term");
		String destTerm = terms.addTerm(destinationRepository, null, "Shared destination term");
		List<String> mapIds = new ArrayList<>();
		try
		{
			mapIds.add(insertTermMap(otherRepository, otherTerm, destTerm, true));
			mapIds.add(insertTermMap(originRepository, currentTerm, destTerm, false));
			PortalFixture.inTransaction((session, transaction) ->
			{
				Map<String, Object> scope = new HashMap<>();
				scope.put("session", session);
				scope.put("transaction", transaction);
				scope.put("connection", ((ITransactionJdbc) transaction).getConnection());
				scope.put("serviceId", PortalFixture.HIERARCHICAL_SERVICE_ID);
				scope.put("originServiceInstanceId", origin.getId());
				scope.put("destinationServiceInstanceId", destination.getId());
				Object context = ModuleBridge.newDependencyContext(scope, originRepository.getId(), destinationRepository.getId());
				Object handler = ModuleBridge.invokeStatic(
						"br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandlerRegistry",
						"getHandler",
						CATEGORIZATION);
				String localeId = ContentManagerFactory.getContentManager().getContent(destTerm).getContentLocales().iterator().next().getId();
				ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandler")
						.getMethod(
								"deleteClone",
								ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext"),
								String.class)
						.invoke(handler, context, localeId);
				return null;
			});
			assertEquals("0", value("select count(*) from lum_cmig_HMigItemMap where id = ?", mapIds.get(1)));
			assertEquals("1", value("select count(*) from lum_cmig_HMigItemMap where id = ?", mapIds.get(0)));
			assertEquals(destinationRepository.getId(), value("select serviceInstanceId from lum_Content where id = ?", destTerm));
		}
		finally
		{
			if (!PortalFixture.keepTestData())
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
							.prepareStatement("delete from lum_cmig_HMigItemMap where id = ?"))
					{
						for (String id : mapIds)
						{
							statement.setString(1, id);
							statement.executeUpdate();
						}
					}
					return null;
				});
			}
		}
	}

	private String insertTermMap(ServiceInstanceConfig repository, String originTerm, String destTerm, boolean created) throws Exception
	{
		String id = lumis.util.PortalUtil.generateNewGuid();
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentManagerFactory.getContentManager().getContent(originTerm).getContentLocales().iterator().next();
			ContentLocale destLocale = ContentManagerFactory.getContentManager().getContent(destTerm).getContentLocales().iterator().next();
			Map<String, Object> row = new HashMap<>();
			row.put("id", id);
			row.put("kind", "TERM");
			row.put("serviceId", CategorizationFixture.SERVICE_ID);
			row.put("sourceId", CategorizationFixture.SOURCE_ID);
			row.put("locale", originLocale.getLocale().toString());
			row.put("warnings", "");
			row.put("destCreated", created);
			row.put("originServiceInstanceId", repository.getId());
			row.put("originContentId", originTerm);
			row.put("originContentLocaleId", originLocale.getId());
			row.put("originItemId", originTerm);
			row.put("destServiceInstanceId", destinationRepository.getId());
			row.put("destContentId", destTerm);
			row.put("destContentLocaleId", destLocale.getId());
			row.put("destItemId", destTerm);
			ModuleBridge.insertItemMap(((ITransactionJdbc) transaction).getConnection(), row);
			return null;
		});
		return id;
	}

	@Test
	public void tagsAreCreatedByNameAcrossTheHierarchy() throws Exception
	{
		assertTags(false);
	}

	@Test
	public void tagsReuseExistingDestinationTermsAndRemainStableOnRerun() throws Exception
	{
		assertTags(true);
	}

	private void assertTags(boolean existing) throws Exception
	{
		ServiceInstanceConfig originTags = portal.createServiceInstance(channel, "lumis.service.tag");
		ServiceInstanceConfig destinationTags = portal.createServiceInstance(channel, "lumis.service.tag");
		portal.setDependency(originTags, origin, TAG_REPOSITORY);
		portal.setDependency(destinationTags, destination, TAG_REPOSITORY);
		String parent = portal.addHierarchicalContent(origin, "tag parent", null, true);
		String child = portal.addHierarchicalContent(origin, "tag child", parent, true);
		String originTag = addTag(parent);
		assertEquals(originTag, addTag(child));
		String existingTag = existing ? addTag(portal.addHierarchicalContent(destination, "tag seed", null, true)) : null;
		migrate(parent);
		String destParent = result(parent, "destContentId");
		String destChild = result(child, "destContentId");
		String destTag = onlyTag(destParent, destinationTags.getId());
		assertNotEquals(originTag, destTag);
		assertEquals(destTag, onlyTag(destChild, destinationTags.getId()));
		if (existing)
		{
			assertEquals(existingTag, destTag);
		}
		assertEquals("1", repositoryContentCount(destinationTags));
		run();
		assertEquals(destParent, result(parent, "destContentId"));
		assertEquals(destTag, onlyTag(destParent, destinationTags.getId()));
		assertEquals(destTag, onlyTag(destChild, destinationTags.getId()));
		assertEquals("1", repositoryContentCount(destinationTags));
	}

	private static String addTag(String content) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(content).getContentLocale(ContentMetadataFixture.LOCALE, false);
			assertTrue(locale.addTag(TAG_NAME));
			return locale.getTags().iterator().next().getContentVersion().getContentLocale().getContent().getId();
		});
	}

	private static String onlyTag(String content, String repository) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(content).getContentLocale(ContentMetadataFixture.LOCALE, false);
			List<ContentTag> tags = new ArrayList<>(locale.getTags());
			assertEquals(1, tags.size());
			ContentTag tag = tags.get(0);
			assertEquals(TAG_NAME, tag.getName());
			assertEquals(repository, tag.getContentVersion().getContentLocale().getContent().getServiceInstanceId());
			return tag.getContentVersion().getContentLocale().getContent().getId();
		});
	}

	private void createRepositories(boolean shared) throws Exception
	{
		originRepository = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		destinationRepository = shared ? originRepository : portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		portal.setDependency(originRepository, origin, CATEGORIZATION);
		portal.setDependency(destinationRepository, destination, CATEGORIZATION);
	}

	private static String onlyTerm(String content, Locale language, boolean published) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(content).getContentLocale(language, false);
			ContentVersion version = published ? locale.getPublishedVersion() : locale.getActiveVersion();
			List<String> ids = new ArrayList<>();
			for (ContentAssociation association : version.getContentAssociations())
			{
				if (ContentAssociation.TYPE_CATEGORIZATION_TERM.equals(association.getType()))
				{
					ids.add(association.getAssociatedContent().getId());
				}
			}
			assertEquals("one categorization association", 1, ids.size());
			return ids.get(0);
		});
	}

	private String termMapCount() throws Exception
	{
		return value("select count(*) from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ? and kind = 'TERM'",
				originRepository.getId(), destinationRepository.getId());
	}

	private String mapped(String term, String field) throws Exception
	{
		String sql = "select " + field + " from lum_cmig_HMigItemMap "
				+ "where originServiceInstanceId = ? and destServiceInstanceId = ? and originItemId = ? and kind = 'TERM'";
		return value(sql, originRepository.getId(), destinationRepository.getId(), term);
	}

	private String result(String content, String field) throws Exception
	{
		// Default locale only: the locale-specific associations are inspected through the content API.
		return value("select " + field + " from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ? and originContentId = ? "
				+ "and kind = 'CONTENT' and locale = ?", origin.getId(), destination.getId(), content, ContentMetadataFixture.LOCALE.toString());
	}

	private static String repositoryContentCount(ServiceInstanceConfig repository) throws Exception
	{
		return value("select count(*) from lum_Content where serviceInstanceId = ?", repository.getId());
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
		params.put("name", "hierarchical categorization and tags");
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
		Map<String, Object> result = map(ModuleBridge.api("awaitTermination", migrationId, "120000"));
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
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
}
