package br.com.lumis.commons.contentmigrator.service.contentmigration.query;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.StatusAccessDeniedException;
import br.com.lumis.commons.contentmigrator.service.contentmigration.lock.GlobalLock;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.user.UserConfig;
import lumis.util.ITransaction;

/** Read-only status projection for the content migration REST endpoint. */
public final class MigrationStatusQueries
{
	public static final String ERROR_TYPE_INTERRUPTED = "INTERRUPTED";

	private MigrationStatusQueries()
	{
	}

	/**
	 * Lists status fields for migrations the user may administer. A running migration whose
	 * heartbeat is missing or older than the global lock timeout is reported as interrupted; this
	 * read does not change the migration row or lock.
	 *
	 * @throws StatusAccessDeniedException if the user is not logged in, or migrations exist but
	 *             none are visible to the user
	 */
	public static List<Map<String, Object>> listStatuses(SessionConfig session, ITransaction transaction) throws Exception
	{
		if (session == null || UserConfig.USER_GUEST_ID.equals(session.getUserId()))
		{
			throw new StatusAccessDeniedException();
		}

		Connection connection = ((ITransactionJdbc) transaction).getConnection();
		List<Map<String, Object>> result = new ArrayList<>();
		List<MigrationRow> migrations = MigrationDao.listMigrations(connection);
		for (MigrationRow migration : migrations)
		{
			String origin = migration.getOriginServiceInstanceId();
			String destination = migration.getDestServiceInstanceId();
			if (!canManage(session, origin, destination, transaction))
			{
				continue;
			}

			String status = migration.getStatus();
			String errorType = migration.getErrorType();
			if (MigrationDao.STATUS_RUNNING.equals(status) && isStale(migration.getHeartbeat()))
			{
				status = MigrationDao.STATUS_STOPPED_WITH_ERROR;
				errorType = ERROR_TYPE_INTERRUPTED;
			}

			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", migration.getId());
			item.put("status", status);
			item.put("operation", migration.getOperation());
			item.put("errorType", errorType);
			item.put("totalCount", migration.getTotalCount());
			item.put("migratedCount", migration.getMigratedCount());
			result.add(item);
		}

		if (!migrations.isEmpty() && result.isEmpty())
		{
			throw new StatusAccessDeniedException();
		}
		return result;
	}

	private static boolean isStale(Object heartbeat)
	{
		if (!(heartbeat instanceof Number))
		{
			return true;
		}
		return System.currentTimeMillis() - ((Number) heartbeat).longValue() > GlobalLock.STALE_MINUTES * 60_000L;
	}

	private static boolean canManage(SessionConfig session, String origin, String destination, ITransaction transaction) throws Exception
	{
		return ManagerFactory.getServiceInstanceAclManager()
				.checkServiceInstancePermission(session, origin, ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE, transaction)
				&& ManagerFactory.getServiceInstanceAclManager()
						.checkServiceInstancePermission(session, destination, ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT, transaction);
	}
}
