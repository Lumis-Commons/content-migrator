package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentComment;
import lumis.content.core.ContentLocale;
import lumis.content.table.ContentTableSource;
import lumis.content.workflow.ContentWorkflowMetaData;
import lumis.content.workflow.WorkflowPermission;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.acl.IServiceInstanceAclManager;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.util.security.acl.AccessControlEntry;
import lumis.util.security.acl.AccessControlList;
import lumis.util.security.acl.IPermission;
import lumis.util.security.acl.Permissions;

/** Integration coverage for workflow, published/active versions, comments and authorship cloning. */
public class WorkflowMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	/** Content service whose {@code category} source declares no workflow, unlike its {@code default} source. */
	private static final String LINK_SERVICE_ID = "lumis.service.link";

	private static final String LINK_CATEGORY_SOURCE_ID = "category";

	private PortalFixture portal;

	private ContentMetadataFixture content;

	private ChannelConfig channel;

	private final List<ServiceInstanceConfig[]> pairs = new ArrayList<>();

	private final List<String> migrationIds = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		content = new ContentMetadataFixture();
		channel = portal.createChannel();
		resetLock();
	}

	@After
	public void tearDown() throws Exception
	{
		TestCleanup cleanup = new TestCleanup();
		for (String migrationId : migrationIds)
		{
			ModuleBridge.api("stop", migrationId);
			ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
		}
		if (!PortalFixture.keepTestData())
		{
			for (ServiceInstanceConfig[] pair : pairs)
			{
				for (Map<String, Object> row : mappings(pair[0].getId(), pair[1].getId()))
				{
					String itemId = (String) row.get("destitemid");
					if (itemId != null)
					{
						content.track(pair[1].getId(), itemId);
					}
				}
			}
			cleanup.run(content::cleanup);
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = ((ITransactionJdbc) transaction).getConnection();
				for (ServiceInstanceConfig[] pair : pairs)
				{
					try (PreparedStatement statement = connection
							.prepareStatement("delete from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ?"))
					{
						statement.setString(1, pair[0].getId());
						statement.setString(2, pair[1].getId());
						statement.executeUpdate();
					}
				}
				for (String migrationId : migrationIds)
				{
					try (PreparedStatement statement = connection.prepareStatement("delete from lum_cmig_Migration where id = ?"))
					{
						statement.setString(1, migrationId);
						statement.executeUpdate();
					}
				}
				try (PreparedStatement statement = connection.prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
				{
					statement.executeUpdate();
				}
				return null;
			});
		}
		cleanup.run(portal::cleanup);
		cleanup.assertNoFailures();
	}

	@Test
	public void copiesPublishedActiveWorkflowAuthorshipAndCommentsWithoutDuplicates() throws Exception
	{
		ServiceInstanceConfig[] pair = workflowPair();
		String authorId = content.createUser("author");
		String editorId = content.createUser("editor");
		grantViewInstance(pair[0], authorId);
		grantEditContent(pair[1], authorId);
		Map<String, String> roles = new HashMap<>();
		roles.put(authorId, ContentMetadataFixture.ROLE_AUTHOR);
		roles.put(editorId, ContentMetadataFixture.ROLE_EDITOR);
		ContentMetadataFixture.setRoles(pair[0], roles);
		ContentMetadataFixture.setRoles(pair[1], roles);
		assertTrue(
				"destination grants the assigned editor a workflow role: " + permissionSummary(pair[1], editorId),
				principalHasRole(pair[1], editorId, ContentMetadataFixture.ROLE_EDITOR));

		String publishedItemId = content.addNews(
				UserConfig.USER_SYSTEM_ID,
				pair[0],
				ContentMetadataFixture.news("published", "published intro", "published body"),
				ContentMetadataFixture.TRANSITION_APPROVE,
				editorId,
				"editorial note");
		String activeItemId = ContentMetadataFixture.updateNews(
				UserConfig.USER_SYSTEM_ID,
				pair[0],
				publishedItemId,
				ContentMetadataFixture.news("active", "active intro", "active body"),
				ContentMetadataFixture.TRANSITION_REVIEW,
				editorId);
		content.track(pair[0].getId(), activeItemId);
		assertEquals("origin workflow is assigned to the editor", editorId, workflowAssignedTo(activeItemId));

		Map<String, Object> firstRun = runMigration(pair, authorId, null);
		assertEquals("Migration error: " + firstRun.get("errorMessage"), "FINISHED", firstRun.get("status"));
		Map<String, Object> mapping = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String destActiveItemId = (String) mapping.get("destitemid");
		content.track(pair[1].getId(), destActiveItemId);
		assertClone(pair, publishedItemId, activeItemId, destActiveItemId, editorId);

		Map<String, Object> updateRun = runMigration(pair, authorId, "UPDATE");
		assertEquals("Migration error: " + updateRun.get("errorMessage"), "FINISHED", updateRun.get("status"));
		mapping = mappings(pair[0].getId(), pair[1].getId()).get(0);
		destActiveItemId = (String) mapping.get("destitemid");
		assertClone(pair, publishedItemId, activeItemId, destActiveItemId, editorId);
	}

	@Test
	public void nonAdminWithOnlyStartStateRoleCanUpdateRestrictedClone() throws Exception
	{
		ServiceInstanceConfig[] pair = workflowPair();
		String authorId = content.createUser("start-state-author");
		String editorId = content.createUser("assigned-editor");
		grantViewInstance(pair[0], authorId);
		grantEditContent(pair[1], authorId);
		Map<String, String> originRoles = new HashMap<>();
		originRoles.put(authorId, ContentMetadataFixture.ROLE_AUTHOR);
		originRoles.put(editorId, ContentMetadataFixture.ROLE_EDITOR);
		ContentMetadataFixture.setRoles(pair[0], originRoles);
		Map<String, String> destinationRoles = new HashMap<>();
		destinationRoles.put(authorId, ContentMetadataFixture.ROLE_AUTHOR);
		ContentMetadataFixture.setRoles(pair[1], destinationRoles);

		String originItemId = content.addNews(
				UserConfig.USER_SYSTEM_ID,
				pair[0],
				ContentMetadataFixture.news("approved", "approved intro", "approved body"),
				ContentMetadataFixture.TRANSITION_APPROVE,
				editorId,
				null);
		Map<String, Object> firstRun = runMigration(pair, authorId, null);
		assertEquals("Migration error: " + firstRun.get("errorMessage"), "FINISHED", firstRun.get("status"));
		Map<String, Object> mapping = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String destItemId = (String) mapping.get("destitemid");
		content.track(pair[0].getId(), originItemId);
		content.track(pair[1].getId(), destItemId);

		Map<String, Object> updateRun = runMigration(pair, authorId, "UPDATE");
		assertEquals("Update as a non-admin failed: " + updateRun.get("errorMessage"), "FINISHED", updateRun.get("status"));
		mapping = mappings(pair[0].getId(), pair[1].getId()).get(0);
		String updatedDestItemId = (String) mapping.get("destitemid");
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentMetadataFixture.contentLocale(originItemId);
			ContentLocale destLocale = ContentMetadataFixture.contentLocale(updatedDestItemId);
			ContentWorkflowMetaData originWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, originLocale);
			ContentWorkflowMetaData destWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, destLocale);
			assertEquals(ContentMetadataFixture.STATE_APPROVED, originWorkflow.getStateId());
			assertEquals(originWorkflow.getStateId(), destWorkflow.getStateId());
			return null;
		});
		assertNull("assigned principal without destination role is cleared", workflowAssignedTo(updatedDestItemId));
		assertTrue(String.valueOf(mapping.get("warnings")).contains("no workflow role"));
	}

	@Test
	public void sourceWithoutWorkflowInAWorkflowInstanceIsUpdatedAndUndone() throws Exception
	{
		ServiceInstanceConfig[] pair = {linkInstanceWithWorkflow(), linkInstanceWithWorkflow()};
		String originItemId = addLinkCategory(pair[0], "contentmigrator category");
		try
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				assertNotNull("the link instance has a workflow", ContentMetadataFixture.workflow(session, transaction, pair[0]));
				ContentLocale originLocale = ContentMetadataFixture.contentLocale(originItemId);
				assertNull(
						"the category source declares no workflow, so its content has no workflow row",
						ContentMetadataFixture.workflowMetaData(session, transaction, originLocale));
				return null;
			});
			Map<String, Object> firstRun = runMigration(pair, UserConfig.USER_ADMINISTRATOR_ID, null);
			assertEquals("Migration error: " + firstRun.get("errorMessage"), "FINISHED", firstRun.get("status"));
			Map<String, Object> updateRun = runMigration(pair, UserConfig.USER_ADMINISTRATOR_ID, "UPDATE");
			assertEquals("Update error: " + updateRun.get("errorMessage"), "FINISHED", updateRun.get("status"));
			String destItemId = (String) mappings(pair[0].getId(), pair[1].getId()).get(0).get("destitemid");

			Map<String, Object> undoRun = runUndo(pair, UserConfig.USER_ADMINISTRATOR_ID);
			assertEquals("Undo error: " + undoRun.get("errorMessage"), "FINISHED", undoRun.get("status"));
			assertTrue("the undo removes the map row", mappings(pair[0].getId(), pair[1].getId()).isEmpty());
			assertTrue("the undo deletes the destination category", PortalFixture.isNotFound((session, transaction) -> ContentMetadataFixture.contentLocale(destItemId)));
		}
		finally
		{
			deleteLinkCategories(pair, originItemId);
		}
	}

	private void assertClone(ServiceInstanceConfig[] pair, String originPublishedItemId, String originActiveItemId, String destActiveItemId, String assignedTo)
			throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentMetadataFixture.contentLocale(originActiveItemId);
			ContentLocale destLocale = ContentMetadataFixture.contentLocale(destActiveItemId);
			assertEquals(pair[1].getId(), destLocale.getContent().getServiceInstanceId());
			assertTrue("both copied versions are retained", destLocale.getVersions().size() >= 2);
			assertNotNull(destLocale.getPublishedVersion());
			assertNotEquals(destLocale.getPublishedVersion(), destLocale.getActiveVersion());
			assertEquals(destActiveItemId, destLocale.getActiveVersion().getItemId());
			assertEquals(
					ContentMetadataFixture.readNews(session, transaction, pair[0].getId(), originPublishedItemId),
					ContentMetadataFixture.readNews(session, transaction, pair[1].getId(), destLocale.getPublishedVersion().getItemId()));
			assertEquals(
					ContentMetadataFixture.readNews(session, transaction, pair[0].getId(), originActiveItemId),
					ContentMetadataFixture.readNews(session, transaction, pair[1].getId(), destActiveItemId));

			ContentWorkflowMetaData originWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, originLocale);
			ContentWorkflowMetaData destWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, destLocale);
			assertEquals(ContentMetadataFixture.STATE_REVIEWING, destWorkflow.getStateId());
			assertEquals(originWorkflow.getStateId(), destWorkflow.getStateId());
			assertEquals("origin assignment", assignedTo, originWorkflow.getAssignedTo());
			assertEquals(assignedTo, destWorkflow.getAssignedTo());
			assertEquals(originLocale.getCreatedBy(), destLocale.getCreatedBy());
			assertEquals(originLocale.getCreatedDateTime(), destLocale.getCreatedDateTime());
			assertEquals(originLocale.getPublishedVersion().getLastModifiedBy(), destLocale.getPublishedVersion().getLastModifiedBy());
			assertEquals(originLocale.getPublishedVersion().getLastModifiedDateTime(), destLocale.getPublishedVersion().getLastModifiedDateTime());
			assertEquals(originLocale.getActiveVersion().getLastModifiedBy(), destLocale.getActiveVersion().getLastModifiedBy());
			assertEquals(originLocale.getActiveVersion().getLastModifiedDateTime(), destLocale.getActiveVersion().getLastModifiedDateTime());
			assertEquals(1, originLocale.getComments().size());
			assertEquals("update did not duplicate the editorial comment", 1, destLocale.getComments().size());
			ContentComment originComment = originLocale.getComments().get(0);
			ContentComment destComment = destLocale.getComments().get(0);
			assertEquals(originComment.getCommentText(), destComment.getCommentText());
			assertEquals(originComment.getCreatedBy(), destComment.getCreatedBy());
			assertEquals(originComment.getCreatedDateTime(), destComment.getCreatedDateTime());
			return null;
		});
	}

	private ServiceInstanceConfig[] workflowPair() throws Exception
	{
		ServiceInstanceConfig origin = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		ServiceInstanceConfig destination = ContentMetadataFixture.createNewsInstanceWithWorkflow(portal, channel);
		ServiceInstanceConfig[] pair = {origin, destination};
		pairs.add(pair);
		return pair;
	}

	private ServiceInstanceConfig linkInstanceWithWorkflow() throws Exception
	{
		ServiceInstanceConfig instance = portal.createServiceInstance(channel, LINK_SERVICE_ID);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getWorkflowManager().setServiceInstanceWorkflow(session, instance.getId(), ContentMetadataFixture.STANDARD_WORKFLOW_ID, transaction);
			return null;
		});
		return instance;
	}

	private static String addLinkCategory(ServiceInstanceConfig instance, String name) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			Map<String, Object> data = new HashMap<>();
			data.put("name", name);
			return linkCategorySource(session, transaction, instance).addData(data).getItemId();
		});
	}

	private static ContentTableSource linkCategorySource(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance) throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource(LINK_CATEGORY_SOURCE_ID, new SourceContext(session, instance, transaction));
	}

	/** Deletes the categories of a link pair and its map rows; the tear down only knows news pairs. */
	private void deleteLinkCategories(ServiceInstanceConfig[] pair, String originItemId) throws Exception
	{
		if (PortalFixture.keepTestData())
		{
			return;
		}
		List<String[]> items = new ArrayList<>();
		items.add(new String[]{pair[0].getId(), originItemId});
		for (Map<String, Object> row : mappings(pair[0].getId(), pair[1].getId()))
		{
			items.add(new String[]{pair[1].getId(), (String) row.get("destitemid")});
		}
		for (String[] item : items)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				try
				{
					ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(item[0], transaction);
					linkCategorySource(session, transaction, instance).deleteDataByItemIds(Collections.singleton(item[1]));
				}
				catch (PortalObjectNotFoundException e)
				{
					// The category was already removed, by the undo or by the instance.
				}
				return null;
			});
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("delete from lum_cmig_MigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ?"))
			{
				statement.setString(1, pair[0].getId());
				statement.setString(2, pair[1].getId());
				statement.executeUpdate();
			}
			return null;
		});
	}

	private static void grantEditContent(ServiceInstanceConfig instance, String userId) throws Exception
	{
		grantServiceInstancePermission(instance, userId, ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT);
	}

	private static void grantViewInstance(ServiceInstanceConfig instance, String userId) throws Exception
	{
		grantServiceInstancePermission(instance, userId, ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE);
	}

	private static void grantServiceInstancePermission(ServiceInstanceConfig instance, String userId, String permissionId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			IServiceInstanceAclManager aclManager = ManagerFactory.getServiceInstanceAclManager();
			AccessControlList acl = aclManager.get(session, instance.getId(), transaction);
			ServiceInstancePermissions definitions = aclManager.getPermissions(session, instance.getServiceId(), transaction);
			IPermission permission = definitions.valueOf(permissionId);
			AccessControlEntry[] localEntries = acl.getLocalEntries();
			for (AccessControlEntry entry : localEntries)
			{
				if (userId.equals(entry.getPrincipalId()))
				{
					Permissions permissions = entry.getPermissions();
					permissions.setAllowPermissions(permissions.getAllowPermissions() | permission.value());
					aclManager.update(session, instance.getId(), acl, transaction);
					return null;
				}
			}
			AccessControlEntry[] entries = Arrays.copyOf(localEntries, localEntries.length + 1);
			entries[localEntries.length] = new AccessControlEntry(userId, new Permissions(permission.value(), 0, 0, 0), false);
			acl.setLocalEntries(entries);
			aclManager.update(session, instance.getId(), acl, transaction);
			return null;
		});
	}

	private static String workflowAssignedTo(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			ContentWorkflowMetaData metadata = ContentMetadataFixture.workflowMetaData(session, transaction, ContentMetadataFixture.contentLocale(itemId));
			return metadata.getAssignedTo();
		});
	}

	private static boolean principalHasRole(ServiceInstanceConfig instance, String principalId, String role) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Set<String> principalIds = ManagerFactory.getPrincipalManager().getPrincipals(session, principalId, transaction);
			Collection<WorkflowPermission> permissions = lumis.content.ContentManagerFactory.getWorkflowManager()
					.getWorkflowPermissions(session, instance.getId(), transaction);
			for (WorkflowPermission permission : permissions)
			{
				boolean isGrantedToPrincipal = principalIds.contains(permission.getPrincipalId()) && role.equals(permission.getRole());
				if (isGrantedToPrincipal && permission.getType() == WorkflowPermission.PERMISSION_TYPE_ALLOW)
				{
					return true;
				}
			}
			return false;
		});
	}

	private static String permissionSummary(ServiceInstanceConfig instance, String principalId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Set<String> principalIds = ManagerFactory.getPrincipalManager().getPrincipals(session, principalId, transaction);
			Collection<WorkflowPermission> permissions = lumis.content.ContentManagerFactory.getWorkflowManager()
					.getWorkflowPermissions(session, instance.getId(), transaction);
			StringBuilder summary = new StringBuilder("principalClosure=").append(principalIds).append("; grants=");
			for (WorkflowPermission permission : permissions)
			{
				summary.append('[').append(permission.getPrincipalId()).append(':').append(permission.getRole()).append(':').append(permission.getType()).append(']');
			}
			return summary.toString();
		});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runMigration(ServiceInstanceConfig[] pair, String userId, String updateMode) throws Exception
	{
		String key = pair[0].getId() + ":" + pair[1].getId();
		String migrationId = migrationByPair.get(key);
		if (migrationId == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-workflow-migration");
			create.put("originServiceInstanceId", pair[0].getId());
			create.put("destServiceInstanceId", pair[1].getId());
			Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
			migrationId = (String) migration.get("id");
			migrationIds.add(migrationId);
			migrationByPair.put(key, migrationId);
		}
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", userId);
		if (updateMode != null)
		{
			start.put("updateMode", updateMode);
		}
		ModuleBridge.api("start", start);
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> runUndo(ServiceInstanceConfig[] pair, String userId) throws Exception
	{
		String migrationId = migrationByPair.get(pair[0].getId() + ":" + pair[1].getId());
		Map<String, Object> undo = new HashMap<>();
		undo.put("migrationId", migrationId);
		undo.put("userId", userId);
		ModuleBridge.api("undo", undo);
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
	}

	private List<Map<String, Object>> mappings(String origin, String destination) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			List<Map<String, Object>> result = new ArrayList<>();
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
					"select destItemId, warnings from lum_cmig_MigItemMap " + "where originServiceInstanceId = ? and destServiceInstanceId = ? and kind = ?"))
			{
				statement.setString(1, origin);
				statement.setString(2, destination);
				statement.setString(3, "CONTENT");
				try (ResultSet rows = statement.executeQuery())
				{
					while (rows.next())
					{
						Map<String, Object> row = new HashMap<>();
						row.put("destitemid", rows.getString(1));
						row.put("warnings", rows.getString(2));
						result.add(row);
					}
				}
			}
			return result;
		});
	}

	private static void resetLock() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
			{
				statement.executeUpdate();
			}
			return null;
		});
	}
}
