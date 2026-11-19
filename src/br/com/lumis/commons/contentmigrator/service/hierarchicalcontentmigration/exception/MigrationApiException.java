package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception;

import java.util.List;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation;

/** Validation failure of the {@code MigrationApi}; {@link #getCode()} is one of the {@code ERROR_*} constants. */
public class MigrationApiException extends RuntimeException
{
	public static final String ERROR_REQUIRED = "REQUIRED";

	public static final String ERROR_INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND";

	public static final String ERROR_SAME_INSTANCE = "SAME_INSTANCE";

	public static final String ERROR_DOUI_SERVICE = "DOUI_SERVICE";

	public static final String ERROR_REPOSITORY_SERVICE = "REPOSITORY_SERVICE";

	public static final String ERROR_DIFFERENT_SERVICES = "DIFFERENT_SERVICES";

	public static final String ERROR_CONTENTMIGRATION_CONFLICT = "CONTENTMIGRATION_CONFLICT";

	public static final String ERROR_DUPLICATE_PAIR = "DUPLICATE_PAIR";

	public static final String ERROR_UNSUPPORTED_SERVICE = "UNSUPPORTED_SERVICE";

	public static final String ERROR_RULES_VIOLATED = "RULES_VIOLATED";

	public static final String ERROR_INVALID_UPDATE_MODE = "INVALID_UPDATE_MODE";

	public static final String ERROR_INVALID_WIDGET_MAPPING = "INVALID_WIDGET_MAPPING";

	public static final String ERROR_NOT_HIERARCHICAL = "NOT_HIERARCHICAL";

	public static final String ERROR_NO_SELECTION = "NO_SELECTION";

	public static final String ERROR_INVALID_SELECTION = "INVALID_SELECTION";

	public static final String ERROR_NOT_FOUND = "NOT_FOUND";

	public static final String ERROR_FORBIDDEN = "FORBIDDEN";

	public static final String ERROR_LOCKED = "LOCKED";

	private static final long serialVersionUID = 1L;

	private final String code;

	/**
	 * Ordered rule violations, empty for other validation failures.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final List<RuleViolation> violations;

	/**
	 * Names of the request parameters reported as missing, empty for other validation failures.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final List<String> fields;

	/**
	 * Creates an API validation exception with an error code and diagnostic message.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public MigrationApiException(String code, String message)
	{
		super(code + ": " + message);
		this.code = code;
		this.violations = List.of();
		this.fields = List.of();
	}

	/**
	 * Creates the {@link #ERROR_REQUIRED} exception that names every missing request parameter, so that the
	 * caller can tell the user which fields to fill in.
	 *
	 * @param message
	 *            diagnostic message, not meant to be shown to the user.
	 * @param fields
	 *            names of the missing parameters, in the order they were checked.
	 * @since %LUM_NEXT_VERSION%
	 */
	public MigrationApiException(String message, List<String> fields)
	{
		super(ERROR_REQUIRED + ": " + message);
		this.code = ERROR_REQUIRED;
		this.violations = List.of();
		this.fields = List.copyOf(fields);
	}

	/**
	 * Wraps a creation rule failure with its diagnostic and immutable list of violations.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public MigrationApiException(RulesViolatedException cause)
	{
		super(ERROR_RULES_VIOLATED + ": " + cause.getMessage(), cause);
		this.code = ERROR_RULES_VIOLATED;
		this.violations = List.copyOf(cause.getViolations());
		this.fields = List.of();
	}

	/**
	 * Returns the ordered list of broken creation rules.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public List<RuleViolation> getViolations()
	{
		return violations;
	}

	/**
	 * Returns the names of the missing request parameters of an {@link #ERROR_REQUIRED} failure.
	 *
	 * @return the immutable list of parameter names, empty for any other failure.
	 * @since %LUM_NEXT_VERSION%
	 */
	public List<String> getFields()
	{
		return fields;
	}

	/** Returns the machine-readable {@code ERROR_*} code of this validation failure. */
	public String getCode()
	{
		return code;
	}
}
