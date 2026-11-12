package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
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
import lumis.portal.manager.ManagerFactory;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/**
 * Editing selection preserves pair maps, serializes with execution and keeps undo complete.
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
public class EditSelectionTest
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
	public void addsANewSubtreeWithoutDuplicatingExistingCopies() throws Exception
	{
		String id = (String) create(root).get("id");
		start(id);
		assertEquals("FINISHED", await(id).get("status"));
		String originalCopy = destinationContentOf(root);
		Map<String, Object> edited = edit(id, root + "," + child + "," + other);
		assertEquals("6", edited.get("totalCount"));
		assertEquals(new HashSet<>(Arrays.asList(root, other)), new HashSet<>(storedMarks(id)));
		start(id);
		assertEquals("6", await(id).get("migratedCount"));
		assertEquals(originalCopy, destinationContentOf(root));
		assertEquals(6, migratedOriginContents().size());
		assertEquals("no duplicate destination contents", 6, destinationContents());
	}

	@Test
	public void runningAndOrdinaryUsersCannotEditAndInvalidInputRollsBack() throws Exception
	{
		String id = (String) create(root).get("id");
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("update lum_cmig_HMigration set status = 'RUNNING' where id = ?"))
			{
				statement.setString(1, id);
				statement.executeUpdate();
			}
			return null;
		});
		assertEditRejected("LOCKED", id, other);
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("update lum_cmig_HMigration set status = 'STOPPED' where id = ?"))
			{
				statement.setString(1, id);
				statement.executeUpdate();
			}
			return null;
		});
		UserConfig ordinary = new UserConfig();
		String login = "contentmigrator-edit-" + PortalUtil.generateNewGuid().substring(0, 12).toLowerCase();
		ordinary.setLogin(login);
		ordinary.setFirstName(login);
		ordinary.setEmail(login + "@example.com");
		ordinary.setPassword("contentmigrator#9" + PortalUtil.generateNewGuid() + "Q!");
		String userId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, ordinary, transaction));
		var ordinarySession = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			assertEditRejected("FORBIDDEN", id, other);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(ordinarySession);
			PortalFixture.inTransaction((session, transaction) ->
			{
				ManagerFactory.getUserManager().delete(session, userId, transaction);
				return null;
			});
		}

		assertEditRejected("NO_SELECTION", id, "");
		assertEditRejected("INVALID_SELECTION", id, other + ",00000000000000000000000000000000");
		assertEditRejected("SELECTION_TOO_LARGE", id, String.join(",", Collections.nCopies(2001, root)));
		assertEquals(Collections.singletonList(root), storedMarks(id));
		assertEquals("4", castMap(ModuleBridge.api("get", id)).get("totalCount"));
	}

	@Test
	public void unmarkedContentsStayUnchangedUntilUndo() throws Exception
	{
		String id = (String) create(root + "," + other).get("id");
		start(id);
		assertEquals("FINISHED", await(id).get("status"));
		String copy = destinationContentOf(other);
		fixture.updateHierarchicalParent(origin, other, root);
		// Select only the child branch. The old root/other copies must survive unchanged.
		edit(id, child);
		Map<String, Object> update = new HashMap<>();
		update.put("migrationId", id);
		update.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		update.put("updateMode", "UPDATE");
		ModuleBridge.api("start", update);
		Map<String, Object> finished = await(id);
		assertEquals("FINISHED", finished.get("status"));
		assertEquals("2", finished.get("migratedCount"));
		assertEquals(copy, destinationContentOf(other));
		assertNull("unmarked copy must not get its new origin parent", destinationParentByOriginContent().get(other));
		ModuleBridge.api("undo", Map.of("migrationId", id, "userId", UserConfig.USER_ADMINISTRATOR_ID));
		assertEquals("FINISHED", await(id).get("status"));
		assertTrue("undo removes old and current copies", migratedOriginContents().isEmpty());
		assertEquals("undo removes the contents too", 0, destinationContents());
	}

	@Test
	public void concurrentStartAndEditKeepTheCommittedSelectionAndFinalTotalConsistent() throws Exception
	{
		String id = (String) create(root).get("id");
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		var gate = new java.util.concurrent.CountDownLatch(1);
		try
		{
			var editing = pool.submit(() ->
			{
				gate.await();
				try
				{
					edit(id, other);
					return true;
				}
				catch (RuntimeException exception)
				{
					assertEquals("LOCKED", ModuleBridge.errorCode(exception));
					return false;
				}
			});
			var starting = pool.submit(() ->
			{
				gate.await();
				start(id);
				return true;
			});
			gate.countDown();
			boolean changed = editing.get(30, java.util.concurrent.TimeUnit.SECONDS);
			starting.get(30, java.util.concurrent.TimeUnit.SECONDS);
			Map<String, Object> finished = await(id);
			assertEquals("FINISHED", finished.get("status"));
			assertEquals(changed ? Collections.singletonList(other) : Collections.singletonList(root), storedMarks(id));
			assertEquals(changed ? "2" : "4", finished.get("totalCount"));
			assertEquals(finished.get("totalCount"), finished.get("migratedCount"));
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	private int destinationContents() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement("select count(*) from lum_Content where serviceInstanceId = ?"))
			{
				statement.setString(1, destination.getId());
				try (ResultSet resultSet = statement.executeQuery())
				{
					resultSet.next();
					return resultSet.getInt(1);
				}
			}
		});
	}

	private Map<String, Object> edit(String id, String ids) throws Exception
	{
		return castMap(ModuleBridge.api("updateSelection", Map.of("migrationId", id, "selectedContentIds", ids)));
	}

	private void assertEditRejected(String code, String id, String ids) throws Exception
	{
		try
		{
			edit(id, ids);
			fail("expected " + code);
		}
		catch (RuntimeException exception)
		{
			assertEquals(code, ModuleBridge.errorCode(exception));
		}
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
			try (PreparedStatement statement = connection(transaction)
					.prepareStatement("select destContentId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and originContentId = ? and kind = 'CONTENT'"))
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
