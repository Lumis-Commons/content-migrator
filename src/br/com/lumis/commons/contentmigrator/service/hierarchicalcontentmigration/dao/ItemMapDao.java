package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.ItemMapRow;

/**
 * JDBC access to lum_cmig_HMigItemMap, which maps origin items to the destination items copied or
 * cloned from them. Every method uses the connection of the caller's transaction and never commits.
 */
public class ItemMapDao
{
	/**
	 * Counts CONTENT map rows for a migration pair, including retained references.
	 */
	public static int countContentItemMaps(Connection connection, String originServiceInstanceId, String destServiceInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_cmig_HMigItemMap "
				+ "where kind = 'CONTENT' "
				+ "and originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originServiceInstanceId);
			statement.setString(2, destServiceInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1);
			}
		}
	}

	/**
	 * Returns mapped items for an origin and destination pair, filtering either CONTENT rows or
	 * repository dependency clones.
	 */
	public static List<ItemMapRow> listItemMaps(Connection connection, String originServiceInstanceId, String destServiceInstanceId, boolean isContentOnly)
			throws SQLException
	{
		List<ItemMapRow> result = new ArrayList<>();
		// @formatter:off
		String sql = "select id, kind, serviceId, sourceId, locale, warnings, destCreated, "
				+ "originContentLocaleId, originItemId, destContentId, destContentLocaleId, destItemId "
				+ "from lum_cmig_HMigItemMap "
				+ "where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? "
				+ (isContentOnly ? "and kind = 'CONTENT' " : "and kind <> 'CONTENT' ")
				+ "order by kind, sourceId, destContentLocaleId";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originServiceInstanceId);
			statement.setString(2, destServiceInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				while (resultSet.next())
				{
					result.add(mapItemMapRow(resultSet));
				}
			}
		}
		return result;
	}

	private static ItemMapRow mapItemMapRow(ResultSet resultSet) throws SQLException
	{
		ItemMapRow row = new ItemMapRow();
		row.setId(resultSet.getString("id"));
		row.setKind(resultSet.getString("kind"));
		row.setServiceId(resultSet.getString("serviceId"));
		row.setSourceId(resultSet.getString("sourceId"));
		row.setLocale(resultSet.getString("locale"));
		row.setWarnings(resultSet.getString("warnings"));
		row.setDestCreated(resultSet.getBoolean("destCreated"));
		row.setOriginContentLocaleId(resultSet.getString("originContentLocaleId"));
		row.setOriginItemId(resultSet.getString("originItemId"));
		row.setDestContentId(resultSet.getString("destContentId"));
		row.setDestContentLocaleId(resultSet.getString("destContentLocaleId"));
		row.setDestItemId(resultSet.getString("destItemId"));
		return row;
	}

	/**
	 * Inserts a row of lum_cmig_HMigItemMap; null fields are stored as null.
	 *
	 * @throws SQLException
	 *             if the (originServiceInstanceId, destServiceInstanceId, sourceId,
	 *             originContentLocaleId) key already exists.
	 */
	public static void insertItemMap(Connection connection, ItemMapRow row) throws SQLException
	{
		// @formatter:off
		String sql = "insert into lum_cmig_HMigItemMap (id, kind, serviceId, sourceId, locale, warnings, destCreated, "
				+ "originServiceInstanceId, originContentId, originContentLocaleId, originItemId, "
				+ "originFileFingerprint, destServiceInstanceId, destContentId, destContentLocaleId, destItemId) "
				+ "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, row.getId());
			statement.setString(2, row.getKind());
			statement.setString(3, row.getServiceId());
			statement.setString(4, row.getSourceId());
			statement.setString(5, row.getLocale());
			statement.setString(6, row.getWarnings());
			statement.setBoolean(7, row.isDestCreated());
			statement.setString(8, row.getOriginServiceInstanceId());
			statement.setString(9, row.getOriginContentId());
			statement.setString(10, row.getOriginContentLocaleId());
			statement.setString(11, row.getOriginItemId());
			statement.setString(12, row.getOriginFileFingerprint());
			statement.setString(13, row.getDestServiceInstanceId());
			statement.setString(14, row.getDestContentId());
			statement.setString(15, row.getDestContentLocaleId());
			statement.setString(16, row.getDestItemId());
			statement.executeUpdate();
		}
	}

	/**
	 * Replaces the warning text for an item map row.
	 */
	public static void updateItemMapWarnings(Connection connection, String id, String warnings) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_HMigItemMap "
				+ "set warnings = ? "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, warnings);
			statement.setString(2, id);
			statement.executeUpdate();
		}
	}

	/**
	 * Deletes one item map row after its clone was removed or ownership was released.
	 */
	public static void deleteItemMap(Connection connection, String id) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_HMigItemMap "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, id);
			statement.executeUpdate();
		}
	}

	/**
	 * Deletes only CONTENT map rows for a service-instance pair.
	 */
	public static void deleteContentItemMaps(Connection connection, String originServiceInstanceId, String destServiceInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_HMigItemMap "
				+ "where kind = 'CONTENT' "
				+ "and originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originServiceInstanceId);
			statement.setString(2, destServiceInstanceId);
			statement.executeUpdate();
		}
	}

	/**
	 * Deletes dependency-clone map rows for a repository pair without deleting destination objects.
	 */
	public static void deleteRepositoryItemMaps(Connection connection, String originRepoInstanceId, String destRepoInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "delete from lum_cmig_HMigItemMap "
				+ "where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? "
				+ "and kind <> 'CONTENT'";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originRepoInstanceId);
			statement.setString(2, destRepoInstanceId);
			statement.executeUpdate();
		}
	}
}
