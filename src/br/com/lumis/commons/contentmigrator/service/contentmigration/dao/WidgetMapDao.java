package br.com.lumis.commons.contentmigrator.service.contentmigration.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.WidgetMapRow;

/**
 * JDBC access to lum_cmig_MigWidgetMap, which stores the destination widget chosen for each origin
 * widget. Every method uses the connection of the caller's transaction and never commits.
 */
public class WidgetMapDao
{
	/**
	 * Inserts or updates a widget mapping by (originWidgetContentId, destRepoInstanceId). The id
	 * of {@code row} is used only when a new row is inserted.
	 */
	public static void saveWidgetMapping(Connection connection, WidgetMapRow row) throws SQLException
	{
		int updatedRows;
		// @formatter:off
		String updateSql = "update lum_cmig_MigWidgetMap "
				+ "set destWidgetContentId = ? "
				+ "where originWidgetContentId = ? "
				+ "and destRepoInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(updateSql))
		{
			statement.setString(1, row.getDestWidgetContentId());
			statement.setString(2, row.getOriginWidgetContentId());
			statement.setString(3, row.getDestRepoInstanceId());
			updatedRows = statement.executeUpdate();
		}
		if (updatedRows > 0)
		{
			return;
		}
		// @formatter:off
		String insertSql = "insert into lum_cmig_MigWidgetMap (id, originWidgetContentId, destRepoInstanceId, destWidgetContentId) "
				+ "values (?,?,?,?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(insertSql))
		{
			statement.setString(1, row.getId());
			statement.setString(2, row.getOriginWidgetContentId());
			statement.setString(3, row.getDestRepoInstanceId());
			statement.setString(4, row.getDestWidgetContentId());
			statement.executeUpdate();
		}
	}

	/**
	 * Returns the destination widget content id mapped for the given origin widget and destination
	 * repository, or {@code null} when unmapped.
	 */
	public static String getWidgetMapping(Connection connection, String originWidgetContentId, String destRepoInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "select destWidgetContentId "
				+ "from lum_cmig_MigWidgetMap "
				+ "where originWidgetContentId = ? "
				+ "and destRepoInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originWidgetContentId);
			statement.setString(2, destRepoInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				return resultSet.next() ? resultSet.getString(1) : null;
			}
		}
	}

	/**
	 * Deletes widget mappings owned by an origin widget repository and destination widget repository.
	 */
	public static void deleteWidgetMaps(Connection connection, String originWidgetRepositoryId, String destWidgetRepositoryId) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_MigWidgetMap "
				+ "where destRepoInstanceId = ? "
				+ "and originWidgetContentId in ("
				+ "select id "
				+ "from lum_Content "
				+ "where serviceInstanceId = ? "
				+ "and sourceId = 'widget')";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, destWidgetRepositoryId);
			statement.setString(2, originWidgetRepositoryId);
			statement.executeUpdate();
		}
	}
}
