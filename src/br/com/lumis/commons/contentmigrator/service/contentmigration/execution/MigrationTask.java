package br.com.lumis.commons.contentmigrator.service.contentmigration.execution;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import br.com.lumis.commons.contentmigrator.service.contentmigration.config.ExecutionSettings;
import br.com.lumis.commons.contentmigrator.service.contentmigration.copy.ItemProcessor;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.Transactions;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.ItemOutcome;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationItem;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.UndoItem;
import br.com.lumis.commons.contentmigrator.service.contentmigration.lock.GlobalLock;
import br.com.lumis.commons.contentmigrator.service.contentmigration.undo.MigrationUndoer;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.util.log.ILogger;
import lumis.util.log.LoggerFactory;

/**
 * Background worker thread that executes a migration or undo run after its {@code runId} has
 * acquired the {@link GlobalLock}, submitted to the portal executor by
 * {@link MigrationExecutor#submit(MigrationTask)}.
 * <p>
 * The worker impersonates the user who started the run. Each item is copied or undone in its own
 * transaction, while status, counters, and heartbeat are written in separate short transactions
 * conditioned on {@code runId}. Stop requests are honored between items. If the lock is taken over
 * by another run, the current item is rolled back and the worker exits without overwriting status.
 */
public class MigrationTask implements Runnable
{
	private static final ILogger LOGGER = LoggerFactory.getLogger(MigrationTask.class);

	private final MigrationRow migration;

	private final String migrationId;

	private final String runId;

	private final String userId;

	private final String originServiceInstanceId;

	private final String destServiceInstanceId;

	private final String operation;

	private final CountDownLatch done = new CountDownLatch(1);

	private final long itemTimeLimitMillis = ExecutionSettings.itemTimeLimitMillis();

	private final long heartbeatSeconds = ExecutionSettings.heartbeatSeconds();

	/** Start timestamp of the current item in milliseconds, or 0 between items. */
	private volatile long currentItemStartMillis;

	private volatile boolean hasLostLock;

	/**
	 * Creates a background task for the operation recorded in the migration row, either
	 * {@link MigrationDao#OPERATION_MIGRATE} or {@link MigrationDao#OPERATION_UNDO}. The row must
	 * be the one read after the run was marked as running, so that its update mode and operation
	 * are the recorded ones, and must not be changed afterwards. The constructor neither validates
	 * nor throws.
	 *
	 * @param migration
	 *            the migration row with id, service, instances, update mode, and operation.
	 * @param runId
	 *            the unique id of the run holding the global lock.
	 * @param userId
	 *            the user impersonated by the worker.
	 */
	public MigrationTask(MigrationRow migration, String runId, String userId)
	{
		this.migration = migration;
		this.migrationId = migration.getId();
		this.runId = runId;
		this.userId = userId;
		this.originServiceInstanceId = migration.getOriginServiceInstanceId();
		this.destServiceInstanceId = migration.getDestServiceInstanceId();
		this.operation = MigrationDao.OPERATION_UNDO.equals(migration.getOperation()) ? MigrationDao.OPERATION_UNDO : MigrationDao.OPERATION_MIGRATE;
	}

	/** Returns the id of the migration executed by this task. */
	public String getMigrationId()
	{
		return migrationId;
	}

	/** Returns the unique id of this execution run holding the global lock. */
	public String getRunId()
	{
		return runId;
	}

	/**
	 * Executes the migration or undo loop on a background thread while renewing the heartbeat
	 * timer and recording the final status under the global lock.
	 */
	@Override
	public void run()
	{
		ScheduledFuture<?> heartbeatTimer = null;
		try
		{
			SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
			try
			{
				heartbeatTimer = startHeartbeatTimer();
				String finalStatus = executeOperation(session);
				recordCompletion(finalStatus);
			}
			finally
			{
				ManagerFactory.getAuthenticationManager().endImpersonation(session);
			}
		}
		catch (LockLostException exception)
		{
			LOGGER.warn("Migration " + migrationId + " run " + runId + " lost the lock and was aborted");
		}
		catch (Throwable failure)
		{
			recordFailure(failure);
		}
		finally
		{
			if (heartbeatTimer != null)
			{
				heartbeatTimer.cancel(false);
			}
			MigrationExecutor.removeFinishedTask(this);
			done.countDown();
		}
	}

	private ScheduledFuture<?> startHeartbeatTimer()
	{
		return ManagerFactory.getExecutorManager().scheduleWithFixedDelay(this::renewHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
	}

	/**
	 * Renews the lock heartbeat while the current item remains within its allowed time limit.
	 */
	private void renewHeartbeat()
	{
		long startMillis = currentItemStartMillis;
		boolean isItemOverTimeLimit = startMillis != 0 && System.currentTimeMillis() - startMillis > itemTimeLimitMillis;
		if (isItemOverTimeLimit)
		{
			LOGGER.warn("Migration " + migrationId + ": item above " + itemTimeLimitMillis + " ms, heartbeat not renewed");
			return;
		}
		try
		{
			if (!Transactions.inShortTransaction(connection -> GlobalLock.heartbeat(connection, runId, migrationId)))
			{
				hasLostLock = true;
			}
		}
		catch (Throwable failure)
		{
			// An uncaught exception would cancel the scheduled timer; the next beat retries.
			LOGGER.warn("Migration " + migrationId + ": heartbeat failed", failure);
		}
	}

	private String executeOperation(SessionConfig session) throws Exception
	{
		if (MigrationDao.OPERATION_UNDO.equals(operation))
		{
			return undoItems(session);
		}
		return migrateItems(session);
	}

	/**
	 * Discovers and copies origin items one transaction at a time until all items are processed or
	 * a stop is requested.
	 */
	private String migrateItems(SessionConfig session) throws Exception
	{
		ItemProcessor processor = new ItemProcessor(runId, session, migration);
		List<MigrationItem> items = processor.discover();
		updateCounts(items.size());

		int copiedCount = 0;
		for (MigrationItem item : items)
		{
			ensureLockStillHeld();
			if (shouldStop())
			{
				return MigrationDao.STATUS_STOPPED;
			}

			ItemOutcome outcome = processSingleItem(processor, item, copiedCount + 1);
			if (outcome == ItemOutcome.LOCK_LOST)
			{
				throw new LockLostException();
			}
			if (outcome == ItemOutcome.COPIED)
			{
				copiedCount++;
				updateCounts(items.size());
			}
		}
		return MigrationDao.STATUS_FINISHED;
	}

	private void ensureLockStillHeld() throws LockLostException
	{
		if (hasLostLock)
		{
			throw new LockLostException();
		}
	}

	private boolean shouldStop() throws Exception
	{
		return Transactions.inShortTransaction(connection -> RunStatusUpdater.isStopRequested(connection, migrationId));
	}

	private ItemOutcome processSingleItem(ItemProcessor processor, MigrationItem item, int ordinal) throws Exception
	{
		currentItemStartMillis = System.currentTimeMillis();
		try
		{
			return processor.process(item, ordinal);
		}
		finally
		{
			currentItemStartMillis = 0;
		}
	}

	private void updateCounts(int totalCount) throws Exception
	{
		boolean isLockHeld = Transactions.inShortTransaction(
				connection -> RunStatusUpdater.updateCounts(
						connection,
						migrationId,
						runId,
						totalCount,
						ItemProcessor.countMigrated(connection, originServiceInstanceId, destServiceInstanceId)));
		if (!isLockHeld)
		{
			throw new LockLostException();
		}
	}

	/** Removes destination content and owned repository clones, one transaction per map row. */
	private String undoItems(SessionConfig session) throws Exception
	{
		List<UndoItem> contentItems = MigrationUndoer.contentItems(originServiceInstanceId, destServiceInstanceId);
		updateCounts(contentItems.size());
		for (UndoItem item : contentItems)
		{
			ensureLockStillHeld();
			if (shouldStop())
			{
				return MigrationDao.STATUS_STOPPED;
			}
			undoSingleContentItem(session, item);
			updateCounts(contentItems.size());
		}

		List<UndoItem> repositoryItems = MigrationUndoer.repositoryItems(session, migrationId, originServiceInstanceId, destServiceInstanceId);
		for (UndoItem item : repositoryItems)
		{
			ensureLockStillHeld();
			if (shouldStop())
			{
				return MigrationDao.STATUS_STOPPED;
			}
			undoSingleRepositoryItem(session, item);
			updateCounts(contentItems.size());
		}
		return MigrationDao.STATUS_FINISHED;
	}

	private void undoSingleContentItem(SessionConfig session, UndoItem item) throws Exception
	{
		currentItemStartMillis = System.currentTimeMillis();
		boolean isLockHeld;
		try
		{
			injectUndoDelay();
			isLockHeld = MigrationUndoer.undoContent(session, runId, destServiceInstanceId, item);
		}
		finally
		{
			currentItemStartMillis = 0;
		}
		if (!isLockHeld)
		{
			throw new LockLostException();
		}
	}

	private void undoSingleRepositoryItem(SessionConfig session, UndoItem item) throws Exception
	{
		currentItemStartMillis = System.currentTimeMillis();
		boolean isLockHeld;
		try
		{
			injectUndoDelay();
			isLockHeld = MigrationUndoer.undoRepositoryItem(session, runId, migration, item);
		}
		finally
		{
			currentItemStartMillis = 0;
		}
		if (!isLockHeld)
		{
			throw new LockLostException();
		}
	}

	/** Applies the per-item test delay to undo so stop and lock behavior can be exercised. */
	private static void injectUndoDelay() throws InterruptedException
	{
		int delayMillis = ExecutionSettings.intProperty(ExecutionSettings.PROPERTY_ITEM_DELAY_MILLIS, 0);
		if (delayMillis > 0)
		{
			Thread.sleep(delayMillis);
		}
	}

	private void recordCompletion(String status) throws Exception
	{
		boolean isLockHeld = Transactions.inShortTransaction(connection -> RunStatusUpdater.finish(connection, migrationId, runId, status, null));
		if (!isLockHeld)
		{
			throw new LockLostException();
		}
		LOGGER.info("Migration " + migrationId + " run " + runId + " ended " + status);
	}

	private void recordFailure(Throwable failure)
	{
		LOGGER.error("Migration " + migrationId + " run " + runId + " failed", failure);
		try
		{
			Transactions.inShortTransaction(connection -> RunStatusUpdater.finish(connection, migrationId, runId, MigrationDao.STATUS_STOPPED_WITH_ERROR, failure));
		}
		catch (Throwable finishError)
		{
			LOGGER.error("Could not record the failure of migration " + migrationId, finishError);
		}
	}

	/**
	 * Waits for the completion of this task.
	 *
	 * @return whether the task finished within the timeout.
	 */
	boolean awaitTermination(long timeoutMillis) throws InterruptedException
	{
		return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
	}

	/** Signals that the run lost the global lock to another execution. */
	private static class LockLostException extends Exception
	{
		private static final long serialVersionUID = 1L;
	}
}
