package br.com.lumis.commons.contentmigrator.service.contentmigration.dto;

import java.util.Objects;

import lumis.content.core.ContentVersion;
import lumis.doui.source.ISourceData;

/**
 * One origin version to write in the destination: its source row, the version itself and the ids
 * that place it in the destination.
 */
public final class VersionCopy
{
	private final ISourceData originRow;

	private final ContentVersion originVersion;

	/** The destination content of the item, or null when the content is created by this write. */
	private final String destinationContentId;

	/** The destination ContentLocale of the item, or null when the locale is created by this write. */
	private final String destinationContentLocaleId;

	/** The destination item the write updates, or null exactly when {@link #destinationContentLocaleId} is. */
	private final String destinationItemId;

	/**
	 * Creates the description of one version write.
	 *
	 * @throws NullPointerException
	 *             if the origin row or version is null.
	 * @throws IllegalArgumentException
	 *             if only one of the destination ContentLocale id and item id is null.
	 */
	public VersionCopy(ISourceData originRow, ContentVersion originVersion, String destinationContentId, String destinationContentLocaleId, String destinationItemId)
	{
		this.originRow = Objects.requireNonNull(originRow, "originRow");
		this.originVersion = Objects.requireNonNull(originVersion, "originVersion");
		if ((destinationContentLocaleId == null) != (destinationItemId == null))
		{
			throw new IllegalArgumentException("The destination ContentLocale id and item id must be both set or both null.");
		}
		this.destinationContentId = destinationContentId;
		this.destinationContentLocaleId = destinationContentLocaleId;
		this.destinationItemId = destinationItemId;
	}

	/** Returns the source row of the origin version. */
	public ISourceData getOriginRow()
	{
		return originRow;
	}

	/** Returns the origin version. */
	public ContentVersion getOriginVersion()
	{
		return originVersion;
	}

	/** Returns the destination content id, or null when the write creates the content. */
	public String getDestinationContentId()
	{
		return destinationContentId;
	}

	/** Returns the destination ContentLocale id, or null when the write creates the locale. */
	public String getDestinationContentLocaleId()
	{
		return destinationContentLocaleId;
	}

	/** Returns the destination item id the write updates, or null when the write creates the locale. */
	public String getDestinationItemId()
	{
		return destinationItemId;
	}

	/** Returns whether the destination locale exists, so the write is an {@code updateData} and not an {@code addData}. */
	public boolean hasDestinationLocale()
	{
		return destinationContentLocaleId != null;
	}
}
