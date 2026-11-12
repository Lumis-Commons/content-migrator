package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/** One repository dependency pair used by a migration. */
public final class RepositoryPair
{
	private final String originRepositoryId;

	private final String destinationRepositoryId;

	private final String dependencyType;

	/** Creates a repository pair linking an origin repository to its destination counterpart. */
	public RepositoryPair(String originRepositoryId, String destinationRepositoryId, String dependencyType)
	{
		this.originRepositoryId = originRepositoryId;
		this.destinationRepositoryId = destinationRepositoryId;
		this.dependencyType = dependencyType;
	}

	/** Returns the service instance id of the origin repository. */
	public String getOriginRepositoryId()
	{
		return originRepositoryId;
	}

	/** Returns the service instance id of the destination repository. */
	public String getDestinationRepositoryId()
	{
		return destinationRepositoryId;
	}

	/** Returns the Lumis service instance dependency type of this repository pair. */
	public String getDependencyType()
	{
		return dependencyType;
	}
}
