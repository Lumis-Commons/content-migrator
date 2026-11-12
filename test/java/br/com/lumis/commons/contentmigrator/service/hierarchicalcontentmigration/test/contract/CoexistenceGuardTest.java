package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.util.PortalUtil;

/** Exercises coexistence without changing the original component's implementation or retained data. */
public class CoexistenceGuardTest
{
	private static final String DAO = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.ContentMigrationCoexistenceDao";

	private static final String LOCK = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.lock.GlobalLock";

	private final PortalFixture fixture = new PortalFixture();

	private final String originalId = PortalUtil.generateNewGuid();

	private Map<String, Object> params;

	private String migrationId;

	private Object[] savedLock;

	private boolean lockSnapshotTaken;

	@Before
	public void setUp() throws Exception
	{
		lumis.portal.channel.ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig destination = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		params = new HashMap<>();
		params.put("name", "contentmigrator-test-coexistence");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", fixture.addHierarchicalContent(origin, "root", null, true));
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			try (PreparedStatement statement = connection.prepareStatement("select runId, migrationId, heartbeat from lum_cmig_MigLock where id = 'global'");
					ResultSet result = statement.executeQuery())
			{
				if (result.next())
				{
					savedLock = new Object[]{result.getString(1), result.getString(2), result.getTimestamp(3)};
					assertNull("An original migration is already running", savedLock[0]);
				}
			}
			lockSnapshotTaken = true;
			return null;
		});
	}

	@After
	public void tearDown() throws Exception
	{
		try
		{
			if (migrationId != null)
			{
				ModuleBridge.api("awaitTermination", migrationId, "60000");
				ModuleBridge.api("delete", migrationId);
			}
		}
		finally
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = ((ITransactionJdbc) transaction).getConnection();
				try (PreparedStatement statement = connection.prepareStatement("delete from lum_cmig_MigLock where id = 'global' and ? = ?"))
				{
					statement.setInt(1, lockSnapshotTaken ? 1 : 0);
					statement.setInt(2, 1);
					statement.executeUpdate();
				}
				if (lockSnapshotTaken && savedLock != null)
				{
					try (PreparedStatement statement = connection
							.prepareStatement("insert into lum_cmig_MigLock (id, runId, migrationId, heartbeat) values ('global', ?, ?, ?)"))
					{
						statement.setString(1, (String) savedLock[0]);
						statement.setString(2, (String) savedLock[1]);
						statement.setTimestamp(3, (Timestamp) savedLock[2]);
						statement.executeUpdate();
					}
				}
				try (PreparedStatement statement = connection.prepareStatement("delete from lum_cmig_Migration where id = ?"))
				{
					statement.setString(1, originalId);
					statement.executeUpdate();
				}
				return null;
			});
			fixture.cleanup();
		}
	}

	@SuppressWarnings("unchecked")
	private void create() throws Exception
	{
		migrationId = (String) ((Map<String, Object>) ModuleBridge.api("create", params)).get("id");
	}

	private void original(boolean samePair, boolean sameDestination, Boolean recent) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			try (PreparedStatement statement = connection.prepareStatement(
					"insert into lum_cmig_Migration "
							+ "(id, name, serviceId, originServiceInstanceId, destServiceInstanceId, updateMode, operation, status, stopRequested, totalCount, migratedCount, createdDate) "
							+ "values (?, 'contentmigrator-test-original', ?, ?, ?, 'ADD_ONLY', 'MIGRATE', 'STOPPED', ?, 0, 0, CURRENT_TIMESTAMP)"))
			{
				statement.setString(1, originalId);
				statement.setString(2, PortalFixture.HIERARCHICAL_SERVICE_ID);
				statement.setString(3, samePair ? (String) params.get("originServiceInstanceId") : PortalUtil.generateNewGuid());
				statement.setString(4, sameDestination ? (String) params.get("destServiceInstanceId") : PortalUtil.generateNewGuid());
				statement.setBoolean(5, false);
				statement.executeUpdate();
			}
			if (recent != null)
			{
				ModuleBridge.invokeStatic(LOCK, "ensureLockRow", connection);
				Timestamp now = (Timestamp) ModuleBridge.invokeStatic(LOCK, "databaseNow", connection);
				int minutes = ModuleBridge.load(LOCK).getField("STALE_MINUTES").getInt(null);
				Timestamp heartbeat = recent ? now : new Timestamp(now.getTime() - (minutes * 60_000L + 60_000L));
				if (savedLock == null)
				{
					try (PreparedStatement statement = connection.prepareStatement("insert into lum_cmig_MigLock (id) values ('global')"))
					{
						statement.executeUpdate();
					}
				}
				try (PreparedStatement statement = connection
						.prepareStatement("update lum_cmig_MigLock set runId = ?, migrationId = ?, heartbeat = ? where id = 'global'"))
				{
					statement.setString(1, PortalUtil.generateNewGuid());
					statement.setString(2, originalId);
					statement.setTimestamp(3, heartbeat);
					statement.executeUpdate();
				}
			}
			return null;
		});
	}

	private void rejected(String action, Map<String, Object> arguments) throws Exception
	{
		try
		{
			ModuleBridge.api(action, arguments);
			fail("Expected CONTENTMIGRATION_CONFLICT");
		}
		catch (RuntimeException exception)
		{
			assertEquals(exception.toString(), "CONTENTMIGRATION_CONFLICT", ModuleBridge.errorCode(exception));
			assertFalse(exception.getMessage().contains("STR_CONTENTMIGRATION_CONFLICT"));
		}
		if (migrationId != null)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = ((ITransactionJdbc) transaction).getConnection();
				try (PreparedStatement statement = connection.prepareStatement("select runId from lum_cmig_HMigLock where id = 'global'");
						ResultSet result = statement.executeQuery())
				{
					assertTrue(result.next());
					assertNull("Conflict must roll back the hierarchical lock", result.getString(1));
				}
				return null;
			});
		}
	}

	@Test
	public void samePairRejectsCreate() throws Exception
	{
		original(true, true, null);
		rejected("create", params);
	}

	@Test
	public void samePairRejectsStart() throws Exception
	{
		create();
		original(true, true, null);
		rejected("start", java.util.Collections.singletonMap("migrationId", migrationId));
	}

	@Test
	public void recentDestinationLockRejectsStartButAllowsCreate() throws Exception
	{
		original(false, true, true);
		create();
		rejected("start", java.util.Collections.singletonMap("migrationId", migrationId));
	}

	private void allowed(Boolean recent, boolean sameDestination) throws Exception
	{
		original(false, sameDestination, recent);
		create();
		ModuleBridge.api("start", java.util.Collections.singletonMap("migrationId", migrationId));
		Map<?, ?> result = (Map<?, ?>) ModuleBridge.api("awaitTermination", migrationId, "60000");
		assertEquals(Boolean.TRUE, result.get("terminated"));
		assertEquals("FINISHED", result.get("status"));
	}

	@Test
	public void staleDestinationLockAllowsStart() throws Exception
	{
		allowed(false, true);
	}

	@Test
	public void recentOtherDestinationLockAllowsStart() throws Exception
	{
		allowed(true, false);
	}

	@Test
	public void stoppedCommonDestinationAllowsStart() throws Exception
	{
		allowed(null, true);
	}

	@Test
	public void missingTablesAreIgnoredAndMetadataIsCached() throws Exception
	{
		Class<?> dao = ModuleBridge.load(DAO);
		Field migration = dao.getDeclaredField("migrationTablePresent");
		Field lock = dao.getDeclaredField("lockTablePresent");
		migration.setAccessible(true);
		lock.setAccessible(true);
		Object oldMigration = migration.get(null);
		Object oldLock = lock.get(null);
		int[] queries = {0};
		DatabaseMetaData metadata = (DatabaseMetaData) Proxy
				.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) ->
				{
					assertEquals("getTables", method.getName());
					queries[0]++;
					return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (p, m, a) ->
					{
						if (m.getName().equals("next"))
						{
							return false;
						}
						if (m.getName().equals("close"))
						{
							return null;
						}
						throw new AssertionError(m.getName());
					});
				});
		Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) ->
		{
			if (method.getName().equals("getMetaData"))
			{
				return metadata;
			}
			if (method.getName().equals("getCatalog") || method.getName().equals("getSchema"))
			{
				return null;
			}
			throw new AssertionError("Unexpected SQL for absent tables: " + method.getName());
		});
		try
		{
			migration.set(null, null);
			lock.set(null, null);
			assertEquals(false, ModuleBridge.invokeStatic(DAO, "hasPair", connection, "origin", "destination"));
			assertEquals(false, ModuleBridge.invokeStatic(DAO, "hasActiveDestination", connection, "destination", new Timestamp(0)));
			assertEquals(false, ModuleBridge.invokeStatic(DAO, "hasPair", connection, "origin", "destination"));
			assertEquals(3, queries[0]);
			migration.set(null, true);
			assertEquals(false, ModuleBridge.invokeStatic(DAO, "hasActiveDestination", connection, "destination", new Timestamp(0)));
			assertEquals(false, ModuleBridge.invokeStatic(DAO, "hasActiveDestination", connection, "destination", new Timestamp(0)));
			assertEquals(6, queries[0]);
		}
		finally
		{
			migration.set(null, oldMigration);
			lock.set(null, oldLock);
		}
	}
}
