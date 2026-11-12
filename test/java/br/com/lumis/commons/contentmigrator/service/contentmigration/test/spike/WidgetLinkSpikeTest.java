package br.com.lumis.commons.contentmigrator.service.contentmigration.test.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.WidgetFixture;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.doui.source.field.type.HtmlDataTypeUtil.HtmlDataToRenderArea;
import lumis.portal.PortalException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.manager.ManagerFactoryInternal;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinterfaceinstance.IServiceInterfaceInstanceManager;
import lumis.portal.serviceinterfaceinstance.ServiceInterfaceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;
import lumis.service.pagepersonalization.bean.HtmlFieldWidgetInstance;
import lumis.service.pagepersonalization.bean.Widget;
import lumis.service.pagepersonalization.bean.WidgetInstance;

/**
 * Spike of the widget link in HTML fields (docs/architecture/widget-migration.md): a content copied
 * to an instance with another widget repository is saved with its embeds rewritten to new
 * {@code HtmlFieldWidgetInstance}s of the mapped destination widget, created in the item's
 * transaction and linked by the native hooks to the new item id; the origin widget and its
 * instances stay untouched.
 *
 * <p>{@link #precheck(String, String, Map)} and {@link #rewrite(String, Map)} are the algorithm the
 * widget handler is to follow: the precheck finds every embed of every area, including the JSON
 * {@code lumHtmlContent} areas, and blocks without writing when an origin widget has no mapping;
 * the rewrite, in the item's transaction and before {@code addData}, persists one new instance per
 * embed, copies the embed's custom properties and shared preferences when both widgets have the
 * same interface, and rewrites {@code id} and {@code data-widget-interfaceinstanceid}.
 */
public class WidgetLinkSpikeTest
{
	/** {@code PagePersonalizationUtil.WIDGET_ENCODE_PATTERN}: the embeds the portal renders. */
	static final Pattern EMBED = Pattern.compile("<div[^>]+?id=\"lum-html-widget-embedded-(\\w{32})[^>]++><\\/div>");

	private static final Pattern EMBED_ID = Pattern.compile("id=\"lum-html-widget-embedded-\\w{32}\"");

	private static final Pattern EMBED_INTERFACE_INSTANCE = Pattern.compile("data-widget-interfaceinstanceid=\"[^\"]*\"");

	private static final String NEWS_SOURCE_ID = "default";

	private static final String CUSTOM_PROPERTY = "contentmigrator.spike.embed";

	private PortalFixture fixture;

	private WidgetFixture widgets;

	private ServiceInstanceConfig repositoryA;

	private ServiceInstanceConfig repositoryB;

	private ServiceInstanceConfig originNews;

	private ServiceInstanceConfig destNews;

	private String widgetA;

	private String widgetB;

	private HtmlFieldWidgetInstance originEmbed1;

	private HtmlFieldWidgetInstance originEmbed2;

	private String originHtml;

	private String originItemId;

	/** Raised by the precheck when an origin widget has no destination widget mapped. */
	static class WidgetMappingRequiredException extends PortalException
	{
		private static final long serialVersionUID = 1L;

		final String originItemId;

		final String widgetContentId;

		WidgetMappingRequiredException(String originItemId, String widgetContentId)
		{
			super("widget " + widgetContentId + " of item " + originItemId + " has no destination widget");
			this.originItemId = originItemId;
			this.widgetContentId = widgetContentId;
		}
	}

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		widgets = new WidgetFixture();

		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig html = fixture.createServiceInstance(channel, WidgetFixture.HTML_SERVICE_ID);
		repositoryA = fixture.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		repositoryB = fixture.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		originNews = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		destNews = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		fixture.setDependency(repositoryA, originNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		fixture.setDependency(repositoryB, destNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);

		// both widgets have the same interface, on different base interface instances
		widgetA = widgets.addWidget(repositoryA, "contentmigrator spike widget A", widgets.addBaseInterfaceInstance(html));
		widgetB = widgets.addWidget(repositoryB, "contentmigrator spike widget B", widgets.addBaseInterfaceInstance(html));

		// origin content: two embeds of widget A, in two lumHtmlContent areas, saved natively
		originEmbed1 = widgets.addEditorInstance(widgetA);
		originEmbed2 = widgets.addEditorInstance(widgetA);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ManagerFactory.getServiceInterfaceInstanceManager()
					.setCustomProperties(session, originEmbed1.getInterfaceInstanceId(), Collections.singletonMap(CUSTOM_PROPERTY, "origin value"), transaction);
			return null;
		});
		originHtml = HtmlDataTypeUtil.buildContentValue(
				Arrays.asList(
						new HtmlDataToRenderArea(
								"main",
								"<p>antes</p>" + WidgetFixture.embed(originEmbed1.getId(), originEmbed1.getInterfaceInstanceId()) + "<p>depois</p>"),
						new HtmlDataToRenderArea("side", WidgetFixture.embed(originEmbed2.getId(), originEmbed2.getInterfaceInstanceId()))));
		originItemId = widgets.addContent(originNews, NEWS_SOURCE_ID, news("origem", originHtml));
	}

	@After
	public void tearDown()
	{
		TestCleanup.assertNoFailures(widgets::cleanup, fixture::cleanup);
	}

	@Test
	public void rewrittenHtmlReferencesDestinationWidgetInstanceLinkedToNewItem() throws Exception
	{
		// the origin content was saved with the embeds as given, each instance linked to its item
		assertOriginIntact(Collections.emptySet());

		// modal options: only the widgets of the destination repository
		Map<String, String> options = PortalFixture.inTransaction((session, transaction) -> WidgetFixture.listWidgets(repositoryB.getId()));
		assertEquals(Collections.singletonMap(widgetB, "contentmigrator spike widget B"), options);

		Map<String, String> widgetMap = Collections.singletonMap(widgetA, widgetB);
		assertEquals(Collections.singleton(widgetA), PortalFixture.inTransaction((session, transaction) -> precheck(originHtml, originItemId, widgetMap)));

		String[] result = PortalFixture.inTransaction((session, transaction) ->
		{
			String html = rewrite(originHtml, widgetMap);
			String itemId = WidgetFixture.addData(session, transaction, destNews.getId(), NEWS_SOURCE_ID, news("destino", html));
			return new String[]{itemId, html};
		});
		String destItemId = result[0];
		String rewrittenHtml = result[1];
		widgets.track(destNews.getId(), NEWS_SOURCE_ID, destItemId);

		System.out.println("[WidgetLinkSpikeTest] before: " + originHtml);
		System.out.println("[WidgetLinkSpikeTest] after:  " + rewrittenHtml);

		PortalFixture.inTransaction((session, transaction) ->
		{
			String storedHtml = WidgetFixture.readColumn(transaction, "lum_News", "content", "id", destItemId);
			assertEquals("native hooks changed the rewritten HTML (a native clone?)", rewrittenHtml, storedHtml);

			// same areas and text, other instances
			List<HtmlDataToRenderArea> originAreas = HtmlDataTypeUtil.parseContent(originHtml);
			List<HtmlDataToRenderArea> destAreas = HtmlDataTypeUtil.parseContent(storedHtml);
			assertEquals(originAreas.size(), destAreas.size());
			for (int i = 0; i < originAreas.size(); i++)
			{
				assertEquals(originAreas.get(i).getTargetRenderAreaName(), destAreas.get(i).getTargetRenderAreaName());
				assertEquals(
						EMBED.matcher(originAreas.get(i).getHtmlContent()).replaceAll("[widget]"),
						EMBED.matcher(destAreas.get(i).getHtmlContent()).replaceAll("[widget]"));
			}
			assertFalse(storedHtml.contains(originEmbed1.getId()));
			assertFalse(storedHtml.contains(originEmbed2.getId()));
			assertFalse(storedHtml.contains(originEmbed1.getInterfaceInstanceId()));

			List<String[]> embeds = embeds(storedHtml);
			assertEquals(2, embeds.size());
			Set<String> instanceIds = new HashSet<>();
			Widget destWidget = WidgetFixture.getWidget(widgetB);
			for (String[] embed : embeds)
			{
				instanceIds.add(embed[0]);
				HtmlFieldWidgetInstance instance = WidgetFixture.getInstance(embed[0]);
				assertNotNull("destination instance not found: " + embed[0], instance);
				assertEquals(widgetB, instance.getWidgetContentId());
				assertEquals(instance.getInterfaceInstanceId(), embed[1]);
				assertEquals(Collections.singletonList(destItemId), new ArrayList<>(instance.getFieldItemIds()));
				assertEquals(destNews.getId(), instance.getFieldServiceInstanceId());
				assertEquals(NEWS_SOURCE_ID, instance.getFieldSourceId());

				ServiceInterfaceInstanceConfig interfaceInstance = ManagerFactory.getServiceInterfaceInstanceManager()
						.get(session, instance.getInterfaceInstanceId(), transaction);
				assertEquals(destWidget.getInterfaceInstanceId(), interfaceInstance.getBaseInterfaceInstanceId());
			}
			assertEquals("one new instance per embed", 2, instanceIds.size());
			assertEquals("only the rewritten instances point to the destination widget", instanceIds, instanceIdsOf(widgetB));

			// same interface: the custom properties of each origin embed went to its new instance
			HtmlFieldWidgetInstance destEmbed1 = WidgetFixture.getInstance(embeds.get(0)[0]);
			assertEquals(
					"origin value",
					ManagerFactory.getServiceInterfaceInstanceManager().getCustomProperties(session, destEmbed1.getInterfaceInstanceId(), transaction)
							.get(CUSTOM_PROPERTY));
			return null;
		});

		assertOriginIntact(Collections.emptySet());
	}

	@Test
	public void failedItemRollsBackTheDestinationInstances() throws Exception
	{
		Map<String, String> widgetMap = Collections.singletonMap(widgetA, widgetB);
		Set<String> createdInterfaceInstances = new HashSet<>();
		try
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				String html = rewrite(originHtml, widgetMap);
				WidgetFixture.addData(session, transaction, destNews.getId(), NEWS_SOURCE_ID, news("rollback " + originItemId, html));
				for (String[] embed : embeds(html))
				{
					createdInterfaceInstances.add(embed[1]);
				}
				throw new IllegalStateException("item failed after addData");
			});
			fail("the item did not fail");
		}
		catch (IllegalStateException e)
		{
			// expected: the item's transaction is rolled back
		}

		assertEquals(2, createdInterfaceInstances.size());
		PortalFixture.inTransaction((session, transaction) ->
		{
			assertTrue("destination instances survived the rollback", instanceIdsOf(widgetB).isEmpty());
			for (String interfaceInstanceId : createdInterfaceInstances)
			{
				assertNull("widget instance survived the rollback", WidgetInstance.findByInterfaceInstanceId(interfaceInstanceId));
				assertNull(
						"interface instance survived the rollback",
						WidgetFixture.readColumn(transaction, "lum_InterfaceInstance", "interfaceInstanceId", "interfaceInstanceId", interfaceInstanceId));
			}
			assertNull(
					"destination content survived the rollback",
					WidgetFixture.readColumn(transaction, "lum_News", "id", "title", "contentmigrator spike rollback " + originItemId));
			return null;
		});
		assertOriginIntact(Collections.emptySet());
	}

	@Test
	public void missingMappingBlocksBeforeAnyWrite() throws Exception
	{
		try
		{
			PortalFixture.inTransaction((session, transaction) -> precheck(originHtml, originItemId, Collections.emptyMap()));
			fail("precheck did not block");
		}
		catch (WidgetMappingRequiredException e)
		{
			assertEquals(originItemId, e.originItemId);
			assertEquals(widgetA, e.widgetContentId);
		}

		// a mapping to a widget that no longer exists blocks too
		try
		{
			PortalFixture.inTransaction((session, transaction) -> precheck(originHtml, originItemId, Collections.singletonMap(widgetA, originItemId)));
			fail("precheck did not block a mapping to a missing widget");
		}
		catch (WidgetMappingRequiredException e)
		{
			assertEquals(widgetA, e.widgetContentId);
		}

		PortalFixture.inTransaction((session, transaction) ->
		{
			assertTrue(instanceIdsOf(widgetB).isEmpty());
			return null;
		});
		assertOriginIntact(Collections.emptySet());
	}

	@Test
	public void sameRepositoryKeepsTheHtmlAndTheNativeHookClonesTheInstances() throws Exception
	{
		// destination with the origin's repository: the handler does not rewrite
		fixture.setDependency(repositoryA, destNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		String destItemId = widgets.addContent(destNews, NEWS_SOURCE_ID, news("destino", originHtml));

		Set<String> clones = PortalFixture.inTransaction((session, transaction) ->
		{
			String storedHtml = WidgetFixture.readColumn(transaction, "lum_News", "content", "id", destItemId);
			List<String[]> embeds = embeds(storedHtml);
			assertEquals(2, embeds.size());
			for (String[] embed : embeds)
			{
				assertNotEquals(originEmbed1.getId(), embed[0]);
				assertNotEquals(originEmbed2.getId(), embed[0]);
				HtmlFieldWidgetInstance clone = WidgetFixture.getInstance(embed[0]);
				assertNotNull("native clone not found", clone);
				assertEquals(widgetA, clone.getWidgetContentId());
				assertEquals(Collections.singletonList(destItemId), new ArrayList<>(clone.getFieldItemIds()));
				assertEquals(clone.getInterfaceInstanceId(), embed[1]);
			}
			assertEquals(
					"origin value",
					ManagerFactory.getServiceInterfaceInstanceManager()
							.getCustomProperties(session, WidgetFixture.getInstance(embeds.get(0)[0]).getInterfaceInstanceId(), transaction).get(CUSTOM_PROPERTY));
			Set<String> ids = new HashSet<>();
			for (String[] embed : embeds)
			{
				ids.add(embed[0]);
			}
			return ids;
		});
		assertEquals("one native clone per embed", 2, clones.size());
		assertOriginIntact(clones);
	}

	/**
	 * Asserts that the origin content, its two instances and widget A are as created.
	 *
	 * @param otherInstancesOfWidgetA instances of widget A expected besides the origin's.
	 */
	private void assertOriginIntact(Set<String> otherInstancesOfWidgetA) throws Exception
	{
		Set<String> instancesOfWidgetA = new HashSet<>(otherInstancesOfWidgetA);
		instancesOfWidgetA.add(originEmbed1.getId());
		instancesOfWidgetA.add(originEmbed2.getId());
		PortalFixture.inTransaction((session, transaction) ->
		{
			assertEquals(originHtml, WidgetFixture.readColumn(transaction, "lum_News", "content", "id", originItemId));
			for (HtmlFieldWidgetInstance created : Arrays.asList(originEmbed1, originEmbed2))
			{
				HtmlFieldWidgetInstance instance = WidgetFixture.getInstance(created.getId());
				assertNotNull("origin instance removed", instance);
				assertEquals(widgetA, instance.getWidgetContentId());
				assertEquals(created.getInterfaceInstanceId(), instance.getInterfaceInstanceId());
				assertEquals(Collections.singletonList(originItemId), new ArrayList<>(instance.getFieldItemIds()));
				assertEquals(originNews.getId(), instance.getFieldServiceInstanceId());
			}
			Widget widget = WidgetFixture.getWidget(widgetA);
			assertNotNull("origin widget removed", widget);
			assertEquals("contentmigrator spike widget A", widget.getName());
			assertEquals(instancesOfWidgetA, instanceIdsOf(widgetA));
			assertEquals(
					"origin value",
					ManagerFactory.getServiceInterfaceInstanceManager().getCustomProperties(session, originEmbed1.getInterfaceInstanceId(), transaction)
							.get(CUSTOM_PROPERTY));
			return null;
		});
	}

	/**
	 * Returns the ids of the instances of a widget content. Call inside a transaction.
	 */
	private static Set<String> instanceIdsOf(String widgetContentId)
	{
		Set<String> ids = new HashSet<>();
		for (WidgetInstance instance : WidgetInstance.findByWidgetContentId(widgetContentId))
		{
			ids.add(instance.getId());
		}
		return ids;
	}

	/**
	 * Returns the embeds of every area of an HTML value, in order, as {instance id, value of
	 * {@code data-widget-interfaceinstanceid} or null}.
	 */
	static List<String[]> embeds(String html)
	{
		List<String[]> embeds = new ArrayList<>();
		for (HtmlDataToRenderArea area : HtmlDataTypeUtil.parseContent(html))
		{
			if (area.getHtmlContent() == null)
			{
				continue;
			}
			Matcher matcher = EMBED.matcher(area.getHtmlContent());
			while (matcher.find())
			{
				Matcher interfaceInstance = EMBED_INTERFACE_INSTANCE.matcher(matcher.group());
				embeds.add(new String[]{matcher.group(1), interfaceInstance.find() ? interfaceInstance.group().replaceAll(".*=\"([^\"]*)\"", "$1") : null});
			}
		}
		return embeds;
	}

	/**
	 * Precheck of the widget handler: reads the origin instance of every embed and returns the origin
	 * widget content ids found. Writes nothing. Embeds whose instance no longer exists are left to
	 * the rewrite, which drops them.
	 *
	 * @param widgetMap origin widget content id to destination widget content id, the rows of
	 * {@code lum_cmig_MigWidgetMap} for the destination repository.
	 * @throws WidgetMappingRequiredException for the first origin widget without a destination widget
	 * that exists.
	 */
	static Set<String> precheck(String html, String originItemId, Map<String, String> widgetMap) throws Exception
	{
		Set<String> widgetContentIds = new HashSet<>();
		for (String[] embed : embeds(html))
		{
			HtmlFieldWidgetInstance instance = WidgetFixture.getInstance(embed[0]);
			if (instance == null)
			{
				continue;
			}
			String destWidgetContentId = widgetMap.get(instance.getWidgetContentId());
			if (destWidgetContentId == null || WidgetFixture.getWidget(destWidgetContentId) == null)
			{
				throw new WidgetMappingRequiredException(originItemId, instance.getWidgetContentId());
			}
			widgetContentIds.add(instance.getWidgetContentId());
		}
		return widgetContentIds;
	}

	/**
	 * Rewrite of the widget handler, in the item's transaction, after a successful
	 * {@link #precheck(String, String, Map)} and before {@code addData} or {@code updateData}: every
	 * embed of every area gets a new instance of the mapped destination widget, without item ids, so
	 * the native {@code cloneWidgetsIfNeeded} keeps it and {@code processWidgetInstaceInHtmlAdded}
	 * links it to the new item id.
	 *
	 * @return the HTML to save.
	 */
	static String rewrite(String html, Map<String, String> widgetMap) throws PortalException
	{
		return HtmlDataTypeUtil.processAreasHtml(html, area ->
		{
			String areaHtml = area.getHtmlContent();
			if (areaHtml == null)
			{
				return null;
			}
			StringBuilder sb = new StringBuilder(areaHtml.length());
			Matcher matcher = EMBED.matcher(areaHtml);
			while (matcher.find())
			{
				HtmlFieldWidgetInstance originInstance = WidgetFixture.getInstance(matcher.group(1));
				if (originInstance == null)
				{
					// dangling embed: the portal does not render it; the handler records a warning
					matcher.appendReplacement(sb, "");
					continue;
				}
				String destWidgetContentId = widgetMap.get(originInstance.getWidgetContentId());

				HtmlFieldWidgetInstance destInstance = new HtmlFieldWidgetInstance(destWidgetContentId);
				ManagerFactory.getEntityManager().persist(destInstance);
				copyEmbedConfigurationIfSameInterface(originInstance, destInstance);

				String embed = EMBED_ID.matcher(matcher.group()).replaceFirst("id=\"lum-html-widget-embedded-" + destInstance.getId() + "\"");
				String interfaceInstanceAttribute = "data-widget-interfaceinstanceid=\"" + destInstance.getInterfaceInstanceId() + "\"";
				Matcher attribute = EMBED_INTERFACE_INSTANCE.matcher(embed);
				embed = attribute.find() ? attribute.replaceFirst(interfaceInstanceAttribute) : embed.replaceFirst("<div", "<div " + interfaceInstanceAttribute);
				matcher.appendReplacement(sb, Matcher.quoteReplacement(embed));
			}
			matcher.appendTail(sb);
			return sb.toString();
		});
	}

	/**
	 * Copies the custom properties and the shared preferences of the origin embed's interface
	 * instance to the new one, as {@code PagePersonalizationUtil.copyProperties} does, when both
	 * widgets' base interface instances are of the same interface.
	 */
	private static void copyEmbedConfigurationIfSameInterface(HtmlFieldWidgetInstance originInstance, HtmlFieldWidgetInstance destInstance) throws PortalException
	{
		SessionConfig session = SessionConfig.getCurrentSessionConfig();
		ITransaction transaction = PortalTransactionFactory.getCurrentTransaction();
		IServiceInterfaceInstanceManager manager = ManagerFactory.getServiceInterfaceInstanceManager();
		ServiceInterfaceInstanceConfig origin = manager.get(session, originInstance.getInterfaceInstanceId(), transaction);
		ServiceInterfaceInstanceConfig dest = manager.get(session, destInstance.getInterfaceInstanceId(), transaction);
		if (!origin.getServiceInterfaceId().equals(dest.getServiceInterfaceId()))
		{
			return;
		}

		Map<String, String> customProperties = manager.getCustomProperties(session, origin.getId(), transaction);
		manager.setCustomProperties(session, dest.getId(), customProperties, transaction);
		ManagerFactoryInternal.getPreferencesManager().copySharedInterfaceInstancePreferences(origin.getId(), dest.getId());
	}

	private static Map<String, Object> news(String title, String html)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", "contentmigrator spike " + title);
		data.put("content", html);
		return data;
	}
}
