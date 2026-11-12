package br.com.lumis.commons.contentmigrator.service.contentmigration.exception;

/** Validation failure of the {@code MigrationApi}; {@link #getCode()} is one of the {@code ERROR_*} constants. */
public class MigrationApiException extends RuntimeException
{
	public static final String ERROR_REQUIRED = "REQUIRED";

	public static final String ERROR_INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND";

	public static final String ERROR_SAME_INSTANCE = "SAME_INSTANCE";

	public static final String ERROR_DOUI_SERVICE = "DOUI_SERVICE";

	public static final String ERROR_REPOSITORY_SERVICE = "REPOSITORY_SERVICE";

	public static final String ERROR_DIFFERENT_SERVICES = "DIFFERENT_SERVICES";

	public static final String ERROR_DUPLICATE_PAIR = "DUPLICATE_PAIR";

	public static final String ERROR_UNSUPPORTED_SERVICE = "UNSUPPORTED_SERVICE";

	public static final String ERROR_RULES_VIOLATED = "RULES_VIOLATED";

	public static final String ERROR_INVALID_UPDATE_MODE = "INVALID_UPDATE_MODE";

	public static final String ERROR_INVALID_WIDGET_MAPPING = "INVALID_WIDGET_MAPPING";

	public static final String ERROR_NOT_FOUND = "NOT_FOUND";

	public static final String ERROR_FORBIDDEN = "FORBIDDEN";

	public static final String ERROR_LOCKED = "LOCKED";

	private static final long serialVersionUID = 1L;

	private final String code;

	/** Creates an API validation exception with an error code and diagnostic message. */
	public MigrationApiException(String code, String message)
	{
		super(code + ": " + message);
		this.code = code;
	}

	/** Returns the machine-readable {@code ERROR_*} code of this validation failure. */
	public String getCode()
	{
		return code;
	}
}
