package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;

/** Warning counts and details come from real copies whose parent was not selected. */
public class MigrationWarningsTest
{
	private static final String QUERIES = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.query.MigrationAdminQueries";

	private PortalFixture fixture;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig destination;

	private final List<String> migrations = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		var channel = fixture.createChannel();
		origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		PortalFixture.awaitGlobalLockFree();
	}

	@After
	public void tearDown() throws Exception
	{
		for (String id : migrations)
		{
			ModuleBridge.api("stop", id);
			ModuleBridge.api("awaitTermination", id, "120000");
			if (!PortalFixture.keepTestData())
			{
				ModuleBridge.api("undo", Map.of("migrationId", id, "userId", UserConfig.USER_ADMINISTRATOR_ID));
				ModuleBridge.api("awaitTermination", id, "120000");
				ModuleBridge.api("delete", id);
			}
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void countsItemsAndListsTitlesAndMessagesWithoutExposingThemToGuests() throws Exception
	{
		String root = fixture.addHierarchicalContent(origin, "warning root", null, true);
		String child = fixture.addHierarchicalContent(origin, "warning child <sample>", root, false);
		String sibling = fixture.addHierarchicalContent(origin, "warning sibling", root, true);
		String id = (String) ((Map<?, ?>) ModuleBridge.api(
				"create",
				Map.of(
						"name",
						"contentmigrator-test-warnings",
						"originServiceInstanceId",
						origin.getId(),
						"destServiceInstanceId",
						destination.getId(),
						"selectedContentIds",
						child + "," + sibling)))
				.get("id");
		migrations.add(id);
		assertCount(id, 0);
		ModuleBridge.api("start", Map.of("migrationId", id, "userId", UserConfig.USER_ADMINISTRATOR_ID));
		assertEquals("FINISHED", ((Map<?, ?>) ModuleBridge.api("awaitTermination", id, "120000")).get("status"));
		assertCount(id, 2);
		PortalFixture.inTransaction((session, transaction) ->
		{
			List<?> warnings = (List<?>) ModuleBridge.invokeStatic(QUERIES, "listWarnings", session, id, transaction);
			assertEquals(2, warnings.size());
			boolean foundChild = false;
			for (Object value : warnings)
			{
				Map<?, ?> warning = (Map<?, ?>) value;
				foundChild |= String.valueOf(warning.get("title")).contains("warning child <sample>");
				assertTrue(String.valueOf(warning.get("warnings")).contains("Parent content " + root));
				assertTrue(String.valueOf(warning.get("warnings")).contains("parent was left empty"));
			}
			assertTrue(foundChild);
			var guest = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_GUEST_ID);
			try
			{
				assertTrue(((List<?>) ModuleBridge.invokeStatic(QUERIES, "listWarnings", guest, id, transaction)).isEmpty());
				List<?> rows = (List<?>) ModuleBridge.invokeStatic(QUERIES, "listMigrations", guest, transaction);
				assertTrue(rows.stream().noneMatch(row -> id.equals(((Map<?, ?>) row).get("id"))));
			}
			finally
			{
				ManagerFactory.getAuthenticationManager().endImpersonation(guest);
			}
			assertTrue(((List<?>) ModuleBridge.invokeStatic(QUERIES, "listWarnings", session, "missing", transaction)).isEmpty());
			return null;
		});
	}

	private void assertCount(String id, int expected) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			List<?> rows = (List<?>) ModuleBridge.invokeStatic(QUERIES, "listMigrations", session, transaction);
			Map<?, ?> migration = (Map<?, ?>) rows.stream().filter(row -> id.equals(((Map<?, ?>) row).get("id"))).findFirst().orElseThrow();
			assertEquals(expected, ((Number) migration.get("warningCount")).intValue());
			return null;
		});
	}
}
