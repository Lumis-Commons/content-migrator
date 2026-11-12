package br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.WidgetMapDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationItem;
import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.MigrationBlockedException;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.ISourceData;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.doui.source.field.ISourceField;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.portal.PortalException;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.manager.ManagerFactoryInternal;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.serviceinterfaceinstance.IServiceInterfaceInstanceManager;
import lumis.portal.serviceinterfaceinstance.ServiceInterfaceInstanceConfig;
import lumis.service.pagepersonalization.bean.HtmlFieldWidgetInstance;
import lumis.service.pagepersonalization.bean.Widget;
import lumis.service.pagepersonalization.bean.WidgetInstance;

/**
 * Migrates the widget embeds of html fields to the widgets mapped in the destination widget
 * repository (lum_cmig_MigWidgetMap). An embed is the empty {@code div} that the editor stores for an
 * {@link HtmlFieldWidgetInstance}; the platform finds embeds with the same pattern as {@link #EMBED},
 * in {@code PagePersonalizationUtil#renderWidgetInHtml}, {@code #cloneWidgetsIfNeeded} and
 * {@code #processWidgetInstaceInHtmlAdded}, so an embed the handler does not see is not rendered nor
 * tracked by the platform either.
 * <p>
 * Life cycle of the instances, as in the platform:
 * <ul>
 * <li>with different repositories, {@link #rewriteHtml(DependencyContext, String)} creates a new
 * instance of the mapped destination widget for each embed, as
 * {@code PagePersonalizationUtil#cloneWidgetsIfNeeded} does, copies the custom properties and shared
 * preferences of the origin embed when both widgets have the same interface, and points the embed
 * to it; the native hooks of {@code HtmlDataType} on {@code addData} and {@code updateData}
 * ({@code PagePersonalizationUtil#processWidgetInstaceInHtmlAdded} and
 * {@code #processWidgetInstaceInHtmlUpdate}) link it to the destination item and remove the
 * destination instances the new value no longer embeds;</li>
 * <li>when the repository is shared, or one side has none, the embeds are kept, as are the embeds
 * of widgets of a third repository; {@code HtmlDataType#preAdd} and {@code #preUpdate} then clone
 * each of these instances natively ({@code PagePersonalizationUtil#cloneWidgetsIfNeeded}), because
 * it is linked to an item of another content;</li>
 * <li>in both cases, the embed of an instance or widget that no longer exists is removed with a
 * warning, because the native hooks reject an embed whose instance does not exist and
 * {@code WidgetInstance(String)} fails when the widget does not exist.</li>
 * </ul>
 */
public class WidgetDependencyHandler implements DependencyHandler
{
	private static final String WIDGET_SOURCE_ID = "widget";

	private static final String EMBED_ID_PREFIX = "lum-html-widget-embedded-";

	/** {@code PagePersonalizationUtil.WIDGET_ENCODE_PATTERN} of the platform; group 1 is the instance id. */
	private static final Pattern EMBED = Pattern.compile("<div[^>]+?id=\"lum-html-widget-embedded-(\\w{32})[^>]++><\\/div>");

	private static final Pattern EMBED_ID = Pattern.compile("id=\"lum-html-widget-embedded-\\w{32}\"");

	private static final Pattern EMBED_INTERFACE_INSTANCE = Pattern.compile("(?i)\\bdata-widget-interfaceinstanceid\\s*=\\s*(?:\"[^\"]*\"|'[^']*')");

	@Override
	public String getDependencyType()
	{
		return ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY;
	}

	@Override
	public boolean handles(ISourceField field) throws Exception
	{
		return false; // Widget references exist only inside HTML embeds.
	}

	@Override
	public void precheck(DependencyContext context, MigrationItem item) throws Exception
	{
		if (isRewriteNotNeeded(context))
		{
			return;
		}

		ContentLocale locale = ContentManagerFactory.getContentManager().getContentLocale(item.getContentLocaleId());
		String originItemId = locale.getActiveVersion() == null ? item.getContentLocaleId() : locale.getActiveVersion().getItemId();
		ContentTableSource source = source(context, context.getOriginServiceInstanceId(), item.getSourceId());
		for (ContentVersion version : versionsToCopy(locale))
		{
			List<ISourceData> rows = source.readData(version.getItemId());
			if (rows.isEmpty())
			{
				continue;
			}
			ISourceData row = rows.get(0);
			for (ISourceField field : source.getFields())
			{
				if (!"html".equals(field.getDataType().getId()))
				{
					continue;
				}
				Object value = row.get(field.getId());
				if (value != null)
				{
					precheckHtml(context, value.toString(), originItemId);
				}
			}
		}
	}

	/**
	 * Tells whether widget references can be kept as they are, because both sides share the repository
	 * or one of the repository instances is unknown and no mapping can be applied.
	 */
	private static boolean isRewriteNotNeeded(DependencyContext context)
	{
		boolean hasBothRepositories = context.getOriginRepositoryInstanceId() != null && context.getDestRepositoryInstanceId() != null;
		return context.isSameRepository() || !hasBothRepositories;
	}

	private void precheckHtml(DependencyContext context, String html, String originItemId) throws Exception
	{
		for (HtmlDataTypeUtil.HtmlDataToRenderArea area : HtmlDataTypeUtil.parseContent(html))
		{
			String areaHtml = area.getHtmlContent();
			if (areaHtml == null)
			{
				continue;
			}
			Matcher matcher = EMBED.matcher(areaHtml);
			while (matcher.find())
			{
				// The rewrite removes, with a warning, the embed of an instance or widget that no longer exists.
				HtmlFieldWidgetInstance instance = loadInstance(matcher.group(1));
				if (instance == null)
				{
					continue;
				}
				WidgetInfo originWidget = loadWidget(instance.getWidgetContentId());
				if (originWidget == null)
				{
					continue;
				}
				if (!context.getOriginRepositoryInstanceId().equals(originWidget.serviceInstanceId))
				{
					context.addWarning("Widget " + originWidget.contentId + " belongs to another repository and its embed was left unchanged.");
					continue;
				}
				String mappedWidgetId = WidgetMapDao.getWidgetMapping(context.getConnection(), originWidget.contentId, context.getDestRepositoryInstanceId());
				if (mappedWidgetId == null || !isValidDestinationWidget(mappedWidgetId, context.getDestRepositoryInstanceId()))
				{
					throw new MigrationBlockedException(
							MigrationBlockedException.ERROR_TYPE_WIDGET_MAPPING_REQUIRED,
							originItemId,
							"Widget " + originWidget.contentId + " needs a valid mapping to a widget in the destination repository.",
							originWidget.contentId);
				}
			}
		}
	}

	@Override
	public Object resolve(DependencyContext context, String fieldOrAssociationId, Object value)
	{
		return value;
	}

	@Override
	public String rewriteHtml(DependencyContext context, String html) throws Exception
	{
		boolean hasEmbeds = html != null && html.contains(EMBED_ID_PREFIX);
		if (!hasEmbeds)
		{
			return html;
		}

		boolean isMappingNeeded = !isRewriteNotNeeded(context);
		return HtmlDataTypeUtil.processAreasHtml(html, area ->
		{
			String areaHtml = area.getHtmlContent();
			if (areaHtml == null)
			{
				return null;
			}
			try
			{
				return rewriteArea(context, areaHtml, isMappingNeeded);
			}
			catch (Exception e)
			{
				throw new PortalException("Could not rewrite widget embeds in HTML.", e);
			}
		});
	}

	/**
	 * Rewrites the embeds of one html area: removes those whose instance or widget no longer exists
	 * and, when a mapping is needed, points the others to new instances of the mapped widgets.
	 */
	private String rewriteArea(DependencyContext context, String html, boolean isMappingNeeded) throws Exception
	{
		StringBuilder result = new StringBuilder(html.length());
		Matcher matcher = EMBED.matcher(html);
		while (matcher.find())
		{
			HtmlFieldWidgetInstance originInstance = loadInstance(matcher.group(1));
			if (originInstance == null)
			{
				context.addWarning("HTML widget instance " + matcher.group(1) + " no longer exists; its embed was removed.");
				matcher.appendReplacement(result, "");
				continue;
			}

			WidgetInfo originWidget = loadWidget(originInstance.getWidgetContentId());
			if (originWidget == null)
			{
				context.addWarning("Widget " + originInstance.getWidgetContentId() + " no longer exists; its embed was removed.");
				matcher.appendReplacement(result, "");
				continue;
			}
			if (!isMappingNeeded)
			{
				matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
				continue;
			}
			if (!context.getOriginRepositoryInstanceId().equals(originWidget.serviceInstanceId))
			{
				context.addWarning("Widget " + originWidget.contentId + " belongs to another repository and its embed was left unchanged.");
				matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
				continue;
			}

			String destinationWidgetId = WidgetMapDao.getWidgetMapping(context.getConnection(), originWidget.contentId, context.getDestRepositoryInstanceId());
			WidgetInfo destinationWidget = destinationWidgetId == null ? null : validDestinationWidget(destinationWidgetId, context.getDestRepositoryInstanceId());
			if (destinationWidget == null)
			{
				throw new MigrationBlockedException(
						MigrationBlockedException.ERROR_TYPE_WIDGET_MAPPING_REQUIRED,
						originInstance.getFieldItemIds().isEmpty() ? null : originInstance.getFieldItemIds().iterator().next(),
						"Widget " + originWidget.contentId + " needs a valid mapping to a widget in the destination repository.",
						originWidget.contentId);
			}

			HtmlFieldWidgetInstance destinationInstance = new HtmlFieldWidgetInstance(destinationWidget.contentId);
			ManagerFactory.getEntityManager().persist(destinationInstance);
			copyEmbedConfigurationIfSameInterface(context, originWidget, destinationWidget, originInstance, destinationInstance);

			String embed = EMBED_ID.matcher(matcher.group()).replaceFirst(Matcher.quoteReplacement("id=\"" + EMBED_ID_PREFIX + destinationInstance.getId() + "\""));
			String interfaceInstanceAttribute = "data-widget-interfaceinstanceid=\"" + destinationInstance.getInterfaceInstanceId() + "\"";
			Matcher attribute = EMBED_INTERFACE_INSTANCE.matcher(embed);
			embed = attribute.find()
					? attribute.replaceFirst(Matcher.quoteReplacement(interfaceInstanceAttribute))
					: embed.replaceFirst("<div", "<div " + interfaceInstanceAttribute);
			matcher.appendReplacement(result, Matcher.quoteReplacement(embed));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Copies the custom properties and the shared preferences of the origin embed to the destination
	 * one, as {@code PagePersonalizationUtil#copyProperties} does, when the base interface instances of
	 * both widgets are of the same interface.
	 */
	private void copyEmbedConfigurationIfSameInterface(
			DependencyContext context,
			WidgetInfo originWidget,
			WidgetInfo destinationWidget,
			HtmlFieldWidgetInstance originInstance,
			HtmlFieldWidgetInstance destinationInstance) throws Exception
	{
		SessionConfig session = context.getSession();
		IServiceInterfaceInstanceManager manager = ManagerFactory.getServiceInterfaceInstanceManager();
		ServiceInterfaceInstanceConfig originBase = manager.get(session, originWidget.widget.getInterfaceInstanceId(), context.getTransaction());
		ServiceInterfaceInstanceConfig destinationBase = manager.get(session, destinationWidget.widget.getInterfaceInstanceId(), context.getTransaction());
		if (!originBase.getServiceInterfaceId().equals(destinationBase.getServiceInterfaceId()))
		{
			return;
		}

		String originInterfaceInstanceId = originInstance.getInterfaceInstanceId();
		String destinationInterfaceInstanceId = destinationInstance.getInterfaceInstanceId();
		manager.setCustomProperties(
				session,
				destinationInterfaceInstanceId,
				manager.getCustomProperties(session, originInterfaceInstanceId, context.getTransaction()),
				context.getTransaction());
		ManagerFactoryInternal.getPreferencesManager().copySharedInterfaceInstancePreferences(originInterfaceInstanceId, destinationInterfaceInstanceId);
	}

	private static HtmlFieldWidgetInstance loadInstance(String id)
	{
		WidgetInstance instance = ManagerFactory.getEntityManager().find(WidgetInstance.class, id);
		return instance instanceof HtmlFieldWidgetInstance ? (HtmlFieldWidgetInstance) instance : null;
	}

	private static WidgetInfo loadWidget(String contentId) throws Exception
	{
		try
		{
			Content content = ContentManagerFactory.getContentManager().getContent(contentId);
			boolean isWidgetContent = content != null && WIDGET_SOURCE_ID.equals(content.getSourceId());
			if (!isWidgetContent || content.getContentLocales().isEmpty())
			{
				return null;
			}
			ContentVersion activeVersion = content.getContentLocales().iterator().next().getActiveVersion();
			if (activeVersion == null)
			{
				return null;
			}
			Widget widget = ManagerFactory.getEntityManager().find(Widget.class, activeVersion.getItemId());
			return widget == null ? null : new WidgetInfo(contentId, content.getServiceInstanceId(), widget);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	private static boolean isValidDestinationWidget(String widgetContentId, String destinationRepositoryId) throws Exception
	{
		return validDestinationWidget(widgetContentId, destinationRepositoryId) != null;
	}

	private static WidgetInfo validDestinationWidget(String widgetContentId, String destinationRepositoryId) throws Exception
	{
		WidgetInfo destination = loadWidget(widgetContentId);
		return destination != null && destinationRepositoryId.equals(destination.serviceInstanceId) ? destination : null;
	}

	private static ContentTableSource source(DependencyContext context, String serviceInstanceId, String sourceId) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(serviceInstanceId, context.getTransaction());
		return (ContentTableSource) SourceFactory.createSource(sourceId, new SourceContext(context.getSession(), instance, context.getTransaction()));
	}

	private static List<ContentVersion> versionsToCopy(ContentLocale locale)
	{
		List<ContentVersion> versions = new ArrayList<>();
		ContentVersion published = locale.getPublishedVersion();
		ContentVersion active = locale.getActiveVersion();
		if (published != null)
		{
			versions.add(published);
		}
		if (active != null && (published == null || !active.getItemId().equals(published.getItemId())))
		{
			versions.add(active);
		}
		return versions;
	}

	@Override
	public boolean isCloneReferenced(DependencyContext context, String destContentLocaleId)
	{
		return false;
	}

	@Override
	public void deleteClone(DependencyContext context, String destContentLocaleId)
	{
		// Widget instances are linked to destination items by the native HTML field hooks and are
		// removed when those items are deleted; they are not repository clones or MigItemMap rows.
	}

	private static final class WidgetInfo
	{
		final String contentId;

		final String serviceInstanceId;

		final Widget widget;

		WidgetInfo(String contentId, String serviceInstanceId, Widget widget)
		{
			this.contentId = contentId;
			this.serviceInstanceId = serviceInstanceId;
			this.widget = widget;
		}
	}
}
