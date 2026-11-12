package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/** A row of lum_cmig_HMigWidgetMap: the destination widget chosen for an origin widget in a destination repository. */
public final class WidgetMapRow
{
	private String id;

	private String originWidgetContentId;

	private String destRepoInstanceId;

	private String destWidgetContentId;

	/** Returns the primary key of this widget map row. */
	public String getId()
	{
		return id;
	}

	/** Sets the primary key of this widget map row. */
	public void setId(String id)
	{
		this.id = id;
	}

	/** Returns the content id of the origin widget. */
	public String getOriginWidgetContentId()
	{
		return originWidgetContentId;
	}

	/** Sets the content id of the origin widget. */
	public void setOriginWidgetContentId(String originWidgetContentId)
	{
		this.originWidgetContentId = originWidgetContentId;
	}

	/** Returns the service instance id of the destination widget repository. */
	public String getDestRepoInstanceId()
	{
		return destRepoInstanceId;
	}

	/** Sets the service instance id of the destination widget repository. */
	public void setDestRepoInstanceId(String destRepoInstanceId)
	{
		this.destRepoInstanceId = destRepoInstanceId;
	}

	/** Returns the content id of the mapped destination widget. */
	public String getDestWidgetContentId()
	{
		return destWidgetContentId;
	}

	/** Sets the content id of the mapped destination widget. */
	public void setDestWidgetContentId(String destWidgetContentId)
	{
		this.destWidgetContentId = destWidgetContentId;
	}
}
