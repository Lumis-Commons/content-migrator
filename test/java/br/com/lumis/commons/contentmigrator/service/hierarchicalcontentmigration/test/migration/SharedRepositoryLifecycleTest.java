package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;

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

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.CategorizationFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/**
 * Exercises migration, undo and direct deletion against a shared categorization repository.
 *
 * @since %LUM_NEXT_VERSION%
 */
public class SharedRepositoryLifecycleTest
{
	/**
	 * Dependency used by the shared repository fixture.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final String CATEGORIZATION = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY;

	/**
	 * Creates and removes Portal instances and hierarchical content.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final PortalFixture portal = new PortalFixture();

	/**
	 * Creates and removes categorization terms.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final CategorizationFixture terms = new CategorizationFixture();

	/**
	 * Migrations still registered for cleanup.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final List<String> migrationIds = new ArrayList<>();

	/**
	 * Channel containing the test instances.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private ChannelConfig channel;

	/**
	 * Categorization repository linked to both ends of the main migration.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private ServiceInstanceConfig repository;

	/**
	 * Source instance of the test migrations.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private ServiceInstanceConfig origin;

	/**
	 * Selected source content associated with a term in the shared repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String selection;

	/**
	 * Unreferenced leaf used to model an obsolete ownership claim.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String unreferencedTerm;

	/**
	 * ID of the injected map, retained so teardown can delete it after any failure.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String legacyMapId;

	/**
	 * Sets up hierarchical content and a shared repository with an associated term.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Before
	public void setUp() throws Exception
	{
		PortalFixture.awaitGlobalLockFree();
		channel = portal.createChannel();
		repository = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		origin = contentInstanceUsing(repository);
		String associatedTerm = terms.addTerm(repository, null, "Shared term");
		selection = terms.addHierarchicalContent(portal, origin, "contentmigrator-test-shared-repository", null, associatedTerm);
		unreferencedTerm = terms.addTerm(repository, null, "Unreferenced shared term");
	}

	/**
	 * Removes tracked migrations and fixtures, collecting failures so later cleanup steps still run.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@After
	public void tearDown()
	{
		TestCleanup cleanup = new TestCleanup();
		if (legacyMapId != null && !PortalFixture.keepTestData())
		{
			try
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
							.prepareStatement("delete from lum_cmig_HMigItemMap where id = ?"))
					{
						statement.setString(1, legacyMapId);
						statement.executeUpdate();
					}
					return null;
				});
			}
			catch (Exception e)
			{
				cleanup.recordFailure("Could not clean up the legacy item map", e);
			}
		}
		for (String id : List.copyOf(migrationIds))
		{
			try
			{
				ModuleBridge.api("stop", id);
				await(id);
				if (!PortalFixture.keepTestData())
				{
					delete(id);
				}
			}
			catch (Exception | AssertionError e)
			{
				cleanup.recordFailure("Could not clean up migration " + id, e);
			}
		}
		cleanup.run(portal::cleanup, terms::cleanup);
		cleanup.assertNoFailures();
	}

	/**
	 * Verifies that undo preserves even an unreferenced shared term marked as created in a legacy map.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void undoPreservesSharedRepositoryItems() throws Exception
	{
		ServiceInstanceConfig destination = contentInstanceUsing(repository);
		Set<String> before = repositoryItems();
		String migration = create(destination);
		run("start", migration);
		assertMigrated(destination, before);
		insertLegacyMap();
		assertEquals("legacy map before undo", Set.of(legacyMapId), repositoryItemMaps());

		run("undo", migration);

		assertEquals("destination contents after undo", Set.of(), contentIds(destination));
		assertEquals("repository items after undo", before, repositoryItems());
		assertEquals("undo leaves the legacy map for deletion", Set.of(legacyMapId), repositoryItemMaps());
		assertEquals("repository pair while the undone migration exists", 1, repositoryMaps(repository).size());
		delete(migration);
		assertEquals("repository pair after deleting the undone migration", Set.of(), repositoryMaps(repository));
		assertEquals("legacy map after deleting the undone migration", Set.of(), repositoryItemMaps());
		assertEquals("repository items after deleting the undone migration", before, repositoryItems());
	}

	/**
	 * Verifies direct deletion while another migration uses the shared pair and after its last user is deleted.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void directDeletionRetainsPairUntilLastMigration() throws Exception
	{
		ServiceInstanceConfig destination = contentInstanceUsing(repository);
		ServiceInstanceConfig otherRepository = portal.createServiceInstance(channel, CategorizationFixture.SERVICE_ID);
		Set<String> before = repositoryItems();
		String first = create(destination);
		String last = create(contentInstanceUsing(repository));
		create(contentInstanceUsing(otherRepository));
		run("start", first);
		assertMigrated(destination, before);
		insertLegacyMap();

		Set<String> pairMaps = repositoryMaps(repository);
		Set<String> otherPairMaps = repositoryMaps(otherRepository);
		Set<String> copies = contentIds(destination);
		assertEquals("one shared pair row for both migrations", 1, pairMaps.size());
		assertEquals("one row for the distinct pair", 1, otherPairMaps.size());

		delete(first);

		assertEquals("shared pair row retained", pairMaps, repositoryMaps(repository));
		assertEquals("legacy map retained with the shared pair", Set.of(legacyMapId), repositoryItemMaps());
		assertEquals("distinct pair row retained", otherPairMaps, repositoryMaps(otherRepository));
		assertEquals("repository items after direct deletion", before, repositoryItems());
		assertEquals("direct deletion keeps the destination copy", copies, contentIds(destination));

		delete(last);

		assertEquals("shared pair row removed", Set.of(), repositoryMaps(repository));
		assertEquals("legacy map removed with the shared pair", Set.of(), repositoryItemMaps());
		assertEquals("distinct pair row retained", otherPairMaps, repositoryMaps(otherRepository));
		assertEquals("repository items after last deletion", before, repositoryItems());
	}

	/**
	 * Creates a hierarchical instance linked to the given repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private ServiceInstanceConfig contentInstanceUsing(ServiceInstanceConfig categorization) throws Exception
	{
		ServiceInstanceConfig instance = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		portal.setDependency(categorization, instance, CATEGORIZATION);
		return instance;
	}

	/**
	 * Saves a migration from the test origin to the given destination.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String create(ServiceInstanceConfig destination) throws Exception
	{
		Map<?, ?> result = (Map<?, ?>) ModuleBridge.api("create", Map.of(
				"name", "contentmigrator-test-shared-repository",
				"originServiceInstanceId", origin.getId(),
				"destServiceInstanceId", destination.getId(),
				"selectedContentIds", selection));
		String id = (String) result.get("id");
		migrationIds.add(id);
		return id;
	}

	/**
	 * Runs a migration or undo to completion.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void run(String operation, String migration) throws Exception
	{
		ModuleBridge.api(operation, Map.of("migrationId", migration, "userId", UserConfig.USER_ADMINISTRATOR_ID));
		Map<?, ?> result = await(migration);
		assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
	}

	/**
	 * Waits for an operation to terminate and returns the resulting migration state.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static Map<?, ?> await(String migration) throws Exception
	{
		Map<?, ?> result = (Map<?, ?>) ModuleBridge.api("awaitTermination", migration, "120000");
		assertEquals("operation terminated", Boolean.TRUE, result.get("terminated"));
		return result;
	}

	/**
	 * Deletes a saved migration and releases its cleanup registration.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void delete(String migration) throws Exception
	{
		Map<?, ?> result = (Map<?, ?>) ModuleBridge.api("delete", migration);
		migrationIds.remove(migration);
		assertEquals("migration deleted", Boolean.TRUE, result.get("deleted"));
	}

	/**
	 * Checks the destination copy and the absence of shared repository item changes.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void assertMigrated(ServiceInstanceConfig destination, Set<String> before) throws Exception
	{
		assertEquals("two original term items in the shared repository", 2, before.size());
		assertEquals("one hierarchical content copied", 1, contentIds(destination).size());
		assertEquals("repository items after migration", before, repositoryItems());
		assertEquals("shared repository has no new item maps", Set.of(), repositoryItemMaps());
	}

	/**
	 * Inserts a legacy TERM map that claims ownership of an unreferenced term in the shared repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void insertLegacyMap() throws Exception
	{
		legacyMapId = PortalUtil.generateNewGuid();
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(unreferencedTerm).getContentLocales().iterator().next();
			Map<String, Object> row = new HashMap<>();
			row.put("id", legacyMapId);
			row.put("kind", "TERM");
			row.put("serviceId", CategorizationFixture.SERVICE_ID);
			row.put("sourceId", CategorizationFixture.SOURCE_ID);
			row.put("locale", locale.getLocale().toString());
			row.put("warnings", "");
			row.put("destCreated", true);
			row.put("originServiceInstanceId", repository.getId());
			row.put("originContentId", unreferencedTerm);
			row.put("originContentLocaleId", locale.getId());
			row.put("originItemId", unreferencedTerm);
			row.put("destServiceInstanceId", repository.getId());
			row.put("destContentId", unreferencedTerm);
			row.put("destContentLocaleId", locale.getId());
			row.put("destItemId", unreferencedTerm);
			ModuleBridge.insertItemMap(((ITransactionJdbc) transaction).getConnection(), row);
			return null;
		});
	}

	/**
	 * Lists stored term item IDs in the shared repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private Set<String> repositoryItems() throws Exception
	{
		String sql = "select t.termId from lum_CategorizationTerm t "
				+ "join lum_ContentVersion v on v.itemId = t.termId join lum_ContentLocale l on l.id = v.contentLocaleId "
				+ "join lum_Content c on c.id = l.contentId where c.serviceInstanceId = ?";
		return queryIds(sql, repository.getId());
	}

	/**
	 * Lists repository map IDs for the exact origin and destination pair.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private Set<String> repositoryMaps(ServiceInstanceConfig destinationRepository) throws Exception
	{
		return queryIds("select id from lum_cmig_HMigRepoMap where originRepoInstanceId = ? and destRepoInstanceId = ?",
				repository.getId(), destinationRepository.getId());
	}

	/**
	 * Lists item map IDs for the shared repository pair.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private Set<String> repositoryItemMaps() throws Exception
	{
		return queryIds("select id from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ?",
				repository.getId(), repository.getId());
	}

	/**
	 * Lists content IDs belonging to an instance.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static Set<String> contentIds(ServiceInstanceConfig instance) throws Exception
	{
		return queryIds("select id from lum_Content where serviceInstanceId = ?", instance.getId());
	}

	/**
	 * Reads the first column of a parameterized query as a set of IDs.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static Set<String> queryIds(String sql, String... parameters) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			try (PreparedStatement statement = connection.prepareStatement(sql))
			{
				for (int i = 0; i < parameters.length; i++)
				{
					statement.setString(i + 1, parameters[i]);
				}
				try (ResultSet rows = statement.executeQuery())
				{
					Set<String> ids = new HashSet<>();
					while (rows.next())
					{
						ids.add(rows.getString(1));
					}
					return ids;
				}
			}
		});
	}
}
