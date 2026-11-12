package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.HierarchyNode;

/** Reads the hierarchy of the contents of a hierarchical content service instance. */
public final class HierarchyQueries
{
	private HierarchyQueries()
	{
	}

	/**
	 * Loads the hierarchy of the origin instance: one node per content, whatever its state, so
	 * archived contents and drafts are there too. The row of the default locale gives the title and
	 * the parent; a content without a row for its default locale uses its oldest locale. One scan of
	 * the instance, in SQL that MySQL, Oracle and SQL Server all accept, with no list of ids bound.
	 */
	public static HierarchyTree loadTree(Connection connection, String serviceInstanceId) throws SQLException
	{
		// @formatter:off
		// With an active version, h is that version, so its parent is the active parent.
		String sql = "select c.id, cl.locale, c.defaultLocale, cl.publishedVersionId, h.parentContentId, h.title, h.hidden, h.position, cl.id, cl.activeVersionId, ph.parentContentId "
				+ "from lum_Content c "
				+ "inner join lum_ContentLocale cl on cl.contentId = c.id "
				+ "inner join lum_ContentVersion cv on cv.id = coalesce(cl.activeVersionId, cl.publishedVersionId) "
				+ "inner join lum_HierarchicalContent h on h.id = cv.itemId "
				+ "left join lum_ContentVersion pv on pv.id = cl.publishedVersionId "
				+ "left join lum_HierarchicalContent ph on ph.id = pv.itemId "
				+ "where c.serviceInstanceId = ? "
				+ "order by c.id, cl.createdDateTime, cl.id";
		// @formatter:on
		Map<String, HierarchyNode> chosen = new LinkedHashMap<>();
		Map<String, Boolean> chosenIsDefault = new LinkedHashMap<>();
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, serviceInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				while (resultSet.next())
				{
					String contentId = resultSet.getString(1);
					boolean isDefault = Objects.equals(resultSet.getString(2), resultSet.getString(3));
					// A content keeps the row of its default locale, or else the first row read.
					boolean keepsChosenRow = Boolean.TRUE.equals(chosenIsDefault.get(contentId)) || !isDefault;
					if (chosen.containsKey(contentId) && keepsChosenRow)
					{
						continue;
					}
					chosen.put(contentId, readNode(resultSet, contentId));
					chosenIsDefault.put(contentId, isDefault);
				}
			}
		}
		return new HierarchyTree(new ArrayList<>(chosen.values()));
	}

	private static HierarchyNode readNode(ResultSet resultSet, String contentId) throws SQLException
	{
		// Columns are read in select order, which every JDBC driver supports.
		boolean published = resultSet.getString(4) != null;
		String parentContentId = resultSet.getString(5);
		HierarchyNode.Builder builder = HierarchyNode.builder()
				.contentId(contentId)
				.published(published)
				.parentContentId(parentContentId)
				.title(resultSet.getString(6))
				.hidden(resultSet.getBoolean(7))
				.position(resultSet.getInt(8))
				.referenceContentLocaleId(resultSet.getString(9));
		boolean hasActiveVersion = resultSet.getString(10) != null;
		return builder.activeParentId(hasActiveVersion ? parentContentId : null)
				.publishedParentId(resultSet.getString(11))
				.build();
	}

	/** Returns, for each content of the instance, how many locales it has; each locale is one item to migrate. */
	public static Map<String, Integer> countLocalesByContent(Connection connection, String serviceInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "select c.id, count(*) "
				+ "from lum_Content c "
				+ "inner join lum_ContentLocale cl on cl.contentId = c.id "
				+ "where c.serviceInstanceId = ? "
				+ "group by c.id";
		// @formatter:on
		Map<String, Integer> counts = new LinkedHashMap<>();
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, serviceInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				while (resultSet.next())
				{
					counts.put(resultSet.getString(1), resultSet.getInt(2));
				}
			}
		}
		return counts;
	}
}
