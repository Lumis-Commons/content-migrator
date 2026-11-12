package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
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
	private static final String RESOURCE = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.rest.MigrationStatusResource";

	private static final String LOCK = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.lock.GlobalLock";

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
							.prepareStatement("update lum_cmig_HMigration set status = ?, heartbeat = null, errorType = null " + "where id = ?"))
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
		ServiceInstanceConfig origin = hierarchicalContent();
		ServiceInstanceConfig destination = hierarchicalContent();
		Map<String, Object> params = new HashMap<>();
		params.put("name", "status REST test");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", fixture.addHierarchicalContent(origin, "root", null, true));
		Map<String, Object> created = (Map<String, Object>) ModuleBridge.api("create", params);
		migrationId = (String) created.get("id");

		Class<?> resource = ModuleBridge.load(RESOURCE);
		assertEquals("/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration", resource.getAnnotation(Path.class).value());

		for (int repetition = 0; repetition < 20; repetition++)
		{
			markRunningMigration(migrationId, true);
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
		markRunningMigration(migrationId, false);
		try (Response recent = getStatusAs(UserConfig.USER_SYSTEM_ID))
		{
			assertEquals(200, recent.getStatus());
			JSONArray items = new JSONArray((String) recent.getEntity());
			boolean found = false;
			for (int i = 0; i < items.length(); i++)
			{
				JSONObject item = items.getJSONObject(i);
				if (migrationId.equals(item.getString("id")))
				{
					assertEquals("RUNNING", item.getString("status"));
					found = true;
				}
			}
			assertTrue("recent migration must be returned", found);
		}
	}

	@Test
	public void refusesUserWithoutMigrationPermissions() throws Exception
	{
		ServiceInstanceConfig origin = hierarchicalContent();
		ServiceInstanceConfig destination = hierarchicalContent();
		Map<String, Object> params = new HashMap<>();
		params.put("name", "status REST authorization test");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", fixture.addHierarchicalContent(origin, "root", null, true));
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

	private ServiceInstanceConfig hierarchicalContent() throws Exception
	{
		return fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
	}

	private void markRunningMigration(String id, boolean stale) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Timestamp now = (Timestamp) ModuleBridge.invokeStatic(LOCK, "databaseNow", connection(transaction));
			long staleMinutes = ModuleBridge.load(LOCK).getField("STALE_MINUTES").getInt(null);
			long heartbeat = now.getTime() - (stale ? staleMinutes * 60_000L + 60_000L : 0);
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("update lum_cmig_HMigration set status = ?, operation = ?, heartbeat = ?, totalCount = ?, " + "migratedCount = ? where id = ?"))
			{
				statement.setString(1, "RUNNING");
				statement.setString(2, "MIGRATE");
				statement.setTimestamp(3, new Timestamp(heartbeat));
				statement.setInt(4, 8);
				statement.setInt(5, 3);
				statement.setString(6, id);
				assertTrue(statement.executeUpdate() == 1);
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
