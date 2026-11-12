package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * Migration of the contents the user marked: the marked contents and all of their descendants are
 * copied, the rest of the instance is not, and the selection is stored without redundant marks.
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
public class SelectionMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private PortalFixture fixture;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig destination;

	private final List<String> migrationIds = new ArrayList<>();

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
		for (String id : migrationIds)
		{
			ModuleBridge.api("stop", id);
			ModuleBridge.api("awaitTermination", id, AWAIT_MILLIS);
		}
		if (!PortalFixture.keepTestData())
		{
			deleteMigratedCopies();
			for (String id : migrationIds)
			{
				ModuleBridge.api("delete", id);
			}
			PortalFixture.awaitGlobalLockFree();
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void createStoresOnlyTheMarksNoAncestorCoversAndCountsTheDescendants() throws Exception
	{
		Map<String, Object> created = create(String.join(",", grandchild, root, otherChild, sibling, child));

		assertEquals("root, child, grandchild, sibling and otherChild", "5", created.get("totalCount"));
		assertEquals(new HashSet<>(Arrays.asList(root, otherChild)), new HashSet<>(storedMarks((String) created.get("id"))));
	}

	@Test
	public void startCountsTheItemsOfTheSelectionAgain() throws Exception
	{
		Map<String, Object> created = create(child);
		assertEquals("child and grandchild", "2", created.get("totalCount"));
		fixture.addHierarchicalContent(origin, "late child", child, true);

		start((String) created.get("id"));
		Map<String, Object> finished = await((String) created.get("id"));

		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
		assertEquals("a content added under a marked one is part of the next run", "3", finished.get("totalCount"));
		assertEquals("3", finished.get("migratedCount"));
	}

	@Test
	public void migratesTheMarkedContentWithItsDescendantsAndNothingElse() throws Exception
	{
		Map<String, Object> created = create(child);
		start((String) created.get("id"));
		Map<String, Object> finished = await((String) created.get("id"));

		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
		Set<String> migrated = migratedOriginContents();
		assertEquals(new HashSet<>(Arrays.asList(child, grandchild)), migrated);
		assertFalse(migrated.contains(root));
		assertFalse(migrated.contains(sibling));
		assertFalse(migrated.contains(other));
		assertFalse(migrated.contains(otherChild));

		Map<String, String> destinationParents = destinationParentByOriginContent();
		assertNull("the marked content's parent was not migrated, so the copy has none", destinationParents.get(child));
		assertEquals("the descendant hangs under the copy of its parent", destinationContentOf(child), destinationParents.get(grandchild));
	}

	@Test
	public void aMarkedAncestorMigratesAllLevelsParentsFirst() throws Exception
	{
		Map<String, Object> created = create(root);
		start((String) created.get("id"));
		Map<String, Object> finished = await((String) created.get("id"));

		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
		assertEquals(new HashSet<>(Arrays.asList(root, child, grandchild, sibling)), migratedOriginContents());
		Map<String, String> parents = destinationParentByOriginContent();
		assertNull(parents.get(root));
		assertEquals(destinationContentOf(root), parents.get(child));
		assertEquals(destinationContentOf(child), parents.get(grandchild));
		assertEquals(destinationContentOf(root), parents.get(sibling));
	}

	@Test
	public void creationRequiresAnExistingSelectionInTheOrigin() throws Exception
	{
		assertRejected("NO_SELECTION", " , ");
		assertRejected("INVALID_SELECTION", root + ",00000000000000000000000000000000");
		String contentOfDestination = fixture.addHierarchicalContent(destination, "foreign", null, true);
		assertRejected("INVALID_SELECTION", contentOfDestination);
		assertEquals("a rejected selection stores nothing", 0, countMigrations());
	}

	@Test
	public void deletingTheMigrationDeletesItsSelection() throws Exception
	{
		Map<String, Object> created = create(root);
		String id = (String) created.get("id");
		assertFalse(storedMarks(id).isEmpty());

		ModuleBridge.api("delete", id);
		migrationIds.remove(id);

		assertEquals(Collections.emptyList(), storedMarks(id));
	}

	@Test
	public void childCreatedBeforeParentWithoutDefaultLocaleIsCopiedAfterParent() throws Exception
	{
		assertLateParentOrder(false);
	}

	@Test
	public void childCreatedBeforeParentWithDefaultLocaleIsCopiedAfterParent() throws Exception
	{
		assertLateParentOrder(true);
	}

	private void assertLateParentOrder(boolean defaultLocale) throws Exception
	{
		String earlyChild = fixture.addHierarchicalContent(origin, "early child", null, true);
		fixture.addHierarchicalTranslation(origin, earlyChild);
		String lateParent = fixture.addHierarchicalContent(origin, "late parent", null, true);
		fixture.updateHierarchicalParent(origin, earlyChild, lateParent);
		// Both locales are older than their parent; the reference locale still has to lead.
		fixture.forceHierarchicalParent(earlyChild, lateParent);
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("update lum_Content set defaultLocale = ? where serviceInstanceId = ?"))
			{
				statement.setString(1, defaultLocale ? "pt_BR" : null);
				statement.setString(2, origin.getId());
				statement.executeUpdate();
			}
			return null;
		});
		String id = (String) create(lateParent).get("id");
		start(id);
		Map<String, Object> finished = await(id);
		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
		assertEquals("3", finished.get("totalCount"));
		assertEquals("3", finished.get("migratedCount"));
		assertEquals(destinationContentOf(lateParent), destinationParentByOriginContent().get(earlyChild));
		assertEquals(0, warningCount());
	}

	@Test
	public void cyclicContentsRemainDiscoverableAndFinishWithAWarning() throws Exception
	{
		fixture.forceHierarchicalParent(root, child);
		String id = (String) create(root + "," + child).get("id");
		start(id);
		Map<String, Object> finished = await(id);
		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
		assertEquals(new HashSet<>(Arrays.asList(root, child, grandchild, sibling)), migratedOriginContents());
		assertTrue("a missing cyclic parent must be visible in the item map", warningCount() > 0);
		fixture.forceHierarchicalParent(root, null);
	}

	@Test
	public void migratedCountExcludesContentsThatLeftTheMarkedSubtree() throws Exception
	{
		String id = (String) create(root).get("id");
		start(id);
		assertEquals("4", await(id).get("migratedCount"));
		fixture.updateHierarchicalParent(origin, child, other);
		start(id);
		Map<String, Object> finished = await(id);
		assertEquals("FINISHED", finished.get("status"));
		assertEquals("2", finished.get("totalCount"));
		assertEquals("2", finished.get("migratedCount"));
		assertEquals("old destination maps remain for undo", 4, migratedOriginContents().size());
	}

	@Test
	public void startRejectsASelectionWhoseClosureBecameEmpty() throws Exception
	{
		String id = (String) create(root).get("id");
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("update lum_cmig_HMigSelection set contentId = ? where migrationId = ?"))
			{
				statement.setString(1, "00000000000000000000000000000000");
				statement.setString(2, id);
				statement.executeUpdate();
			}
			return null;
		});
		try
		{
			start(id);
			fail("expected NO_SELECTION");
		}
		catch (RuntimeException e)
		{
			assertEquals("NO_SELECTION", ModuleBridge.errorCode(e));
		}
		assertEquals("STOPPED", castMap(ModuleBridge.api("get", id)).get("status"));
		PortalFixture.awaitGlobalLockFree();
	}

	private int warningCount() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select count(*) from lum_cmig_HMigItemMap where originServiceInstanceId = ? and kind = 'CONTENT' and warnings is not null and warnings <> ''"))
			{
				statement.setString(1, origin.getId());
				try (ResultSet resultSet = statement.executeQuery())
				{
					resultSet.next();
					return resultSet.getInt(1);
				}
			}
		});
	}

	private Map<String, Object> create(String selectedContentIds) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-selection-migration");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", selectedContentIds);
		Map<String, Object> result = castMap(ModuleBridge.api("create", params));
		migrationIds.add((String) result.get("id"));
		return result;
	}

	private void assertRejected(String code, String selectedContentIds) throws Exception
	{
		try
		{
			create(selectedContentIds);
			fail("expected " + code);
		}
		catch (RuntimeException e)
		{
			assertEquals(e.toString(), code, ModuleBridge.errorCode(e));
		}
	}

	private void start(String migrationId) throws Exception
	{
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		ModuleBridge.api("start", start);
	}

	private Map<String, Object> await(String migrationId) throws Exception
	{
		return castMap(ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Object value)
	{
		return (Map<String, Object>) value;
	}

	private List<String> storedMarks(String migrationId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<String> ids = new ArrayList<>();
			try (PreparedStatement statement = connection(transaction).prepareStatement("select contentId from lum_cmig_HMigSelection where migrationId = ?"))
			{
				statement.setString(1, migrationId);
				try (ResultSet resultSet = statement.executeQuery())
				{
					while (resultSet.next())
					{
						ids.add(resultSet.getString(1));
					}
				}
			}
			return ids;
		});
	}

	private int countMigrations() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("select count(*) from lum_cmig_HMigration where originServiceInstanceId = ?"))
			{
				statement.setString(1, origin.getId());
				try (ResultSet resultSet = statement.executeQuery())
				{
					resultSet.next();
					return resultSet.getInt(1);
				}
			}
		});
	}

	private Set<String> migratedOriginContents() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Set<String> ids = new HashSet<>();
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("select originContentId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and kind = 'CONTENT'"))
			{
				statement.setString(1, origin.getId());
				try (ResultSet resultSet = statement.executeQuery())
				{
					while (resultSet.next())
					{
						ids.add(resultSet.getString(1));
					}
				}
			}
			return ids;
		});
	}

	private String destinationContentOf(String originContentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"select destContentId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and originContentId = ? and kind = 'CONTENT'"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, originContentId);
				try (ResultSet resultSet = statement.executeQuery())
				{
					return resultSet.next() ? resultSet.getString(1) : null;
				}
			}
		});
	}

	/** Maps each migrated origin content to the parent content id of its copy. */
	private Map<String, String> destinationParentByOriginContent() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Map<String, String> parents = new HashMap<>();
			// @formatter:off
			String sql = "select m.originContentId, h.parentContentId "
					+ "from lum_cmig_HMigItemMap m "
					+ "inner join lum_HierarchicalContent h on h.id = m.destItemId "
					+ "where m.originServiceInstanceId = ? and m.kind = 'CONTENT'";
			// @formatter:on
			try (PreparedStatement statement = connection(transaction).prepareStatement(sql))
			{
				statement.setString(1, origin.getId());
				try (ResultSet resultSet = statement.executeQuery())
				{
					while (resultSet.next())
					{
						parents.put(resultSet.getString(1), resultSet.getString(2));
					}
				}
			}
			return parents;
		});
	}

	private void deleteMigratedCopies() throws Exception
	{
		List<String> itemIds = PortalFixture.inTransaction((session, transaction) ->
		{
			List<String> ids = new ArrayList<>();
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("select destItemId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and destItemId is not null"))
			{
				statement.setString(1, origin.getId());
				try (ResultSet resultSet = statement.executeQuery())
				{
					while (resultSet.next())
					{
						ids.add(resultSet.getString(1));
					}
				}
			}
			return ids;
		});
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

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}
}
