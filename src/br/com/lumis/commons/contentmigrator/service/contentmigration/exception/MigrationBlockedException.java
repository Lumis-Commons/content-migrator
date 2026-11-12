package br.com.lumis.commons.contentmigrator.service.contentmigration.exception;

/**
 * Thrown by a {@code DependencyHandler} when a mapping requires an action of the user. The engine
 * rolls the item back and writes the block in lum_cmig_Migration (errorType, errorMessage,
 * blockingOriginItemId and blockingWidgetContentId).
 */
public class MigrationBlockedException extends Exception
{
	private static final long serialVersionUID = 1L;

	public static final String ERROR_TYPE_WIDGET_MAPPING_REQUIRED = "WIDGET_MAPPING_REQUIRED";

	public static final String ERROR_TYPE_LOCALE_NOT_ACCEPTED = "LOCALE_NOT_ACCEPTED";

	private final String errorType;

	private final String originItemId;

	private final String blockingWidgetContentId;

	/**
	 * Creates a block exception that does not involve a missing widget mapping.
	 *
	 * @param errorType
	 *            the lum_cmig_Migration.errorType of the block.
	 * @param originItemId
	 *            the itemId of the origin item blocked.
	 * @param details
	 *            the message shown to the user.
	 */
	public MigrationBlockedException(String errorType, String originItemId, String details)
	{
		this(errorType, originItemId, details, null);
	}

	/**
	 * Creates a block exception, optionally recording the unmapped origin widget content id.
	 *
	 * @param blockingWidgetContentId
	 *            the contentId of the origin widget without mapping, for
	 *            {@value #ERROR_TYPE_WIDGET_MAPPING_REQUIRED}.
	 */
	public MigrationBlockedException(String errorType, String originItemId, String details, String blockingWidgetContentId)
	{
		super(details);
		this.errorType = errorType;
		this.originItemId = originItemId;
		this.blockingWidgetContentId = blockingWidgetContentId;
	}

	/** Returns the block error type stored in lum_cmig_Migration.errorType. */
	public String getErrorType()
	{
		return errorType;
	}

	/** Returns the item id of the origin content version that could not be migrated. */
	public String getOriginItemId()
	{
		return originItemId;
	}

	/** Returns the human-readable details explaining why the item was blocked. */
	public String getDetails()
	{
		return getMessage();
	}

	/** Returns the content id of the unmapped origin widget, or {@code null} when not applicable. */
	public String getBlockingWidgetContentId()
	{
		return blockingWidgetContentId;
	}
}
