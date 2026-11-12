package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.api;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.config.ExecutionSettings;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.config.HierarchicalContentService;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.ContentMigrationCoexistenceDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.ItemMapDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.RepositoryMapDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.SelectionDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.WidgetMapDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RepositoryMapRow;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RepositoryPair;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.WidgetMapRow;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationBlockedException;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.RulesViolatedException;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.execution.MigrationExecutor;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.execution.MigrationTask;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.execution.RunStatusUpdater;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.HierarchyQueries;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.HierarchyTree;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.MigrationSelection;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.lock.GlobalLock;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.security.MigrationPermissions;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.undo.MigrationUndoer;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.validation.DependencyRules;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentVersion;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.service.ServiceConfig;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;
import lumis.portal.user.UserConfig;
import lumis.service.pagepersonalization.bean.Widget;
import lumis.util.PortalUtil;

/**
 * Facade of the hierarchical content migration service, called by the administration process
 * actions and, by reflection, by the tests: public static methods whose parameters and results are only
 * {@link String}, {@code boolean} and {@link Map} (values are String, Boolean or nested Map; dates
 * are milliseconds since the epoch). Each call runs in its own committed transaction. The write
 * operations check the permissions of the user; {@link #get(String)}, {@link #list()} and
 * {@link #awaitTermination(String, String)} do not, so they serve trusted callers, and the
 * administration reads migrations through {@code MigrationAdminQueries}, which filters them.
 */
public class MigrationApi
{
	/** Service type of the DOUI (non-content) services. */
	private static final String DOUI_SERVICE_TYPE = "lum_doui";

	/** Services that are repositories of other contents and cannot be migrated on their own. */
	private static final Set<String> REPOSITORY_SERVICE_IDS = new HashSet<>(
			Arrays.asList(
					"lumis.service.media",
					"lumis.service.document",
					"lumis.service.pagepersonalization",
					"lumis.service.tagmanager",
					"lumis.service.categorization"));

	/**
	 * Creates a migration in STOPPED status after validating the instance pair, user permissions,
	 * and dependency rules, and registers its shared repository mappings.
	 *
	 * @param params
	 *            name, originServiceInstanceId, destServiceInstanceId, selectedContentIds (the ids
	 *            of the contents to migrate, separated by commas; their descendants are migrated
	 *            too), and optional updateMode ({@code ADD_ONLY}, the default, or {@code UPDATE}).
	 *            Both instances must be of the service {@value HierarchicalContentService#SERVICE_ID}.
	 * @return the created migration, in the same format returned by {@link #get(String)}.
	 */
	public static Map<String, Object> create(Map<String, Object> params)
	{
		String name = requireParameter(params, "name");
		String originInstanceId = requireParameter(params, "originServiceInstanceId");
		String destinationInstanceId = requireParameter(params, "destServiceInstanceId");
		List<String> requestedContentIds = readContentIds(requireParameter(params, "selectedContentIds"));
		String requestedUpdateMode = readUpdateMode(params);
		String updateMode = requestedUpdateMode == null ? "ADD_ONLY" : requestedUpdateMode;
		String author = getCurrentUserId();

		if (originInstanceId.equals(destinationInstanceId))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_SAME_INSTANCE, "origin and destination are the same instance");
		}

		return runInTransaction((connection, session, transaction) ->
		{
			MigrationPermissions.requirePortalAdministrator(author, transaction);
			ServiceInstanceConfig originInstance = loadInstance(originInstanceId, transaction);
			ServiceInstanceConfig destinationInstance = loadInstance(destinationInstanceId, transaction);
			validateServiceInstancesForCreation(session, originInstance, destinationInstance, transaction);
			rejectDuplicatePair(connection, originInstanceId, destinationInstanceId);
			requireCreatePermission(author, originInstanceId, destinationInstanceId, transaction);
			if (hasContentMigrationConflict(connection, originInstanceId, destinationInstanceId, false))
			{
				throw contentMigrationConflict(session, transaction);
			}
			checkCreationRules(session, originInstanceId, destinationInstanceId, transaction);

			MigrationRow migration = new MigrationRow();
			migration.setName(name);
			migration.setServiceId(originInstance.getServiceId());
			migration.setOriginServiceInstanceId(originInstanceId);
			migration.setDestServiceInstanceId(destinationInstanceId);
			migration.setUpdateMode(updateMode);
			migration.setAuthor(author);
			MigrationSelection selection = selectContents(connection, originInstanceId, requestedContentIds);
			String migrationId = insertStoppedMigration(connection, migration, selection.getLocaleCount());
			SelectionDao.insertAll(connection, migrationId, selection.getMarkedContentIds());
			saveRepositoryMappings(connection, session, originInstanceId, destinationInstanceId, transaction);
			GlobalLock.ensureLockRow(connection);
			return toResult(MigrationDao.getMigration(connection, migrationId));
		});
	}

	/**
	 * Replaces the minimal marks while holding the migration row lock. Removed contents remain
	 * at the destination and in the pair's maps, so undo still removes them. An interrupted
	 * RUNNING migration must be restarted and stopped before its selection can be changed.
	 */
	public static Map<String, Object> updateSelection(Map<String, Object> params)
	{
		String userId = getCurrentUserId();
		return runInTransaction((connection, session, transaction) ->
		{
			MigrationPermissions.requirePortalAdministrator(userId, transaction);
			String migrationId = requireParameter(params, "migrationId");
			if (!MigrationDao.lockStoppedMigration(connection, migrationId))
			{
				requireMigration(connection, migrationId);
				throw new MigrationApiException(MigrationApiException.ERROR_LOCKED, "a running migration cannot change selection");
			}
			MigrationRow migration = requireMigration(connection, migrationId);
			Object submitted = params.get("selectedContentIds");
			String ids = submitted instanceof String ? (String) submitted : "";
			MigrationSelection selection = selectContents(connection, migration.getOriginServiceInstanceId(), readContentIds(ids));
			SelectionDao.deleteByMigration(connection, migrationId);
			SelectionDao.insertAll(connection, migrationId, selection.getMarkedContentIds());
			MigrationDao.updateSelectionCount(connection, migrationId, selection.getLocaleCount());
			return toResult(requireMigration(connection, migrationId));
		});
	}

	/**
	 * Whether the original contentmigration owns the pair or, when {@code starting}, has a recent run
	 * writing to the destination.
	 */
	private static boolean hasContentMigrationConflict(Connection connection, String originId, String destinationId, boolean starting) throws Exception
	{
		if (ContentMigrationCoexistenceDao.hasPair(connection, originId, destinationId))
		{
			return true;
		}
		return starting && ContentMigrationCoexistenceDao.hasActiveDestination(connection, destinationId, GlobalLock.staleLimit(connection));
	}

	private static MigrationApiException contentMigrationConflict(SessionConfig session, ITransaction transaction) throws Exception
	{
		ServiceConfig service = ManagerFactory.getServiceManager().get(session, "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration", transaction);
		String message = ManagerFactory.getLocalizationManager().localize("STR_CONTENTMIGRATION_CONFLICT", service.getResource(), session.getLocale());
		return new MigrationApiException(MigrationApiException.ERROR_CONTENTMIGRATION_CONFLICT, message);
	}

	private static List<String> readContentIds(String commaSeparated)
	{
		int maximum = ExecutionSettings.maxSelection();
		if (commaSeparated.length() > maximum * 33L)
		{
			throw new MigrationApiException("SELECTION_TOO_LARGE", "selectedContentIds exceeds the selection limit");
		}
		int count = 1;
		for (int index = 0; index < commaSeparated.length(); index++)
		{
			if (commaSeparated.charAt(index) == ',' && ++count > maximum)
			{
				throw new MigrationApiException("SELECTION_TOO_LARGE", "too many submitted content ids");
			}
		}
		Set<String> ids = new LinkedHashSet<>();
		for (String id : commaSeparated.split(","))
		{
			if (!id.trim().isEmpty())
			{
				ids.add(id.trim());
			}
		}
		if (ids.isEmpty())
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NO_SELECTION, "at least one content must be selected");
		}
		return new ArrayList<>(ids);
	}

	/**
	 * Checks that every requested content is in the origin instance and keeps only the ones that no
	 * other requested content already covers, since all descendants of a marked content are migrated.
	 */
	private static MigrationSelection selectContents(Connection connection, String originInstanceId, List<String> requestedContentIds) throws Exception
	{
		HierarchyTree tree = HierarchyQueries.loadTree(connection, originInstanceId);
		for (String contentId : requestedContentIds)
		{
			if (!tree.contains(contentId))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_INVALID_SELECTION, "content " + contentId + " is not in the origin instance");
			}
		}
		Set<String> minimal = tree.minimalSelection(requestedContentIds);
		if (minimal.isEmpty())
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NO_SELECTION, "at least one content must be selected");
		}
		return MigrationSelection.of(connection, originInstanceId, tree, minimal);
	}

	private static String requireParameter(Map<String, Object> params, String key)
	{
		Object value = params == null ? null : params.get(key);
		if (!(value instanceof String) || ((String) value).trim().isEmpty())
		{
			throw new MigrationApiException(MigrationApiException.ERROR_REQUIRED, key + " is required");
		}
		return (String) value;
	}

	private static String readUpdateMode(Map<String, Object> params)
	{
		Object updateMode = params.get("updateMode");
		boolean isSupportedMode = "ADD_ONLY".equals(updateMode) || "UPDATE".equals(updateMode);
		if (updateMode != null && !isSupportedMode)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_UPDATE_MODE, "updateMode must be ADD_ONLY or UPDATE");
		}
		return (String) updateMode;
	}

	private static String getCurrentUserId()
	{
		try
		{
			SessionConfig session = SessionConfig.getCurrentSessionConfig();
			String userId = session == null ? null : session.getUserId();
			return userId == null ? UserConfig.USER_SYSTEM_ID : userId;
		}
		catch (Exception exception)
		{
			throw new IllegalStateException(exception);
		}
	}

	private static <T> T runInTransaction(Work<T> work)
	{
		try
		{
			SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
			// try-with-resources keeps the error of the work when the rollback in close() also fails
			try (ITransaction transaction = PortalTransactionFactory.createTransaction())
			{
				transaction.begin();
				T result = work.run(((ITransactionJdbc) transaction).getConnection(), session, transaction);
				transaction.commit();
				return result;
			}
			finally
			{
				ManagerFactory.getAuthenticationManager().endImpersonation(session);
			}
		}
		catch (RuntimeException exception)
		{
			throw exception;
		}
		catch (Exception exception)
		{
			throw new IllegalStateException(exception);
		}
	}

	private static ServiceInstanceConfig loadInstance(String instanceId, ITransaction transaction) throws Exception
	{
		try
		{
			return ManagerFactory.getServiceInstanceManager().get(instanceId, transaction);
		}
		catch (PortalObjectNotFoundException exception)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INSTANCE_NOT_FOUND, "service instance " + instanceId);
		}
	}

	private static void validateServiceInstancesForCreation(
			SessionConfig session,
			ServiceInstanceConfig originInstance,
			ServiceInstanceConfig destinationInstance,
			ITransaction transaction) throws Exception
	{
		for (ServiceInstanceConfig instance : Arrays.asList(originInstance, destinationInstance))
		{
			validateContentServiceInstance(session, instance, transaction);
		}
		if (!originInstance.getServiceId().equals(destinationInstance.getServiceId()))
		{
			throw new MigrationApiException(
					MigrationApiException.ERROR_DIFFERENT_SERVICES,
					originInstance.getServiceId() + " differs from " + destinationInstance.getServiceId());
		}
		if (!HierarchicalContentService.SERVICE_ID.equals(originInstance.getServiceId()))
		{
			throw new MigrationApiException(
					MigrationApiException.ERROR_NOT_HIERARCHICAL,
					"only instances of " + HierarchicalContentService.SERVICE_ID + " can be migrated, not " + originInstance.getServiceId());
		}
	}

	private static void validateContentServiceInstance(SessionConfig session, ServiceInstanceConfig instance, ITransaction transaction) throws Exception
	{
		ServiceConfig service = ManagerFactory.getServiceManager().get(session, instance.getServiceId(), transaction);
		if (DOUI_SERVICE_TYPE.equals(service.getServiceType()))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_DOUI_SERVICE, "DOUI service " + instance.getServiceId() + " cannot be migrated");
		}
		if (!DependencyRules.SERVICE_TYPE_CONTENT.equals(service.getServiceType()))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_UNSUPPORTED_SERVICE, "service " + instance.getServiceId() + " is not a content service");
		}
		if (REPOSITORY_SERVICE_IDS.contains(instance.getServiceId()))
		{
			throw new MigrationApiException(
					MigrationApiException.ERROR_REPOSITORY_SERVICE,
					"repository service " + instance.getServiceId() + " cannot be migrated on its own");
		}
	}

	private static void rejectDuplicatePair(Connection connection, String originInstanceId, String destinationInstanceId) throws Exception
	{
		if (MigrationDao.getMigrationByPair(connection, originInstanceId, destinationInstanceId) != null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_DUPLICATE_PAIR, "the pair already has a migration");
		}
	}

	private static void requireCreatePermission(String author, String originInstanceId, String destinationInstanceId, ITransaction transaction) throws Exception
	{
		if (!canViewOriginAndEditDestination(author, originInstanceId, destinationInstanceId, transaction))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "user " + author + " cannot create a migration for this pair");
		}
	}

	private static void checkCreationRules(SessionConfig session, String originInstanceId, String destinationInstanceId, ITransaction transaction) throws Exception
	{
		try
		{
			DependencyRules.check(session, originInstanceId, destinationInstanceId, transaction);
		}
		catch (RulesViolatedException exception)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_RULES_VIOLATED, exception.getMessage());
		}
	}

	private static boolean canViewOriginAndEditDestination(String userId, String originInstanceId, String destinationInstanceId, ITransaction transaction)
			throws Exception
	{
		return MigrationPermissions.canViewOrigin(userId, originInstanceId, transaction)
				&& MigrationPermissions.canEditDestination(userId, destinationInstanceId, transaction);
	}

	/**
	 * Inserts the migration filled by {@link #create(Map)} with name, service, instances, update
	 * mode, and author, completing its id, operation, status, and counters.
	 *
	 * @param totalCount
	 *            the number of items the selected contents give.
	 * @return the id of the inserted migration.
	 */
	private static String insertStoppedMigration(Connection connection, MigrationRow migration, int totalCount) throws Exception
	{
		String id = PortalUtil.generateNewGuid();
		migration.setId(id);
		migration.setOperation(MigrationDao.OPERATION_MIGRATE);
		migration.setStatus(MigrationDao.STATUS_STOPPED);
		migration.setTotalCount((long) totalCount);
		migration.setMigratedCount(0L);
		MigrationDao.insertMigration(connection, migration);
		return id;
	}

	private static void saveRepositoryMappings(
			Connection connection,
			SessionConfig session,
			String originInstanceId,
			String destinationInstanceId,
			ITransaction transaction) throws Exception
	{
		for (String dependencyType : DependencyRules.REQUIRED_REPOSITORY_TYPES)
		{
			String originRepository = DependencyRules.repository(session, originInstanceId, dependencyType, transaction);
			String destinationRepository = DependencyRules.repository(session, destinationInstanceId, dependencyType, transaction);
			if (originRepository != null && destinationRepository != null)
			{
				RepositoryMapRow repositoryMap = new RepositoryMapRow();
				repositoryMap.setId(PortalUtil.generateNewGuid());
				repositoryMap.setOriginRepoInstanceId(originRepository);
				repositoryMap.setDestRepoInstanceId(destinationRepository);
				repositoryMap.setDependencyType(dependencyType);
				RepositoryMapDao.saveRepoMap(connection, repositoryMap);
			}
		}
	}

	private static Map<String, Object> toResult(MigrationRow row)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", row.getId());
		result.put("name", row.getName());
		result.put("serviceId", row.getServiceId());
		result.put("originServiceInstanceId", row.getOriginServiceInstanceId());
		result.put("destServiceInstanceId", row.getDestServiceInstanceId());
		result.put("updateMode", row.getUpdateMode());
		result.put("operation", row.getOperation());
		result.put("status", row.getStatus());
		result.put("stopRequested", row.isStopRequested());
		result.put("totalCount", formatValueAsString(row.getTotalCount()));
		result.put("migratedCount", formatValueAsString(row.getMigratedCount()));
		result.put("heartbeat", formatValueAsString(row.getHeartbeat()));
		result.put("createdDate", formatValueAsString(row.getCreatedDate()));
		result.put("startDate", formatValueAsString(row.getStartDate()));
		result.put("endDate", formatValueAsString(row.getEndDate()));
		result.put("author", row.getAuthor());
		result.put("errorType", row.getErrorType());
		result.put("errorMessage", row.getErrorMessage());
		result.put("errorStack", row.getErrorStack());
		result.put("blockingOriginItemId", row.getBlockingOriginItemId());
		result.put("blockingWidgetContentId", row.getBlockingWidgetContentId());
		return result;
	}

	private static String formatValueAsString(Object value)
	{
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * Returns the migration with the given id as a String-keyed map.
	 *
	 * @throws MigrationApiException
	 *             with {@link MigrationApiException#ERROR_NOT_FOUND} if it does not exist.
	 */
	public static Map<String, Object> get(String migrationId)
	{
		return runInTransaction((connection, session, transaction) -> toResult(requireMigration(connection, migrationId)));
	}

	private static MigrationRow requireMigration(Connection connection, String migrationId) throws Exception
	{
		MigrationRow migration = MigrationDao.getMigration(connection, migrationId);
		if (migration == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "migration " + migrationId);
		}
		return migration;
	}

	/**
	 * Lists all migrations in creation order. The result map has {@code count} and {@code items},
	 * a map from position ("0", "1", ...) to each migration map.
	 */
	public static Map<String, Object> list()
	{
		return runInTransaction((connection, session, transaction) ->
		{
			List<MigrationRow> rows = MigrationDao.listMigrations(connection);
			Map<String, Object> items = new LinkedHashMap<>();
			for (int index = 0; index < rows.size(); index++)
			{
				items.put(String.valueOf(index), toResult(rows.get(index)));
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("count", String.valueOf(rows.size()));
			result.put("items", items);
			return result;
		});
	}

	/**
	 * Starts a migration in the background under the global lock so that only one execution runs
	 * at a time across the portal.
	 *
	 * @param params
	 *            migrationId, optional updateMode (keeps current if omitted), and optional userId
	 *            (defaults to the current session user or the system user).
	 * @return the migration in RUNNING status.
	 * @throws MigrationApiException
	 *             with {@link MigrationApiException#ERROR_FORBIDDEN} if the user lacks permission,
	 *             or {@link MigrationApiException#ERROR_LOCKED} if another execution is running.
	 */
	public static Map<String, Object> start(Map<String, Object> params)
	{
		String migrationId = requireParameter(params, "migrationId");
		String updateMode = readUpdateMode(params);
		String startUserId = resolveUserId(params);
		String runId = PortalUtil.generateNewGuid();

		// The task is created before the commit, so that a failure while creating it rolls back the lock.
		MigrationTask task = runInTransaction((connection, session, transaction) ->
		{
			MigrationRow migration = requireMigration(connection, migrationId);
			String originInstanceId = migration.getOriginServiceInstanceId();
			String destinationInstanceId = migration.getDestServiceInstanceId();
			if (!canViewOriginAndEditDestination(startUserId, originInstanceId, destinationInstanceId, transaction))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "user " + startUserId + " cannot edit the contents of " + destinationInstanceId);
			}
			if (!GlobalLock.acquire(connection, runId, migrationId))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_LOCKED, "another migration is running");
			}
			if (hasContentMigrationConflict(connection, originInstanceId, destinationInstanceId, true))
			{
				throw contentMigrationConflict(session, transaction);
			}
			int totalCount = MigrationSelection.load(connection, migrationId, originInstanceId).getLocaleCount();
			if (totalCount == 0)
			{
				throw new MigrationApiException(MigrationApiException.ERROR_NO_SELECTION, "the selected hierarchy is empty");
			}
			RunStatusUpdater.markRunning(connection, migrationId, runId, updateMode, totalCount);
			return new MigrationTask(MigrationDao.getMigration(connection, migrationId), runId, startUserId);
		});

		MigrationExecutor.submit(task);
		return get(migrationId);
	}

	private static String resolveUserId(Map<String, Object> params)
	{
		Object candidate = params.get("userId");
		String userId = candidate instanceof String ? (String) candidate : null;
		if (userId == null || userId.trim().isEmpty())
		{
			return getCurrentUserId();
		}
		return userId;
	}

	/**
	 * Records a stop request for a running migration. Its background run honors the request after
	 * the current item, records STOPPED status, and releases the global lock; a migration that is
	 * not running is left unchanged.
	 *
	 * @return the migration state after recording the stop request.
	 */
	public static Map<String, Object> stop(String migrationId)
	{
		String userId = getCurrentUserId();
		return runInTransaction((connection, session, transaction) ->
		{
			MigrationRow migration = requireMigration(connection, migrationId);
			String originInstanceId = migration.getOriginServiceInstanceId();
			String destinationInstanceId = migration.getDestServiceInstanceId();
			if (!canViewOriginAndEditDestination(userId, originInstanceId, destinationInstanceId, transaction))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "user " + userId + " cannot stop this migration");
			}
			RunStatusUpdater.requestStop(connection, migrationId);
			return toResult(MigrationDao.getMigration(connection, migrationId));
		});
	}

	/**
	 * Deletes a migration that is not running and its content maps. Shared repository maps are
	 * retained until the last migration using each pair is deleted; destination content is never
	 * deleted here.
	 *
	 * @throws MigrationApiException
	 *             with {@link MigrationApiException#ERROR_LOCKED} while the migration is running.
	 */
	public static Map<String, Object> delete(String migrationId)
	{
		String userId = getCurrentUserId();
		return runInTransaction((connection, session, transaction) ->
		{
			MigrationPermissions.requirePortalAdministrator(userId, transaction);
			MigrationRow migration = requireMigration(connection, migrationId);
			String originInstanceId = migration.getOriginServiceInstanceId();
			String destinationInstanceId = migration.getDestServiceInstanceId();
			lockStoppedMigrationForDeletion(connection, migrationId);

			List<RepositoryPair> repositoryPairs = MigrationUndoer.repositoryPairs(session, transaction, originInstanceId, destinationInstanceId);
			ItemMapDao.deleteContentItemMaps(connection, originInstanceId, destinationInstanceId);
			SelectionDao.deleteByMigration(connection, migrationId);
			MigrationDao.deleteMigration(connection, migrationId);
			deleteUnusedRepositoryMaps(connection, session, transaction, migrationId, repositoryPairs);

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", migrationId);
			result.put("deleted", Boolean.TRUE);
			return result;
		});
	}

	private static void lockStoppedMigrationForDeletion(Connection connection, String migrationId) throws Exception
	{
		if (MigrationDao.lockStoppedMigration(connection, migrationId))
		{
			return;
		}
		requireMigration(connection, migrationId);
		throw new MigrationApiException(MigrationApiException.ERROR_LOCKED, "a running migration cannot be deleted");
	}

	private static void deleteUnusedRepositoryMaps(
			Connection connection,
			SessionConfig session,
			ITransaction transaction,
			String migrationId,
			List<RepositoryPair> repositoryPairs) throws Exception
	{
		for (RepositoryPair pair : repositoryPairs)
		{
			if (MigrationUndoer.isRepositoryPairUsedByAnother(connection, session, transaction, migrationId, pair))
			{
				continue;
			}
			ItemMapDao.deleteRepositoryItemMaps(connection, pair.getOriginRepositoryId(), pair.getDestinationRepositoryId());
			if (ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY.equals(pair.getDependencyType()))
			{
				WidgetMapDao.deleteWidgetMaps(connection, pair.getOriginRepositoryId(), pair.getDestinationRepositoryId());
			}
			RepositoryMapDao.deleteRepoMap(connection, pair.getOriginRepositoryId(), pair.getDestinationRepositoryId());
		}
	}

	/**
	 * Starts an undo operation in the background under the same global lock used by migrations.
	 */
	public static Map<String, Object> undo(Map<String, Object> params)
	{
		String migrationId = requireParameter(params, "migrationId");
		String undoUserId = resolveUserId(params);
		String runId = PortalUtil.generateNewGuid();

		MigrationTask task = runInTransaction((connection, session, transaction) ->
		{
			MigrationPermissions.requirePortalAdministrator(undoUserId, transaction);
			MigrationRow migration = requireMigration(connection, migrationId);
			String originInstanceId = migration.getOriginServiceInstanceId();
			String destinationInstanceId = migration.getDestServiceInstanceId();
			if (!GlobalLock.acquire(connection, runId, migrationId))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_LOCKED, "another migration or undo is running");
			}
			int totalCount = ItemMapDao.countContentItemMaps(connection, originInstanceId, destinationInstanceId);
			RunStatusUpdater.markUndoRunning(connection, migrationId, runId, totalCount);
			return new MigrationTask(MigrationDao.getMigration(connection, migrationId), runId, undoUserId);
		});

		MigrationExecutor.submit(task);
		return get(migrationId);
	}

	/**
	 * Waits for the background execution of the migration running in this JVM, if any, to end.
	 *
	 * @param timeoutMillis
	 *            maximum time to wait, in milliseconds.
	 * @return the migration map plus {@code terminated} (Boolean), false if the timeout elapsed first.
	 */
	public static Map<String, Object> awaitTermination(String migrationId, String timeoutMillis)
	{
		boolean hasTerminated;
		try
		{
			hasTerminated = MigrationExecutor.awaitTermination(migrationId, Long.parseLong(timeoutMillis));
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
		Map<String, Object> result = get(migrationId);
		result.put("terminated", hasTerminated);
		return result;
	}

	/**
	 * Saves the mapping of an origin widget to a destination widget for a destination repository,
	 * replacing any existing mapping for the same origin widget and repository.
	 *
	 * @param params
	 *            originWidgetContentId, destRepoInstanceId, destWidgetContentId, and optional
	 *            migrationId when resolving a blocked migration.
	 * @return the saved mapping.
	 */
	public static Map<String, Object> saveWidgetMapping(Map<String, Object> params)
	{
		String requestedOriginWidget = readOptionalParameter(params, "originWidgetContentId");
		String requestedDestinationRepository = readOptionalParameter(params, "destRepoInstanceId");
		String destinationWidget = requireParameter(params, "destWidgetContentId");
		String migrationId = readOptionalParameter(params, "migrationId");
		String requestingUserId = getCurrentUserId();
		WidgetMappingRequest request = new WidgetMappingRequest(migrationId, requestedOriginWidget, requestedDestinationRepository, requestingUserId);

		return runInTransaction((connection, session, transaction) ->
		{
			WidgetMappingTarget target = migrationId != null
					? resolveBlockedMigrationWidgetTarget(connection, session, transaction, request)
					: resolveDirectWidgetTarget(params, session, transaction, requestingUserId);
			validateWidgetRepositories(session, transaction, target, destinationWidget);
			return persistWidgetMapping(connection, target.originWidgetContentId, target.destinationRepositoryId, destinationWidget);
		});
	}

	private static String readOptionalParameter(Map<String, Object> params, String key)
	{
		Object value = params == null ? null : params.get(key);
		if (value == null || String.valueOf(value).trim().isEmpty())
		{
			return null;
		}
		if (!(value instanceof String))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_REQUIRED, key + " must be a string");
		}
		return (String) value;
	}

	private static WidgetMappingTarget resolveBlockedMigrationWidgetTarget(
			Connection connection,
			SessionConfig session,
			ITransaction transaction,
			WidgetMappingRequest request) throws Exception
	{
		String requestingUserId = request.requestingUserId;
		MigrationRow migration = MigrationDao.getMigration(connection, request.migrationId);
		if (migration == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "migration is not waiting for a widget mapping");
		}

		String originInstanceId = migration.getOriginServiceInstanceId();
		String destinationInstanceId = migration.getDestServiceInstanceId();
		verifyBlockedMigrationInstancePermissions(session, transaction, requestingUserId, originInstanceId, destinationInstanceId);

		String linkedRepository = DependencyRules
				.repository(session, destinationInstanceId, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, transaction);
		if (linkedRepository == null)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "destination has no widget repository");
		}
		verifyBlockedWidgetRepositoryPermission(session, transaction, requestingUserId, linkedRepository);
		if (!isMigrationWaitingForWidgetMapping(migration))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "migration is not waiting for a widget mapping");
		}
		if (!matchesBlockedMigrationWidgetTarget(migration, linkedRepository, request))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "mapping does not match the blocked migration");
		}
		verifyBlockingItemBelongsToOrigin(migration.getBlockingOriginItemId(), originInstanceId);
		return new WidgetMappingTarget(migration.getBlockingWidgetContentId(), linkedRepository, originInstanceId);
	}

	private static boolean isMigrationWaitingForWidgetMapping(MigrationRow migration)
	{
		if (migration == null)
		{
			return false;
		}
		return MigrationDao.STATUS_STOPPED_WITH_ERROR.equals(migration.getStatus())
				&& MigrationBlockedException.ERROR_TYPE_WIDGET_MAPPING_REQUIRED.equals(migration.getErrorType());
	}

	private static boolean matchesBlockedMigrationWidgetTarget(MigrationRow migration, String linkedRepository, WidgetMappingRequest request)
	{
		String blockedItemId = migration.getBlockingOriginItemId();
		String blockedWidget = migration.getBlockingWidgetContentId();
		boolean hasBlockingDetails = blockedItemId != null && blockedWidget != null;
		if (!hasBlockingDetails || linkedRepository == null)
		{
			return false;
		}
		boolean matchesRequestedWidget = request.originWidgetContentId == null || request.originWidgetContentId.equals(blockedWidget);
		boolean matchesRequestedRepository = request.destinationRepositoryId == null || request.destinationRepositoryId.equals(linkedRepository);
		return matchesRequestedWidget && matchesRequestedRepository;
	}

	private static void verifyBlockingItemBelongsToOrigin(String blockedItemId, String originInstanceId) throws Exception
	{
		try
		{
			ContentVersion blockingItem = ContentManagerFactory.getContentManager().getContentVersionByItemId(blockedItemId);
			String itemInstanceId = blockingItem.getContentLocale().getContent().getServiceInstanceId();
			if (!originInstanceId.equals(itemInstanceId))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "blocked content does not belong to the migration origin");
			}
		}
		catch (PortalObjectNotFoundException exception)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "blocked origin content no longer exists");
		}
	}

	private static void verifyBlockedMigrationInstancePermissions(
			SessionConfig session,
			ITransaction transaction,
			String requestingUserId,
			String originInstanceId,
			String destinationInstanceId) throws Exception
	{
		String viewPermission = ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE;
		String editPermission = ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT;
		boolean canAccessInstances = MigrationPermissions.hasServiceInstancePermission(session, requestingUserId, originInstanceId, viewPermission, transaction)
				&& MigrationPermissions.hasServiceInstancePermission(session, requestingUserId, destinationInstanceId, editPermission, transaction);
		if (!canAccessInstances)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "user " + requestingUserId + " cannot manage this migration");
		}
	}

	private static void verifyBlockedWidgetRepositoryPermission(
			SessionConfig session,
			ITransaction transaction,
			String requestingUserId,
			String destinationRepositoryId) throws Exception
	{
		String editPermission = ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT;
		boolean canEditRepository = MigrationPermissions.hasServiceInstancePermission(session, requestingUserId, destinationRepositoryId, editPermission, transaction);
		if (!canEditRepository)
		{
			throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "user " + requestingUserId + " cannot edit the widget mapping destination");
		}
	}

	private static WidgetMappingTarget resolveDirectWidgetTarget(Map<String, Object> params, SessionConfig session, ITransaction transaction, String requestingUserId)
			throws Exception
	{
		String originWidget = requireParameter(params, "originWidgetContentId");
		String destinationRepository = requireParameter(params, "destRepoInstanceId");
		String editPermission = ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT;
		boolean canEditRepository = MigrationPermissions.hasServiceInstancePermission(session, requestingUserId, destinationRepository, editPermission, transaction);
		if (!canEditRepository)
		{
			throw new MigrationApiException(
					MigrationApiException.ERROR_FORBIDDEN,
					"user " + requestingUserId + " cannot edit widget repository " + destinationRepository);
		}
		return new WidgetMappingTarget(originWidget, destinationRepository, null);
	}

	private static void validateWidgetRepositories(SessionConfig session, ITransaction transaction, WidgetMappingTarget target, String destinationWidget) throws Exception
	{
		if (!isWidgetInRepository(target.originWidgetContentId, null) || !isWidgetInRepository(destinationWidget, target.destinationRepositoryId))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "origin and destination must be widgets in the expected repositories");
		}
		if (target.originInstanceId != null)
		{
			String originRepository = DependencyRules
					.repository(session, target.originInstanceId, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, transaction);
			if (originRepository == null || !isWidgetInRepository(target.originWidgetContentId, originRepository))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "origin widget does not belong to the blocked migration origin");
			}
		}
	}

	private static boolean isWidgetInRepository(String widgetContentId, String repositoryId) throws Exception
	{
		try
		{
			Content content = ContentManagerFactory.getContentManager().getContent(widgetContentId);
			boolean isWidgetContent = "widget".equals(content.getSourceId()) && !content.getContentLocales().isEmpty();
			boolean matchesRepository = repositoryId == null || repositoryId.equals(content.getServiceInstanceId());
			if (!isWidgetContent || !matchesRepository)
			{
				return false;
			}
			ContentVersion activeVersion = content.getContentLocales().iterator().next().getActiveVersion();
			return activeVersion != null && ManagerFactory.getEntityManager().find(Widget.class, activeVersion.getItemId()) != null;
		}
		catch (PortalObjectNotFoundException exception)
		{
			return false;
		}
	}

	private static Map<String, Object> persistWidgetMapping(Connection connection, String originWidget, String destinationRepository, String destinationWidget)
			throws Exception
	{
		WidgetMapRow widgetMap = new WidgetMapRow();
		widgetMap.setId(PortalUtil.generateNewGuid());
		widgetMap.setOriginWidgetContentId(originWidget);
		widgetMap.setDestRepoInstanceId(destinationRepository);
		widgetMap.setDestWidgetContentId(destinationWidget);
		WidgetMapDao.saveWidgetMapping(connection, widgetMap);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("originWidgetContentId", originWidget);
		result.put("destRepoInstanceId", destinationRepository);
		result.put("destWidgetContentId", WidgetMapDao.getWidgetMapping(connection, originWidget, destinationRepository));
		return result;
	}

	@FunctionalInterface
	private interface Work<T>
	{
		T run(Connection connection, SessionConfig session, ITransaction transaction) throws Exception;
	}

	/**
	 * Widget mapping requested by {@link #saveWidgetMapping(Map)}, read from its parameters before
	 * the transaction.
	 */
	private static final class WidgetMappingRequest
	{
		/** Id of the blocked migration being resolved, or {@code null} for a direct mapping. */
		private final String migrationId;

		/** Requested origin widget content id, or {@code null} to accept the blocked one. */
		private final String originWidgetContentId;

		/** Requested destination repository id, or {@code null} to accept the linked one. */
		private final String destinationRepositoryId;

		private final String requestingUserId;

		private WidgetMappingRequest(String migrationId, String originWidgetContentId, String destinationRepositoryId, String requestingUserId)
		{
			this.migrationId = migrationId;
			this.originWidgetContentId = originWidgetContentId;
			this.destinationRepositoryId = destinationRepositoryId;
			this.requestingUserId = requestingUserId;
		}
	}

	private static final class WidgetMappingTarget
	{
		private final String originWidgetContentId;

		private final String destinationRepositoryId;

		private final String originInstanceId;

		private WidgetMappingTarget(String originWidgetContentId, String destinationRepositoryId, String originInstanceId)
		{
			this.originWidgetContentId = originWidgetContentId;
			this.destinationRepositoryId = destinationRepositoryId;
			this.originInstanceId = originInstanceId;
		}
	}
}
