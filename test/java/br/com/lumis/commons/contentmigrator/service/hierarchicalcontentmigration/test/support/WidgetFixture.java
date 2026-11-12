package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture.Work;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.hibernate.ITransactionHibernate;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinterfaceinstance.ServiceInterfaceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.service.pagepersonalization.PagePersonalizationUtil;
import lumis.service.pagepersonalization.bean.HtmlFieldWidgetInstance;
import lumis.service.pagepersonalization.bean.Widget;
import lumis.service.pagepersonalization.bean.WidgetInstance;
import lumis.service.pagepersonalization.doui.serviceinterfaceinstance.PagePersonalizationInterfaceInstanceOwner;

/**
 * Creates widgets in {@value #WIDGET_SERVICE_ID} instances, the embeds of HTML fields that point to
 * them, and contents with HTML fields through their content table source, so the native
 * {@code HtmlDataType} hooks run; reads them back and removes them at the end.
 *
 * <p>A widget is a content of the {@value #WIDGET_SOURCE_ID} source whose {@code lum_PPWidget} row
 * points to a base interface instance, here an orphan {@value #HTML_INTERFACE_ID} instance owned by
 * {@link PagePersonalizationInterfaceInstanceOwner}, as {@code TestHtmlDataTypeData} in the portal
 * tests does.
 *
 * <p>Every create method runs in its own committed transaction, through
 * {@link PortalFixture#inTransaction(Work)}. {@link #cleanup()} removes, in the inverse order of
 * creation, the contents, the widgets' HTML field instances, the widgets and their base interface
 * instances; call it before {@link PortalFixture#cleanup()}, which removes the service instances.
 * The system property {@value PortalFixture#KEEP_TEST_DATA} keeps them, as in
 * {@link PortalFixture}.
 */
public class WidgetFixture
{
	/** Widget repository service. */
	public static final String WIDGET_SERVICE_ID = "lumis.service.pagepersonalization";

	/** Dependency type of a content service on its widget repository. */
	public static final String WIDGET_REPOSITORY_DEPENDENCY = "widget_repository";

	/** Content table source of the widget repository, rows of {@code lum_PPWidget}. */
	public static final String WIDGET_SOURCE_ID = "widget";

	/** Service whose interface is the base of the widgets created. */
	public static final String HTML_SERVICE_ID = "lumis.service.html";

	/** Interface of {@value #HTML_SERVICE_ID} used as the widgets' base interface instance. */
	public static final String HTML_INTERFACE_ID = HTML_SERVICE_ID + ".display";

	/** Locale of the contents created; the impersonated session has none. */
	public static final Locale LOCALE = new Locale("pt", "BR");

	private final Deque<Work<Void>> cleaners = new ArrayDeque<>();

	/**
	 * Adds an orphan {@value #HTML_INTERFACE_ID} interface instance of the given
	 * {@value #HTML_SERVICE_ID} instance, to be the base interface instance of a widget.
	 *
	 * @return the interface instance id.
	 */
	public String addBaseInterfaceInstance(ServiceInstanceConfig htmlInstance) throws Exception
	{
		String interfaceInstanceId = PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInterfaceInstanceConfig interfaceInstance = new ServiceInterfaceInstanceConfig();
			interfaceInstance.setServiceInstanceId(htmlInstance.getId());
			interfaceInstance.setServiceInterfaceId(HTML_INTERFACE_ID);
			interfaceInstance.setOrphan(PagePersonalizationInterfaceInstanceOwner.class);
			interfaceInstance.setCreatedRunTime(false);
			return ManagerFactory.getServiceInterfaceInstanceManager().add(session, interfaceInstance, transaction);
		});
		cleaners.push((session, transaction) ->
		{
			try
			{
				ManagerFactory.getServiceInterfaceInstanceManager().delete(session, interfaceInstanceId, transaction);
			}
			catch (PortalObjectNotFoundException e)
			{
				// already removed
			}
			return null;
		});
		return interfaceInstanceId;
	}

	/**
	 * Adds a published widget to the given widget repository, with one {@link #LOCALE} version
	 * whose {@code lum_PPWidget} row points to the given base interface instance.
	 *
	 * @return the widget's content id, the value of {@code lum_PPWidgetInstance.widgetContentId}.
	 */
	public String addWidget(ServiceInstanceConfig widgetRepository, String name, String baseInterfaceInstanceId) throws Exception
	{
		String contentId = PortalFixture.inTransaction((session, transaction) ->
		{
			Widget widget = new Widget();
			widget.setName(name);
			widget.setInterfaceInstanceId(baseInterfaceInstanceId);
			ManagerFactory.getEntityManager().persist(widget);

			Content content = new Content();
			content.setServiceInstanceId(widgetRepository.getId());
			content.setSourceId(WIDGET_SOURCE_ID);
			ContentLocale contentLocale = content.addContentLocale(LOCALE);
			ContentVersion version = contentLocale.addVersion();
			version.setItemId(widget.getId());
			version.setPrimaryName(name);
			contentLocale.setPublishedVersion(version);
			ContentManagerFactory.getContentManager().addContent(content);
			return content.getId();
		});
		cleaners.push((session, transaction) ->
		{
			// HTML field instances first: they hold interface instances derived from the widget's
			for (WidgetInstance instance : WidgetInstance.findByWidgetContentId(contentId))
			{
				instance.remove();
			}
			String widgetId = getWidgetId(contentId);
			if (widgetId != null)
			{
				ContentManagerFactory.getContentManager().deleteContent(ContentManagerFactory.getContentManager().getContent(contentId));
				Widget widget = ManagerFactory.getEntityManager().find(Widget.class, widgetId);
				if (widget != null)
				{
					ManagerFactory.getEntityManager().remove(widget);
				}
			}
			return null;
		});
		return contentId;
	}

	/**
	 * Creates an HTML field instance of the given widget the way the editor does when a widget is
	 * selected: {@link PagePersonalizationUtil#createHtmlFieldWidgetInstance(String)}, committed in a
	 * transaction of its own and without item ids until a content is saved with its embed.
	 *
	 * @return the instance, detached.
	 */
	public HtmlFieldWidgetInstance addEditorInstance(String widgetContentId) throws Exception
	{
		String instanceId = PortalFixture.inTransaction((session, transaction) -> PagePersonalizationUtil.createHtmlFieldWidgetInstance(getWidgetId(widgetContentId)));
		return PortalFixture.inTransaction((session, transaction) -> getInstance(instanceId));
	}

	/**
	 * Returns the stored embed of an HTML field instance, as {@code cloneWidgetsIfNeeded} writes it.
	 */
	public static String embed(String instanceId, String interfaceInstanceId)
	{
		return "<div class=\"lum-html-widget-embedded\" data-widget-interfaceinstanceid=\"" + interfaceInstanceId + "\" id=\"lum-html-widget-embedded-" + instanceId
				+ "\"></div>";
	}

	/**
	 * Adds a row to a content table source of a content instance, in the given transaction, as the
	 * given session; the native field hooks run, including those of {@code html} fields. Used by the
	 * fixture and by migration code under test.
	 *
	 * @return the item id.
	 */
	public static String addData(SessionConfig session, ITransaction transaction, String serviceInstanceId, String sourceId, Map<String, Object> data) throws Exception
	{
		if (session.getLocale() == null)
		{
			session.setLocale(LOCALE);
		}
		return contentSource(session, transaction, serviceInstanceId, sourceId).addData(data).getItemId();
	}

	/**
	 * Registers a content added outside the fixture (for example, by migration code) for removal by
	 * {@link #cleanup()}, through its source, so the {@code html} fields release their widget
	 * instances. Contents already removed are skipped.
	 */
	public void track(String serviceInstanceId, String sourceId, String itemId)
	{
		cleaners.push((session, transaction) ->
		{
			if (getContentVersion(itemId) != null)
			{
				contentSource(session, transaction, serviceInstanceId, sourceId).deleteDataByItemIds(Collections.singleton(itemId));
			}
			return null;
		});
	}

	/**
	 * Adds a content to a content table source in its own transaction and tracks it.
	 *
	 * @return the item id.
	 */
	public String addContent(ServiceInstanceConfig serviceInstance, String sourceId, Map<String, Object> data) throws Exception
	{
		String itemId = PortalFixture.inTransaction((session, transaction) -> addData(session, transaction, serviceInstance.getId(), sourceId, data));
		track(serviceInstance.getId(), sourceId, itemId);
		return itemId;
	}

	/** Adds HTML content through the hierarchical source, including its native widget hooks. */
	public String addHierarchicalContent(ServiceInstanceConfig instance, String title, String html, String parentContentId) throws Exception
	{
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("title", title);
		data.put("linkContent", html);
		data.put("type", 0);
		data.put("position", 0);
		data.put("hidden", false);
		data.put("parentContentId", parentContentId);
		return addContent(instance, "default", data);
	}

	private static ContentTableSource contentSource(SessionConfig session, ITransaction transaction, String serviceInstanceId, String sourceId) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(serviceInstanceId, transaction);
		return (ContentTableSource) SourceFactory.createSource(sourceId, new SourceContext(session, instance, transaction));
	}

	/**
	 * Returns the {@code lum_PPWidget.id} of a widget content: the item id of the active version of
	 * its first locale, the version {@code WidgetInstance} takes the base interface instance from; or
	 * null if the content does not exist. Call inside a transaction.
	 */
	public static String getWidgetId(String widgetContentId) throws Exception
	{
		try
		{
			Content content = ContentManagerFactory.getContentManager().getContent(widgetContentId);
			return content.getContentLocales().iterator().next().getActiveVersion().getItemId();
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	/**
	 * Returns the {@code lum_PPWidget} row of a widget content, or null. Call inside a transaction.
	 */
	public static Widget getWidget(String widgetContentId) throws Exception
	{
		String widgetId = getWidgetId(widgetContentId);
		return widgetId == null ? null : ManagerFactory.getEntityManager().find(Widget.class, widgetId);
	}

	/**
	 * Returns the widgets of a widget repository, the options of the widget mapping modal: content
	 * id to the name of the active version of a locale, ordered by name. Call inside a transaction.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, String> listWidgets(String widgetRepositoryId) throws Exception
	{
		// @formatter:off
		List<Object[]> rows = ManagerFactory.getEntityManager()
				.createQuery("select c.id, w.name from ContentVersion cv inner join cv.contentLocale cl "
						+ "inner join cl.content c, Widget w where cv.itemId = w.id and cl.activeVersion.id = cv.id "
						+ "and c.serviceInstanceId = :repository and c.sourceId = :sourceId order by w.name, c.id")
				.setParameter("repository", widgetRepositoryId).setParameter("sourceId", WIDGET_SOURCE_ID)
				.getResultList();
		// @formatter:on
		Map<String, String> widgets = new LinkedHashMap<>();
		for (Object[] row : rows)
		{
			widgets.putIfAbsent((String) row[0], (String) row[1]);
		}
		return widgets;
	}

	/**
	 * Returns the HTML field instance with the given id, or null. Call inside a transaction.
	 */
	public static HtmlFieldWidgetInstance getInstance(String instanceId)
	{
		WidgetInstance instance = ManagerFactory.getEntityManager().find(WidgetInstance.class, instanceId);
		return instance instanceof HtmlFieldWidgetInstance ? (HtmlFieldWidgetInstance) instance : null;
	}

	/**
	 * Returns the content version with the given item id, or null. Call inside a transaction.
	 */
	public static ContentVersion getContentVersion(String itemId) throws Exception
	{
		try
		{
			return ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	/**
	 * Returns a column of the first row of the given table whose key column has the given value, as
	 * stored, or null if there is none. Call inside a transaction.
	 */
	public static String readColumn(ITransaction transaction, String table, String column, String keyColumn, String key) throws Exception
	{
		try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection()
				.prepareStatement("select " + column + " from " + table + " where " + keyColumn + " = ?"))
		{
			statement.setString(1, key);
			try (ResultSet resultSet = statement.executeQuery())
			{
				return resultSet.next() ? resultSet.getString(1) : null;
			}
		}
	}

	/**
	 * Deletes everything this fixture created or tracked, in the inverse order of creation, each
	 * step in its own transaction. A failing step is reported and does not stop the following ones.
	 *
	 * @return the number of steps that failed.
	 */
	public int cleanup()
	{
		if (PortalFixture.keepTestData())
		{
			System.out.println("[WidgetFixture] " + PortalFixture.KEEP_TEST_DATA + "=true: keeping " + cleaners.size() + " objects");
			cleaners.clear();
			return 0;
		}

		int failures = 0;
		while (!cleaners.isEmpty())
		{
			try
			{
				PortalFixture.inTransaction(cleaners.pop());
			}
			catch (Exception e)
			{
				failures++;
				System.err.println("[WidgetFixture] cleanup step failed: " + e);
				e.printStackTrace();
			}
		}
		return failures;
	}
}
