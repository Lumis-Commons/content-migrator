package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.copy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.json.JSONArray;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.manager.ManagerFactoryInternal;
import lumis.portal.page.PageConfig;
import lumis.portal.service.ServiceConfig;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.website.IWebsite;

/** Resolves portal links without copying the source data-type wrapper into the destination. */
public final class LinkFieldPolicy
{
	private final Set<String> pageLayoutIds = new HashSet<>();

	LinkFieldPolicy(SessionConfig session, ITransaction transaction, String destinationInstanceId) throws Exception
	{
		String layouts = ManagerFactory.getServiceInstanceManager().getCustomProperty(session, destinationInstanceId, "pageLayouts", transaction);
		if (layouts != null && !layouts.trim().isEmpty())
		{
			JSONArray array = new JSONArray(layouts);
			for (int i = 0; i < array.length(); i++)
			{
				pageLayoutIds.addAll(array.getJSONObject(i).keySet());
			}
		}
	}

	static boolean handles(String fieldId, String dataType)
	{
		return "lumisPage".equals(dataType) || "lumisChannel".equals(dataType)
				|| "linkContentId".equals(fieldId) && "guid".equals(dataType) || "pageLayoutId".equals(fieldId);
	}

	String resolve(MigrationScope scope, String fieldId, String dataType, Object value, String originItemId) throws Exception
	{
		// ChannelDataType converts an orphaned channel to null. Recover its stored id so it
		// can be distinguished from an intentionally empty link and produce an item warning.
		boolean isChannelLink = "lumisChannel".equals(dataType) && "linkChannelId".equals(fieldId);
		if (value == null && isChannelLink)
		{
			try (PreparedStatement statement = scope.getConnection().prepareStatement("select linkChannelId from lum_HierarchicalContent where id = ?"))
			{
				statement.setString(1, originItemId);
				try (ResultSet result = statement.executeQuery())
				{
					value = result.next() ? result.getString(1) : null;
				}
			}
		}
		String id = value instanceof PageConfig ? ((PageConfig) value).getId()
				: value instanceof ChannelConfig ? ((ChannelConfig) value).getId() : value == null ? null : value.toString();
		if (id == null || id.isEmpty())
		{
			return null;
		}
		if ("pageLayoutId".equals(fieldId))
		{
			if (pageLayoutIds.contains(id))
			{
				return id;
			}
			warn(scope, "STR_WARNING_PAGE_LAYOUT", id);
			return null;
		}
		if ("guid".equals(dataType))
		{
			return resolveContent(scope, id);
		}
		String channelId;
		try
		{
			channelId = "lumisPage".equals(dataType)
					? ManagerFactory.getPageManager().get(scope.getSession(), id, scope.getTransaction()).getChannelId()
					: ManagerFactory.getChannelManager().get(scope.getSession(), id, scope.getTransaction()).getId();
		}
		catch (PortalObjectNotFoundException e)
		{
			warn(scope, "STR_WARNING_MISSING_LINK", fieldId, id);
			return null;
		}
		ServiceInstanceConfig destination = ManagerFactory.getServiceInstanceManager().get(scope.getDestinationServiceInstanceId(), scope.getTransaction());
		IWebsite targetWebsite = ManagerFactoryInternal.getWebsiteManager().getWebsiteByChannelId(channelId);
		IWebsite destinationWebsite = ManagerFactoryInternal.getWebsiteManager().getWebsiteByChannelId(destination.getChannelId());
		String targetWebsiteId = targetWebsite == null ? null : targetWebsite.getId();
		String destinationWebsiteId = destinationWebsite == null ? null : destinationWebsite.getId();
		if (isDifferentWebsite(targetWebsiteId, destinationWebsiteId))
		{
			warn(scope, "STR_WARNING_LINK_WEBSITE", fieldId, id, targetWebsiteId);
		}
		return id;
	}

	/** Compares website identities, including a channel outside any configured website. */
	public static boolean isDifferentWebsite(String targetWebsiteId, String destinationWebsiteId)
	{
		return !Objects.equals(targetWebsiteId, destinationWebsiteId);
	}

	private static String resolveContent(MigrationScope scope, String id) throws Exception
	{
		try (PreparedStatement statement = scope.getConnection().prepareStatement("select serviceInstanceId from lum_Content where id = ?"))
		{
			statement.setString(1, id);
			try (ResultSet result = statement.executeQuery())
			{
				if (!result.next())
				{
					warn(scope, "STR_WARNING_MISSING_LINK", "linkContentId", id);
					return null;
				}
				if (!scope.getOriginServiceInstanceId().equals(result.getString(1)))
				{
					return id;
				}
			}
		}
		String mapped = ContentCopier.findMappedDestinationContentId(
				scope.getConnection(),
				scope.getOriginServiceInstanceId(),
				scope.getDestinationServiceInstanceId(),
				id);
		if (mapped != null)
		{
			return mapped;
		}
		warn(scope, "STR_WARNING_FORWARD_CONTENT_LINK", id);
		return id;
	}

	private static void warn(MigrationScope scope, String key, Object... arguments) throws Exception
	{
		ServiceConfig service = ManagerFactory.getServiceManager().get(
				scope.getSession(),
				"br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration",
				scope.getTransaction());
		String message = ManagerFactory.getLocalizationManager().localize(key, service.getResource(), scope.getSession().getLocale());
		scope.addWarning(MessageFormat.format(message, arguments));
	}
}
