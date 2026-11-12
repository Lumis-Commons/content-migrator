package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/** A rule broken by a pair of instances. */
public class RuleViolation
{
	private final String rule;

	private final String subject;

	private final String message;

	/** Creates a rule violation for the given rule code, offending subject, and user-facing message. */
	public RuleViolation(String rule, String subject, String message)
	{
		this.rule = rule;
		this.subject = subject;
		this.message = message;
	}

	/** Returns one of the {@code DependencyRules.RULE_*} constants identifying the broken rule. */
	public String getRule()
	{
		return rule;
	}

	/**
	 * Returns what broke the rule: the dependency type, {@code sourceId.fieldId}, the source id,
	 * or {@code null} for the workflow rule.
	 */
	public String getSubject()
	{
		return subject;
	}

	/** Returns the human-readable explanation of the rule violation. */
	public String getMessage()
	{
		return message;
	}

	/** Formats the rule code and message for diagnostic logs and exception messages. */
	@Override
	public String toString()
	{
		return rule + ": " + message;
	}
}
