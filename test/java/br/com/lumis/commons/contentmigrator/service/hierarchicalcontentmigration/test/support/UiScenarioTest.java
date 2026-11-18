package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.user.UserConfig;

/** Persistent, opt-in fixture shared by the HTTP browser and a later cleanup JVM. */
public class UiScenarioTest
{
	private static final Path IDS = Path.of("target/ui-ids.json");

	@Test
	public void manageScenario() throws Exception
	{
		String action = System.getProperty("contentmigrator.uiScenario");
		Assume.assumeTrue("UI scenario is opt-in", action != null);
		assertTrue("Expected create or remove", "create".equals(action) || "remove".equals(action));
		if ("create".equals(action))
		{
			create();
		}
		else
		{
			remove();
		}
	}

	private static void save(JSONObject ids) throws Exception
	{
		Files.writeString(IDS, ids.toString(2), StandardCharsets.UTF_8);
	}

	private static void create() throws Exception
	{
		assertFalse("Remove the previous UI scenario first", Files.exists(IDS));
		Files.createDirectories(IDS.getParent());
		Files.createFile(IDS, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		JSONObject ids = new JSONObject().put("instances", new JSONArray());
		save(ids);
		PortalFixture fixture = new PortalFixture();
		ChannelConfig channel = fixture.createChannel();
		ids.put("channelId", channel.getId());
		save(ids);
		System.out.println("PROGRESS UI scenario channel created");
		ServiceInstanceConfig origin = addInstance(fixture, channel, PortalFixture.HIERARCHICAL_SERVICE_ID, ids, "originId");
		addInstance(fixture, channel, PortalFixture.HIERARCHICAL_SERVICE_ID, ids, "destinationId");
		addInstance(fixture, channel, "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration", ids, "migrationInstanceId");
		ids.put("rootId", fixture.addHierarchicalContent(origin, "ui-root", null, false));
		save(ids);
		ids.put("childId", fixture.addHierarchicalContent(origin, "ui-child", ids.getString("rootId"), false));
		save(ids);
		ids.put("grandchildId", fixture.addHierarchicalContent(origin, "ui-grandchild", ids.getString("childId"), false));
		save(ids);
		var migration = (java.util.Map<?, ?>) ModuleBridge.api("create", java.util.Map.of(
				"name", "contentmigrator-test-ui-selection", "originServiceInstanceId", origin.getId(),
				"destServiceInstanceId", ids.getString("destinationId"), "selectedContentIds", ids.getString("childId")));
		ids.put("migrationId", migration.get("id"));
		save(ids);
		// Selecting the child without its root produces a real missing-parent warning.
		ModuleBridge.api("start", java.util.Map.of("migrationId", ids.getString("migrationId"), "userId", UserConfig.USER_ADMINISTRATOR_ID));
		org.junit.Assert.assertEquals("FINISHED", ((java.util.Map<?, ?>) ModuleBridge.api("awaitTermination", ids.getString("migrationId"), "120000")).get("status"));
		createViolationPair(fixture, channel, ids);
		String login = "contentmigrator-test-ui-" + UUID.randomUUID().toString().substring(0, 12);
		String password = "contentmigrator#9" + UUID.randomUUID() + "Q!";
		UserConfig user = new UserConfig();
		user.setLogin(login);
		user.setFirstName(login);
		user.setEmail(login + "@example.com");
		user.setPassword(password);
		String userId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
		ids.put("userId", userId).put("userLogin", login).put("userPassword", password);
		save(ids);
		System.out.println("PROGRESS UI scenario ready: target/ui-ids.json (private)");
	}

	/**
	 * Creates an origin and destination pair that breaks two creation rules at once: the origin uses a
	 * media repository the destination lacks and the two use different e-mail template repositories.
	 * Instance names carry accents and an ampersand to prove the displayed text is escaped and decoded.
	 *
	 * @param fixture
	 *            the fixture that owns the created portal objects.
	 * @param channel
	 *            the channel that receives the instances.
	 * @param ids
	 *            the persisted ids, completed with the pair, its selection and its displayed names.
	 * @throws Exception
	 *             if any portal object cannot be created.
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void createViolationPair(PortalFixture fixture, ChannelConfig channel, JSONObject ids) throws Exception
	{
		ServiceInstanceConfig media = addInstance(fixture, channel, PortalFixture.MEDIA_SERVICE_ID, ids, "violationMediaId");
		ServiceInstanceConfig originEmail = addInstance(fixture, channel, "lumis.service.emailtemplaterepository", ids, "violationOriginEmailId");
		ServiceInstanceConfig destinationEmail = addInstance(fixture, channel, "lumis.service.emailtemplaterepository", ids, "violationDestinationEmailId");
		ServiceInstanceConfig origin = addInstance(fixture, channel, PortalFixture.HIERARCHICAL_SERVICE_ID, ids, "violationOriginId");
		ServiceInstanceConfig destination = addInstance(fixture, channel, PortalFixture.HIERARCHICAL_SERVICE_ID, ids, "violationDestinationId");
		String originName = "contentmigrator-test-ui-Origem Ação & Cia";
		String destinationName = "contentmigrator-test-ui-Destino Área & Cia";
		PortalFixture.inTransaction((session, transaction) ->
		{
			origin.setName(originName);
			ManagerFactory.getServiceInstanceManager().update(session, origin, transaction);
			destination.setName(destinationName);
			ManagerFactory.getServiceInstanceManager().update(session, destination, transaction);
			return null;
		});
		ids.put("violationOriginName", originName).put("violationDestinationName", destinationName);
		fixture.setDependency(media, origin, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(destination.getId());
			removal.setDependencyType(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
		fixture.setDependency(originEmail, origin, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_EMAIL_TEMPLATE_REPOSITORY);
		fixture.setDependency(destinationEmail, destination, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_EMAIL_TEMPLATE_REPOSITORY);
		ids.put("violationRootId", fixture.addHierarchicalContent(origin, "ui-violation-root", null, true));
		save(ids);
	}

	private static ServiceInstanceConfig addInstance(PortalFixture fixture, ChannelConfig channel, String service,
			JSONObject ids, String key) throws Exception
	{
		ServiceInstanceConfig instance = fixture.createServiceInstance(channel, service);
		ids.getJSONArray("instances").put(instance.getId());
		ids.put(key, instance.getId());
		save(ids);
		return instance;
	}

	/**
	 * Deletes the persisted UI fixture, tolerating already deleted portal objects.
	 * @throws Exception if fixture deletion fails
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void remove() throws Exception
	{
		if (!Files.exists(IDS))
		{
			System.out.println("PROGRESS UI scenario already removed");
			return;
		}
		JSONObject ids = new JSONObject(Files.readString(IDS, StandardCharsets.UTF_8));
		if (ids.has("migrationId"))
		{
			ModuleBridge.api("undo", java.util.Map.of("migrationId", ids.getString("migrationId"), "userId", UserConfig.USER_ADMINISTRATOR_ID));
			ModuleBridge.api("awaitTermination", ids.getString("migrationId"), "120000");
			ModuleBridge.api("delete", ids.getString("migrationId"));
		}
		JSONArray instances = ids.getJSONArray("instances");
		for (int index = instances.length() - 1; index >= 0; index--)
		{
			String id = instances.getString(index);
			deleteIfPresent(() -> PortalFixture.inTransaction((session, transaction) ->
			{
				ManagerFactory.getServiceInstanceManager().delete(session, id, true, transaction);
				return null;
			}));
		}
		if (ids.has("channelId"))
		{
			deleteIfPresent(() -> PortalFixture.inTransaction((session, transaction) ->
			{
				ManagerFactory.getChannelManager().delete(session, ids.getString("channelId"), transaction);
				return null;
			}));
		}
		if (ids.has("userId"))
		{
			deleteIfPresent(() -> PortalFixture.inTransaction((session, transaction) ->
			{
				ManagerFactory.getUserManager().delete(session, ids.getString("userId"), transaction);
				return null;
			}));
		}
		new NoLeftoverDataTest().noTestRecordsOrOrphanSelectionsRemain();
		Files.delete(IDS);
		System.out.println("PROGRESS UI scenario removed; no contentmigrator-test-% records remain");
	}

	@FunctionalInterface
	private interface Deletion
	{
		void run() throws Exception;
	}

	private static void deleteIfPresent(Deletion deletion) throws Exception
	{
		try
		{
			deletion.run();
		}
		catch (PortalObjectNotFoundException e)
		{
			// A previous cleanup attempt may already have committed this deletion.
		}
	}
}
