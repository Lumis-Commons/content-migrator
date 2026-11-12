package br.com.lumis.commons.contentmigrator.service.contentmigration.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.RepositoryMapRow;

/**
 * JDBC access to lum_cmig_MigRepoMap, which stores the repository pairs used by migrations. Every
 * method uses the connection of the caller's transaction and never commits.
 */
public class RepositoryMapDao
{
	/**
	 * Inserts the repository pair used by a migration if it is not already registered.
	 */
	public static void saveRepoMap(Connection connection, RepositoryMapRow row) throws SQLException
	{
		// @formatter:off
		String selectSql = "select dependencyType "
				+ "from lum_cmig_MigRepoMap "
				+ "where originRepoInstanceId = ? "
				+ "and destRepoInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(selectSql))
		{
			statement.setString(1, row.getOriginRepoInstanceId());
			statement.setString(2, row.getDestRepoInstanceId());
			try (ResultSet resultSet = statement.executeQuery())
			{
				if (resultSet.next())
				{
					return;
				}
			}
		}
		// @formatter:off
		String insertSql = "insert into lum_cmig_MigRepoMap (id, originRepoInstanceId, destRepoInstanceId, dependencyType) "
				+ "values (?,?,?,?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(insertSql))
		{
			statement.setString(1, row.getId());
			statement.setString(2, row.getOriginRepoInstanceId());
			statement.setString(3, row.getDestRepoInstanceId());
			statement.setString(4, row.getDependencyType());
			statement.executeUpdate();
		}
	}

	/**
	 * Deletes an unused repository pair row.
	 */
	public static void deleteRepoMap(Connection connection, String originRepoInstanceId, String destRepoInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_MigRepoMap "
				+ "where originRepoInstanceId = ? "
				+ "and destRepoInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originRepoInstanceId);
			statement.setString(2, destRepoInstanceId);
			statement.executeUpdate();
		}
	}
}
