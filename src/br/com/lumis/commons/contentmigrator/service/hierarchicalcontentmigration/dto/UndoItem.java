package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/** A content or repository-clone map row selected for cleanup. */
public final class UndoItem
{
	private final ItemMapRow map;

	private final RepositoryPair repositoryPair;

	/** Creates an undo item for a mapped row and, when undoing a repository clone, its repository pair. */
	public UndoItem(ItemMapRow map, RepositoryPair repositoryPair)
	{
		this.map = map;
		this.repositoryPair = repositoryPair;
	}

	/** Returns the item map row whose destination clone is being undone. */
	public ItemMapRow getMap()
	{
		return map;
	}

	/** Returns the repository pair of a repository-clone row, or {@code null} for a content row. */
	public RepositoryPair getRepositoryPair()
	{
		return repositoryPair;
	}
}
