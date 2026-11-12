package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import lumis.util.PortalUtil;

/**
 * JDBC access to lum_cmig_HMigSelection, which stores the contents the user marked in a migration.
 * Only the marked contents are stored: their descendants are migrated because of them, and are found
 * again each time the migration runs. Every method uses the connection of the caller's transaction
 * and never commits.
 */
public class SelectionDao
{
	/** Stores the marked contents of a migration. */
	public static void insertAll(Connection connection, String migrationId, Collection<String> contentIds) throws SQLException
	{
		// @formatter:off
		String sql = "insert into lum_cmig_HMigSelection (id, migrationId, contentId) "
				+ "values (?, ?, ?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			for (String contentId : contentIds)
			{
				statement.setString(1, PortalUtil.generateNewGuid());
				statement.setString(2, migrationId);
				statement.setString(3, contentId);
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	/** Returns the ids of the contents marked in a migration. */
	public static List<String> listContentIds(Connection connection, String migrationId) throws SQLException
	{
		// @formatter:off
		String sql = "select contentId "
				+ "from lum_cmig_HMigSelection "
				+ "where migrationId = ? "
				+ "order by contentId";
		// @formatter:on
		List<String> ids = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, migrationId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				while (resultSet.next())
				{
					ids.add(resultSet.getString(1));
				}
			}
		}
		return ids;
	}

	/** Deletes the marked contents of a migration. */
	public static void deleteByMigration(Connection connection, String migrationId) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_HMigSelection "
				+ "where migrationId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, migrationId);
			statement.executeUpdate();
		}
	}
}
