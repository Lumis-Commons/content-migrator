package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Locale;

/** Read-only coexistence checks against the optional original contentmigration component. */
public final class ContentMigrationCoexistenceDao
{
	private static Boolean migrationTablePresent;

	private static Boolean lockTablePresent;

	private ContentMigrationCoexistenceDao()
	{
	}

	/** Returns whether the original component already owns this ordered instance pair. */
	public static boolean hasPair(Connection connection, String originId, String destinationId) throws SQLException
	{
		if (!hasMigrationTable(connection))
		{
			return false;
		}
		// @formatter:off
		String sql = "select count(*) from lum_cmig_Migration "
				+ "where originServiceInstanceId = ? and destServiceInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originId);
			statement.setString(2, destinationId);
			try (ResultSet result = statement.executeQuery())
			{
				return result.next() && result.getInt(1) > 0;
			}
		}
	}

	/**
	 * Returns whether an original run writes to this destination with a heartbeat at or after
	 * {@code staleLimit}, a moment on the database clock.
	 */
	public static boolean hasActiveDestination(Connection connection, String destinationId, Timestamp staleLimit) throws SQLException
	{
		if (!hasMigrationTable(connection) || !hasLockTable(connection))
		{
			return false;
		}
		// @formatter:off
		String sql = "select m.id from lum_cmig_MigLock l "
				+ "inner join lum_cmig_Migration m on m.id = l.migrationId "
				+ "where l.id = 'global' and l.runId is not null "
				+ "and l.heartbeat >= ? and m.destServiceInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setTimestamp(1, staleLimit);
			statement.setString(2, destinationId);
			try (ResultSet result = statement.executeQuery())
			{
				return result.next();
			}
		}
	}

	private static synchronized boolean hasMigrationTable(Connection connection) throws SQLException
	{
		if (migrationTablePresent == null)
		{
			migrationTablePresent = tableExists(connection, "lum_cmig_Migration");
		}
		return migrationTablePresent;
	}

	private static synchronized boolean hasLockTable(Connection connection) throws SQLException
	{
		if (lockTablePresent == null)
		{
			lockTablePresent = tableExists(connection, "lum_cmig_MigLock");
		}
		return lockTablePresent;
	}

	private static boolean tableExists(Connection connection, String table) throws SQLException
	{
		DatabaseMetaData metadata = connection.getMetaData();
		for (String name : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)})
		{
			try (ResultSet tables = metadata.getTables(connection.getCatalog(), connection.getSchema(), name, new String[]{"TABLE"}))
			{
				while (tables.next())
				{
					// Underscores in the JDBC search pattern may also match unrelated table names.
					if (name.equalsIgnoreCase(tables.getString("TABLE_NAME")))
					{
						return true;
					}
				}
			}
		}
		return false;
	}
}
