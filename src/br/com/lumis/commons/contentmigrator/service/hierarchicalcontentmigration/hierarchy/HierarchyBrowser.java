package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.config.ExecutionSettings;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.config.HierarchicalContentService;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.HierarchyNode;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.util.ITransaction;

/**
 * Paged reads for the REST content browser. Snapshots are local to this cluster node and retained
 * for at most 60 seconds by default. Migration execution never uses this cache. Callers check access.
 */
public final class HierarchyBrowser
{
	/** Page size when the request does not give one. */
	public static final int DEFAULT_LIMIT = 200;

	/** Largest page size a request may ask for. */
	public static final int MAX_LIMIT = 500;

	private static final Cache CACHE = new Cache(System::currentTimeMillis);

	private HierarchyBrowser()
	{
	}

	/** Returns the first page with the default limit. */
	public static Map<String, Object> browse(String serviceInstanceId, String parentContentId, ITransaction transaction) throws Exception
	{
		return browse(serviceInstanceId, parentContentId, 0, DEFAULT_LIMIT, transaction);
	}

	/** Returns whether the offset is nonnegative and the limit is between 1 and {@link #MAX_LIMIT}. */
	public static boolean isValidPage(int offset, int limit)
	{
		boolean validLimit = limit >= 1 && limit <= MAX_LIMIT;
		return offset >= 0 && validLimit;
	}

	/** Returns a level sorted by position, title and id, with its breadcrumb and snapshot time. */
	public static Map<String, Object> browse(String serviceInstanceId, String parentContentId, int offset, int limit, ITransaction transaction)
			throws Exception
	{
		if (!isValidPage(offset, limit))
		{
			throw new MigrationApiException("INVALID_PAGE", "offset must be nonnegative and limit must be between 1 and " + MAX_LIMIT);
		}
		Snapshot snapshot = loadSnapshot(serviceInstanceId, transaction);
		HierarchyTree tree = snapshot.tree;
		List<Object> breadcrumb = new ArrayList<>();
		if (parentContentId != null)
		{
			if (!tree.contains(parentContentId))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_NOT_FOUND, "content " + parentContentId);
			}
			for (HierarchyNode node : tree.path(parentContentId))
			{
				breadcrumb.add(summary(node));
			}
		}
		List<HierarchyNode> children = tree.children(parentContentId);
		List<Object> items = new ArrayList<>();
		int end = (int) Math.min(children.size(), (long) offset + limit);
		for (int index = Math.min(offset, children.size()); index < end; index++)
		{
			HierarchyNode node = children.get(index);
			Map<String, Object> item = summary(node);
			item.put("childCount", tree.childCount(node.getContentId()));
			item.put("hasChildren", tree.childCount(node.getContentId()) > 0);
			item.put("published", node.isPublished());
			item.put("hidden", node.isHidden());
			items.add(item);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("serviceInstanceId", serviceInstanceId);
		result.put("parentContentId", parentContentId);
		result.put("breadcrumb", breadcrumb);
		result.put("items", items);
		result.put("total", children.size());
		result.put("offset", offset);
		result.put("limit", limit);
		result.put("snapshotMillis", snapshot.snapshotMillis);
		return result;
	}

	/** Returns minimal marks, ancestor paths for all known submitted ids, unknown ids and counts. */
	public static Map<String, Object> resolveSelection(String serviceInstanceId, Collection<String> markedContentIds, ITransaction transaction)
			throws Exception
	{
		if (markedContentIds.size() > ExecutionSettings.maxSelection())
		{
			throw new MigrationApiException("SELECTION_TOO_LARGE", "too many submitted content ids");
		}
		Snapshot snapshot = loadSnapshot(serviceInstanceId, transaction);
		HierarchyTree tree = snapshot.tree;
		MigrationSelection selection = MigrationSelection.of(tree, snapshot.localesByContent, tree.minimalSelection(markedContentIds));
		Map<String, Object> paths = new LinkedHashMap<>();
		Collection<String> unknownContentIds = new LinkedHashSet<>();
		for (String id : markedContentIds)
		{
			if (!tree.contains(id))
			{
				unknownContentIds.add(id);
				continue;
			}
			List<String> path = new ArrayList<>();
			for (HierarchyNode ancestor : tree.path(id))
			{
				if (!id.equals(ancestor.getContentId()))
				{
					path.add(ancestor.getContentId());
				}
			}
			paths.put(id, path);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("markedContentIds", selection.getMarkedContentIds());
		result.put("paths", paths);
		result.put("unknownContentIds", unknownContentIds);
		result.put("contentCount", selection.getContentIds().size());
		result.put("localeCount", selection.getLocaleCount());
		return result;
	}

	private static Snapshot loadSnapshot(String serviceInstanceId, ITransaction transaction) throws Exception
	{
		// Validate the instance on every request, including cache hits.
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(serviceInstanceId, transaction);
		if (!HierarchicalContentService.SERVICE_ID.equals(instance.getServiceId()))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_NOT_HIERARCHICAL, "service instance " + serviceInstanceId);
		}
		return CACHE.get(serviceInstanceId, () ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			return new Snapshot(HierarchyQueries.loadTree(connection, serviceInstanceId),
					HierarchyQueries.countLocalesByContent(connection, serviceInstanceId));
		});
	}

	private static Map<String, Object> summary(HierarchyNode node)
	{
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("contentId", node.getContentId());
		summary.put("title", node.getTitle());
		return summary;
	}

	/** A tree and its locale counts, loaded together for the browser. */
	public static final class Snapshot
	{
		private final HierarchyTree tree;

		private final Map<String, Integer> localesByContent;

		private long snapshotMillis;

		public Snapshot(HierarchyTree tree, Map<String, Integer> localesByContent)
		{
			this.tree = tree;
			this.localesByContent = Map.copyOf(localesByContent);
		}

		/** Returns the hierarchy of the instance. */
		public HierarchyTree getTree()
		{
			return tree;
		}

		/** Returns when the load of this snapshot started, by the clock of its cache. */
		public long getSnapshotMillis()
		{
			return snapshotMillis;
		}
	}

	/** Loads a snapshot on the requesting thread, which owns the database transaction. */
	@FunctionalInterface
	public interface SnapshotLoader
	{
		Snapshot load() throws Exception;
	}

	/** Single load per instance, injectable clock, and at most three retained snapshots in LRU order. */
	public static final class Cache
	{
		private static final int MAX_INSTANCES = 3;

		private final LongSupplier clock;

		private final Map<String, Snapshot> retained = new LinkedHashMap<>(4, 0.75f, true);

		private final ConcurrentHashMap<String, CompletableFuture<Snapshot>> loading = new ConcurrentHashMap<>();

		public Cache(LongSupplier clock)
		{
			this.clock = clock;
		}

		/**
		 * Returns the retained snapshot of the instance or loads it; concurrent requests for the same
		 * instance wait for one load and share its result or its failure.
		 */
		public Snapshot get(String instanceId, SnapshotLoader loader) throws Exception
		{
			Snapshot hit = cached(instanceId);
			if (hit != null)
			{
				return hit;
			}
			CompletableFuture<Snapshot> future = new CompletableFuture<>();
			CompletableFuture<Snapshot> existing = loading.putIfAbsent(instanceId, future);
			if (existing != null)
			{
				try
				{
					return existing.get();
				}
				catch (InterruptedException exception)
				{
					Thread.currentThread().interrupt();
					throw exception;
				}
				catch (ExecutionException exception)
				{
					Throwable cause = exception.getCause();
					if (cause instanceof Exception)
					{
						throw (Exception) cause;
					}
					throw (Error) cause;
				}
			}
			try
			{
				// A previous load may have finished between the lookup and putIfAbsent.
				Snapshot snapshot = cached(instanceId);
				if (snapshot == null)
				{
					// The rows read are at least as recent as the start of the load.
					long started = clock.getAsLong();
					snapshot = loader.load();
					snapshot.snapshotMillis = started;
					retain(instanceId, snapshot);
				}
				future.complete(snapshot);
				return snapshot;
			}
			catch (Exception | Error exception)
			{
				future.completeExceptionally(exception);
				throw exception;
			}
			finally
			{
				loading.remove(instanceId, future);
			}
		}

		private synchronized Snapshot cached(String instanceId)
		{
			long now = clock.getAsLong();
			retained.values().removeIf(snapshot -> !isFresh(snapshot, now));
			return retained.get(instanceId);
		}

		private synchronized void retain(String instanceId, Snapshot snapshot)
		{
			if (!isFresh(snapshot, clock.getAsLong()))
			{
				return;
			}
			retained.put(instanceId, snapshot);
			while (retained.size() > MAX_INSTANCES)
			{
				retained.remove(retained.keySet().iterator().next());
			}
		}

		/**
		 * Returns whether the snapshot may be served: younger than the TTL, which is zero when caching
		 * is off, and not above the node ceiling. A clock that went back also expires the snapshot.
		 */
		private static boolean isFresh(Snapshot snapshot, long now)
		{
			long age = now - snapshot.snapshotMillis;
			boolean withinTtl = age >= 0 && age < ExecutionSettings.browserCacheMillis();
			return withinTtl && snapshot.tree.size() <= ExecutionSettings.browserCacheMaxNodes();
		}
	}
}
