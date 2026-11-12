package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.PriorityQueue;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.HierarchyNode;

/**
 * The hierarchy of the contents of one service instance, in memory. A content whose parent is not in
 * the tree is a root. Nodes unreachable because of cycles are also exposed as roots; all walks
 * guard against cycles in the data.
 * <p>
 * Selecting a content selects all of its descendants: {@link #closure(Collection)} is the set of
 * contents a selection migrates, and {@link #minimalSelection(Collection)} is the selection without
 * the contents that an ancestor in the selection already covers.
 */
public final class HierarchyTree
{
	private static final Comparator<HierarchyNode> SIBLING_ORDER = Comparator.comparingInt(HierarchyNode::getPosition)
			.thenComparing(node -> node.getTitle() == null ? "" : node.getTitle().toLowerCase()).thenComparing(HierarchyNode::getContentId);

	private final Map<String, HierarchyNode> nodesById = new HashMap<>();

	private final Map<String, List<HierarchyNode>> childrenByParentId = new HashMap<>();

	private final List<HierarchyNode> roots = new ArrayList<>();

	private final Set<String> rootIds = new HashSet<>();

	/** Builds the tree of the given nodes. */
	public HierarchyTree(Collection<HierarchyNode> nodes)
	{
		for (HierarchyNode node : nodes)
		{
			nodesById.put(node.getContentId(), node);
		}
		for (HierarchyNode node : nodesById.values())
		{
			String parentId = node.getParentContentId();
			if (hasParentInTree(node))
			{
				childrenByParentId.computeIfAbsent(parentId, id -> new ArrayList<>()).add(node);
			}
			else
			{
				roots.add(node);
			}
		}
		Set<String> reachable = closure(roots.stream().map(HierarchyNode::getContentId).collect(java.util.stream.Collectors.toList()));
		for (HierarchyNode node : nodesById.values())
		{
			if (!reachable.contains(node.getContentId()))
			{
				roots.add(node);
			}
		}
		roots.forEach(node -> rootIds.add(node.getContentId()));
		roots.sort(SIBLING_ORDER);
		childrenByParentId.values().forEach(children -> children.sort(SIBLING_ORDER));
	}

	/** Returns the node of the content, or null when the content is not in the tree. */
	public HierarchyNode get(String contentId)
	{
		return nodesById.get(contentId);
	}

	/** Returns whether the content is in the tree. */
	public boolean contains(String contentId)
	{
		return nodesById.containsKey(contentId);
	}

	/** Returns the number of contents in the tree. */
	public int size()
	{
		return nodesById.size();
	}

	/** Returns the contents without a parent in the tree, in the order of the siblings. */
	public List<HierarchyNode> roots()
	{
		return Collections.unmodifiableList(roots);
	}

	/**
	 * Returns the direct children of a content in the order of the siblings; with a null id, the
	 * roots. An unknown id has no children.
	 */
	public List<HierarchyNode> children(String parentContentId)
	{
		if (parentContentId == null)
		{
			return roots();
		}
		return Collections.unmodifiableList(childrenByParentId.getOrDefault(parentContentId, Collections.emptyList()));
	}

	/** Returns the number of direct children of a content. */
	public int childCount(String contentId)
	{
		return childrenByParentId.getOrDefault(contentId, Collections.emptyList()).size();
	}

	/** Returns the number of descendants of a content, at any depth. */
	public int descendantCount(String contentId)
	{
		return closure(Collections.singleton(contentId)).size() - (contains(contentId) ? 1 : 0);
	}

	/**
	 * Returns the ancestors of a content from the root down to the content itself, or an empty list
	 * when the content is not in the tree.
	 */
	public List<HierarchyNode> path(String contentId)
	{
		LinkedHashSet<HierarchyNode> reversed = new LinkedHashSet<>();
		HierarchyNode node = nodesById.get(contentId);
		while (node != null && reversed.add(node))
		{
			node = isRoot(node) ? null : nodesById.get(node.getParentContentId());
		}
		List<HierarchyNode> path = new ArrayList<>(reversed);
		Collections.reverse(path);
		return path;
	}

	/**
	 * Returns the selected contents that are in the tree, plus all of their descendants. Ids that are
	 * not in the tree are ignored.
	 */
	public Set<String> closure(Collection<String> selectedContentIds)
	{
		Set<String> closure = new LinkedHashSet<>();
		Deque<String> pending = new ArrayDeque<>();
		for (String id : selectedContentIds)
		{
			if (nodesById.containsKey(id) && closure.add(id))
			{
				pending.add(id);
			}
		}
		while (!pending.isEmpty())
		{
			for (HierarchyNode child : children(pending.poll()))
			{
				if (closure.add(child.getContentId()))
				{
					pending.add(child.getContentId());
				}
			}
		}
		return closure;
	}

	/**
	 * Returns the selected contents that have no selected ancestor, which is the smallest selection
	 * with the same {@link #closure(Collection)}. Ids that are not in the tree are dropped.
	 */
	public Set<String> minimalSelection(Collection<String> selectedContentIds)
	{
		Set<String> selected = new HashSet<>();
		for (String id : selectedContentIds)
		{
			if (nodesById.containsKey(id))
			{
				selected.add(id);
			}
		}
		Set<String> minimal = new LinkedHashSet<>();
		for (String id : selectedContentIds)
		{
			if (selected.contains(id) && !hasSelectedAncestor(id, selected))
			{
				minimal.add(id);
			}
		}
		return minimal;
	}

	/**
	 * Orders the requested contents after both reference-locale version parents, when selected.
	 * Kahn's algorithm emits all acyclic dependencies first; remaining cyclic nodes follow in stable
	 * sibling order, so corrupt data cannot hide contents or prevent discovery from finishing.
	 */
	public List<String> ancestorsFirst(Collection<String> contentIds)
	{
		Map<String, Integer> dependencies = new HashMap<>();
		Map<String, Set<String>> dependents = new HashMap<>();
		for (String id : contentIds)
		{
			if (contains(id))
			{
				dependencies.put(id, 0);
			}
		}
		for (String id : dependencies.keySet())
		{
			HierarchyNode node = get(id);
			Set<String> parents = new HashSet<>();
			parents.add(node.getActiveParentId());
			parents.add(node.getPublishedParentId());
			for (String parent : parents)
			{
				if (dependencies.containsKey(parent))
				{
					dependencies.put(id, dependencies.get(id) + 1);
					dependents.computeIfAbsent(parent, key -> new HashSet<>()).add(id);
				}
			}
		}
		PriorityQueue<HierarchyNode> ready = new PriorityQueue<>(SIBLING_ORDER);
		dependencies.forEach((id, count) ->
		{
			if (count == 0)
			{
				ready.add(get(id));
			}
		});
		List<String> ordered = new ArrayList<>();
		while (!ready.isEmpty())
		{
			String id = ready.remove().getContentId();
			ordered.add(id);
			for (String child : dependents.getOrDefault(id, Collections.emptySet()))
			{
				int count = dependencies.compute(child, (key, value) -> value - 1);
				if (count == 0)
				{
					ready.add(get(child));
				}
			}
		}
		Set<String> emitted = new HashSet<>(ordered);
		dependencies.keySet().stream().filter(id -> !emitted.contains(id)).map(this::get).sorted(SIBLING_ORDER).map(HierarchyNode::getContentId).forEach(ordered::add);
		return ordered;
	}

	private boolean hasSelectedAncestor(String contentId, Set<String> selected)
	{
		List<HierarchyNode> path = path(contentId);
		for (int index = 0; index < path.size() - 1; index++)
		{
			if (selected.contains(path.get(index).getContentId()))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isRoot(HierarchyNode node)
	{
		return rootIds.contains(node.getContentId());
	}

	/** Whether the parent of the node is another node of this tree; otherwise the node is a root. */
	private boolean hasParentInTree(HierarchyNode node)
	{
		String parentId = node.getParentContentId();
		boolean isOtherNode = parentId != null && !parentId.equals(node.getContentId());
		return isOtherNode && nodesById.containsKey(parentId);
	}
}
