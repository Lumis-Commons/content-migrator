package br.com.lumis.commons.contentmigrator.service.contentmigration.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.MigrationBlockedException;
import br.com.lumis.commons.contentmigrator.service.contentmigration.lock.GlobalLock;

/**
 * Writes status, counters, and error details of a migration run, always conditioned on the
 * {@code runId} that holds the {@link GlobalLock}.
 */
public final class RunStatusUpdater
{
	public static final String ERROR_TYPE_GENERIC = "GENERIC";

	/** Maximum length of lum_cmig_Migration.errorMessage. */
	private static final int ERROR_MESSAGE_SIZE = 1000;

	private RunStatusUpdater()
	{
	}

	/**
	 * Marks the migration as running for the run that has just acquired the lock, clearing the
	 * stop request, dates, and error details from the previous execution.
	 *
	 * @param updateMode
	 *            the new update mode, or {@code null} to keep the current one.
	 */
	public static void markRunning(Connection connection, String migrationId, String runId, String updateMode, int totalCount) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_Migration "
				+ "set status = ?, operation = ?, "
				+ (updateMode == null ? "" : "updateMode = ?, ")
				+ "stopRequested = ?, totalCount = ?, startDate = CURRENT_TIMESTAMP, endDate = null, "
				+ "heartbeat = CURRENT_TIMESTAMP, errorType = null, errorMessage = null, errorStack = null, "
				+ "blockingOriginItemId = null, blockingWidgetContentId = null "
				+ "where id = ? "
				+ "and " + GlobalLock.HELD_BY_RUN;
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			int index = 1;
			statement.setString(index++, MigrationDao.STATUS_RUNNING);
			statement.setString(index++, MigrationDao.OPERATION_MIGRATE);
			if (updateMode != null)
			{
				statement.setString(index++, updateMode);
			}
			statement.setBoolean(index++, false);
			statement.setInt(index++, totalCount);
			statement.setString(index++, migrationId);
			statement.setString(index++, runId);
			if (statement.executeUpdate() != 1)
			{
				throw new SQLException("Could not mark migration " + migrationId + " as running for run " + runId);
			}
		}
	}

	/**
	 * Marks a migration as undoing under the run that acquired the global lock, clearing previous
	 * run dates and error details.
	 */
	public static void markUndoRunning(Connection connection, String migrationId, String runId, int totalCount) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_Migration "
				+ "set status = ?, operation = ?, stopRequested = ?, totalCount = ?, "
				+ "startDate = CURRENT_TIMESTAMP, endDate = null, heartbeat = CURRENT_TIMESTAMP, errorType = null, "
				+ "errorMessage = null, errorStack = null, blockingOriginItemId = null, blockingWidgetContentId = null "
				+ "where id = ? "
				+ "and " + GlobalLock.HELD_BY_RUN;
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, MigrationDao.STATUS_RUNNING);
			statement.setString(2, MigrationDao.OPERATION_UNDO);
			statement.setBoolean(3, false);
			statement.setInt(4, totalCount);
			statement.setString(5, migrationId);
			statement.setString(6, runId);
			if (statement.executeUpdate() != 1)
			{
				throw new SQLException("Could not mark migration " + migrationId + " as undoing for run " + runId);
			}
		}
	}

	/**
	 * Requests a running migration to stop after its current item finishes.
	 *
	 * @return whether the migration was in RUNNING status.
	 */
	public static boolean requestStop(Connection connection, String migrationId) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_Migration "
				+ "set stopRequested = ? "
				+ "where id = ? "
				+ "and status = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setBoolean(1, true);
			statement.setString(2, migrationId);
			statement.setString(3, MigrationDao.STATUS_RUNNING);
			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * Ends a run by writing its final status and error details (if any) and releasing the lock.
	 * Writes nothing if the run no longer holds the lock.
	 *
	 * @return whether the run still held the lock.
	 */
	public static boolean finish(Connection connection, String migrationId, String runId, String status, Throwable error) throws SQLException
	{
		if (!GlobalLock.confirm(connection, runId))
		{
			return false;
		}
		MigrationBlockedException blocked = findBlockedException(error);
		// @formatter:off
		String sql = "update lum_cmig_Migration "
				+ "set status = ?, stopRequested = ?, endDate = CURRENT_TIMESTAMP, heartbeat = CURRENT_TIMESTAMP, "
				+ "errorType = ?, errorMessage = ?, errorStack = ?, blockingOriginItemId = ?, blockingWidgetContentId = ? "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, status);
			statement.setBoolean(2, false);
			statement.setString(3, resolveErrorType(error, blocked));
			statement.setString(4, error == null ? null : formatErrorMessage(error));
			statement.setString(5, error == null ? null : formatStackTrace(error));
			statement.setString(6, blocked == null ? null : blocked.getOriginItemId());
			statement.setString(7, blocked == null ? null : blocked.getBlockingWidgetContentId());
			statement.setString(8, migrationId);
			statement.executeUpdate();
		}
		GlobalLock.release(connection, runId);
		return true;
	}

	private static MigrationBlockedException findBlockedException(Throwable error)
	{
		for (Throwable current = error; current != null; current = current.getCause())
		{
			if (current instanceof MigrationBlockedException)
			{
				return (MigrationBlockedException) current;
			}
		}
		return null;
	}

	private static String resolveErrorType(Throwable error, MigrationBlockedException blocked)
	{
		if (error == null)
		{
			return null;
		}
		return blocked == null ? ERROR_TYPE_GENERIC : blocked.getErrorType();
	}

	private static String formatErrorMessage(Throwable error)
	{
		String message = error.getMessage() == null ? error.getClass().getName() : error.getMessage();
		return message.length() > ERROR_MESSAGE_SIZE ? message.substring(0, ERROR_MESSAGE_SIZE) : message;
	}

	private static String formatStackTrace(Throwable error)
	{
		StringWriter writer = new StringWriter();
		error.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	/**
	 * Updates the total and migrated item counters of a running migration. The lock row is
	 * confirmed first, as in {@link #finish} and {@link GlobalLock#heartbeat}, so every write of a run
	 * locks lum_cmig_MigLock before lum_cmig_Migration and the item thread and the heartbeat timer of
	 * the same run take the two row locks in the same order.
	 *
	 * @return whether the run still holds the lock.
	 */
	static boolean updateCounts(Connection connection, String migrationId, String runId, int totalCount, int migratedCount) throws SQLException
	{
		if (!GlobalLock.confirm(connection, runId))
		{
			return false;
		}
		// @formatter:off
		String sql = "update lum_cmig_Migration "
				+ "set totalCount = ?, migratedCount = ? "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setInt(1, totalCount);
			statement.setInt(2, migratedCount);
			statement.setString(3, migrationId);
			statement.executeUpdate();
		}
		return true;
	}

	/**
	 * Returns whether a stop was requested for the migration.
	 */
	static boolean isStopRequested(Connection connection, String migrationId) throws SQLException
	{
		// @formatter:off
		String sql = "select stopRequested "
				+ "from lum_cmig_Migration "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, migrationId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				return resultSet.next() && resultSet.getBoolean(1);
			}
		}
	}
}
