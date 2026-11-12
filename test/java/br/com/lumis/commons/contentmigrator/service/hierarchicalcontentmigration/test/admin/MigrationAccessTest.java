package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;
import lumis.util.security.acl.AccessControlEntry;
import lumis.util.security.acl.AccessControlList;
import lumis.util.security.acl.Permissions;

/** Administrative operations require portal management even for instance operators. */
public class MigrationAccessTest
{
	private PortalFixture fixture;
	private String plainUserId;
	private String migrationId;
	private Map<String, Object> create;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		ServiceInstanceConfig origin = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig destination = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.HIERARCHICAL_SERVICE_ID);
		String content = fixture.addHierarchicalContent(origin, "access root", null, true);
		create = Map.of("name", "access test", "originServiceInstanceId", origin.getId(),
				"destServiceInstanceId", destination.getId(), "selectedContentIds", content);
		UserConfig user = new UserConfig();
		String guid = PortalUtil.generateNewGuid().toLowerCase();
		String login = "contentmigrator-access-" + guid.substring(guid.length() - 12);
		user.setLogin(login);
		user.setFirstName(login);
		user.setEmail(login + "@example.com");
		user.setPassword("contentmigrator#9" + guid + "Q!");
		plainUserId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
		for (ServiceInstanceConfig instance : List.of(origin, destination))
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				var manager = ManagerFactory.getServiceInstanceAclManager();
				AccessControlList acl = manager.get(session, instance.getId(), transaction);
				var permissions = manager.getPermissions(session, instance.getServiceId(), transaction);
				int allowed = permissions.valueOf("VIEW_SERVICE_INSTANCE").value() | permissions.valueOf("EDIT_SERVICE_INSTANCE_CONTENT").value();
				List<AccessControlEntry> entries = new ArrayList<>(Arrays.asList(acl.getEntries()));
				entries.add(new AccessControlEntry(plainUserId, new Permissions(allowed, 0, 0, 0), false));
				manager.update(session, instance.getId(), new AccessControlList(acl.getId(), entries.toArray(new AccessControlEntry[0]),
						acl.isInheriting(), acl.getParentId()), transaction);
				return null;
			});
		}
	}

	@After
	public void tearDown() throws Exception
	{
		if (!PortalFixture.keepTestData())
		{
			if (migrationId != null)
			{
				ModuleBridge.api("awaitTermination", migrationId, "30000");
				ModuleBridge.api("delete", migrationId);
			}
			if (plainUserId != null)
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					ManagerFactory.getUserManager().delete(session, plainUserId, transaction);
					return null;
				});
			}
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void operatorCannotCreateDeleteOrUndoButCanStartAndStop() throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(plainUserId);
		try
		{
			forbidden("create", create);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
		migrationId = (String) ((Map<?, ?>) ModuleBridge.api("create", create)).get("id");
		session = ManagerFactory.getAuthenticationManager().impersonate(plainUserId);
		try
		{
			forbidden("delete", migrationId);
			forbidden("undo", Map.of("migrationId", migrationId));
			assertEquals(migrationId, ((Map<?, ?>) ModuleBridge.api("start", Map.of("migrationId", migrationId))).get("id"));
			assertEquals(migrationId, ((Map<?, ?>) ModuleBridge.api("stop", migrationId)).get("id"));
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
		ModuleBridge.api("awaitTermination", migrationId, "30000");
		ModuleBridge.api("undo", Map.of("migrationId", migrationId));
		ModuleBridge.api("awaitTermination", migrationId, "30000");
	}

	private static void forbidden(String method, Object argument) throws Exception
	{
		try
		{
			ModuleBridge.api(method, argument);
			fail(method + " should be forbidden");
		}
		catch (RuntimeException exception)
		{
			assertEquals("FORBIDDEN", ModuleBridge.errorCode(exception));
		}
	}
}
