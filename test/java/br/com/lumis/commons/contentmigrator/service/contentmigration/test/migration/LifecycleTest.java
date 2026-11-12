package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentVersion;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/**
 * Proves the lifecycle of the background execution of a migration: start as the user who started
 * it, FINISHED with one map row per ContentLocale, injected failure, stop, the global lock (refusal,
 * release, takeover of a stale lock and abort of a run whose lock was taken), incremental resume,
 * counters, dates in milliseconds, malformed execution properties and the edit permission on the
 * destination.
 */
public class LifecycleTest
{
	private static final String FAIL_AT_ITEM = "contentmigrator.contentmigration.faultInjection.failAtItem";

	private static final String ITEM_DELAY_MILLIS = "contentmigrator.contentmigration.faultInjection.itemDelayMillis";

	private static final String HEARTBEAT_SECONDS = "contentmigrator.contentmigration.heartbeatSeconds";

	private static final String ITEM_TIME_LIMIT_SECONDS = "contentmigrator.contentmigration.itemTimeLimitSeconds";

	private static final String AWAIT_MILLIS = "120000";

	private PortalFixture fixture;

	private ChannelConfig channel;

	private final List<String> migrationIds = new ArrayList<>();

	private final List<String[]> pairs = new ArrayList<>();

	private final List<String> userIds = new ArrayList<>();

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
		System.clearProperty(FAIL_AT_ITEM);
		System.clearProperty(ITEM_DELAY_MILLIS);
		System.clearProperty(HEARTBEAT_SECONDS);
		System.clearProperty(ITEM_TIME_LIMIT_SECONDS);
		for (String id : migrationIds)
		{
			ModuleBridge.api("stop", id);
			ModuleBridge.api("awaitTermination", id, AWAIT_MILLIS);
		}
		resetLock();
		if (!PortalFixture.keepTestData())
		{
			for (String[] pair : pairs)
			{
				deleteCopies(pair[0], pair[1]);
			}
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = connection(transaction);
				for (String id : migrationIds)
				{
					update(connection, "delete from lum_cmig_Migration where id = ?", id);
				}
				for (String userId : userIds)
				{
					ManagerFactory.getUserManager().delete(session, userId, transaction);
				}
				return null;
			});
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	private static Connection connection(Object transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}

	private static int update(Connection connection, String sql, Object... params) throws Exception
	{
		try (PreparedStatement st = connection.prepareStatement(sql))
		{
			for (int i = 0; i < params.length; i++)
			{
				st.setObject(i + 1, params[i]);
			}
			return st.executeUpdate();
		}
	}

	private static List<Map<String, Object>> query(String sql, Object... params) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<Map<String, Object>> rows = new ArrayList<>();
			try (PreparedStatement st = connection(transaction).prepareStatement(sql))
			{
				for (int i = 0; i < params.length; i++)
				{
					st.setObject(i + 1, params[i]);
				}
				try (ResultSet rs = st.executeQuery())
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
		for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++)
		{
			row.put(rs.getMetaData().getColumnLabel(c).toLowerCase(), rs.getObject(c));
		}
		return row;
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

	private static Map<String, Object> lockRow() throws Exception
	{
		List<Map<String, Object>> rows = query("select runId, migrationId, heartbeat from lum_cmig_MigLock");
		assertEquals("one global lock row", 1, rows.size());
		return rows.get(0);
	}

	private static List<Map<String, Object>> mapRows(ServiceInstanceConfig origin, ServiceInstanceConfig dest) throws Exception
	{
		// @formatter:off
		return query("select * from lum_cmig_MigItemMap where originServiceInstanceId = ? and "
				+ "destServiceInstanceId = ? order by originContentLocaleId", origin.getId(), dest.getId());
		// @formatter:on
	}

	/** Removes the destination contents copied for the pair, and its map rows. */
	private static void deleteCopies(String origin, String dest) throws Exception
	{
		// @formatter:off
		List<Map<String, Object>> rows = query("select destContentId, destItemId from lum_cmig_MigItemMap where "
				+ "originServiceInstanceId = ? and destServiceInstanceId = ?", origin, dest);
		// @formatter:on
		for (Map<String, Object> row : rows)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				try
				{
					ContentManagerFactory.getContentManager().deleteContent(ContentManagerFactory.getContentManager().getContent((String) row.get("destcontentid")));
				}
				catch (PortalObjectNotFoundException e)
				{
					// already removed
				}
				update(connection(transaction), "delete from lum_News where id = ?", row.get("destitemid"));
				return null;
			});
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			// @formatter:off
			update(connection(transaction), "delete from lum_cmig_MigItemMap where originServiceInstanceId = ? "
					+ "and destServiceInstanceId = ?", origin, dest);
			// @formatter:on
			return null;
		});
	}

	private ServiceInstanceConfig[] pairWithNews(int contents) throws Exception
	{
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		for (int i = 0; i < contents; i++)
		{
			fixture.addNewsContent(origin);
		}
		pairs.add(new String[]{origin.getId(), dest.getId()});
		return new ServiceInstanceConfig[]{origin, dest};
	}

	@SuppressWarnings("unchecked")
	private String create(ServiceInstanceConfig[] pair) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-test-lifecycle");
		params.put("originServiceInstanceId", pair[0].getId());
		params.put("destServiceInstanceId", pair[1].getId());
		String id = (String) ((Map<String, Object>) ModuleBridge.api("create", params)).get("id");
		migrationIds.add(id);
		return id;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> start(String migrationId, String userId) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("migrationId", migrationId);
		params.put("userId", userId);
		return (Map<String, Object>) ModuleBridge.api("start", params);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> await(String migrationId) throws Exception
	{
		Map<String, Object> result = (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
		assertEquals("terminated", Boolean.TRUE, result.get("terminated"));
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> get(String migrationId) throws Exception
	{
		return (Map<String, Object>) ModuleBridge.api("get", migrationId);
	}

	private static void assertStartRefused(String code, String migrationId, String userId) throws Exception
	{
		try
		{
			start(migrationId, userId);
			fail("expected " + code);
		}
		catch (RuntimeException e)
		{
			assertEquals(e.toString(), code, ModuleBridge.errorCode(e));
		}
	}

	private static int waitForMapRows(ServiceInstanceConfig[] pair, int minimum) throws Exception
	{
		long limit = System.currentTimeMillis() + 60_000;
		while (System.currentTimeMillis() < limit)
		{
			int rows = mapRows(pair[0], pair[1]).size();
			if (rows >= minimum)
			{
				return rows;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("map rows did not reach " + minimum);
	}

	private static boolean contentLocaleExists(Object contentLocaleId) throws Exception
	{
		return !query("select id from lum_ContentLocale where id = ?", contentLocaleId).isEmpty();
	}

	private static void assertEpochMillis(Map<String, Object> migration, String key)
	{
		String value = (String) migration.get(key);
		assertNotNull(key, value);
		assertTrue(key + " = " + value, value.matches("\\d+"));
	}

	@Test
	public void startRunsInBackgroundAsTheStarterAndFinishesWithOneMapRowPerContentLocale() throws Exception
	{
		ServiceInstanceConfig[] pair = pairWithNews(3);
		String id = create(pair);
		System.setProperty(ITEM_DELAY_MILLIS, "300");

		Map<String, Object> started = start(id, UserConfig.USER_ADMINISTRATOR_ID);
		assertEquals("RUNNING", started.get("status"));
		assertEquals("3", started.get("totalCount"));
		assertNotNull(lockRow().get("runid"));
		assertEquals(id, lockRow().get("migrationid"));

		Map<String, Object> finished = await(id);
		assertEquals("FINISHED", finished.get("status"));
		assertEquals("3", finished.get("totalCount"));
		assertEquals("3", finished.get("migratedCount"));
		assertEquals(Boolean.FALSE, finished.get("stopRequested"));
		assertEpochMillis(finished, "startDate");
		assertEpochMillis(finished, "endDate");
		assertEpochMillis(finished, "heartbeat");
		assertNull(finished.get("errorMessage"));
		assertNull("lock released", lockRow().get("runid"));

		List<Map<String, Object>> rows = mapRows(pair[0], pair[1]);
		assertEquals(3, rows.size());
		for (Map<String, Object> row : rows)
		{
			assertEquals("CONTENT", row.get("kind"));
			assertEquals(PortalFixture.NEWS_SERVICE_ID, row.get("serviceid"));
			assertEquals("default", row.get("sourceid"));
			assertTrue(contentLocaleExists(row.get("destcontentlocaleid")));
			String originItemId = (String) row.get("originitemid");
			String destItemId = (String) row.get("destitemid");
			List<Map<String, Object>> origin = query("select title, content from lum_News where id = ?", originItemId);
			List<Map<String, Object>> dest = query("select title, content from lum_News where id = ?", destItemId);
			assertEquals("simple fields copied", origin, dest);
			PortalFixture.inTransaction((session, transaction) ->
			{
				ContentVersion originVersion = ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId);
				ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(destItemId);
				assertEquals("source authorship is preserved", originVersion.getLastModifiedBy(), version.getLastModifiedBy());
				assertEquals(pair[1].getId(), version.getContentLocale().getContent().getServiceInstanceId());
				return null;
			});
		}
	}

	@Test
	public void injectedFailureStopsWithErrorAndAnIncrementalRunCopiesOnlyWhatIsMissing() throws Exception
	{
		ServiceInstanceConfig[] pair = pairWithNews(3);
		String id = create(pair);
		System.setProperty(FAIL_AT_ITEM, "2");

		start(id, UserConfig.USER_SYSTEM_ID);
		Map<String, Object> failed = await(id);
		assertEquals("STOPPED_WITH_ERROR", failed.get("status"));
		assertEquals("GENERIC", failed.get("errorType"));
		assertTrue(String.valueOf(failed.get("errorMessage")), String.valueOf(failed.get("errorMessage")).contains("Injected failure at item 2"));
		assertTrue(String.valueOf(failed.get("errorStack")).contains("IllegalStateException"));
		assertEquals("1", failed.get("migratedCount"));
		assertNull("lock released", lockRow().get("runid"));
		List<Map<String, Object>> afterFailure = mapRows(pair[0], pair[1]);
		assertEquals("previous item kept", 1, afterFailure.size());
		Object firstDest = afterFailure.get(0).get("destcontentlocaleid");
		assertTrue(contentLocaleExists(firstDest));

		System.clearProperty(FAIL_AT_ITEM);
		start(id, UserConfig.USER_SYSTEM_ID);
		Map<String, Object> finished = await(id);
		assertEquals("FINISHED", finished.get("status"));
		assertNull(finished.get("errorMessage"));
		assertNull(finished.get("errorStack"));
		assertEquals("3", finished.get("migratedCount"));
		List<Map<String, Object>> rows = mapRows(pair[0], pair[1]);
		assertEquals(3, rows.size());
		assertTrue("mapped item not copied again", rows.stream().anyMatch(r -> firstDest.equals(r.get("destcontentlocaleid"))));
		// @formatter:off
		assertEquals("no duplicate in the destination", 3,
				query("select c.id from lum_Content c where c.serviceInstanceId = ?", pair[1].getId()).size());
		// @formatter:on

		// a destination removed counts as not migrated and is copied again by the next run
		Map<String, Object> removed = rows.get(0);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getContentManager().deleteContent(ContentManagerFactory.getContentManager().getContent((String) removed.get("destcontentid")));
			return null;
		});
		assertEquals("a removed destination is not migrated", 2, countMigrated(pair));

		start(id, UserConfig.USER_SYSTEM_ID);
		assertEquals("FINISHED", await(id).get("status"));
		assertEquals("3", get(id).get("migratedCount"));
		List<Map<String, Object>> recopied = mapRows(pair[0], pair[1]);
		assertEquals("the map row is reused", 3, recopied.size());
		Map<String, Object> row = recopied.stream().filter(r -> removed.get("origincontentlocaleid").equals(r.get("origincontentlocaleid"))).findFirst().get();
		assertNotEquals(removed.get("destcontentlocaleid"), row.get("destcontentlocaleid"));
		assertTrue(contentLocaleExists(row.get("destcontentlocaleid")));
	}

	private static int countMigrated(ServiceInstanceConfig[] pair) throws Exception
	{
		return PortalFixture.inTransaction(
				(
						session,
						transaction) -> (Integer) ModuleBridge.invokeStatic(
								"br.com.lumis.commons.contentmigrator.service.contentmigration.copy.ItemProcessor",
								"countMigrated",
								connection(transaction),
								pair[0].getId(),
								pair[1].getId()));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void startIsRefusedWhileAnotherRunsAndStopEndsTheCurrentItemAndReleasesTheLock() throws Exception
	{
		ServiceInstanceConfig[] first = pairWithNews(3);
		ServiceInstanceConfig[] second = pairWithNews(1);
		String firstId = create(first);
		String secondId = create(second);
		System.setProperty(ITEM_DELAY_MILLIS, "1500");

		start(firstId, UserConfig.USER_SYSTEM_ID);
		assertStartRefused("LOCKED", secondId, UserConfig.USER_SYSTEM_ID);
		assertStartRefused("LOCKED", firstId, UserConfig.USER_SYSTEM_ID);
		assertEquals("STOPPED", get(secondId).get("status"));

		int rowsAtStop = waitForMapRows(first, 1);
		Map<String, Object> stopping = (Map<String, Object>) ModuleBridge.api("stop", firstId);
		assertEquals(Boolean.TRUE, stopping.get("stopRequested"));
		Map<String, Object> stopped = await(firstId);
		assertEquals("STOPPED", stopped.get("status"));
		assertEquals(Boolean.FALSE, stopped.get("stopRequested"));
		assertNull(stopped.get("errorMessage"));
		int rows = mapRows(first[0], first[1]).size();
		assertTrue("current item ended: " + rows, rows >= rowsAtStop && rows <= rowsAtStop + 1);
		assertTrue("stopped before the end: " + rows, rows < 3);
		assertEquals(String.valueOf(rows), stopped.get("migratedCount"));
		assertNull("lock released", lockRow().get("runid"));

		System.clearProperty(ITEM_DELAY_MILLIS);
		start(secondId, UserConfig.USER_SYSTEM_ID);
		assertEquals("FINISHED", await(secondId).get("status"));
		assertEquals(1, mapRows(second[0], second[1]).size());
	}

	@Test
	public void runningWithHeartbeatStoppedForMoreThanFiveMinutesIsResumed() throws Exception
	{
		ServiceInstanceConfig[] pair = pairWithNews(2);
		String id = create(pair);
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = connection(transaction);
			Timestamp now;
			try (PreparedStatement st = connection.prepareStatement("select CURRENT_TIMESTAMP from lum_cmig_MigLock"); ResultSet rs = st.executeQuery())
			{
				rs.next();
				now = rs.getTimestamp(1);
			}
			Timestamp stale = new Timestamp(now.getTime() - 6 * 60_000L);
			// @formatter:off
			update(connection, "update lum_cmig_MigLock set runId = ?, migrationId = ?, heartbeat = ?",
					PortalUtil.generateNewGuid(), id, stale);
			// @formatter:on
			update(connection, "update lum_cmig_Migration set status = ?, heartbeat = ? where id = ?", "RUNNING", stale, id);
			return null;
		});

		Map<String, Object> resumed = start(id, UserConfig.USER_SYSTEM_ID);
		assertEquals("RUNNING", resumed.get("status"));
		assertEquals("FINISHED", await(id).get("status"));
		assertEquals(2, mapRows(pair[0], pair[1]).size());
		assertNull("lock released", lockRow().get("runid"));
	}

	@Test
	public void runWhoseLockWasTakenRollsBackTheItemAndAbortsWithoutStatus() throws Exception
	{
		ServiceInstanceConfig[] pair = pairWithNews(3);
		String id = create(pair);
		System.setProperty(ITEM_DELAY_MILLIS, "1500");

		start(id, UserConfig.USER_SYSTEM_ID);
		waitForMapRows(pair, 1);
		String otherRunId = PortalUtil.generateNewGuid();
		PortalFixture.inTransaction((session, transaction) ->
		{
			update(connection(transaction), "update lum_cmig_MigLock set runId = ?", otherRunId);
			return null;
		});
		int rowsAtTakeover = mapRows(pair[0], pair[1]).size();

		Map<String, Object> aborted = await(id);
		assertEquals("no status written", "RUNNING", aborted.get("status"));
		assertNull(aborted.get("endDate"));
		assertEquals("item rolled back", rowsAtTakeover, mapRows(pair[0], pair[1]).size());
		assertTrue(rowsAtTakeover < 3);
		assertEquals("lock kept by the new run", otherRunId, lockRow().get("runid"));
	}

	/**
	 * Regression: a malformed property threw from the MigrationTask constructor after start had
	 * committed RUNNING and taken the lock, so the migration stayed RUNNING with the lock held.
	 */
	@Test
	public void malformedExecutionPropertiesFallBackToTheDefaultsAndTheRunFinishes() throws Exception
	{
		ServiceInstanceConfig[] pair = pairWithNews(1);
		String id = create(pair);
		System.setProperty(HEARTBEAT_SECONDS, "not-a-number");
		System.setProperty(ITEM_TIME_LIMIT_SECONDS, "30s");

		Map<String, Object> started = start(id, UserConfig.USER_SYSTEM_ID);
		assertEquals("RUNNING", started.get("status"));
		Map<String, Object> finished = await(id);
		assertEquals("FINISHED", finished.get("status"));
		assertEquals("1", finished.get("migratedCount"));
		assertNull("lock released", lockRow().get("runid"));
	}

	/** Last 12 characters of a new GUID: the leading part is the same for every GUID generated by this JVM. */
	private static String uniqueSuffix()
	{
		String guid = PortalUtil.generateNewGuid().toLowerCase();
		return guid.substring(guid.length() - 12);
	}

	@Test
	public void userWithoutEditPermissionOnTheDestinationCannotStart() throws Exception
	{
		ServiceInstanceConfig[] pair = pairWithNews(1);
		String id = create(pair);
		UserConfig user = new UserConfig();
		String login = "contentmigrator-test-" + uniqueSuffix();
		user.setLogin(login);
		user.setFirstName(login);
		user.setEmail(login + "@example.com");
		// satisfies the password policy: lower and upper case letters, digit and special character
		user.setPassword("contentmigrator#9" + PortalUtil.generateNewGuid().toLowerCase() + "Q!");
		String userId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
		userIds.add(userId);

		assertStartRefused("FORBIDDEN", id, userId);
		assertEquals("STOPPED", get(id).get("status"));
		assertNull("lock not taken", lockRow().get("runid"));
		assertTrue(mapRows(pair[0], pair[1]).isEmpty());
	}
}
