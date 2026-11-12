package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import lumis.util.log.ILogger;
import lumis.util.log.LoggerFactory;

/**
 * Coordinates the single global row of lum_cmig_HMigLock so only one migration or undo runs at a
 * time, identified by a {@code runId}. The lock is acquired by a conditional update when free or
 * when its heartbeat stopped for more than {@link #STALE_MINUTES} minutes on the database clock.
 * Every subsequent write of the run (heartbeat, status, item commit, release) is conditioned on
 * the same {@code runId}, ensuring a run whose lock was taken over writes nothing further.
 * <p>
 * Every method uses the connection of the caller's transaction and never commits.
 */
public class GlobalLock
{
	private static final ILogger LOGGER = LoggerFactory.getLogger(GlobalLock.class);
	/** Id of the single row of lum_cmig_HMigLock. */
	public static final String LOCK_ID = "global";

	/** Minutes without heartbeat after which a held lock may be taken over. */
	public static final int STALE_MINUTES = 5;

	// @formatter:off
	/** Plain SQL predicate that checks whether the lock is held by the run bound as its single parameter. */
	public static final String HELD_BY_RUN = "exists ("
			+ "select 1 "
			+ "from lum_cmig_HMigLock "
			+ "where id = '" + LOCK_ID + "' "
			+ "and runId = ?)";
	// @formatter:on

	/**
	 * Ensures the single global row of lum_cmig_HMigLock exists.
	 */
	public static void ensureLockRow(Connection connection) throws SQLException
	{
		boolean hasRow;
		// @formatter:off
		String selectSql = "select count(*) "
				+ "from lum_cmig_HMigLock "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(selectSql))
		{
			statement.setString(1, LOCK_ID);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				hasRow = resultSet.getInt(1) > 0;
			}
		}
		if (hasRow)
		{
			return;
		}
		// @formatter:off
		String insertSql = "insert into lum_cmig_HMigLock (id) "
				+ "values (?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(insertSql))
		{
			statement.setString(1, LOCK_ID);
			statement.executeUpdate();
		}
	}

	/**
	 * Acquires the lock for the given run if it is free or if its heartbeat stopped more than
	 * {@link #STALE_MINUTES} minutes ago on the database clock.
	 *
	 * @return whether the lock was acquired.
	 */
	public static boolean acquire(Connection connection, String runId, String migrationId) throws SQLException
	{
		ensureLockRow(connection);
		Timestamp staleLimit = staleLimit(connection);
		// @formatter:off
		String sql = "update lum_cmig_HMigLock "
				+ "set runId = ?, migrationId = ?, heartbeat = CURRENT_TIMESTAMP "
				+ "where id = ? "
				+ "and (runId is null or heartbeat is null or heartbeat < ?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, runId);
			statement.setString(2, migrationId);
			statement.setString(3, LOCK_ID);
			statement.setTimestamp(4, staleLimit);
			return statement.executeUpdate() > 0;
		}
	}

	/** Returns the moment, on the database clock, before which a heartbeat is stale. */
	public static Timestamp staleLimit(Connection connection) throws SQLException
	{
		return new Timestamp(databaseNow(connection).getTime() - STALE_MINUTES * 60_000L);
	}

	/**
	 * Returns the current time of the database clock using lum_cmig_HMigLock as a portable one-row
	 * table across MySQL, Oracle, and SQL Server. Falls back to the JVM clock if the row is absent.
	 */
	public static Timestamp databaseNow(Connection connection) throws SQLException
	{
		// @formatter:off
		String sql = "select CURRENT_TIMESTAMP "
				+ "from lum_cmig_HMigLock "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, LOCK_ID);
			try (ResultSet resultSet = statement.executeQuery())
			{
				if (resultSet.next())
				{
					return resultSet.getTimestamp(1);
				}
				LOGGER.debug("Global lock row absent; using JVM clock");
				return new Timestamp(System.currentTimeMillis());
			}
		}
	}

	/**
	 * Confirms the lock is still held by the given run, locking its row until the end of the
	 * caller's transaction. Called right before the commit of every item.
	 *
	 * @return whether the run still holds the lock; when false the caller must roll back.
	 */
	public static boolean confirm(Connection connection, String runId) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_HMigLock "
				+ "set migrationId = migrationId "
				+ "where id = ? "
				+ "and runId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, LOCK_ID);
			statement.setString(2, runId);
			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * Renews the heartbeat of the lock and of its migration row.
	 *
	 * @return whether the run still holds the lock.
	 */
	public static boolean heartbeat(Connection connection, String runId, String migrationId) throws SQLException
	{
		// @formatter:off
		String lockSql = "update lum_cmig_HMigLock "
				+ "set heartbeat = CURRENT_TIMESTAMP "
				+ "where id = ? "
				+ "and runId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(lockSql))
		{
			statement.setString(1, LOCK_ID);
			statement.setString(2, runId);
			if (statement.executeUpdate() == 0)
			{
				return false;
			}
		}
		// @formatter:off
		String migrationSql = "update lum_cmig_HMigration "
				+ "set heartbeat = CURRENT_TIMESTAMP "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(migrationSql))
		{
			statement.setString(1, migrationId);
			statement.executeUpdate();
		}
		return true;
	}

	/**
	 * Releases the lock if it is still held by the given run.
	 *
	 * @return whether the lock was held by the run.
	 */
	public static boolean release(Connection connection, String runId) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_HMigLock "
				+ "set runId = null, migrationId = null, heartbeat = null "
				+ "where id = ? "
				+ "and runId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, LOCK_ID);
			statement.setString(2, runId);
			return statement.executeUpdate() > 0;
		}
	}
}
