package br.com.lumis.commons.contentmigrator.service.contentmigration.dto;

/** A row of lum_cmig_MigItemMap: an origin item and the destination item copied or cloned from it. */
public final class ItemMapRow
{
	private String id;

	private String kind;

	private String serviceId;

	private String sourceId;

	private String locale;

	private String warnings;

	private boolean destCreated;

	private String originServiceInstanceId;

	private String originContentId;

	private String originContentLocaleId;

	private String originItemId;

	private String originFileFingerprint;

	private String destServiceInstanceId;

	private String destContentId;

	private String destContentLocaleId;

	private String destItemId;

	/** Returns the primary key of this item map row. */
	public String getId()
	{
		return id;
	}

	/** Sets the primary key of this item map row. */
	public void setId(String id)
	{
		this.id = id;
	}

	/** Returns the kind of mapped item ({@code CONTENT} or repository dependency kind). */
	public String getKind()
	{
		return kind;
	}

	/** Sets the kind of mapped item ({@code CONTENT} or repository dependency kind). */
	public void setKind(String kind)
	{
		this.kind = kind;
	}

	/** Returns the Lumis service id of the mapped item. */
	public String getServiceId()
	{
		return serviceId;
	}

	/** Sets the Lumis service id of the mapped item. */
	public void setServiceId(String serviceId)
	{
		this.serviceId = serviceId;
	}

	/** Returns the DOUI source id of the mapped item. */
	public String getSourceId()
	{
		return sourceId;
	}

	/** Sets the DOUI source id of the mapped item. */
	public void setSourceId(String sourceId)
	{
		this.sourceId = sourceId;
	}

	/** Returns the locale code of the mapped item. */
	public String getLocale()
	{
		return locale;
	}

	/** Sets the locale code of the mapped item. */
	public void setLocale(String locale)
	{
		this.locale = locale;
	}

	/** Returns the non-fatal warning messages recorded for this item, or {@code null}. */
	public String getWarnings()
	{
		return warnings;
	}

	/** Sets the non-fatal warning messages recorded for this item. */
	public void setWarnings(String warnings)
	{
		this.warnings = warnings;
	}

	/** Returns whether the destination item was created by the migration rather than matched. */
	public boolean isDestCreated()
	{
		return destCreated;
	}

	/** Sets whether the destination item was created by the migration rather than matched. */
	public void setDestCreated(boolean destCreated)
	{
		this.destCreated = destCreated;
	}

	/** Returns the service instance id of the origin content or repository. */
	public String getOriginServiceInstanceId()
	{
		return originServiceInstanceId;
	}

	/** Sets the service instance id of the origin content or repository. */
	public void setOriginServiceInstanceId(String originServiceInstanceId)
	{
		this.originServiceInstanceId = originServiceInstanceId;
	}

	/** Returns the content id of the origin item. */
	public String getOriginContentId()
	{
		return originContentId;
	}

	/** Sets the content id of the origin item. */
	public void setOriginContentId(String originContentId)
	{
		this.originContentId = originContentId;
	}

	/** Returns the ContentLocale id of the origin item. */
	public String getOriginContentLocaleId()
	{
		return originContentLocaleId;
	}

	/** Sets the ContentLocale id of the origin item. */
	public void setOriginContentLocaleId(String originContentLocaleId)
	{
		this.originContentLocaleId = originContentLocaleId;
	}

	/** Returns the version item id of the origin item. */
	public String getOriginItemId()
	{
		return originItemId;
	}

	/** Sets the version item id of the origin item. */
	public void setOriginItemId(String originItemId)
	{
		this.originItemId = originItemId;
	}

	/** Returns the SHA-256 fingerprint of the origin file bytes, when applicable. */
	public String getOriginFileFingerprint()
	{
		return originFileFingerprint;
	}

	/** Sets the SHA-256 fingerprint of the origin file bytes. */
	public void setOriginFileFingerprint(String originFileFingerprint)
	{
		this.originFileFingerprint = originFileFingerprint;
	}

	/** Returns the service instance id of the destination content or repository. */
	public String getDestServiceInstanceId()
	{
		return destServiceInstanceId;
	}

	/** Sets the service instance id of the destination content or repository. */
	public void setDestServiceInstanceId(String destServiceInstanceId)
	{
		this.destServiceInstanceId = destServiceInstanceId;
	}

	/** Returns the content id of the destination item. */
	public String getDestContentId()
	{
		return destContentId;
	}

	/** Sets the content id of the destination item. */
	public void setDestContentId(String destContentId)
	{
		this.destContentId = destContentId;
	}

	/** Returns the ContentLocale id of the destination item. */
	public String getDestContentLocaleId()
	{
		return destContentLocaleId;
	}

	/** Sets the ContentLocale id of the destination item. */
	public void setDestContentLocaleId(String destContentLocaleId)
	{
		this.destContentLocaleId = destContentLocaleId;
	}

	/** Returns the version item id of the destination item. */
	public String getDestItemId()
	{
		return destItemId;
	}

	/** Sets the version item id of the destination item. */
	public void setDestItemId(String destItemId)
	{
		this.destItemId = destItemId;
	}
}
