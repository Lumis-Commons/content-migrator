package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.SelectionDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.security.MigrationPermissions;
import lumis.content.ContentManagerFactory;
import lumis.doui.source.IDataProvider;
import lumis.doui.source.TabularSource;
import lumis.doui.source.field.ITabularSourceField;
import lumis.portal.PortalException;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.UnexpectedException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.user.UserConfig;
import lumis.util.ITransaction;

/** Testable queries used by the administration interface of the hierarchical content migration. */
public final class MigrationAdminQueries
{
	private MigrationAdminQueries()
	{
	}

	/**
	 * Instances of {@code serviceId} visible to the user, without the excluded one; destination
	 * choices also require edit permission. The form passes the hierarchical content service, already
	 * validated as a content service by {@code MigrationApi} when the migration is created.
	 */
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

	/** Migration list rows enriched with readable instance names and warning and mark counts. */
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
			row.put("warningCount", countWarnings(connection, migration));
			row.put("markCount", SelectionDao.listContentIds(connection, migration.getId()).size());
			row.put("originName", instanceDisplayName(userSession, originId, transaction));
			row.put("destinationName", instanceDisplayName(userSession, destinationId, transaction));
			result.add(row);
		}
		return result;
	}

	/** Loads the edit form without exposing a migration's scope to non-administrators. */
	public static class SelectionFormDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
	{
		@Override
		public void loadData(SessionConfig session, TabularSource<ITabularSourceField> source, ITransaction transaction) throws PortalException
		{
			try
			{
				MigrationPermissions.requirePortalAdministrator(session.getUserId(), (lumis.portal.transaction.ITransaction) transaction);
				Connection connection = ((ITransactionJdbc) transaction).getConnection();
				String migrationId = (String) source.getParameterValue("migrationId");
				if (migrationId == null)
				{
					migrationId = source.getDouiContext().getRequest().getParameter("migrationId");
				}
				MigrationRow migration = MigrationDao.getMigration(connection, migrationId);
				if (migration == null)
				{
					return;
				}
				var row = source.getData().addRow();
				row.put("migrationId", migrationId);
				row.put("originServiceInstanceId", migration.getOriginServiceInstanceId());
				row.put("selectedContentIds", String.join(",", SelectionDao.listContentIds(connection, migrationId)));
			}
			catch (Exception exception)
			{
				if (exception instanceof PortalException)
				{
					throw (PortalException) exception;
				}
				throw new UnexpectedException(exception);
			}
		}
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

	private static final String WARNING_PREDICATE = " from lum_cmig_HMigItemMap where kind = 'CONTENT' "
			+ "and originServiceInstanceId = ? and destServiceInstanceId = ? "
			+ "and warnings is not null and trim(warnings) <> ''";

	private static int countWarnings(Connection connection, MigrationRow migration) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement("select count(*)" + WARNING_PREDICATE))
		{
			statement.setString(1, migration.getOriginServiceInstanceId());
			statement.setString(2, migration.getDestServiceInstanceId());
			try (ResultSet rows = statement.executeQuery())
			{
				rows.next();
				return rows.getInt(1);
			}
		}
	}

	/** Lists item warnings for the migration's pair, using the administration visibility rule. */
	public static List<Map<String, Object>> listWarnings(SessionConfig session, String migrationId, ITransaction transaction) throws Exception
	{
		Connection connection = ((ITransactionJdbc) transaction).getConnection();
		MigrationRow migration = MigrationDao.getMigration(connection, migrationId);
		if (migration == null || !canManage(session, migration.getOriginServiceInstanceId(), migration.getDestServiceInstanceId(), transaction))
		{
			return Collections.emptyList();
		}
		List<Map<String, Object>> result = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("select id, originItemId, warnings" + WARNING_PREDICATE + " order by originItemId, id"))
		{
			statement.setString(1, migration.getOriginServiceInstanceId());
			statement.setString(2, migration.getDestServiceInstanceId());
			try (ResultSet rows = statement.executeQuery())
			{
				while (rows.next())
				{
					String itemId = rows.getString("originItemId");
					String title = itemId;
					try
					{
						title = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId).getPrimaryName();
					}
					catch (PortalObjectNotFoundException exception)
					{
						// Retain the warning even if the origin version has since been deleted.
					}
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", rows.getString("id"));
					row.put("title", title);
					row.put("warnings", rows.getString("warnings"));
					result.add(row);
				}
			}
		}
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
