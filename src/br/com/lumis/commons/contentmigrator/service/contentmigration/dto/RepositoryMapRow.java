package br.com.lumis.commons.contentmigrator.service.contentmigration.dto;

/** A row of lum_cmig_MigRepoMap: an origin repository and the destination repository it maps to. */
public final class RepositoryMapRow
{
	private String id;

	private String originRepoInstanceId;

	private String destRepoInstanceId;

	private String dependencyType;

	/** Returns the primary key of this repository map row. */
	public String getId()
	{
		return id;
	}

	/** Sets the primary key of this repository map row. */
	public void setId(String id)
	{
		this.id = id;
	}

	/** Returns the service instance id of the origin repository. */
	public String getOriginRepoInstanceId()
	{
		return originRepoInstanceId;
	}

	/** Sets the service instance id of the origin repository. */
	public void setOriginRepoInstanceId(String originRepoInstanceId)
	{
		this.originRepoInstanceId = originRepoInstanceId;
	}

	/** Returns the service instance id of the destination repository. */
	public String getDestRepoInstanceId()
	{
		return destRepoInstanceId;
	}

	/** Sets the service instance id of the destination repository. */
	public void setDestRepoInstanceId(String destRepoInstanceId)
	{
		this.destRepoInstanceId = destRepoInstanceId;
	}

	/** Returns the dependency type linking the content service to this repository pair. */
	public String getDependencyType()
	{
		return dependencyType;
	}

	/** Sets the dependency type linking the content service to this repository pair. */
	public void setDependencyType(String dependencyType)
	{
		this.dependencyType = dependencyType;
	}
}
