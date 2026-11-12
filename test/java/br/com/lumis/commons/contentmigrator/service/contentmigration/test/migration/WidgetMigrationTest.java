package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.WidgetFixture;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.doui.source.field.type.HtmlDataTypeUtil.HtmlDataToRenderArea;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;
import lumis.service.pagepersonalization.bean.HtmlFieldWidgetInstance;
import lumis.service.pagepersonalization.bean.WidgetInstance;

/** Integration coverage for widget mapping blocks and HTML embed rewrites. */
public class WidgetMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private static final String NEWS_SOURCE_ID = "default";

	private static final String CUSTOM_PROPERTY = "contentmigrator.migration.widget.embed";

	private static final Pattern EMBED = Pattern.compile("<div[^>]+?id=\"lum-html-widget-embedded-(\\w{32})[^>]++><\\/div>");

	private PortalFixture portal;

	private WidgetFixture widgets;

	private ChannelConfig channel;

	private ServiceInstanceConfig originRepository;

	private ServiceInstanceConfig destinationRepository;

	private ServiceInstanceConfig originNews;

	private ServiceInstanceConfig destinationNews;

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
			trackDestinationContents();
			deleteMigrationData();
		}
		cleanup.run(widgets::cleanup, portal::cleanup);
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
		assertEquals("the first source item is recorded as blocked", originHtmlByItem.keySet().iterator().next(), blocked.get("blockingOriginItemId"));
		assertEquals(originWidget, blocked.get("blockingWidgetContentId"));
		assertEquals("the precheck did not create destination news", 0, contentCount(destinationNews.getId()));
		assertEquals("the precheck did not leave destination widget instances", 0, instanceIdsOf(destinationWidget).size());
		assertOriginUnchanged(originalInstances);

		Map<String, Object> mapping = new HashMap<>();
		mapping.put("originWidgetContentId", originWidget);
		mapping.put("destRepoInstanceId", destinationRepository.getId());
		mapping.put("destWidgetContentId", destinationWidget);
		ModuleBridge.api("saveWidgetMapping", mapping);

		Map<String, Object> resumed = runMigration();
		assertEquals("Migration error: " + resumed.get("errorMessage"), "FINISHED", resumed.get("status"));
		Map<String, String> destinationItems = destinationItemsByOrigin();
		assertEquals("both contents with the same origin widget were copied", 2, destinationItems.size());

		Set<String> destinationInstanceIds = new HashSet<>();
		for (Map.Entry<String, String> migrated : destinationItems.entrySet())
		{
			String originItemId = migrated.getKey();
			String destinationItemId = migrated.getValue();
			widgets.track(destinationNews.getId(), NEWS_SOURCE_ID, destinationItemId);
			String sourceHtml = originHtmlByItem.get(originItemId);
			String destinationHtml = readNewsHtml(destinationItemId);
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
		originNews = portal.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		destinationNews = portal.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		portal.setDependency(originRepository, originNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		portal.setDependency(originRepository, destinationNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		originWidget = widgets.addWidget(originRepository, "shared repository widget", widgets.addBaseInterfaceInstance(html));
		HtmlFieldWidgetInstance kept = widgets.addEditorInstance(originWidget);
		HtmlFieldWidgetInstance orphan = widgets.addEditorInstance(originWidget);
		String keptEmbed = WidgetFixture.embed(kept.getId(), kept.getInterfaceInstanceId());
		String sourceHtml = "<p>before " + keptEmbed + " between " + WidgetFixture.embed(orphan.getId(), orphan.getInterfaceInstanceId()) + " after</p>";
		String originItemId = widgets.addContent(originNews, NEWS_SOURCE_ID, news("shared repository content", sourceHtml));
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
		widgets.track(destinationNews.getId(), NEWS_SOURCE_ID, destinationItemId);
		String destinationHtml = readNewsHtml(destinationItemId);
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
		assertEquals("the origin is unchanged", sourceHtml, readNewsHtml(originItemId));
		assertTrue("the removal is a warning of the item", contentWarnings(originItemId).contains("HTML widget instance " + orphan.getId() + " no longer exists"));
	}

	private void createInstancesAndSourceContent() throws Exception
	{
		ServiceInstanceConfig html = portal.createServiceInstance(channel, WidgetFixture.HTML_SERVICE_ID);
		originRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		destinationRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		originNews = portal.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		destinationNews = portal.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		portal.setDependency(originRepository, originNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		portal.setDependency(destinationRepository, destinationNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
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
		originHtmlByItem.put(widgets.addContent(originNews, NEWS_SOURCE_ID, news("first widget content", firstHtml)), firstHtml);
		originHtmlByItem.put(widgets.addContent(originNews, NEWS_SOURCE_ID, news("second widget content", secondHtml)), secondHtml);
	}

	private Map<String, Object> runMigration() throws Exception
	{
		String key = originNews.getId() + ":" + destinationNews.getId();
		String migrationId = migrationByPair.get(key);
		if (migrationId == null)
		{
			Map<String, Object> create = new HashMap<>();
			create.put("name", "contentmigrator-widget-migration");
			create.put("originServiceInstanceId", originNews.getId());
			create.put("destServiceInstanceId", destinationNews.getId());
			Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
			migrationId = (String) migration.get("id");
			migrationIds.add(migrationId);
			migrationByPair.put(key, migrationId);
		}
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
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
				assertEquals(originHtmlByItem.get(itemId), WidgetFixture.readColumn(transaction, "lum_News", "content", "id", itemId));
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
					"select originItemId, destItemId from lum_cmig_MigItemMap where kind = 'CONTENT' " + "and originServiceInstanceId = ? and destServiceInstanceId = ?"))
			{
				statement.setString(1, originNews.getId());
				statement.setString(2, destinationNews.getId());
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
					.prepareStatement("select warnings from lum_cmig_MigItemMap where kind = 'CONTENT' and originItemId = ?"))
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

	private String readNewsHtml(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> WidgetFixture.readColumn(transaction, "lum_News", "content", "id", itemId));
	}

	private int contentCount(String serviceInstanceId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("select count(*) from lum_Content where serviceInstanceId = ? and sourceId = ?"))
			{
				statement.setString(1, serviceInstanceId);
				statement.setString(2, NEWS_SOURCE_ID);
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
		if (destinationNews == null || originNews == null)
		{
			return;
		}
		for (String itemId : destinationItemsByOrigin().values())
		{
			widgets.track(destinationNews.getId(), NEWS_SOURCE_ID, itemId);
		}
	}

	private void deleteMigrationData() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("delete from lum_cmig_MigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ?"))
			{
				statement.setString(1, originNews.getId());
				statement.setString(2, destinationNews.getId());
				statement.executeUpdate();
			}
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("delete from lum_cmig_MigWidgetMap where originWidgetContentId = ? and destRepoInstanceId = ?"))
			{
				statement.setString(1, originWidget);
				statement.setString(2, destinationRepository.getId());
				statement.executeUpdate();
			}
			for (String migrationId : migrationIds)
			{
				try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement("delete from lum_cmig_Migration where id = ?"))
				{
					statement.setString(1, migrationId);
					statement.executeUpdate();
				}
			}
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection()
					.prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
			{
				statement.executeUpdate();
			}
			return null;
		});
	}

	private static Map<String, Object> news(String title, String content)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", "contentmigrator widget " + title);
		data.put("content", content);
		return data;
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
