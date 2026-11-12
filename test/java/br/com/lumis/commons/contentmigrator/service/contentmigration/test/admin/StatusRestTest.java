package br.com.lumis.commons.contentmigrator.service.contentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.portal.PortalConfiguration;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/** Integration tests for the migration status REST resource and its authorization. */
public class StatusRestTest
{
	private static final String RESOURCE = "br.com.lumis.commons.contentmigrator.service.contentmigration.rest.MigrationStatusResource";

	private static final String STALE_MINUTES = "5";

	private PortalFixture fixture;

	private ChannelConfig channel;

	private String migrationId;

	private String userId;

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
			if (migrationId != null)
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					try (PreparedStatement statement = connection(transaction)
							.prepareStatement("update lum_cmig_Migration set status = ?, heartbeat = null, errorType = null " + "where id = ?"))
					{
						statement.setString(1, "STOPPED");
						statement.setString(2, migrationId);
						statement.executeUpdate();
					}
					return null;
				});
				ModuleBridge.api("delete", migrationId);
			}
			if (userId != null)
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					ManagerFactory.getUserManager().delete(session, userId, transaction);
					return null;
				});
			}
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void returnsStatusFieldsAndReportsStaleRunAsInterrupted() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig destination = news();
		Map<String, Object> params = new HashMap<>();
		params.put("name", "status REST test");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		Map<String, Object> created = (Map<String, Object>) ModuleBridge.api("create", params);
		migrationId = (String) created.get("id");
		markStaleRunningMigration(migrationId);

		Class<?> resource = ModuleBridge.load(RESOURCE);
		assertEquals("/br/com/lumis/commons/contentmigrator/contentmigration", resource.getAnnotation(Path.class).value());

		Response response = getStatusAs(UserConfig.USER_SYSTEM_ID);
		try
		{
			assertEquals(200, response.getStatus());
			JSONArray items = new JSONArray((String) response.getEntity());
			JSONObject item = null;
			for (int i = 0; i < items.length(); i++)
			{
				if (migrationId.equals(items.getJSONObject(i).getString("id")))
				{
					item = items.getJSONObject(i);
				}
			}
			assertNotNull("the migration status must be returned", item);
			assertEquals("STOPPED_WITH_ERROR", item.getString("status"));
			assertEquals("MIGRATE", item.getString("operation"));
			assertEquals("INTERRUPTED", item.getString("errorType"));
			assertEquals(8, item.getInt("totalCount"));
			assertEquals(3, item.getInt("migratedCount"));
			assertFalse("the endpoint must not expose stack traces", item.has("errorStack"));
		}
		finally
		{
			response.close();
		}
	}

	@Test
	public void refusesUserWithoutMigrationPermissions() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig destination = news();
		Map<String, Object> params = new HashMap<>();
		params.put("name", "status REST authorization test");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		Map<String, Object> created = (Map<String, Object>) ModuleBridge.api("create", params);
		migrationId = (String) created.get("id");
		createUnprivilegedUser();

		Response response = getStatusAs(userId);
		try
		{
			assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
		}
		finally
		{
			response.close();
		}
	}

	private ServiceInstanceConfig news() throws Exception
	{
		return fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
	}

	/**
	 * Persists a stale heartbeat with room for database timestamp rounding and checks that precondition.
	 *
	 * @param id migration to mark as running with an expired heartbeat
	 * @throws Exception if the fixture cannot be persisted or read
	 * @since %LUM_NEXT_VERSION%
	 */
	private void markStaleRunningMigration(String id) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			long staleMillis = Long.parseLong(STALE_MINUTES) * 60_000L;
			// Database dateTime columns may round fractional seconds up; 1 ms is not a safe margin.
			long oldHeartbeat = System.currentTimeMillis() - staleMillis - 2_000L;
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("update lum_cmig_Migration set status = ?, operation = ?, heartbeat = ?, totalCount = ?, " + "migratedCount = ? where id = ?"))
			{
				statement.setString(1, "RUNNING");
				statement.setString(2, "MIGRATE");
				statement.setTimestamp(3, new Timestamp(oldHeartbeat));
				statement.setInt(4, 8);
				statement.setInt(5, 3);
				statement.setString(6, id);
				assertTrue(statement.executeUpdate() == 1);
			}
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("select heartbeat from lum_cmig_Migration where id = ?"))
			{
				statement.setString(1, id);
				try (ResultSet result = statement.executeQuery())
				{
					assertTrue("the migration fixture must exist", result.next());
					Timestamp heartbeat = result.getTimestamp(1);
					assertNotNull("the stale heartbeat must be persisted", heartbeat);
					assertTrue("the persisted heartbeat must exceed the stale timeout",
							System.currentTimeMillis() - heartbeat.getTime() > staleMillis);
				}
			}
			return null;
		});
	}

	/** Last 12 characters of a new GUID: the leading part is the same for every GUID generated by this JVM. */
	private static String uniqueSuffix()
	{
		String guid = PortalUtil.generateNewGuid().toLowerCase();
		return guid.substring(guid.length() - 12);
	}

	private void createUnprivilegedUser() throws Exception
	{
		UserConfig user = new UserConfig();
		String login = "contentmigrator-test-" + uniqueSuffix();
		user.setLogin(login);
		user.setFirstName(login);
		user.setEmail(login + "@example.com");
		user.setPassword("contentmigrator#9" + PortalUtil.generateNewGuid().toLowerCase() + "Q!");
		userId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
	}

	private Response getStatusAs(String id) throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(id);
		try
		{
			return (Response) ModuleBridge.invokeStatic(RESOURCE, "getStatusForCurrentUser");
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}
}
