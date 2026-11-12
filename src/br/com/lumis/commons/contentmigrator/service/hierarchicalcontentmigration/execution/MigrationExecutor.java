package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.Transactions;
import lumis.portal.manager.ManagerFactory;
import lumis.util.log.ILogger;
import lumis.util.log.LoggerFactory;

/**
 * Runs background migration tasks through the portal executor, tracks active tasks in this JVM,
 * and allows callers to wait for their termination.
 */
public class MigrationExecutor
{
	private static final ILogger LOGGER = LoggerFactory.getLogger(MigrationExecutor.class);

	/** Active runs of this JVM, indexed by migration id. */
	private static final Map<String, MigrationTask> RUNS = new ConcurrentHashMap<>();

	/**
	 * Submits the task to the portal executor. If submission fails, the migration is marked
	 * stopped with the error and its global lock is released before rethrowing the exception.
	 */
	public static void submit(MigrationTask task)
	{
		RUNS.put(task.getMigrationId(), task);
		try
		{
			ManagerFactory.getExecutorManager().execute(task);
		}
		catch (RuntimeException exception)
		{
			RUNS.remove(task.getMigrationId(), task);
			LOGGER.error("Could not submit migration " + task.getMigrationId(), exception);
			try
			{
				Transactions.inShortTransaction(
						connection -> RunStatusUpdater.finish(connection, task.getMigrationId(), task.getRunId(), MigrationDao.STATUS_STOPPED_WITH_ERROR, exception));
			}
			catch (Exception finishError)
			{
				exception.addSuppressed(finishError);
			}
			throw exception;
		}
	}

	/**
	 * Waits for the run of the migration active in this JVM, if any, to finish.
	 *
	 * @return whether no run of the migration remains active in this JVM when returning.
	 */
	public static boolean awaitTermination(String migrationId, long timeoutMillis) throws InterruptedException
	{
		MigrationTask task = RUNS.get(migrationId);
		return task == null || task.awaitTermination(timeoutMillis);
	}

	/** Removes a finished task from the active registry of this JVM. */
	static void removeFinishedTask(MigrationTask task)
	{
		RUNS.remove(task.getMigrationId(), task);
	}
}
