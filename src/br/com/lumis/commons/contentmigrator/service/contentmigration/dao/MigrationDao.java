package br.com.lumis.commons.contentmigrator.service.contentmigration.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationRow;

/**
 * JDBC access to lum_cmig_Migration. Uses plain, portable SQL across MySQL, Oracle, and SQL Server;
 * every method uses the connection of the caller's transaction and never commits.
 */
public class MigrationDao
{
	public static final String STATUS_STOPPED = "STOPPED";

	public static final String STATUS_STOPPED_WITH_ERROR = "STOPPED_WITH_ERROR";

	public static final String STATUS_RUNNING = "RUNNING";

	public static final String STATUS_FINISHED = "FINISHED";

	public static final String OPERATION_MIGRATE = "MIGRATE";

	public static final String OPERATION_UNDO = "UNDO";

	// @formatter:off
	private static final String MIGRATION_SELECT = "select id, name, serviceId, originServiceInstanceId, "
			+ "destServiceInstanceId, updateMode, operation, status, stopRequested, totalCount, migratedCount, "
			+ "heartbeat, createdDate, startDate, endDate, author, errorType, errorMessage, errorStack, "
			+ "blockingOriginItemId, blockingWidgetContentId "
			+ "from lum_cmig_Migration";
	// @formatter:on

	/**
	 * Inserts a migration row with its initial status, counters, and author.
	 *
	 * @throws SQLException
	 *             if the pair of instances already has a migration.
	 */
	public static void insertMigration(Connection connection, MigrationRow row) throws SQLException
	{
		// @formatter:off
		String sql = "insert into lum_cmig_Migration (id, name, serviceId, originServiceInstanceId, "
				+ "destServiceInstanceId, updateMode, operation, status, stopRequested, totalCount, migratedCount, "
				+ "createdDate, author) "
				+ "values (?,?,?,?,?,?,?,?,?,?,?,?,?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, row.getId());
			statement.setString(2, row.getName());
			statement.setString(3, row.getServiceId());
			statement.setString(4, row.getOriginServiceInstanceId());
			statement.setString(5, row.getDestServiceInstanceId());
			statement.setString(6, row.getUpdateMode());
			statement.setString(7, row.getOperation());
			statement.setString(8, row.getStatus());
			statement.setBoolean(9, false);
			statement.setInt(10, row.getTotalCount().intValue());
			statement.setInt(11, row.getMigratedCount().intValue());
			statement.setTimestamp(12, new Timestamp(System.currentTimeMillis()));
			statement.setString(13, row.getAuthor());
			statement.executeUpdate();
		}
	}

	/**
	 * Returns the migration with the given id, or {@code null} when absent.
	 */
	public static MigrationRow getMigration(Connection connection, String id) throws SQLException
	{
		// @formatter:off
		String whereClause = " where id = ?";
		// @formatter:on
		List<MigrationRow> rows = queryMigrations(connection, whereClause, id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static List<MigrationRow> queryMigrations(Connection connection, String suffix, String... params) throws SQLException
	{
		List<MigrationRow> rows = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(MIGRATION_SELECT + suffix))
		{
			for (int index = 0; index < params.length; index++)
			{
				statement.setString(index + 1, params[index]);
			}
			try (ResultSet resultSet = statement.executeQuery())
			{
				while (resultSet.next())
				{
					rows.add(mapMigrationRow(resultSet));
				}
			}
		}
		return rows;
	}

	private static MigrationRow mapMigrationRow(ResultSet resultSet) throws SQLException
	{
		MigrationRow row = new MigrationRow();
		row.setId(resultSet.getString("id"));
		row.setName(resultSet.getString("name"));
		row.setServiceId(resultSet.getString("serviceId"));
		row.setOriginServiceInstanceId(resultSet.getString("originServiceInstanceId"));
		row.setDestServiceInstanceId(resultSet.getString("destServiceInstanceId"));
		row.setUpdateMode(resultSet.getString("updateMode"));
		row.setOperation(resultSet.getString("operation"));
		row.setStatus(resultSet.getString("status"));
		row.setStopRequested(resultSet.getBoolean("stopRequested"));
		row.setTotalCount(resultSet.getLong("totalCount"));
		row.setMigratedCount(resultSet.getLong("migratedCount"));
		row.setHeartbeat(readEpochMillis(resultSet, "heartbeat"));
		row.setCreatedDate(readEpochMillis(resultSet, "createdDate"));
		row.setStartDate(readEpochMillis(resultSet, "startDate"));
		row.setEndDate(readEpochMillis(resultSet, "endDate"));
		row.setAuthor(resultSet.getString("author"));
		row.setErrorType(resultSet.getString("errorType"));
		row.setErrorMessage(resultSet.getString("errorMessage"));
		row.setErrorStack(resultSet.getString("errorStack"));
		row.setBlockingOriginItemId(resultSet.getString("blockingOriginItemId"));
		row.setBlockingWidgetContentId(resultSet.getString("blockingWidgetContentId"));
		return row;
	}

	/**
	 * Reads a date column as epoch milliseconds. {@link ResultSet#getTimestamp(String)} has the same
	 * type on every driver, while {@code getObject} returns a {@code LocalDateTime} on MySQL.
	 */
	private static Long readEpochMillis(ResultSet resultSet, String column) throws SQLException
	{
		Timestamp value = resultSet.getTimestamp(column);
		return value == null ? null : value.getTime();
	}

	/**
	 * Returns the migration of the given pair of instances, or {@code null} when absent.
	 */
	public static MigrationRow getMigrationByPair(Connection connection, String originServiceInstanceId, String destServiceInstanceId) throws SQLException
	{
		// @formatter:off
		String whereClause = " where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ?";
		// @formatter:on
		List<MigrationRow> rows = queryMigrations(connection, whereClause, originServiceInstanceId, destServiceInstanceId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * Lists all migrations ordered by creation date and id.
	 */
	public static List<MigrationRow> listMigrations(Connection connection) throws SQLException
	{
		// @formatter:off
		String orderByClause = " order by createdDate, id";
		// @formatter:on
		return queryMigrations(connection, orderByClause);
	}

	/**
	 * Counts the ContentLocale rows across all contents of the given service instance.
	 */
	public static int countContentLocales(Connection connection, String serviceInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_ContentLocale cl "
				+ "inner join lum_Content c on c.id = cl.contentId "
				+ "where c.serviceInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, serviceInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1);
			}
		}
	}

	/**
	 * Locks a stopped migration row so deletion is serialized against concurrent start or undo
	 * calls, returning {@code false} when the migration is currently running or absent.
	 */
	public static boolean lockStoppedMigration(Connection connection, String id) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_Migration "
				+ "set id = id "
				+ "where id = ? "
				+ "and status <> ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, id);
			statement.setString(2, STATUS_RUNNING);
			return statement.executeUpdate() == 1;
		}
	}

	/**
	 * Deletes a stopped migration row by id.
	 */
	public static void deleteMigration(Connection connection, String migrationId) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_Migration "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, migrationId);
			statement.executeUpdate();
		}
	}
}
