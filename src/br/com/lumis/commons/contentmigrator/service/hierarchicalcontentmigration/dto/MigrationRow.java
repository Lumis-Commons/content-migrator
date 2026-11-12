package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/**
 * A row of lum_cmig_HMigration. Date columns hold milliseconds since the epoch, or {@code null}, on
 * every database.
 */
public final class MigrationRow
{
	private String id;

	private String name;

	private String serviceId;

	private String originServiceInstanceId;

	private String destServiceInstanceId;

	private String updateMode;

	private String operation;

	private String status;

	private boolean stopRequested;

	private Long totalCount;

	private Long migratedCount;

	private Long heartbeat;

	private Long createdDate;

	private Long startDate;

	private Long endDate;

	private String author;

	private String errorType;

	private String errorMessage;

	private String errorStack;

	private String blockingOriginItemId;

	private String blockingWidgetContentId;

	/** Returns the primary key of the migration. */
	public String getId()
	{
		return id;
	}

	/** Sets the primary key of the migration. */
	public void setId(String id)
	{
		this.id = id;
	}

	/** Returns the user-provided display name of the migration. */
	public String getName()
	{
		return name;
	}

	/** Sets the user-provided display name of the migration. */
	public void setName(String name)
	{
		this.name = name;
	}

	/** Returns the Lumis service id shared by the origin and destination instances. */
	public String getServiceId()
	{
		return serviceId;
	}

	/** Sets the Lumis service id shared by the origin and destination instances. */
	public void setServiceId(String serviceId)
	{
		this.serviceId = serviceId;
	}

	/** Returns the service instance id of the origin content service. */
	public String getOriginServiceInstanceId()
	{
		return originServiceInstanceId;
	}

	/** Sets the service instance id of the origin content service. */
	public void setOriginServiceInstanceId(String originServiceInstanceId)
	{
		this.originServiceInstanceId = originServiceInstanceId;
	}

	/** Returns the service instance id of the destination content service. */
	public String getDestServiceInstanceId()
	{
		return destServiceInstanceId;
	}

	/** Sets the service instance id of the destination content service. */
	public void setDestServiceInstanceId(String destServiceInstanceId)
	{
		this.destServiceInstanceId = destServiceInstanceId;
	}

	/** Returns the update mode ({@code ADD_ONLY} or {@code UPDATE}). */
	public String getUpdateMode()
	{
		return updateMode;
	}

	/** Sets the update mode ({@code ADD_ONLY} or {@code UPDATE}). */
	public void setUpdateMode(String updateMode)
	{
		this.updateMode = updateMode;
	}

	/** Returns the last or current operation ({@code MIGRATE} or {@code UNDO}). */
	public String getOperation()
	{
		return operation;
	}

	/** Sets the last or current operation ({@code MIGRATE} or {@code UNDO}). */
	public void setOperation(String operation)
	{
		this.operation = operation;
	}

	/** Returns the execution status of the migration. */
	public String getStatus()
	{
		return status;
	}

	/** Sets the execution status of the migration. */
	public void setStatus(String status)
	{
		this.status = status;
	}

	/** Returns whether a stop has been requested for the current execution. */
	public boolean isStopRequested()
	{
		return stopRequested;
	}

	/** Sets whether a stop has been requested for the current execution. */
	public void setStopRequested(boolean stopRequested)
	{
		this.stopRequested = stopRequested;
	}

	/** Returns the total number of items in the current or last operation. */
	public Long getTotalCount()
	{
		return totalCount;
	}

	/** Sets the total number of items in the current or last operation. */
	public void setTotalCount(Long totalCount)
	{
		this.totalCount = totalCount;
	}

	/** Returns the number of migrated items currently mapped in the destination. */
	public Long getMigratedCount()
	{
		return migratedCount;
	}

	/** Sets the number of migrated items currently mapped in the destination. */
	public void setMigratedCount(Long migratedCount)
	{
		this.migratedCount = migratedCount;
	}

	/** Returns the last heartbeat timestamp of the migration run. */
	public Long getHeartbeat()
	{
		return heartbeat;
	}

	/** Sets the last heartbeat timestamp of the migration run. */
	public void setHeartbeat(Long heartbeat)
	{
		this.heartbeat = heartbeat;
	}

	/** Returns the creation timestamp of the migration row. */
	public Long getCreatedDate()
	{
		return createdDate;
	}

	/** Sets the creation timestamp of the migration row. */
	public void setCreatedDate(Long createdDate)
	{
		this.createdDate = createdDate;
	}

	/** Returns the start timestamp of the current or last execution run. */
	public Long getStartDate()
	{
		return startDate;
	}

	/** Sets the start timestamp of the current or last execution run. */
	public void setStartDate(Long startDate)
	{
		this.startDate = startDate;
	}

	/** Returns the end timestamp of the last completed execution run. */
	public Long getEndDate()
	{
		return endDate;
	}

	/** Sets the end timestamp of the last completed execution run. */
	public void setEndDate(Long endDate)
	{
		this.endDate = endDate;
	}

	/** Returns the user id of the author who created the migration. */
	public String getAuthor()
	{
		return author;
	}

	/** Sets the user id of the author who created the migration. */
	public void setAuthor(String author)
	{
		this.author = author;
	}

	/** Returns the error type of a failed or blocked execution, or {@code null}. */
	public String getErrorType()
	{
		return errorType;
	}

	/** Sets the error type of a failed or blocked execution. */
	public void setErrorType(String errorType)
	{
		this.errorType = errorType;
	}

	/** Returns the error message of a failed or blocked execution, or {@code null}. */
	public String getErrorMessage()
	{
		return errorMessage;
	}

	/** Sets the error message of a failed or blocked execution. */
	public void setErrorMessage(String errorMessage)
	{
		this.errorMessage = errorMessage;
	}

	/** Returns the stack trace of a failed execution, or {@code null}. */
	public String getErrorStack()
	{
		return errorStack;
	}

	/** Sets the stack trace of a failed execution. */
	public void setErrorStack(String errorStack)
	{
		this.errorStack = errorStack;
	}

	/** Returns the origin version item id that blocked the migration, or {@code null}. */
	public String getBlockingOriginItemId()
	{
		return blockingOriginItemId;
	}

	/** Sets the origin version item id that blocked the migration. */
	public void setBlockingOriginItemId(String blockingOriginItemId)
	{
		this.blockingOriginItemId = blockingOriginItemId;
	}

	/** Returns the content id of the unmapped origin widget that blocked the migration, or {@code null}. */
	public String getBlockingWidgetContentId()
	{
		return blockingWidgetContentId;
	}

	/** Sets the content id of the unmapped origin widget that blocked the migration. */
	public void setBlockingWidgetContentId(String blockingWidgetContentId)
	{
		this.blockingWidgetContentId = blockingWidgetContentId;
	}
}
