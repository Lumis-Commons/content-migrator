package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.hierarchy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;

/**
 * The rules of the content browser and of the selection, over a tree built in memory:
 *
 * <pre>
 * a            (root)
 * ├─ a1
 * │  └─ a1x    (archived: no published version)
 * └─ a2
 * b            (root)
 * └─ b1
 * orphan       (its parent "gone" is not in the instance, so it is a root)
 * </pre>
 *
 * The browser cache of these trees runs here too, with a manual clock, as does a probe that builds
 * and walks 300 thousand synthetic nodes.
 */
public class HierarchyTreeTest
{
	private static final String NODE = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.HierarchyNode";

	private static final String TREE = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.HierarchyTree";

	private static final String BROWSER = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.HierarchyBrowser";

	private static final String CACHE_SECONDS = "contentmigrator.hierarchicalcontentmigration.browserCacheSeconds";

	private static final String CACHE_MAX_NODES = "contentmigrator.hierarchicalcontentmigration.browserCacheMaxNodes";

	private final Tree tree = tree(
			Arrays.asList(
					node("a1x", "a1", "A1X", false, 0),
					node("b1", "b", "B1", true, 0),
					node("a", null, "A", true, 0),
					node("a2", "a", "A2", true, 1),
					node("b", null, "B", true, 1),
					node("a1", "a", "A1", true, 0),
					node("orphan", "gone", "Orphan", true, 2)));

	@Test
	public void rootsAreTheContentsWithoutAParentInTheInstance()
	{
		assertEquals(Arrays.asList("a", "b", "orphan"), tree.roots());
	}

	@Test
	public void childrenAreTheDirectChildrenInSiblingOrderAndIncludeArchivedContents()
	{
		assertEquals(Arrays.asList("a1", "a2"), tree.children("a"));
		assertEquals(Collections.singletonList("a1x"), tree.children("a1"));
		assertFalse(tree.isPublished("a1x"));
		assertEquals(Collections.emptyList(), tree.children("a2"));
		assertEquals(Collections.emptyList(), tree.children("unknown"));
	}

	@Test
	public void pathGoesFromTheRootDownToTheContent()
	{
		assertEquals(Arrays.asList("a", "a1", "a1x"), tree.path("a1x"));
		assertEquals(Collections.singletonList("orphan"), tree.path("orphan"));
		assertEquals(Collections.emptyList(), tree.path("unknown"));
	}

	@Test
	public void markingAContentMigratesAllOfItsDescendants()
	{
		assertEquals(set("a", "a1", "a1x", "a2"), tree.closure(Collections.singleton("a")));
		assertEquals(set("a1", "a1x"), tree.closure(Collections.singleton("a1")));
		assertEquals(set("a2", "b", "b1"), tree.closure(Arrays.asList("a2", "b")));
	}

	@Test
	public void closureIgnoresIdsThatAreNotInTheInstance()
	{
		assertEquals(set("a2"), tree.closure(Arrays.asList("a2", "unknown")));
	}

	@Test
	public void descendantsAreCountedAtAnyDepth()
	{
		assertEquals(3, tree.descendantCount("a"));
		assertEquals(1, tree.descendantCount("a1"));
		assertEquals(0, tree.descendantCount("a2"));
	}

	@Test
	public void minimalSelectionDropsTheContentsAnAncestorAlreadyCovers()
	{
		assertEquals(set("a", "b1"), tree.minimalSelection(Arrays.asList("a1x", "a", "b1", "a2")));
		assertEquals(set("a1x"), tree.minimalSelection(Collections.singleton("a1x")));
		assertEquals(set("a"), tree.minimalSelection(Arrays.asList("a", "unknown")));
	}

	@Test
	public void minimalSelectionHasTheSameClosureAsTheSelection()
	{
		List<String> selection = Arrays.asList("a1x", "a1", "b1", "a");
		assertEquals(tree.closure(selection), tree.closure(tree.minimalSelection(selection)));
	}

	@Test
	public void ancestorsFirstPutsEveryContentAfterItsParent()
	{
		List<String> ordered = tree.ancestorsFirst(Arrays.asList("a1x", "a2", "a", "a1", "b1"));
		assertEquals(5, ordered.size());
		assertTrue(ordered.indexOf("a") < ordered.indexOf("a1"));
		assertTrue(ordered.indexOf("a1") < ordered.indexOf("a1x"));
		assertTrue(ordered.indexOf("a") < ordered.indexOf("a2"));
	}

	@Test
	public void aCycleInTheDataDoesNotHangTheWalks()
	{
		Tree cyclic = tree(
				Arrays.asList(node("x", "y", "X", true, 0), node("y", "x", "Y", true, 0), node("self", "self", "Self", true, 0)));
		assertEquals(set("x", "y"), cyclic.closure(Collections.singleton("x")));
		assertEquals(Collections.singletonList("x"), cyclic.path("x"));
		assertEquals(set("x", "y", "self"), new java.util.HashSet<>(cyclic.roots()));
		assertEquals(set("x", "y", "self"), new java.util.HashSet<>(cyclic.ancestorsFirst(Arrays.asList("y", "self", "x"))));
		assertEquals(cyclic.closure(Arrays.asList("x", "y")), cyclic.closure(cyclic.minimalSelection(Arrays.asList("x", "y"))));
	}

	@Test
	public void ancestorsFirstRespectsBothActiveAndPublishedParents() throws Exception
	{
		Object builder = ModuleBridge.load(NODE).getMethod("builder").invoke(null);
		Tree.invoke(builder, "contentId", "child");
		Tree.invoke(builder, "parentContentId", "active");
		Tree.invoke(builder, "activeParentId", "active");
		Tree.invoke(builder, "publishedParentId", "published");
		Tree.invoke(builder, "title", "A child sorted before parents");
		Tree hierarchy = tree(Arrays.asList(Tree.invoke(builder, "build"),
				node("active", null, "Z active", true, 0), node("published", null, "Z published", true, 0)));
		List<String> ordered = hierarchy.ancestorsFirst(Arrays.asList("child", "published", "active"));
		assertEquals(3, ordered.size());
		assertTrue(ordered.indexOf("active") < ordered.indexOf("child"));
		assertTrue(ordered.indexOf("published") < ordered.indexOf("child"));
	}

	@Test
	public void theBrowserSnapshotIsReusedUntilItsTtlEndsOrTheClockGoesBack() throws Exception
	{
		withCacheSettings("60", "200000", () ->
		{
			CacheProbe cache = new CacheProbe();
			long start = cache.clock.get();
			Object first = cache.get("instance", 3);
			assertEquals(start, CacheProbe.snapshotMillis(first));
			cache.clock.set(start + 59_999);
			assertSame(first, cache.get("instance", 3));
			cache.clock.set(start + 60_000);
			Object second = cache.get("instance", 3);
			assertNotSame("the snapshot expires at 60 s", first, second);
			assertEquals(start + 60_000, CacheProbe.snapshotMillis(second));
			cache.clock.set(start);
			assertNotSame("a clock that went back expires the snapshot", second, cache.get("instance", 3));
			assertEquals(3, cache.loads.get());
		});
	}

	@Test
	public void theBrowserCacheKeepsThreeInstancesInLeastRecentlyUsedOrder() throws Exception
	{
		withCacheSettings("60", "200000", () ->
		{
			CacheProbe cache = new CacheProbe();
			for (String instanceId : Arrays.asList("a", "b", "c", "a", "d"))
			{
				cache.get(instanceId, 1);
			}
			assertEquals("a, b, c and d are loaded once each", 4, cache.loads.get());
			for (String instanceId : Arrays.asList("a", "c", "d"))
			{
				cache.get(instanceId, 1);
			}
			assertEquals(4, cache.loads.get());
			cache.get("b", 1);
			assertEquals("b was the least recently used when d came in", 5, cache.loads.get());
		});
	}

	@Test
	public void instancesAboveTheNodeCeilingAndAZeroTtlAreReadOnEveryRequest() throws Exception
	{
		withCacheSettings("60", "2", () ->
		{
			CacheProbe cache = new CacheProbe();
			cache.get("large", 3);
			cache.get("large", 3);
			cache.get("small", 2);
			cache.get("small", 2);
			assertEquals("the instance above the ceiling is loaded twice and the other once", 3, cache.loads.get());
		});
		withCacheSettings("0", "200000", () ->
		{
			CacheProbe cache = new CacheProbe();
			cache.get("instance", 1);
			cache.get("instance", 1);
			assertEquals(2, cache.loads.get());
		});
	}

	@Test
	public void concurrentRequestsForOneInstanceShareASingleLoad() throws Exception
	{
		withCacheSettings("60", "200000", () ->
		{
			CacheProbe cache = new CacheProbe();
			CountDownLatch loading = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try
			{
				Future<Object> first = executor.submit(() -> cache.get("instance", () ->
				{
					loading.countDown();
					assertTrue(release.await(10, TimeUnit.SECONDS));
					cache.loads.incrementAndGet();
					return CacheProbe.snapshot(1);
				}));
				assertTrue(loading.await(10, TimeUnit.SECONDS));
				Future<Object> second = executor.submit(() -> cache.get("instance", 1));
				Thread.sleep(200);
				assertFalse("the second request waits for the load in flight", second.isDone());
				release.countDown();
				assertSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
				assertEquals(1, cache.loads.get());
			}
			finally
			{
				release.countDown();
				executor.shutdownNow();
			}
		});
	}

	@Test
	public void threeHundredThousandSyntheticNodesAreBuiltAndWalkedInUnderFiveSeconds() throws Exception
	{
		int size = 300_000;
		Constructor<?> constructor = nodeConstructor();
		long started = System.nanoTime();
		List<Object> nodes = new ArrayList<>(size);
		int orphans = 0;
		for (int index = 0; index < size; index++)
		{
			// 1000 roots, then levels of ten children each; every 97th content has its parent outside
			// the instance and every 13th is archived.
			String parentId = index < 1000 ? null : "n" + ((index - 1000) / 10);
			if (index >= 1000 && index % 97 == 0)
			{
				parentId = "gone" + index;
				orphans++;
			}
			nodes.add(constructor.newInstance("n" + index, parentId, "Node " + index, index % 13 != 0, index % 7));
		}
		Tree probe = tree(nodes);
		long built = System.nanoTime();

		List<String> roots = probe.roots();
		assertEquals(1000 + orphans, roots.size());
		assertEquals("every content is reachable, archived ones and orphans included", size, probe.closure(roots).size());
		List<String> marks = new ArrayList<>();
		marks.add("n0");
		for (int index = size - 2000; index < size; index++)
		{
			marks.add("n" + index);
		}
		assertEquals("the last 2000 contents are leaves outside the subtree of n0", 2001, probe.minimalSelection(marks).size());
		assertEquals(Arrays.asList("n188", "n2889", "n29899", "n299999"), probe.path("n299999"));
		assertEquals(10, probe.children("n188").size());
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

		System.out.println("[HierarchyTreeTest] PROGRESS " + size + " synthetic nodes: built in " + TimeUnit.NANOSECONDS.toMillis(built - started)
				+ " ms, built and walked in " + elapsedMillis + " ms");
		assertTrue("took " + elapsedMillis + " ms", elapsedMillis < 5000);
	}

	private static Object node(String id, String parentId, String title, boolean published, int position)
	{
		try
		{
			return nodeConstructor().newInstance(id, parentId, title, published, position);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Constructor<?> nodeConstructor() throws ReflectiveOperationException
	{
		return ModuleBridge.load(NODE).getConstructor(String.class, String.class, String.class, boolean.class, int.class);
	}

	/** Runs the body with the browser cache properties set, restoring their previous values after it. */
	private static void withCacheSettings(String seconds, String maxNodes, Body body) throws Exception
	{
		String previousSeconds = System.setProperty(CACHE_SECONDS, seconds);
		String previousMaxNodes = System.setProperty(CACHE_MAX_NODES, maxNodes);
		try
		{
			body.run();
		}
		finally
		{
			restore(CACHE_SECONDS, previousSeconds);
			restore(CACHE_MAX_NODES, previousMaxNodes);
		}
	}

	private static void restore(String property, String previous)
	{
		if (previous == null)
		{
			System.clearProperty(property);
		}
		else
		{
			System.setProperty(property, previous);
		}
	}

	private interface Body
	{
		void run() throws Exception;
	}

	private static Tree tree(Collection<Object> nodes)
	{
		try
		{
			return new Tree(ModuleBridge.load(TREE).getConstructor(Collection.class).newInstance(nodes));
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Set<String> set(String... ids)
	{
		return new java.util.LinkedHashSet<>(Arrays.asList(ids));
	}

	/** A cache of the module's browser with a manual clock, whose loaders count the loads. */
	private static final class CacheProbe
	{
		final AtomicLong clock = new AtomicLong(1_000_000L);

		final AtomicInteger loads = new AtomicInteger();

		private final Object cache;

		private final Method get;

		private final Class<?> loaderType;

		CacheProbe() throws ReflectiveOperationException
		{
			Class<?> cacheType = ModuleBridge.load(BROWSER + "$Cache");
			loaderType = ModuleBridge.load(BROWSER + "$SnapshotLoader");
			cache = cacheType.getConstructor(LongSupplier.class).newInstance((LongSupplier) clock::get);
			get = cacheType.getMethod("get", String.class, loaderType);
		}

		/** Returns the snapshot of the instance, loading one of the given number of nodes on a miss. */
		Object get(String instanceId, int nodes) throws Exception
		{
			return get(instanceId, () ->
			{
				loads.incrementAndGet();
				return snapshot(nodes);
			});
		}

		Object get(String instanceId, Callable<Object> load) throws Exception
		{
			Object loader = Proxy.newProxyInstance(loaderType.getClassLoader(), new Class<?>[]{loaderType},
					(proxy, method, args) -> "load".equals(method.getName()) ? load.call() : method.invoke(load, args));
			try
			{
				return get.invoke(cache, instanceId, loader);
			}
			catch (InvocationTargetException e)
			{
				if (e.getCause() instanceof Error)
				{
					throw (Error) e.getCause();
				}
				throw (Exception) e.getCause();
			}
		}

		static Object snapshot(int nodes) throws ReflectiveOperationException
		{
			List<Object> list = new ArrayList<>();
			for (int index = 0; index < nodes; index++)
			{
				list.add(node("s" + index, null, "S" + index, true, index));
			}
			Class<?> treeType = ModuleBridge.load(TREE);
			Object tree = treeType.getConstructor(Collection.class).newInstance(list);
			return ModuleBridge.load(BROWSER + "$Snapshot").getConstructor(treeType, Map.class).newInstance(tree, Collections.emptyMap());
		}

		static long snapshotMillis(Object snapshot) throws ReflectiveOperationException
		{
			return (Long) snapshot.getClass().getMethod("getSnapshotMillis").invoke(snapshot);
		}
	}

	/** The module's HierarchyTree, which the test JVM can reach only by reflection, with its nodes turned into ids. */
	private static final class Tree
	{
		private final Object target;

		Tree(Object target)
		{
			this.target = target;
		}

		List<String> roots()
		{
			return ids(call("roots"));
		}

		List<String> children(String id)
		{
			return ids(call("children", id));
		}

		List<String> path(String id)
		{
			return ids(call("path", id));
		}

		boolean isPublished(String id)
		{
			return (Boolean) invoke(call("get", id), "isPublished");
		}

		int descendantCount(String id)
		{
			return (Integer) call("descendantCount", id);
		}

		@SuppressWarnings("unchecked")
		Set<String> closure(Collection<String> selected)
		{
			return (Set<String>) call("closure", selected);
		}

		@SuppressWarnings("unchecked")
		Set<String> minimalSelection(Collection<String> selected)
		{
			return (Set<String>) call("minimalSelection", selected);
		}

		@SuppressWarnings("unchecked")
		List<String> ancestorsFirst(Collection<String> ids)
		{
			return (List<String>) call("ancestorsFirst", ids);
		}

		private Object call(String method, Object... args)
		{
			return invoke(target, method, args);
		}

		private static List<String> ids(Object nodes)
		{
			List<String> ids = new ArrayList<>();
			for (Object node : (List<?>) nodes)
			{
				ids.add((String) invoke(node, "getContentId"));
			}
			return ids;
		}

		private static Object invoke(Object owner, String name, Object... args)
		{
			for (Method method : owner.getClass().getMethods())
			{
				if (method.getName().equals(name) && method.getParameterCount() == args.length)
				{
					try
					{
						return method.invoke(owner, args);
					}
					catch (InvocationTargetException e)
					{
						throw new IllegalStateException(e.getCause());
					}
					catch (IllegalAccessException e)
					{
						throw new IllegalStateException(e);
					}
				}
			}
			throw new IllegalStateException("no method " + name);
		}
	}
}
