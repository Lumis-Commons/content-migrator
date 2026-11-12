package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.WidgetFixture;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.doui.source.field.type.HtmlDataTypeUtil.HtmlDataToRenderArea;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ContentMetadataFixture;
import lumis.content.workflow.WorkflowController;
import lumis.portal.authentication.SessionConfig;
import lumis.util.security.acl.AccessControlEntry;
import lumis.util.security.acl.Permissions;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;
import lumis.service.pagepersonalization.bean.HtmlFieldWidgetInstance;
import lumis.service.pagepersonalization.bean.WidgetInstance;

/** Integration coverage for widget mapping blocks and HTML embed rewrites. */
public class WidgetWorkflowMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final String SOURCE_ID = "default";

	private static final String CUSTOM_PROPERTY = "contentmigrator.migration.widget.embed";

	private static final Pattern EMBED = Pattern.compile("<div[^>]+?id=\"lum-html-widget-embedded-(\\w{32})[^>]++><\\/div>");

	private PortalFixture portal;

	private WidgetFixture widgets;
	private final ContentMetadataFixture metadata = new ContentMetadataFixture();

	private ChannelConfig channel;

	private ServiceInstanceConfig originRepository;

	private ServiceInstanceConfig destinationRepository;

	private String runAsUser = UserConfig.USER_ADMINISTRATOR_ID;

	private ServiceInstanceConfig originContent;

	private ServiceInstanceConfig destinationContent;

	private String originWidget;

	private String destinationWidget;

	private final List<String> migrationIds = new ArrayList<>();

	private final Map<String, String> migrationByPair = new HashMap<>();

	private final Map<String, String> originHtmlByItem = new LinkedHashMap<>();

	private final List<String> originEmbedIds = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		portal = new PortalFixture();
		widgets = new WidgetFixture();
		channel = portal.createChannel();
		PortalFixture.awaitGlobalLockFree();
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
			trackDestinationContents();
			deleteMigrationData();
		}
		cleanup.run(widgets::cleanup, portal::cleanup, metadata::cleanup);
		cleanup.assertNoFailures();
	}

	@Test
	public void blocksWithoutMappingThenResumesAndRewritesEveryEmbed() throws Exception
	{
		createInstancesAndSourceContent();
		Set<String> originalInstances = new HashSet<>(originEmbedIds);

		Map<String, Object> blocked = runMigration();
		assertEquals("STOPPED_WITH_ERROR", blocked.get("status"));
		assertEquals("WIDGET_MAPPING_REQUIRED", blocked.get("errorType"));
		assertTrue("the blocked item belongs to the selected hierarchy", originHtmlByItem.containsKey(blocked.get("blockingOriginItemId")));
		assertEquals(originWidget, blocked.get("blockingWidgetContentId"));
		assertEquals("the precheck did not create destination news", 0, contentCount(destinationContent.getId()));
		assertEquals("the precheck did not leave destination widget instances", 0, instanceIdsOf(destinationWidget).size());
		assertOriginUnchanged(originalInstances);

		PortalFixture.inTransaction((session, transaction) ->
		{
			String query = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.query.WidgetMappingQueries";
			Map<?, ?> blockedContent = (Map<?, ?>) ModuleBridge.invokeStatic(query, "getBlockedMigration", session, migrationIds.get(0), transaction);
			assertEquals(originWidget, blockedContent.get("originWidgetContentId"));
			assertEquals(destinationRepository.getId(), blockedContent.get("destRepoInstanceId"));
			List<?> options = (List<?>) ModuleBridge.invokeStatic(query, "listDestinationWidgets", session, migrationIds.get(0), transaction);
			assertEquals("only the linked repository widget is offered", 1, options.size());
			assertEquals(destinationWidget, ((Map<?, ?>) options.get(0)).get("widgetContentId"));
			return null;
		});
		ModuleBridge.invokeStatic("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.processaction.WidgetMappingProcessActionHandler",
				"saveMapping", migrationIds.get(0), destinationWidget);

		Map<String, Object> resumed = runMigration();
		assertEquals("Migration error: " + resumed.get("errorMessage"), "FINISHED", resumed.get("status"));
		Map<String, String> destinationItems = destinationItemsByOrigin();
		assertEquals("both contents with the same origin widget were copied", 2, destinationItems.size());

		Set<String> destinationInstanceIds = new HashSet<>();
		for (Map.Entry<String, String> migrated : destinationItems.entrySet())
		{
			String originItemId = migrated.getKey();
			String destinationItemId = migrated.getValue();
			widgets.track(destinationContent.getId(), SOURCE_ID, destinationItemId);
			String sourceHtml = originHtmlByItem.get(originItemId);
			String destinationHtml = readHtml(destinationItemId);
			List<HtmlDataToRenderArea> sourceAreas = HtmlDataTypeUtil.parseContent(sourceHtml);
			List<HtmlDataToRenderArea> destinationAreas = HtmlDataTypeUtil.parseContent(destinationHtml);
			assertEquals("all HTML areas were preserved", sourceAreas.size(), destinationAreas.size());
			for (int i = 0; i < sourceAreas.size(); i++)
			{
				assertEquals(sourceAreas.get(i).getTargetRenderAreaName(), destinationAreas.get(i).getTargetRenderAreaName());
				assertEquals(
						"text and markup around embeds were preserved",
						EMBED.matcher(sourceAreas.get(i).getHtmlContent()).replaceAll("[widget]"),
						EMBED.matcher(destinationAreas.get(i).getHtmlContent()).replaceAll("[widget]"));
			}
			List<String[]> embeds = embeds(destinationHtml);
			List<String[]> sourceEmbeds = embeds(sourceHtml);
			assertEquals(originItemId.equals(originHtmlByItem.keySet().iterator().next()) ? 2 : 1, embeds.size());
			assertEquals(sourceEmbeds.size(), embeds.size());
			assertEquals("one interface-instance attribute remains per embed", embeds.size(), countOccurrences(destinationHtml, "data-widget-interfaceinstanceid="));
			PortalFixture.inTransaction((session, transaction) ->
			{
				for (int i = 0; i < embeds.size(); i++)
				{
					String[] embed = embeds.get(i);
					assertFalse("source embed id is not retained", originalInstances.contains(embed[0]));
					HtmlFieldWidgetInstance instance = WidgetFixture.getInstance(embed[0]);
					assertNotNull("rewritten destination instance exists", instance);
					assertEquals(destinationWidget, instance.getWidgetContentId());
					assertEquals(embed[1], instance.getInterfaceInstanceId());
					assertTrue("native html hook linked the instance to the copied item", instance.getFieldItemIds().contains(destinationItemId));
					if (originItemId.equals(originHtmlByItem.keySet().iterator().next()) && i == 0)
					{
						assertEquals(
								"embed custom properties were copied",
								"copied property",
								ManagerFactory.getServiceInterfaceInstanceManager().getCustomProperties(session, instance.getInterfaceInstanceId(), transaction)
										.get(CUSTOM_PROPERTY));
					}
					destinationInstanceIds.add(instance.getId());
				}
				return null;
			});
		}
		assertEquals("each embed has a new instance", 3, destinationInstanceIds.size());
		assertEquals("the destination widget has no orphaned instances", destinationInstanceIds, instanceIdsOf(destinationWidget));
		assertOriginUnchanged(originalInstances);
		String parentItem = originHtmlByItem.keySet().iterator().next();
		String childItem = new ArrayList<>(originHtmlByItem.keySet()).get(1);
		assertEquals(contentId(destinationItems.get(parentItem)), PortalFixture.inTransaction((session, transaction) ->
				WidgetFixture.readColumn(transaction, "lum_HierarchicalContent", "parentContentId", "id", destinationItems.get(childItem))));
		assertEquals("rerun finishes with the saved widget mapping", "FINISHED", runMigration().get("status"));
		Map<String, String> rerunItems = destinationItemsByOrigin();
		assertEquals(destinationItems.keySet(), rerunItems.keySet());
		for (String origin : destinationItems.keySet())
		{
			assertEquals("rerun reuses content mappings", contentId(destinationItems.get(origin)), contentId(rerunItems.get(origin)));
		}
		Set<String> activeEmbeds = new HashSet<>();
		for (String origin : rerunItems.keySet())
		{
			for (String[] embed : embeds(readHtml(activeItemOf(rerunItems.get(origin)))))
			{
				activeEmbeds.add(embed[0]);
			}
		}
		assertEquals("rerun active versions embed one instance per embed", 3, activeEmbeds.size());
		assertTrue("embedded instances exist", instanceIdsOf(destinationWidget).containsAll(activeEmbeds));
	}

	/**
	 * With a shared repository the handler keeps the embeds and {@code HtmlDataType#preAdd} clones
	 * their instances natively; the embed of an instance removed after the content was saved, which
	 * the native hook would reject, is removed with a warning, as with different repositories.
	 */
	@Test
	public void removesOrphanEmbedsAndKeepsTheOthersWhenTheRepositoryIsShared() throws Exception
	{
		ServiceInstanceConfig html = portal.createServiceInstance(channel, WidgetFixture.HTML_SERVICE_ID);
		originRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		destinationRepository = originRepository;
		originContent = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destinationContent = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		portal.setDependency(originRepository, originContent, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		portal.setDependency(originRepository, destinationContent, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		originWidget = widgets.addWidget(originRepository, "shared repository widget", widgets.addBaseInterfaceInstance(html));
		HtmlFieldWidgetInstance kept = widgets.addEditorInstance(originWidget);
		HtmlFieldWidgetInstance orphan = widgets.addEditorInstance(originWidget);
		String keptEmbed = WidgetFixture.embed(kept.getId(), kept.getInterfaceInstanceId());
		String sourceHtml = "<p>before " + keptEmbed + " between " + WidgetFixture.embed(orphan.getId(), orphan.getInterfaceInstanceId()) + " after</p>";
		String originItemId = widgets.addContent(originContent, SOURCE_ID, hierarchical("shared repository content", sourceHtml));
		// as PagePersonalizationUtil#removeDeletedWidgets does when another version stops embedding it
		PortalFixture.inTransaction((session, transaction) ->
		{
			WidgetFixture.getInstance(orphan.getId()).remove();
			return null;
		});

		Map<String, Object> run = runMigration();
		assertEquals("Migration error: " + run.get("errorMessage"), "FINISHED", run.get("status"));
		String destinationItemId = destinationItemsByOrigin().get(originItemId);
		assertNotNull("the content was copied", destinationItemId);
		widgets.track(destinationContent.getId(), SOURCE_ID, destinationItemId);
		String destinationHtml = readHtml(destinationItemId);
		assertEquals("only the orphan embed was removed", "<p>before [widget] between  after</p>", EMBED.matcher(destinationHtml).replaceAll("[widget]"));
		List<String[]> embeds = embeds(destinationHtml);
		assertEquals(1, embeds.size());
		assertNotEquals("the native hook cloned the kept instance", kept.getId(), embeds.get(0)[0]);
		PortalFixture.inTransaction((session, transaction) ->
		{
			HtmlFieldWidgetInstance clone = WidgetFixture.getInstance(embeds.get(0)[0]);
			assertNotNull("the cloned instance exists", clone);
			assertEquals("the clone uses the shared widget", originWidget, clone.getWidgetContentId());
			assertEquals(embeds.get(0)[1], clone.getInterfaceInstanceId());
			assertTrue("the native hook linked the clone to the copied item", clone.getFieldItemIds().contains(destinationItemId));
			assertTrue("the kept instance stays linked to the origin item", WidgetFixture.getInstance(kept.getId()).getFieldItemIds().contains(originItemId));
			return null;
		});
		assertEquals("the origin is unchanged", sourceHtml, readHtml(originItemId));
		assertTrue("the removal is a warning of the item", contentWarnings(originItemId).contains("HTML widget instance " + orphan.getId() + " no longer exists"));
	}

	@Test
	public void mappingRequiresOriginViewAndDestinationEdit() throws Exception
	{
		createInstancesAndSourceContent();
		assertEquals("STOPPED_WITH_ERROR", runMigration().get("status"));
		String user = metadata.createUser("widget-permissions");
		Map<String, Object> mapping = Map.of("migrationId", migrationIds.get(0), "originWidgetContentId", originWidget,
				"destRepoInstanceId", destinationRepository.getId(), "destWidgetContentId", destinationWidget);
		setPermission(destinationContent, user, "EDIT_SERVICE_INSTANCE_CONTENT", true);
		setPermission(originContent, user, "VIEW_SERVICE_INSTANCE", false);
		setPermission(destinationRepository, user, "EDIT_SERVICE_INSTANCE_CONTENT", true);
		assertMappingForbidden(user, mapping);
		setPermission(originContent, user, "VIEW_SERVICE_INSTANCE", true);
		setPermission(destinationRepository, user, "EDIT_SERVICE_INSTANCE_CONTENT", false);
		assertMappingForbidden(user, mapping);
		setPermission(destinationRepository, user, "EDIT_SERVICE_INSTANCE_CONTENT", true);
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(user);
		try
		{
			ModuleBridge.api("saveWidgetMapping", mapping);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
		assertEquals(destinationWidget, savedMapping());
		assertEquals("FINISHED", runMigration().get("status"));
	}

	private void assertMappingForbidden(String user, Map<String, Object> mapping) throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(user);
		try
		{
			try
			{
				ModuleBridge.api("saveWidgetMapping", mapping);
				fail("mapping must require both permissions");
			}
			catch (RuntimeException exception)
			{
				assertEquals("FORBIDDEN", ModuleBridge.errorCode(exception));
			}
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
		assertEquals("denied mapping does not write", null, savedMapping());
	}

	private String savedMapping() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> (String) ModuleBridge.invokeStatic(
				ModuleBridge.WIDGET_MAP_DAO, "getWidgetMapping", ((ITransactionJdbc) transaction).getConnection(), originWidget, destinationRepository.getId()));
	}

	private static void setPermission(ServiceInstanceConfig instance, String user, String permission, boolean allow) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			var manager = ManagerFactory.getServiceInstanceAclManager();
			var acl = manager.get(session, instance.getId(), transaction);
			int bit = manager.getPermissions(session, instance.getServiceId(), transaction).valueOf(permission).value();
			List<AccessControlEntry> entries = new ArrayList<>(Arrays.asList(acl.getLocalEntries()));
			entries.removeIf(entry -> user.equals(entry.getPrincipalId()));
			entries.add(new AccessControlEntry(user, new Permissions(allow ? bit : 0, allow ? 0 : bit, 0, 0), false));
			acl.setLocalEntries(entries.toArray(new AccessControlEntry[0]));
			manager.update(session, instance.getId(), acl, transaction);
			return null;
		});
	}

	@Test
	public void preservesPublishedAndActiveWorkflowAcrossHierarchyAndRerun() throws Exception
	{
		originContent = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destinationContent = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		String editor = metadata.createUser("hierarchy-editor");
		runAsUser = metadata.createUser("hierarchy-author");
		setPermission(originContent, runAsUser, "VIEW_SERVICE_INSTANCE", true);
		setPermission(destinationContent, runAsUser, "EDIT_SERVICE_INSTANCE_CONTENT", true);
		Map<String, String> roles = Map.of(runAsUser, ContentMetadataFixture.ROLE_AUTHOR, editor, ContentMetadataFixture.ROLE_EDITOR);
		ContentMetadataFixture.setRoles(originContent, roles);
		ContentMetadataFixture.setRoles(destinationContent, roles);
		Map<String, Object> parentData = hierarchical("published parent", "published body");
		parentData.put(WorkflowController.TRANSITION_PARAMETER_NAME, ContentMetadataFixture.TRANSITION_APPROVE);
		parentData.put(WorkflowController.ASSIGNED_TO_PARAMETER_NAME, editor);
		parentData.put("comments", "editorial note");
		String published = widgets.addContent(originContent, SOURCE_ID, parentData);
		String parentContentId = contentId(published);
		widgets.addHierarchicalContent(originContent, "authoring child", "child body", parentContentId);
		PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(WidgetFixture.LOCALE);
			Map<String, Object> data = new HashMap<>();
			data.put("id", published);
			data.put("title", "reviewing parent");
			data.put("linkContent", "active body");
			data.put(WorkflowController.TRANSITION_PARAMETER_NAME, ContentMetadataFixture.TRANSITION_REVIEW);
			data.put(WorkflowController.ASSIGNED_TO_PARAMETER_NAME, editor);
			ContentMetadataFixture.newsSource(session, transaction, originContent.getId()).updateData(data);
			return null;
		});
		for (int run = 0; run < 2; run++)
		{
			Map<String, Object> result = runMigration();
			assertEquals("Migration error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
			Map<String, String> items = destinationItemsByOrigin();
			assertEquals(2, items.size());
			PortalFixture.inTransaction((session, transaction) ->
			{
				for (Map.Entry<String, String> item : items.entrySet())
				{
					var from = ContentMetadataFixture.contentLocale(item.getKey());
					var to = ContentMetadataFixture.contentLocale(item.getValue());
					var fromWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, from);
					var toWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, to);
					assertNotNull(fromWorkflow);
					assertNotNull(toWorkflow);
					assertEquals(fromWorkflow.getStateId(), toWorkflow.getStateId());
					assertEquals(fromWorkflow.getAssignedTo(), toWorkflow.getAssignedTo());
					assertEquals(from.getCreatedBy(), to.getCreatedBy());
					assertEquals(from.getCreatedDateTime(), to.getCreatedDateTime());
					assertEquals(from.getComments().size(), to.getComments().size());
					boolean isParent = from.getContent().getId().equals(parentContentId);
					assertEquals(isParent ? ContentMetadataFixture.STATE_REVIEWING : ContentMetadataFixture.STATE_AUTHORING, toWorkflow.getStateId());
					if (isParent)
					{
						assertEquals(editor, toWorkflow.getAssignedTo());
						assertNotNull(to.getPublishedVersion());
						assertNotEquals(to.getPublishedVersion(), to.getActiveVersion());
						String publishedItemId = to.getPublishedVersion().getItemId();
						String activeItemId = to.getActiveVersion().getItemId();
						assertEquals("published body", WidgetFixture.readColumn(transaction, "lum_HierarchicalContent", "linkContent", "id", publishedItemId));
						assertEquals("active body", WidgetFixture.readColumn(transaction, "lum_HierarchicalContent", "linkContent", "id", activeItemId));
						assertEquals(1, to.getComments().size());
						assertEquals("editorial note", to.getComments().get(0).getCommentText());
					}
				}
				return null;
			});
		}
	}

	private void createInstancesAndSourceContent() throws Exception
	{
		ServiceInstanceConfig html = portal.createServiceInstance(channel, WidgetFixture.HTML_SERVICE_ID);
		originRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		destinationRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		originContent = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destinationContent = portal.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		portal.setDependency(originRepository, originContent, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		portal.setDependency(destinationRepository, destinationContent, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		originWidget = widgets.addWidget(originRepository, "migration source widget", widgets.addBaseInterfaceInstance(html));
		destinationWidget = widgets.addWidget(destinationRepository, "migration destination widget", widgets.addBaseInterfaceInstance(html));

		HtmlFieldWidgetInstance first = widgets.addEditorInstance(originWidget);
		HtmlFieldWidgetInstance second = widgets.addEditorInstance(originWidget);
		HtmlFieldWidgetInstance third = widgets.addEditorInstance(originWidget);
		originEmbedIds.addAll(Arrays.asList(first.getId(), second.getId(), third.getId()));
		PortalFixture.inTransaction((session, transaction) ->
		{
			ManagerFactory.getServiceInterfaceInstanceManager()
					.setCustomProperties(session, first.getInterfaceInstanceId(), Collections.singletonMap(CUSTOM_PROPERTY, "copied property"), transaction);
			return null;
		});

		String firstEmbed = WidgetFixture.embed(first.getId(), first.getInterfaceInstanceId())
				.replace(
						"data-widget-interfaceinstanceid=\"" + first.getInterfaceInstanceId() + "\"",
						"data-widget-interfaceinstanceid='" + first.getInterfaceInstanceId() + "'");
		String firstHtml = HtmlDataTypeUtil.buildContentValue(
				Arrays.asList(
						new HtmlDataToRenderArea("main", "before " + firstEmbed + " after"),
						new HtmlDataToRenderArea("sidebar", WidgetFixture.embed(second.getId(), second.getInterfaceInstanceId()))));
		String secondHtml = HtmlDataTypeUtil.buildContentValue(
				Collections.singletonList(new HtmlDataToRenderArea("main", "second content " + WidgetFixture.embed(third.getId(), third.getInterfaceInstanceId()))));
		String parentItem = widgets.addHierarchicalContent(originContent, "first widget content", firstHtml, null);
		originHtmlByItem.put(parentItem, firstHtml);
		originHtmlByItem.put(widgets.addHierarchicalContent(originContent, "second widget content", secondHtml, contentId(parentItem)), secondHtml);
	}

	private Map<String, Object> runMigration() throws Exception
	{
		String key = originContent.getId() + ":" + destinationContent.getId();
		String migrationId = migrationByPair.get(key);
		if (migrationId == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-widget-migration");
			create.put("originServiceInstanceId", originContent.getId());
			create.put("destServiceInstanceId", destinationContent.getId());
			create.put("selectedContentIds", selectedRootContentId());
			create.put("updateMode", "UPDATE");
			Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
			migrationId = (String) migration.get("id");
			migrationIds.add(migrationId);
			migrationByPair.put(key, migrationId);
		}
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", runAsUser);
		ModuleBridge.api("start", start);
		return (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
	}

	private void assertOriginUnchanged(Set<String> expectedInstanceIds) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Set<String> actual = new HashSet<>();
			for (String itemId : originHtmlByItem.keySet())
			{
				assertEquals(originHtmlByItem.get(itemId), WidgetFixture.readColumn(transaction, "lum_HierarchicalContent", "linkContent", "id", itemId));
			}
			for (String id : originEmbedIds)
			{
				HtmlFieldWidgetInstance instance = WidgetFixture.getInstance(id);
				assertNotNull("source instance remains", instance);
				assertEquals(originWidget, instance.getWidgetContentId());
				assertTrue("source instance stays linked to a source item", instance.getFieldItemIds().stream().anyMatch(originHtmlByItem::containsKey));
				actual.add(instance.getId());
			}
			assertEquals(expectedInstanceIds, actual);
			assertNotNull("source widget remains", WidgetFixture.getWidget(originWidget));
			return null;
		});
	}

	private Map<String, String> destinationItemsByOrigin() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Map<String, String> result = new LinkedHashMap<>();
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
					"select originItemId, destItemId from lum_cmig_HMigItemMap where kind = 'CONTENT' "
							+ "and originServiceInstanceId = ? and destServiceInstanceId = ?"))
			{
				statement.setString(1, originContent.getId());
				statement.setString(2, destinationContent.getId());
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						result.put(rs.getString(1), rs.getString(2));
					}
				}
			}
			return result;
		});
	}

	private String contentWarnings(String originItemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("select warnings from lum_cmig_HMigItemMap where kind = 'CONTENT' and originItemId = ?"))
			{
				statement.setString(1, originItemId);
				try (ResultSet rs = statement.executeQuery())
				{
					assertTrue("content map exists", rs.next());
					return String.valueOf(rs.getString(1));
				}
			}
		});
	}

	private static String activeItemOf(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
				WidgetFixture.getContentVersion(itemId).getContentLocale().getActiveVersion().getItemId());
	}

	private String readHtml(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> WidgetFixture.readColumn(transaction, "lum_HierarchicalContent", "linkContent", "id", itemId));
	}

	private int contentCount(String serviceInstanceId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("select count(*) from lum_Content where serviceInstanceId = ? and sourceId = ?"))
			{
				statement.setString(1, serviceInstanceId);
				statement.setString(2, SOURCE_ID);
				try (ResultSet rs = statement.executeQuery())
				{
					rs.next();
					return rs.getInt(1);
				}
			}
		});
	}

	private static List<String[]> embeds(String html)
	{
		List<String[]> result = new ArrayList<>();
		for (HtmlDataToRenderArea area : HtmlDataTypeUtil.parseContent(html))
		{
			Matcher matcher = EMBED.matcher(area.getHtmlContent());
			while (matcher.find())
			{
				Matcher interfaceInstance = Pattern.compile("data-widget-interfaceinstanceid=\"([^\"]*)\"").matcher(matcher.group());
				result.add(new String[]{matcher.group(1), interfaceInstance.find() ? interfaceInstance.group(1) : null});
			}
		}
		return result;
	}

	private static int countOccurrences(String text, String value)
	{
		int count = 0;
		int index = 0;
		while ((index = text.indexOf(value, index)) >= 0)
		{
			count++;
			index += value.length();
		}
		return count;
	}

	private static Set<String> instanceIdsOf(String widgetContentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Set<String> result = new HashSet<>();
			for (WidgetInstance instance : WidgetInstance.findByWidgetContentId(widgetContentId))
			{
				result.add(instance.getId());
			}
			return result;
		});
	}

	private void trackDestinationContents() throws Exception
	{
		if (destinationContent == null || originContent == null)
		{
			return;
		}
		for (String itemId : destinationItemsByOrigin().values())
		{
			widgets.track(destinationContent.getId(), SOURCE_ID, itemId);
		}
	}

	private void deleteMigrationData() throws Exception
	{
		for (String id : migrationIds)
		{
			ModuleBridge.api("undo", Map.of("migrationId", id));
			Map<?, ?> result = (Map<?, ?>) ModuleBridge.api("awaitTermination", id, AWAIT_MILLIS);
			assertEquals("Undo error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
			ModuleBridge.api("delete", id);
		}
		if (originWidget != null)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
						"delete from lum_cmig_HMigWidgetMap where originWidgetContentId = ? and destRepoInstanceId = ?"))
				{
					statement.setString(1, originWidget);
					statement.setString(2, destinationRepository.getId());
					statement.executeUpdate();
				}
				return null;
			});
		}
	}

	private String selectedRootContentId() throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(
					"select cl.contentId from lum_ContentLocale cl inner join lum_Content c on c.id = cl.contentId "
					+ "inner join lum_ContentVersion cv on cv.id = cl.activeVersionId "
					+ "inner join lum_HierarchicalContent h on h.id = cv.itemId "
					+ "where c.serviceInstanceId = ? and h.parentContentId is null"))
			{
				statement.setString(1, originContent.getId());
				try (ResultSet rows = statement.executeQuery())
				{
					assertTrue("one selected root", rows.next());
					String id = rows.getString(1);
					assertFalse("only one root", rows.next());
					return id;
				}
			}
		});
	}

	private static String contentId(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> WidgetFixture.getContentVersion(itemId).getContentLocale().getContent().getId());
	}

	private static Map<String, Object> hierarchical(String title, String content)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", "contentmigrator widget " + title);
		data.put("linkContent", content);
		data.put("type", 0);
		data.put("position", 0);
		data.put("hidden", false);
		return data;
	}

}
