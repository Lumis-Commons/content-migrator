package br.com.lumis.commons.contentmigrator.service.contentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.portal.PortalConfiguration;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.hibernate.ITransactionHibernate;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;

/** Integration coverage for migration administration queries and create validation. */
public class AdminFormValidationTest
{
	private static final String ADMIN = "br.com.lumis.commons.contentmigrator.service.contentmigration.query.MigrationAdminQueries";

	private static final String RULES_VIOLATED = "RULES_VIOLATED";

	private static final String DUPLICATE_PAIR = "DUPLICATE_PAIR";

	private static final String MEDIA_DEPENDENCY = "media_repository";

	private PortalFixture fixture;

	private ChannelConfig channel;

	private final List<String> instanceIds = new ArrayList<>();

	private final List<String> migrationIds = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		PortalConfiguration.getCurrentConfiguration();
		fixture = new PortalFixture();
		channel = fixture.createChannel();
	}

	@After
	public void tearDown() throws Exception
	{
		if (!PortalFixture.keepTestData())
		{
			for (String id : migrationIds)
			{
				ModuleBridge.api("delete", id);
			}
			PortalFixture.inTransaction((session, transaction) ->
			{
				java.sql.Connection connection = ((ITransactionHibernate) transaction).getConnection();
				for (String id : instanceIds)
				{
					// @formatter:off
					delete(connection, "delete from lum_cmig_MigItemMap where originServiceInstanceId = ? "
							+ "or destServiceInstanceId = ?", id, id);
					// @formatter:on
				}
				return null;
			});
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void chainedQueriesFilterServicesAndInstancesAndListReadableColumns() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig destination = news();
		ServiceInstanceConfig third = news();
		ServiceInstanceConfig media = service(PortalFixture.MEDIA_SERVICE_ID);

		List<Map<String, Object>> services = (List<Map<String, Object>>) PortalFixture
				.inTransaction((session, transaction) -> ModuleBridge.invokeStatic(ADMIN, "listContentServices", session, transaction));
		assertTrue("the news content service has two visible instances", services.stream().anyMatch(row -> PortalFixture.NEWS_SERVICE_ID.equals(row.get("serviceId"))));
		assertFalse("repository services are excluded", services.stream().anyMatch(row -> PortalFixture.MEDIA_SERVICE_ID.equals(row.get("serviceId"))));
		assertFalse("only lum_content services are eligible", services.stream().anyMatch(row -> "lumis.service.document".equals(row.get("serviceId"))));

		List<Map<String, Object>> destinations = (List<Map<String, Object>>) PortalFixture.inTransaction(
				(session, transaction) -> ModuleBridge
						.invokeStatic(ADMIN, "listInstancesByService", session, PortalFixture.NEWS_SERVICE_ID, origin.getId(), true, transaction));
		assertFalse(destinations.stream().anyMatch(row -> origin.getId().equals(row.get("serviceInstanceId"))));
		assertTrue(destinations.stream().anyMatch(row -> destination.getId().equals(row.get("serviceInstanceId"))));
		assertTrue(destinations.stream().anyMatch(row -> third.getId().equals(row.get("serviceInstanceId"))));

		Map<String, Object> created = create(origin, destination);
		String migrationId = (String) created.get("id");
		migrationIds.add(migrationId);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> migrations = (List<Map<String, Object>>) PortalFixture
				.inTransaction((session, transaction) -> ModuleBridge.invokeStatic(ADMIN, "listMigrations", session, transaction));
		Map<String, Object> row = migrations.stream().filter(item -> migrationId.equals(item.get("id"))).findFirst().orElse(null);
		assertNotNull(row);
		for (String field : Arrays.asList("name", "serviceName", "originName", "destinationName", "updateMode", "status", "totalCount", "migratedCount"))
		{
			assertTrue("list column " + field, row.containsKey(field));
		}

		updateMigrationError(migrationId, "validation test error", "line one\nline two");
		Map<String, Object> error = (Map<String, Object>) PortalFixture
				.inTransaction((session, transaction) -> ModuleBridge.invokeStatic(ADMIN, "getMigrationError", session, migrationId, transaction));
		assertEquals("validation test error", error.get("errorMessage"));
		assertEquals("line one\nline two", error.get("errorStack"));
		PortalFixture.inTransaction((session, transaction) ->
		{
			updateError(connection(transaction), migrationId, null, null);
			return null;
		});
		assertFalse("the repository fixture is not a valid content origin", media.getServiceId().equals(origin.getServiceId()));
	}

	@Test
	public void creationRejectsDependencyRulesAndDuplicatePairs() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig destination = news();
		ServiceInstanceConfig media = service(PortalFixture.MEDIA_SERVICE_ID);
		fixture.setDependency(media, origin, MEDIA_DEPENDENCY);
		removeDependency(destination, MEDIA_DEPENDENCY);

		try
		{
			create(origin, destination);
			fail("expected the missing media repository to reject creation");
		}
		catch (Exception e)
		{
			assertEquals(RULES_VIOLATED, ModuleBridge.errorCode(e));
			assertTrue(e.getMessage(), e.getMessage().contains(MEDIA_DEPENDENCY));
		}

		ServiceInstanceConfig validOrigin = news();
		ServiceInstanceConfig validDestination = news();
		Map<String, Object> created = create(validOrigin, validDestination);
		String migrationId = (String) created.get("id");
		migrationIds.add(migrationId);
		try
		{
			create(validOrigin, validDestination);
			fail("expected a second migration for the same pair to be rejected");
		}
		catch (Exception e)
		{
			assertEquals(DUPLICATE_PAIR, ModuleBridge.errorCode(e));
		}
	}

	private ServiceInstanceConfig news() throws Exception
	{
		return service(PortalFixture.NEWS_SERVICE_ID);
	}

	private ServiceInstanceConfig service(String serviceId) throws Exception
	{
		ServiceInstanceConfig instance = fixture.createServiceInstance(channel, serviceId);
		instanceIds.add(instance.getId());
		return instance;
	}

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

	private Map<String, Object> create(ServiceInstanceConfig origin, ServiceInstanceConfig destination) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "admin validation " + origin.getId());
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		return (Map<String, Object>) ModuleBridge.api("create", params);
	}

	private void updateMigrationError(String migrationId, String message, String stack) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			updateError(((ITransactionHibernate) transaction).getConnection(), migrationId, message, stack);
			return null;
		});
	}

	private static void updateError(java.sql.Connection connection, String migrationId, String message, String stack) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement("update lum_cmig_Migration set errorMessage = ?, errorStack = ? where id = ?"))
		{
			statement.setString(1, message);
			statement.setString(2, stack);
			statement.setString(3, migrationId);
			statement.executeUpdate();
		}
	}

	private static java.sql.Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionHibernate) transaction).getConnection();
	}

	private static void delete(java.sql.Connection connection, String sql, String... values) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			for (int i = 0; i < values.length; i++)
			{
				statement.setString(i + 1, values[i]);
			}
			statement.executeUpdate();
		}
	}
}
