package br.com.lumis.commons.contentmigrator.service.contentmigration.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.MigrationApiException;
import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.MigrationBlockedException;
import br.com.lumis.commons.contentmigrator.service.contentmigration.validation.DependencyRules;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentVersion;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.serviceinstance.acl.IServiceInstanceAclManager;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.service.pagepersonalization.bean.Widget;

/** Read-only, permission-checked queries used by the widget mapping modal. */
public final class WidgetMappingQueries
{
	private static final String WIDGET_SOURCE_ID = "widget";

	private WidgetMappingQueries()
	{
	}

	/**
	 * Returns the blocked migration item, its widget and the linked destination widget repository.
	 * Only a migration stopped for a missing widget mapping can be opened.
	 */
	public static Map<String, Object> getBlockedMigration(SessionConfig session, String migrationId, ITransaction transaction) throws Exception
	{
		if (session == null || UserConfig.USER_GUEST_ID.equals(session.getUserId()))
		{
			throw forbidden();
		}

		MigrationRow migration = MigrationDao.getMigration(((ITransactionJdbc) transaction).getConnection(), migrationId);
		if (migration == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "migration is not waiting for a widget mapping");
		}

		String originInstanceId = migration.getOriginServiceInstanceId();
		String destinationInstanceId = migration.getDestServiceInstanceId();
		checkCanManageInstances(session, originInstanceId, destinationInstanceId, transaction);
		String destinationRepositoryId = DependencyRules
				.repository(session, destinationInstanceId, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, transaction);
		if (destinationRepositoryId == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "destination has no widget repository");
		}
		checkCanManageDestinationRepository(session, destinationRepositoryId, transaction);

		boolean isWaitingForWidgetMapping = MigrationDao.STATUS_STOPPED_WITH_ERROR.equals(migration.getStatus())
				&& MigrationBlockedException.ERROR_TYPE_WIDGET_MAPPING_REQUIRED.equals(migration.getErrorType());
		if (!isWaitingForWidgetMapping)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "migration is not waiting for a widget mapping");
		}

		String blockingItemId = migration.getBlockingOriginItemId();
		String originWidgetId = migration.getBlockingWidgetContentId();
		if (blockingItemId == null || originWidgetId == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "blocked migration is missing widget mapping details");
		}
		ContentVersion blockingVersion;
		try
		{
			blockingVersion = ContentManagerFactory.getContentManager().getContentVersionByItemId(blockingItemId);
		}
		catch (PortalObjectNotFoundException e)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "blocked origin content no longer exists");
		}
		if (!originInstanceId.equals(blockingVersion.getContentLocale().getContent().getServiceInstanceId()))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "blocked content does not belong to the migration origin");
		}

		Content originWidgetContent = getWidgetContent(originWidgetId);
		String originRepositoryId = DependencyRules.repository(session, originInstanceId, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, transaction);
		boolean isInOriginRepository = originWidgetContent != null && originRepositoryId != null
				&& originRepositoryId.equals(originWidgetContent.getServiceInstanceId());
		if (!isInOriginRepository)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "blocked widget does not belong to the migration origin repository");
		}

		ContentVersion originWidgetVersion = activeWidgetVersion(originWidgetContent);
		Widget widget = originWidgetVersion == null ? null : ManagerFactory.getEntityManager().find(Widget.class, originWidgetVersion.getItemId());
		if (widget == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "blocked origin widget no longer exists");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("migrationId", migrationId);
		result.put("title", blockingVersion.getPrimaryName());
		result.put("introduction", blockingVersion.getIntroduction());
		result.put("blockingOriginItemId", blockingItemId);
		result.put("originWidgetContentId", originWidgetId);
		result.put("originWidgetName", widget.getName());
		result.put("destServiceInstanceId", destinationInstanceId);
		result.put("destRepoInstanceId", destinationRepositoryId);
		return result;
	}

	/** Lists active widgets in the widget repository linked to this migration's destination. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> listDestinationWidgets(SessionConfig session, String migrationId, ITransaction transaction) throws Exception
	{
		Map<String, Object> blocked = getBlockedMigration(session, migrationId, transaction);
		String repositoryId = (String) blocked.get("destRepoInstanceId");
		// @formatter:off
		List<Object[]> rows = ManagerFactory.getEntityManager()
				.createQuery("select c.id, w.name from ContentVersion cv inner join cv.contentLocale cl "
						+ "inner join cl.content c, Widget w where cv.itemId = w.id "
						+ "and cl.activeVersion.id = cv.id and c.serviceInstanceId = :repository "
						+ "and c.sourceId = :sourceId order by w.name, c.id")
				.setParameter("repository", repositoryId).setParameter("sourceId", WIDGET_SOURCE_ID).getResultList();
		// @formatter:on

		List<Map<String, Object>> result = new ArrayList<>();
		for (Object[] row : rows)
		{
			Map<String, Object> widget = new LinkedHashMap<>();
			widget.put("widgetContentId", row[0]);
			widget.put("widgetName", row[1]);
			result.add(widget);
		}
		return result.isEmpty() ? Collections.emptyList() : result;
	}

	/** Validates that the user can view the origin and edit both destination service and repository. */
	public static void checkCanManage(
			SessionConfig session,
			String originInstanceId,
			String destinationInstanceId,
			String destinationRepositoryId,
			ITransaction transaction) throws Exception
	{
		checkCanManageInstances(session, originInstanceId, destinationInstanceId, transaction);
		checkCanManageDestinationRepository(session, destinationRepositoryId, transaction);
	}

	private static void checkCanManageInstances(SessionConfig session, String originInstanceId, String destinationInstanceId, ITransaction transaction)
			throws Exception
	{
		if (session == null || UserConfig.USER_GUEST_ID.equals(session.getUserId()))
		{
			throw forbidden();
		}

		IServiceInstanceAclManager aclManager = ManagerFactory.getServiceInstanceAclManager();
		boolean canViewOrigin = aclManager
				.checkServiceInstancePermission(session, originInstanceId, ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE, transaction);
		boolean canEditDestination = canViewOrigin && aclManager.checkServiceInstancePermission(
				session,
				destinationInstanceId,
				ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT,
				transaction);
		if (!canEditDestination)
		{
			throw forbidden();
		}
	}

	private static void checkCanManageDestinationRepository(SessionConfig session, String destinationRepositoryId, ITransaction transaction) throws Exception
	{
		if (session == null || UserConfig.USER_GUEST_ID.equals(session.getUserId()))
		{
			throw forbidden();
		}

		boolean canEditDestinationRepository = ManagerFactory.getServiceInstanceAclManager().checkServiceInstancePermission(
				session,
				destinationRepositoryId,
				ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT,
				transaction);
		if (!canEditDestinationRepository)
		{
			throw forbidden();
		}
	}

	/** Returns a widget content only if it uses the widget source. */
	public static Content getWidgetContent(String widgetContentId) throws Exception
	{
		if (widgetContentId == null)
		{
			return null;
		}
		try
		{
			Content content = ContentManagerFactory.getContentManager().getContent(widgetContentId);
			return WIDGET_SOURCE_ID.equals(content.getSourceId()) ? content : null;
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	public static ContentVersion activeWidgetVersion(Content widgetContent)
	{
		if (widgetContent == null || widgetContent.getContentLocales().isEmpty())
		{
			return null;
		}
		return widgetContent.getContentLocales().iterator().next().getActiveVersion();
	}

	private static MigrationApiException forbidden()
	{
		return new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "user cannot manage this widget mapping");
	}
}
