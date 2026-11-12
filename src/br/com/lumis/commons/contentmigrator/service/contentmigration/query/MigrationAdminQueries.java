package br.com.lumis.commons.contentmigrator.service.contentmigration.query;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.contentmigration.validation.DependencyRules;
import lumis.portal.PortalException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.service.ServiceConfig;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.user.UserConfig;
import lumis.util.ITransaction;

/** Testable queries used by the content migration administration interface. */
public final class MigrationAdminQueries
{
	private static final List<String> REPOSITORY_SERVICE_IDS = Collections.unmodifiableList(
			Arrays.asList(
					"lumis.service.media",
					"lumis.service.document",
					"lumis.service.pagepersonalization",
					"lumis.service.tagmanager",
					"lumis.service.categorization"));

	private MigrationAdminQueries()
	{
	}

	/** Content services that have at least two instances the user may view. */
	public static List<Map<String, Object>> listContentServices(SessionConfig userSession, ITransaction transaction) throws PortalException
	{
		List<Map<String, Object>> result = new ArrayList<>();
		SessionConfig systemSession = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		try
		{
			for (ServiceConfig service : ManagerFactory.getServiceManager().getServices(systemSession, true, transaction))
			{
				if (!DependencyRules.SERVICE_TYPE_CONTENT.equals(service.getServiceType()) || REPOSITORY_SERVICE_IDS.contains(service.getId()))
				{
					continue;
				}

				int visibleInstances = 0;
				Collection<String> instanceIds = ManagerFactory.getServiceInstanceManager().getIdsByServiceId(systemSession, service.getId(), transaction);
				for (String instanceId : instanceIds)
				{
					if (hasPermission(userSession, instanceId, ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE, transaction))
					{
						visibleInstances++;
					}
					if (visibleInstances >= 2)
					{
						break;
					}
				}
				if (visibleInstances < 2)
				{
					continue;
				}

				Map<String, Object> row = new LinkedHashMap<>();
				row.put("serviceId", service.getId());
				row.put("serviceName", service.getName());
				row.put("visibleInstanceCount", visibleInstances);
				result.add(row);
			}
			result.sort((left, right) -> String.valueOf(left.get("serviceName")).compareToIgnoreCase(String.valueOf(right.get("serviceName"))));
			return result;
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(systemSession);
		}
	}

	/** Instances visible to the user; destination choices also require edit permission. */
	public static List<Map<String, Object>> listInstancesByService(
			SessionConfig userSession,
			String serviceId,
			String excludedServiceInstanceId,
			boolean destination,
			ITransaction transaction) throws PortalException
	{
		if (serviceId == null || serviceId.trim().isEmpty())
		{
			return Collections.emptyList();
		}

		List<Map<String, Object>> result = new ArrayList<>();
		SessionConfig systemSession = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		try
		{
			ServiceConfig selectedService = ManagerFactory.getServiceManager().get(systemSession, serviceId, transaction);
			if (!DependencyRules.SERVICE_TYPE_CONTENT.equals(selectedService.getServiceType()) || REPOSITORY_SERVICE_IDS.contains(serviceId))
			{
				return Collections.emptyList();
			}

			for (String instanceId : ManagerFactory.getServiceInstanceManager().getIdsByServiceId(systemSession, serviceId, transaction))
			{
				if (instanceId.equals(excludedServiceInstanceId))
				{
					continue;
				}
				String permission = destination
						? ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT
						: ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE;
				if (!hasPermission(userSession, instanceId, permission, transaction))
				{
					continue;
				}

				ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(instanceId, transaction);
				if (!serviceId.equals(instance.getServiceId()))
				{
					continue;
				}
				String channelPath = ManagerFactory.getChannelManager().getFriendlyPath(userSession, instance.getChannelId(), transaction);
				String displayName = channelPath == null || channelPath.isEmpty()
						? instance.getName()
						: instance.getName() + " (" + channelPath.replace("/", " / ") + ")";
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("serviceInstanceId", instance.getId());
				row.put("serviceId", instance.getServiceId());
				row.put("serviceInstanceName", displayName);
				result.add(row);
			}
			result.sort((left, right) -> String.valueOf(left.get("serviceInstanceName")).compareToIgnoreCase(String.valueOf(right.get("serviceInstanceName"))));
			return result;
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(systemSession);
		}
	}

	/** Migration list rows enriched with readable service and instance names. */
	public static List<Map<String, Object>> listMigrations(SessionConfig userSession, ITransaction transaction) throws Exception
	{
		Connection connection = ((ITransactionJdbc) transaction).getConnection();
		List<Map<String, Object>> result = new ArrayList<>();
		for (MigrationRow migration : MigrationDao.listMigrations(connection))
		{
			String originId = migration.getOriginServiceInstanceId();
			String destinationId = migration.getDestServiceInstanceId();
			if (!canManage(userSession, originId, destinationId, transaction))
			{
				continue;
			}

			Map<String, Object> row = toMap(migration);
			ServiceConfig service = ManagerFactory.getServiceManager().get(userSession, migration.getServiceId(), transaction);
			row.put("serviceName", service.getName());
			row.put("originName", instanceDisplayName(userSession, originId, transaction));
			row.put("destinationName", instanceDisplayName(userSession, destinationId, transaction));
			result.add(row);
		}
		return result;
	}

	/** Message and stack for an error dialog, with the same visibility checks as the list. */
	public static Map<String, Object> getMigrationError(SessionConfig userSession, String migrationId, ITransaction transaction) throws Exception
	{
		MigrationRow migration = MigrationDao.getMigration(((ITransactionJdbc) transaction).getConnection(), migrationId);
		if (migration == null)
		{
			return Collections.emptyMap();
		}
		if (!canManage(userSession, migration.getOriginServiceInstanceId(), migration.getDestServiceInstanceId(), transaction))
		{
			return Collections.emptyMap();
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("errorMessage", migration.getErrorMessage());
		result.put("errorStack", migration.getErrorStack());
		return result;
	}

	/** The columns of a migration row by name, in select order. */
	private static Map<String, Object> toMap(MigrationRow migration)
	{
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", migration.getId());
		row.put("name", migration.getName());
		row.put("serviceId", migration.getServiceId());
		row.put("originServiceInstanceId", migration.getOriginServiceInstanceId());
		row.put("destServiceInstanceId", migration.getDestServiceInstanceId());
		row.put("updateMode", migration.getUpdateMode());
		row.put("operation", migration.getOperation());
		row.put("status", migration.getStatus());
		row.put("stopRequested", migration.isStopRequested());
		row.put("totalCount", migration.getTotalCount());
		row.put("migratedCount", migration.getMigratedCount());
		row.put("heartbeat", migration.getHeartbeat());
		row.put("createdDate", migration.getCreatedDate());
		row.put("startDate", migration.getStartDate());
		row.put("endDate", migration.getEndDate());
		row.put("author", migration.getAuthor());
		row.put("errorType", migration.getErrorType());
		row.put("errorMessage", migration.getErrorMessage());
		row.put("errorStack", migration.getErrorStack());
		row.put("blockingOriginItemId", migration.getBlockingOriginItemId());
		row.put("blockingWidgetContentId", migration.getBlockingWidgetContentId());
		return row;
	}

	private static boolean canManage(SessionConfig session, String originId, String destinationId, ITransaction transaction) throws Exception
	{
		return hasPermission(session, originId, ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE, transaction)
				&& hasPermission(session, destinationId, ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT, transaction);
	}

	private static boolean hasPermission(SessionConfig session, String instanceId, String permission, ITransaction transaction) throws PortalException
	{
		return session != null && ManagerFactory.getServiceInstanceAclManager().checkServiceInstancePermission(session, instanceId, permission, transaction);
	}

	private static String instanceDisplayName(SessionConfig session, String instanceId, ITransaction transaction) throws PortalException
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(instanceId, transaction);
		String path = ManagerFactory.getChannelManager().getFriendlyPath(session, instance.getChannelId(), transaction);
		return path == null || path.isEmpty() ? instance.getName() : instance.getName() + " (" + path.replace("/", " / ") + ")";
	}
}
