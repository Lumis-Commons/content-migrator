package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.SelectionDao;

/**
 * What a migration migrates: the contents the user marked and, because of them, all of their
 * descendants. The descendants are not stored. They are read from the origin instance each time, so
 * a content added under a marked content is migrated by the next incremental run.
 */
public final class MigrationSelection
{
	private final List<String> markedContentIds;

	private final Set<String> contentIds;

	private final HierarchyTree tree;

	private final int localeCount;

	private MigrationSelection(List<String> markedContentIds, HierarchyTree tree, Set<String> contentIds, int localeCount)
	{
		this.markedContentIds = markedContentIds;
		this.tree = tree;
		this.contentIds = contentIds;
		this.localeCount = localeCount;
	}

	/** Reads the marked contents of a stored migration and expands them over the origin hierarchy. */
	public static MigrationSelection load(Connection connection, String migrationId, String originServiceInstanceId) throws SQLException
	{
		return of(connection, originServiceInstanceId, SelectionDao.listContentIds(connection, migrationId));
	}

	/** Expands the given marked contents over the hierarchy of the origin instance. */
	public static MigrationSelection of(Connection connection, String originServiceInstanceId, Collection<String> markedContentIds) throws SQLException
	{
		HierarchyTree tree = HierarchyQueries.loadTree(connection, originServiceInstanceId);
		return of(connection, originServiceInstanceId, tree, markedContentIds);
	}

	/** Reuses a freshly loaded hierarchy, reading only the locale counts from the database. */
	public static MigrationSelection of(Connection connection, String originServiceInstanceId, HierarchyTree tree,
			Collection<String> markedContentIds) throws SQLException
	{
		return of(tree, HierarchyQueries.countLocalesByContent(connection, originServiceInstanceId), markedContentIds);
	}

	/** Expands marks over one browser snapshot without any database reads. */
	public static MigrationSelection of(HierarchyTree tree, Map<String, Integer> localesByContent, Collection<String> markedContentIds)
	{
		Set<String> closure = tree.closure(markedContentIds);
		int localeCount = 0;
		for (String contentId : closure)
		{
			localeCount += localesByContent.getOrDefault(contentId, 0);
		}
		return new MigrationSelection(List.copyOf(markedContentIds), tree, closure, localeCount);
	}

	/** Returns the marked contents. */
	public List<String> getMarkedContentIds()
	{
		return markedContentIds;
	}

	/** Returns the contents to migrate: the marked ones and all of their descendants. */
	public Set<String> getContentIds()
	{
		return contentIds;
	}

	/** Returns the number of items to migrate, one for each locale of each content to migrate. */
	public int getLocaleCount()
	{
		return localeCount;
	}

	/** Returns the hierarchy of the origin instance this selection was expanded over. */
	public HierarchyTree getTree()
	{
		return tree;
	}
}
