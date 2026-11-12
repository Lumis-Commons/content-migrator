package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation;

/** Thrown by {@code DependencyRules.check} with every violation found. */
public class RulesViolatedException extends IllegalArgumentException
{
	private static final long serialVersionUID = 1L;

	private final List<RuleViolation> violations;

	/** Creates an exception aggregating the messages of all detected dependency rule violations. */
	public RulesViolatedException(List<RuleViolation> violations)
	{
		super(violations.stream().map(RuleViolation::getMessage).collect(Collectors.joining(" ")));
		this.violations = Collections.unmodifiableList(violations);
	}

	/** Returns the unmodifiable list of dependency rule violations. */
	public List<RuleViolation> getViolations()
	{
		return violations;
	}
}
