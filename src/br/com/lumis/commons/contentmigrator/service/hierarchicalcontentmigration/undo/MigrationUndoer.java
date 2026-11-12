package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.undo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.copy.WorkflowCloner;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.ItemMapDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.MigrationDao;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandlerRegistry;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.ItemMapRow;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RepositoryPair;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.UndoItem;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.lock.GlobalLock;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.validation.DependencyRules;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;

/**
 * Deletes migration-owned destination content and repository clones through their native content
 * sources while retaining any item still referenced by another content or migration.
 */
public final class MigrationUndoer
{
	private MigrationUndoer()
	{
	}

	/**
	 * Returns the distinct repository pairs configured on an origin and destination service
	 * instance pair across all migrated repository types.
	 */
	public static List<RepositoryPair> repositoryPairs(
			SessionConfig session,
			ITransaction transaction,
			String originServiceInstanceId,
			String destinationServiceInstanceId) throws Exception
	{
		Map<String, RepositoryPair> pairs = new LinkedHashMap<>();
		for (String dependencyType : DependencyRules.REQUIRED_REPOSITORY_TYPES)
		{
			String originRepository = DependencyRules.repository(session, originServiceInstanceId, dependencyType, transaction);
			String destinationRepository = DependencyRules.repository(session, destinationServiceInstanceId, dependencyType, transaction);
			if (originRepository == null || destinationRepository == null)
			{
				continue;
			}
			String key = originRepository + ":" + destinationRepository;
			pairs.putIfAbsent(key, new RepositoryPair(originRepository, destinationRepository, dependencyType));
		}
		return new ArrayList<>(pairs.values());
	}

	/**
	 * Returns whether another saved migration still uses the same origin and destination repository
	 * pair so shared repository clones are not undone prematurely.
	 */
	public static boolean isRepositoryPairUsedByAnother(
			Connection connection,
			SessionConfig session,
			ITransaction transaction,
			String excludingMigrationId,
			RepositoryPair pair) throws Exception
	{
		for (MigrationRow migration : MigrationDao.listMigrations(connection))
		{
			if (excludingMigrationId.equals(migration.getId()))
			{
				continue;
			}
			for (RepositoryPair other : repositoryPairs(session, transaction, migration.getOriginServiceInstanceId(), migration.getDestServiceInstanceId()))
			{
				boolean isSameOrigin = pair.getOriginRepositoryId().equals(other.getOriginRepositoryId());
				boolean isSameDestination = pair.getDestinationRepositoryId().equals(other.getDestinationRepositoryId());
				if (isSameOrigin && isSameDestination)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Discovers mapped content locales and orders children and association sources before their
	 * target contents so native foreign-key and hierarchy checks succeed during deletion.
	 */
	public static List<UndoItem> contentItems(String originServiceInstanceId, String destinationServiceInstanceId) throws Exception
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			List<ItemMapRow> maps = ItemMapDao.listItemMaps(connection, originServiceInstanceId, destinationServiceInstanceId, true);
			List<ItemMapRow> ordered = orderContentMaps(connection, maps);
			List<UndoItem> result = new ArrayList<>();
			for (ItemMapRow map : ordered)
			{
				result.add(new UndoItem(map, null));
			}
			for (ItemMapRow map : maps)
			{
				if (map.getDestContentId() == null)
				{
					result.add(new UndoItem(map, null));
				}
			}
			transaction.commit();
			return result;
		}
	}

	private static List<ItemMapRow> orderContentMaps(Connection connection, List<ItemMapRow> maps) throws SQLException
	{
		Map<String, List<ItemMapRow>> byContent = new LinkedHashMap<>();
		for (ItemMapRow map : maps)
		{
			String contentId = map.getDestContentId();
			if (contentId != null)
			{
				byContent.computeIfAbsent(contentId, key -> new ArrayList<>()).add(map);
			}
		}
		Map<String, Set<String>> parents = new HashMap<>();
		Set<String> contentIds = byContent.keySet();
		// @formatter:off
		String hierarchicalSql = "select cl.contentId, h.parentContentId "
				+ "from lum_HierarchicalContent h "
				+ "inner join lum_ContentVersion cv on h.id = cv.itemId "
				+ "inner join lum_ContentLocale cl on cv.contentLocaleId = cl.id "
				+ "where h.parentContentId in (";
		String associationSql = "select cl.contentId, a.associatedContentId "
				+ "from lum_ContentAssociation a "
				+ "inner join lum_ContentVersion cv on a.contentVersionId = cv.id "
				+ "inner join lum_ContentLocale cl on cv.contentLocaleId = cl.id "
				+ "where a.associatedContentId in (";
		// @formatter:on
		queryContentEdges(connection, hierarchicalSql, contentIds, parents);
		queryContentEdges(connection, associationSql, contentIds, parents);

		List<String> orderedIds = orderTopologicallyChildFirst(contentIds, parents);
		List<ItemMapRow> result = new ArrayList<>();
		for (String id : orderedIds)
		{
			List<ItemMapRow> rows = byContent.get(id);
			rows.sort(Comparator.comparing(row -> String.valueOf(row.getDestContentLocaleId())));
			result.addAll(rows);
		}
		return result;
	}

	private static void queryContentEdges(Connection connection, String queryPrefix, Collection<String> targetIds, Map<String, Set<String>> parents) throws SQLException
	{
		List<String> values = new ArrayList<>(targetIds);
		for (int start = 0; start < values.size(); start += 400)
		{
			List<String> batch = values.subList(start, Math.min(start + 400, values.size()));
			String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
			try (PreparedStatement statement = connection.prepareStatement(queryPrefix + placeholders + ")"))
			{
				for (int index = 0; index < batch.size(); index++)
				{
					statement.setString(index + 1, batch.get(index));
				}
				try (ResultSet resultSet = statement.executeQuery())
				{
					readContentEdges(resultSet, targetIds, parents);
				}
			}
		}
	}

	private static void readContentEdges(ResultSet resultSet, Collection<String> targetIds, Map<String, Set<String>> parents) throws SQLException
	{
		while (resultSet.next())
		{
			String child = resultSet.getString(1);
			String parent = resultSet.getString(2);
			boolean hasDistinctEndpoints = child != null && parent != null && !child.equals(parent);
			if (hasDistinctEndpoints && targetIds.contains(child))
			{
				parents.computeIfAbsent(child, key -> new LinkedHashSet<>()).add(parent);
			}
		}
	}

	private static List<String> orderTopologicallyChildFirst(Collection<String> ids, Map<String, Set<String>> parents)
	{
		List<String> stableIds = new ArrayList<>(ids);
		Collections.sort(stableIds);
		Map<String, Integer> incoming = countIncomingEdges(stableIds, parents);

		List<String> queue = new ArrayList<>();
		for (String id : stableIds)
		{
			if (incoming.get(id) == 0)
			{
				queue.add(id);
			}
		}

		List<String> ordered = new ArrayList<>();
		for (int index = 0; index < queue.size(); index++)
		{
			String child = queue.get(index);
			ordered.add(child);
			List<String> parentIds = new ArrayList<>(parents.getOrDefault(child, Collections.emptySet()));
			Collections.sort(parentIds);
			for (String parent : parentIds)
			{
				if (!incoming.containsKey(parent))
				{
					continue;
				}
				int remaining = incoming.get(parent) - 1;
				incoming.put(parent, remaining);
				if (remaining == 0)
				{
					queue.add(parent);
				}
			}
		}

		// A cyclic content or term relationship cannot be topologically ordered. Keep those rows
		// after the acyclic portion so their source can report the native validation error.
		for (String id : stableIds)
		{
			if (!ordered.contains(id))
			{
				ordered.add(id);
			}
		}
		return ordered;
	}

	private static Map<String, Integer> countIncomingEdges(List<String> stableIds, Map<String, Set<String>> parents)
	{
		Map<String, Integer> incoming = new HashMap<>();
		for (String id : stableIds)
		{
			incoming.put(id, 0);
		}
		for (String child : stableIds)
		{
			for (String parent : parents.getOrDefault(child, Collections.emptySet()))
			{
				if (incoming.containsKey(parent))
				{
					incoming.put(parent, incoming.get(parent) + 1);
				}
			}
		}
		return incoming;
	}

	/**
	 * Discovers owned repository-clone maps for repository pairs not shared with another migration,
	 * ordering children and files before parent folders or terms.
	 */
	public static List<UndoItem> repositoryItems(SessionConfig session, String migrationId, String originServiceInstanceId, String destinationServiceInstanceId)
			throws Exception
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			List<UndoItem> result = new ArrayList<>();
			for (RepositoryPair pair : repositoryPairs(session, transaction, originServiceInstanceId, destinationServiceInstanceId))
			{
				if (isRepositoryPairUsedByAnother(connection, session, transaction, migrationId, pair))
				{
					continue;
				}
				List<ItemMapRow> maps = ItemMapDao.listItemMaps(connection, pair.getOriginRepositoryId(), pair.getDestinationRepositoryId(), false);
				for (ItemMapRow map : orderRepositoryMaps(connection, maps, pair.getDependencyType()))
				{
					result.add(new UndoItem(map, pair));
				}
			}
			transaction.commit();
			return result;
		}
	}

	private static List<ItemMapRow> orderRepositoryMaps(Connection connection, List<ItemMapRow> maps, String dependencyType) throws SQLException
	{
		Map<String, ItemMapRow> byItem = new LinkedHashMap<>();
		Map<String, ItemMapRow> byContent = new LinkedHashMap<>();
		List<String> itemIds = new ArrayList<>();
		for (ItemMapRow map : maps)
		{
			String itemId = map.getDestItemId();
			if (itemId != null)
			{
				byItem.put(itemId, map);
				itemIds.add(itemId);
			}
			String contentId = map.getDestContentId();
			if (contentId != null)
			{
				byContent.put(contentId, map);
			}
		}

		Map<String, String> parentByItem = collectParentByItem(connection, dependencyType, itemIds);
		Map<String, Set<String>> parents = new HashMap<>();
		for (ItemMapRow map : maps)
		{
			String itemId = map.getDestItemId();
			ItemMapRow parent = resolveParentMap(dependencyType, parentByItem.get(itemId), byContent, byItem);
			if (parent != null)
			{
				parents.computeIfAbsent(itemId, key -> new LinkedHashSet<>()).add(parent.getDestItemId());
			}
		}

		List<String> orderedIds = orderTopologicallyChildFirst(byItem.keySet(), parents);
		List<ItemMapRow> result = new ArrayList<>();
		for (String id : orderedIds)
		{
			result.add(byItem.get(id));
		}
		return result;
	}

	private static Map<String, String> collectParentByItem(Connection connection, String dependencyType, List<String> itemIds) throws SQLException
	{
		boolean isMediaRepository = dependencyType.equals(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY);
		boolean isDocumentRepository = dependencyType.equals(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY);
		if (isMediaRepository || isDocumentRepository)
		{
			String table = isMediaRepository ? "lum_Media" : "lum_Document";
			return queryParentItems(connection, table, itemIds);
		}
		if (dependencyType.equals(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY))
		{
			return queryParentTerms(connection, itemIds);
		}
		return Collections.emptyMap();
	}

	private static Map<String, String> queryParentItems(Connection connection, String table, List<String> itemIds) throws SQLException
	{
		Map<String, String> result = new HashMap<>();
		for (int start = 0; start < itemIds.size(); start += 400)
		{
			List<String> batch = itemIds.subList(start, Math.min(start + 400, itemIds.size()));
			String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
			// @formatter:off
			String sql = "select id, parentFolder "
					+ "from " + table + " "
					+ "where id in (" + placeholders + ")";
			// @formatter:on
			try (PreparedStatement statement = connection.prepareStatement(sql))
			{
				for (int index = 0; index < batch.size(); index++)
				{
					statement.setString(index + 1, batch.get(index));
				}
				try (ResultSet resultSet = statement.executeQuery())
				{
					while (resultSet.next())
					{
						result.put(resultSet.getString(1), resultSet.getString(2));
					}
				}
			}
		}
		return result;
	}

	/**
	 * Returns the parent term content id of each given term. A term clone's item id is its content
	 * id, so the caller passes the item ids of the map rows.
	 */
	private static Map<String, String> queryParentTerms(Connection connection, List<String> termContentIds) throws SQLException
	{
		Map<String, String> result = new HashMap<>();
		for (int start = 0; start < termContentIds.size(); start += 400)
		{
			List<String> batch = termContentIds.subList(start, Math.min(start + 400, termContentIds.size()));
			String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
			// @formatter:off
			String sql = "select cl.contentId, t.parentTermContentId "
					+ "from lum_CategorizationTerm t "
					+ "inner join lum_ContentVersion cv on t.termId = cv.itemId "
					+ "inner join lum_ContentLocale cl on cv.contentLocaleId = cl.id "
					+ "where cl.contentId in (" + placeholders + ")";
			// @formatter:on
			try (PreparedStatement statement = connection.prepareStatement(sql))
			{
				for (int index = 0; index < batch.size(); index++)
				{
					statement.setString(index + 1, batch.get(index));
				}
				try (ResultSet resultSet = statement.executeQuery())
				{
					while (resultSet.next())
					{
						result.put(resultSet.getString(1), resultSet.getString(2));
					}
				}
			}
		}
		return result;
	}

	private static ItemMapRow resolveParentMap(String dependencyType, String parentId, Map<String, ItemMapRow> byContent, Map<String, ItemMapRow> byItem)
	{
		if (parentId == null)
		{
			return null;
		}
		if (dependencyType.equals(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY))
		{
			return byContent.get(parentId);
		}
		return byItem.get(parentId);
	}

	/**
	 * Deletes one destination content locale in its own transaction, or retains it and records a
	 * warning on its map row when another destination content still references it.
	 */
	public static boolean undoContent(SessionConfig session, String runId, String destinationServiceInstanceId, UndoItem item) throws Exception
	{
		ItemMapRow map = item.getMap();
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			ContentLocale locale = loadLocale(map.getDestContentLocaleId());
			boolean isCreatedDestination = locale != null && map.isDestCreated();
			if (isCreatedDestination && isContentReferenced(connection, locale.getContent().getId()))
			{
				String warning = "Destination content " + locale.getContent().getId() + " is still referenced by another content and was retained during undo.";
				ItemMapDao.updateItemMapWarnings(connection, map.getId(), appendWarning(map.getWarnings(), warning));
			}
			else
			{
				if (isCreatedDestination)
				{
					deleteDestinationContentLocale(session, transaction, destinationServiceInstanceId, map, locale);
				}
				ItemMapDao.deleteItemMap(connection, map.getId());
			}
			if (!GlobalLock.confirm(connection, runId))
			{
				transaction.rollback();
				return false;
			}
			transaction.commit();
			return true;
		}
	}

	private static ContentLocale loadLocale(String localeId) throws Exception
	{
		if (localeId == null)
		{
			return null;
		}
		try
		{
			return ContentManagerFactory.getContentManager().getContentLocale(localeId);
		}
		catch (PortalObjectNotFoundException exception)
		{
			return null;
		}
	}

	private static boolean isContentReferenced(Connection connection, String contentId) throws SQLException
	{
		// The select reads from lum_Content because Oracle does not accept a select without from.
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_Content c "
				+ "where c.id = ? "
				+ "and (exists (select 1 "
				+ "from lum_ContentAssociation a "
				+ "inner join lum_ContentVersion cv on a.contentVersionId = cv.id "
				+ "inner join lum_ContentLocale cl on cv.contentLocaleId = cl.id "
				+ "where a.associatedContentId = c.id "
				+ "and cl.contentId <> c.id) "
				+ "or exists (select 1 "
				+ "from lum_HierarchicalContent h "
				+ "inner join lum_ContentVersion cv on h.id = cv.itemId "
				+ "inner join lum_ContentLocale cl on cv.contentLocaleId = cl.id "
				+ "where h.parentContentId = c.id "
				+ "and cl.contentId <> c.id))";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, contentId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1) > 0;
			}
		}
	}

	private static String appendWarning(String existing, String warning)
	{
		if (existing == null || existing.trim().isEmpty())
		{
			return warning;
		}
		if (existing.contains(warning))
		{
			return existing;
		}
		return existing + "\n" + warning;
	}

	private static void deleteDestinationContentLocale(
			SessionConfig session,
			ITransaction transaction,
			String destinationServiceInstanceId,
			ItemMapRow map,
			ContentLocale locale) throws Exception
	{
		ContentVersion active = locale.getActiveVersion();
		if (active == null)
		{
			return;
		}
		session.setLocale(locale.getLocale());
		Connection connection = ((ITransactionJdbc) transaction).getConnection();
		WorkflowCloner.returnToStartState(session, transaction, connection, locale);
		ServiceInstanceConfig destination = ManagerFactory.getServiceInstanceManager().get(destinationServiceInstanceId, transaction);
		ContentTableSource source = (ContentTableSource) SourceFactory.createSource(map.getSourceId(), new SourceContext(session, destination, transaction));
		source.deleteDataByItemIds(Collections.singleton(active.getItemId()));
	}

	/**
	 * Deletes an unused repository clone in its own transaction, or retains it and records a
	 * warning on its map row when a native reference remains.
	 */
	public static boolean undoRepositoryItem(SessionConfig session, String runId, MigrationRow migration, UndoItem item) throws Exception
	{
		ItemMapRow map = item.getMap();
		RepositoryPair pair = item.getRepositoryPair();
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			MigrationScope scope = MigrationScope.builder()
					.session(session)
					.transaction(transaction)
					.connection(connection)
					.serviceId(migration.getServiceId())
					.originServiceInstanceId(migration.getOriginServiceInstanceId())
					.destinationServiceInstanceId(migration.getDestServiceInstanceId())
					.build();
			String localeId = map.getDestContentLocaleId();
			DependencyHandler handler = DependencyHandlerRegistry.getHandler(pair.getDependencyType());
			if (handler == null)
			{
				throw new IllegalStateException("No dependency handler for " + pair.getDependencyType());
			}
			if (localeId == null || loadLocale(localeId) == null)
			{
				ItemMapDao.deleteItemMap(connection, map.getId());
			}
			else
			{
				undoExistingRepositoryClone(scope, item, handler);
			}
			if (!GlobalLock.confirm(connection, runId))
			{
				transaction.rollback();
				return false;
			}
			transaction.commit();
			return true;
		}
	}

	private static void undoExistingRepositoryClone(MigrationScope scope, UndoItem item, DependencyHandler handler) throws Exception
	{
		ItemMapRow map = item.getMap();
		String localeId = map.getDestContentLocaleId();
		DependencyContext context = new DependencyContext(scope, item.getRepositoryPair(), null);
		if (map.isDestCreated() && handler.isCloneReferenced(context, localeId))
		{
			String warning = map.getKind() + " clone " + map.getDestItemId() + " is still referenced and was retained during undo.";
			ItemMapDao.updateItemMapWarnings(scope.getConnection(), map.getId(), appendWarning(map.getWarnings(), warning));
			return;
		}
		// The handler deletes the clone only when the migration created it, and removes its map rows.
		handler.deleteClone(context, localeId);
	}
}
