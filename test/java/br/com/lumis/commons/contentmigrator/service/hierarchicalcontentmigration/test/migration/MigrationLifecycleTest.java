package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;

/**
 * Life cycle of a migration with a selection: incremental runs, update after a change of parent,
 * stop and resume, undo, deletion and recreation of the pair, contents that leave the selection
 * and new contents under a parent migrated outside the selection.
 *
 * <pre>
 * root
 * ├─ child
 * │  └─ grandchild   (no published version, as an archived content)
 * └─ sibling
 * other
 * └─ otherChild
 * </pre>
 */
public class MigrationLifecycleTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final String ITEM_DELAY_PROPERTY = "contentmigrator.hierarchicalcontentmigration.faultInjection.itemDelayMillis";

	private PortalFixture fixture;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig destination;

	private final List<String> migrationIds = new ArrayList<>();

	/** Destination items no map row points to anymore, such as the copies of a deleted migration. */
	private final List<String> unmappedCopyItemIds = new ArrayList<>();

	private String root;

	private String child;

	private String grandchild;

	private String sibling;

	private String other;

	private String otherChild;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		ChannelConfig channel = fixture.createChannel();
		origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		root = fixture.addHierarchicalContent(origin, "root", null, true);
		child = fixture.addHierarchicalContent(origin, "child", root, true);
		grandchild = fixture.addHierarchicalContent(origin, "grandchild", child, false);
		sibling = fixture.addHierarchicalContent(origin, "sibling", root, true);
		other = fixture.addHierarchicalContent(origin, "other", null, true);
		otherChild = fixture.addHierarchicalContent(origin, "otherChild", other, true);
		PortalFixture.awaitGlobalLockFree();
	}

	@After
	public void tearDown() throws Exception
	{
		System.clearProperty(ITEM_DELAY_PROPERTY);
		for (String id : migrationIds)
		{
			ModuleBridge.api("stop", id);
			ModuleBridge.api("awaitTermination", id, AWAIT_MILLIS);
		}
		if (!PortalFixture.keepTestData())
		{
			unmappedCopyItemIds.addAll(mappedCopyItemIds());
			deleteCopies(unmappedCopyItemIds);
			for (String id : migrationIds)
			{
				ModuleBridge.api("delete", id);
			}
			PortalFixture.awaitGlobalLockFree();
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void secondIncrementalRunCopiesOnlyTheNewContent() throws Exception
	{
		String id = migrate(root, null, "4");
		Map<String, String> firstCopies = destinationItemByOriginContent();
		int firstVersions = destinationVersions();
		String lateChild = fixture.addHierarchicalContent(origin, "late child", child, true);

		Map<String, Object> second = run(id, null);

		assertFinished(second);
		assertEquals("5", second.get("totalCount"));
		assertEquals("5", second.get("migratedCount"));
		Map<String, String> secondCopies = destinationItemByOriginContent();
		for (Map.Entry<String, String> copy : firstCopies.entrySet())
		{
			assertEquals("an existing copy is skipped, not written again", copy.getValue(), secondCopies.get(copy.getKey()));
		}
		assertEquals("only the late child gets a version", firstVersions + 1, destinationVersions());
		assertEquals(5, destinationContents());
		assertEquals(destinationContentOf(child), destinationParentByOriginContent().get(lateChild));
	}

	@Test
	public void updateAfterAParentChangeMovesTheCopyUnderTheCopyOfTheNewParent() throws Exception
	{
		String id = migrate(root, null, "4");
		String siblingCopy = destinationContentOf(sibling);
		assertEquals(destinationContentOf(root), destinationParentByOriginContent().get(sibling));
		fixture.updateHierarchicalParent(origin, sibling, child);

		Map<String, Object> updated = run(id, "UPDATE");

		assertFinished(updated);
		assertEquals("4", updated.get("migratedCount"));
		assertEquals("the copy is updated, not duplicated", siblingCopy, destinationContentOf(sibling));
		assertEquals(destinationContentOf(child), destinationParentByOriginContent().get(sibling));
		assertEquals(4, destinationContents());
		assertEquals("the new parent was migrated, so there is nothing to warn", "", warningsOf(sibling));
	}

	@Test
	public void stopAndResumeDoNotDuplicateItems() throws Exception
	{
		String id = create(root + "," + other);
		System.setProperty(ITEM_DELAY_PROPERTY, "1500");
		start(id, null);
		awaitMigratedCount(id, 1);
		ModuleBridge.api("stop", id);
		Map<String, Object> stopped = await(id);
		System.clearProperty(ITEM_DELAY_PROPERTY);

		assertEquals("Migration error: " + stopped.get("errorMessage"), "STOPPED", stopped.get("status"));
		int stoppedCount = Integer.parseInt((String) stopped.get("migratedCount"));
		assertTrue("stopped after " + stoppedCount + " items", stoppedCount >= 1 && stoppedCount < 6);
		assertEquals(stoppedCount, destinationContents());

		Map<String, Object> resumed = run(id, null);

		assertFinished(resumed);
		assertEquals("6", resumed.get("totalCount"));
		assertEquals("6", resumed.get("migratedCount"));
		assertEquals(6, destinationContents());
		assertEquals("one map row per origin content", 6, contentMapRows());
		assertEquals(new HashSet<>(Arrays.asList(root, child, grandchild, sibling, other, otherChild)), destinationItemByOriginContent().keySet());
		assertEquals(destinationContentOf(other), destinationParentByOriginContent().get(otherChild));
	}

	@Test
	public void undoRemovesTheCreatedContentsIncludingTheOnesThatLeftTheSelection() throws Exception
	{
		String id = migrate(root, null, "4");
		fixture.updateHierarchicalParent(origin, sibling, other);
		Map<String, Object> incremental = run(id, null);
		assertFinished(incremental);
		assertEquals("3", incremental.get("migratedCount"));

		Map<String, Object> undone = undo(id);

		assertFinished(undone);
		assertEquals("0", undone.get("migratedCount"));
		assertEquals(0, contentMapRows());
		assertEquals("the copy of the content that left the selection is removed too", 0, destinationContents());
	}

	@Test
	public void recreatingADeletedPairCreatesNewCopiesAndItsUndoRemovesOnlyThem() throws Exception
	{
		String first = migrate(root, null, "4");
		Set<String> oldCopies = destinationContentIds();
		unmappedCopyItemIds.addAll(mappedCopyItemIds());
		ModuleBridge.api("delete", first);
		migrationIds.remove(first);
		assertEquals("deleting keeps the copies", 4, destinationContents());
		assertEquals("deleting removes the maps of the pair", 0, contentMapRows());

		String second = migrate(root, null, "4");

		assertEquals("the new migration does not know the old copies", 8, destinationContents());
		assertEquals(Collections.singleton(2), new HashSet<>(destinationContentsByTitle().values()));
		Set<String> newCopies = new HashSet<>(destinationContentByOriginContent().values());
		assertEquals(4, newCopies.size());
		assertTrue("new copies are distinct from the old ones", Collections.disjoint(oldCopies, newCopies));
		assertEquals(destinationContentOf(root), destinationParentByOriginContent().get(child));

		assertFinished(undo(second));

		assertEquals("undo removes only the copies of the new migration", oldCopies, destinationContentIds());
	}

	@Test
	public void contentMovedOutOfTheSelectionStaysAtTheDestinationUnchanged() throws Exception
	{
		String id = migrate(root, null, "4");
		String siblingCopy = destinationContentOf(sibling);
		String siblingItem = destinationItemByOriginContent().get(sibling);
		fixture.updateHierarchicalParent(origin, sibling, other);

		Map<String, Object> updated = run(id, "UPDATE");

		assertFinished(updated);
		assertEquals("3", updated.get("totalCount"));
		assertEquals("3", updated.get("migratedCount"));
		assertEquals("the copy remains", 4, destinationContents());
		assertEquals(siblingCopy, destinationContentOf(sibling));
		assertEquals("the copy is not written again", siblingItem, destinationItemByOriginContent().get(sibling));
		assertEquals("the copy keeps its old parent", destinationContentOf(root), destinationParentByOriginContent().get(sibling));
		assertEquals("", warningsOf(sibling));
	}

	@Test
	public void newContentUnderAParentMigratedOutsideTheSelectionHangsUnderItsCopy() throws Exception
	{
		String id = migrate(root, null, "4");
		String newcomer = fixture.addHierarchicalContent(origin, "newcomer", child, true);
		ModuleBridge.api("updateSelection", Map.of("migrationId", id, "selectedContentIds", newcomer));

		Map<String, Object> finished = run(id, null);

		assertFinished(finished);
		assertEquals("1", finished.get("totalCount"));
		assertEquals("1", finished.get("migratedCount"));
		assertNotNull(destinationContentOf(child));
		assertEquals(destinationContentOf(child), destinationParentByOriginContent().get(newcomer));
		assertEquals("a parent migrated earlier needs no warning", "", warningsOf(newcomer));
		assertEquals(5, destinationContents());
	}

	/** Creates and runs a migration of the given selection, checking the migrated count. */
	private String migrate(String selectedContentIds, String updateMode, String expectedCount) throws Exception
	{
		String id = create(selectedContentIds);
		Map<String, Object> finished = run(id, updateMode);
		assertFinished(finished);
		assertEquals(expectedCount, finished.get("totalCount"));
		assertEquals(expectedCount, finished.get("migratedCount"));
		return id;
	}

	private String create(String selectedContentIds) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-lifecycle-migration");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", selectedContentIds);
		String id = (String) castMap(ModuleBridge.api("create", params)).get("id");
		migrationIds.add(id);
		return id;
	}

	private Map<String, Object> run(String migrationId, String updateMode) throws Exception
	{
		start(migrationId, updateMode);
		return await(migrationId);
	}

	private void start(String migrationId, String updateMode) throws Exception
	{
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		if (updateMode != null)
		{
			start.put("updateMode", updateMode);
		}
		ModuleBridge.api("start", start);
	}

	private Map<String, Object> undo(String migrationId) throws Exception
	{
		ModuleBridge.api("undo", Map.of("migrationId", migrationId, "userId", UserConfig.USER_ADMINISTRATOR_ID));
		return await(migrationId);
	}

	private Map<String, Object> await(String migrationId) throws Exception
	{
		Map<String, Object> result = castMap(ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS));
		assertEquals("the run did not end in " + AWAIT_MILLIS + " ms", Boolean.TRUE, result.get("terminated"));
		return result;
	}

	private static void awaitMigratedCount(String migrationId, int minimum) throws Exception
	{
		long deadline = System.nanoTime() + Long.parseLong(AWAIT_MILLIS) * 1_000_000L;
		while (true)
		{
			Map<String, Object> current = castMap(ModuleBridge.api("get", migrationId));
			String migrated = (String) current.get("migratedCount");
			if (migrated != null && Integer.parseInt(migrated) >= minimum)
			{
				return;
			}
			if (!"RUNNING".equals(current.get("status")) || System.nanoTime() >= deadline)
			{
				fail("expected " + minimum + " migrated items while running, got " + current);
			}
			Thread.sleep(100L);
		}
	}

	private static void assertFinished(Map<String, Object> migration)
	{
		assertEquals("Migration error: " + migration.get("errorMessage"), "FINISHED", migration.get("status"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value)
	{
		return (Map<String, Object>) value;
	}

	private int destinationContents() throws Exception
	{
		return destinationContentIds().size();
	}

	private Set<String> destinationContentIds() throws Exception
	{
		return new HashSet<>(queryColumn("select id from lum_Content where serviceInstanceId = ?", destination.getId()));
	}

	private int destinationVersions() throws Exception
	{
		// @formatter:off
		String sql = "select cv.id "
				+ "from lum_ContentVersion cv "
				+ "inner join lum_ContentLocale cl on cl.id = cv.contentLocaleId "
				+ "inner join lum_Content c on c.id = cl.contentId "
				+ "where c.serviceInstanceId = ?";
		// @formatter:on
		return queryColumn(sql, destination.getId()).size();
	}

	/** Counts the destination contents of each title, read from their active versions. */
	private Map<String, Integer> destinationContentsByTitle() throws Exception
	{
		// @formatter:off
		String sql = "select distinct c.id, h.title "
				+ "from lum_Content c "
				+ "inner join lum_ContentLocale cl on cl.contentId = c.id "
				+ "inner join lum_ContentVersion cv on cv.contentLocaleId = cl.id "
				+ "inner join lum_HierarchicalContent h on h.id = cv.itemId "
				+ "where c.serviceInstanceId = ?";
		// @formatter:on
		Map<String, Set<String>> contentsByTitle = new HashMap<>();
		for (String[] row : queryPairs(sql, destination.getId()))
		{
			contentsByTitle.computeIfAbsent(row[1], key -> new HashSet<>()).add(row[0]);
		}
		Map<String, Integer> counts = new HashMap<>();
		contentsByTitle.forEach((title, contents) -> counts.put(title, contents.size()));
		return counts;
	}

	private int contentMapRows() throws Exception
	{
		return queryColumn("select id from lum_cmig_HMigItemMap where originServiceInstanceId = ? and kind = 'CONTENT'", origin.getId()).size();
	}

	private Map<String, String> destinationItemByOriginContent() throws Exception
	{
		return toMap(queryPairs("select originContentId, destItemId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and kind = 'CONTENT'", origin.getId()));
	}

	private Map<String, String> destinationContentByOriginContent() throws Exception
	{
		return toMap(queryPairs(
				"select originContentId, destContentId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and kind = 'CONTENT'",
				origin.getId()));
	}

	private String destinationContentOf(String originContentId) throws Exception
	{
		return destinationContentByOriginContent().get(originContentId);
	}

	/** Returns the warnings of the map row of the origin content, empty when it has none. */
	private String warningsOf(String originContentId) throws Exception
	{
		Map<String, String> warnings = toMap(
				queryPairs("select originContentId, warnings from lum_cmig_HMigItemMap where originServiceInstanceId = ? and kind = 'CONTENT'", origin.getId()));
		assertTrue("no map row for " + originContentId, warnings.containsKey(originContentId));
		String value = warnings.get(originContentId);
		return value == null ? "" : value;
	}

	/** Maps each migrated origin content to the parent content id of its copy. */
	private Map<String, String> destinationParentByOriginContent() throws Exception
	{
		// @formatter:off
		String sql = "select m.originContentId, h.parentContentId "
				+ "from lum_cmig_HMigItemMap m "
				+ "inner join lum_HierarchicalContent h on h.id = m.destItemId "
				+ "where m.originServiceInstanceId = ? and m.kind = 'CONTENT'";
		// @formatter:on
		return toMap(queryPairs(sql, origin.getId()));
	}

	private List<String> mappedCopyItemIds() throws Exception
	{
		return queryColumn("select destItemId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destItemId is not null", origin.getId());
	}

	private void deleteCopies(List<String> itemIds) throws Exception
	{
		for (String itemId : itemIds)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				try
				{
					ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, destination, transaction));
					source.deleteDataByItemIds(Collections.singletonList(itemId));
				}
				catch (PortalObjectNotFoundException e)
				{
					// already removed
				}
				return null;
			});
		}
	}

	private static Map<String, String> toMap(List<String[]> pairs)
	{
		Map<String, String> map = new HashMap<>();
		for (String[] pair : pairs)
		{
			map.put(pair[0], pair[1]);
		}
		return map;
	}

	private static List<String> queryColumn(String sql, String parameter) throws Exception
	{
		List<String> values = new ArrayList<>();
		for (String[] row : queryPairs(sql, parameter))
		{
			values.add(row[0]);
		}
		return values;
	}

	/** Runs a query with one string parameter and returns its first one or two columns per row. */
	private static List<String[]> queryPairs(String sql, String parameter) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<String[]> rows = new ArrayList<>();
			try (PreparedStatement statement = connection(transaction).prepareStatement(sql))
			{
				statement.setString(1, parameter);
				try (ResultSet resultSet = statement.executeQuery())
				{
					boolean hasSecondColumn = resultSet.getMetaData().getColumnCount() > 1;
					while (resultSet.next())
					{
						rows.add(new String[] { resultSet.getString(1), hasSecondColumn ? resultSet.getString(2) : null });
					}
				}
			}
			return rows;
		});
	}

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}
}
