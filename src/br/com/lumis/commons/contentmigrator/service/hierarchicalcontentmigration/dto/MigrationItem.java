package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/** An origin ContentLocale to migrate. */
public class MigrationItem
{
	private final String contentLocaleId;

	private final String contentId;

	private final String sourceId;

	private final String locale;

	/** Creates a migration item for an origin ContentLocale and its owning content metadata. */
	public MigrationItem(String contentLocaleId, String contentId, String sourceId, String locale)
	{
		this.contentLocaleId = contentLocaleId;
		this.contentId = contentId;
		this.sourceId = sourceId;
		this.locale = locale;
	}

	/** Returns the id of the origin ContentLocale to copy. */
	public String getContentLocaleId()
	{
		return contentLocaleId;
	}

	/** Returns the id of the origin Content owning the locale. */
	public String getContentId()
	{
		return contentId;
	}

	/** Returns the DOUI source id of the origin content. */
	public String getSourceId()
	{
		return sourceId;
	}

	/** Returns the locale code of the origin ContentLocale. */
	public String getLocale()
	{
		return locale;
	}
}
