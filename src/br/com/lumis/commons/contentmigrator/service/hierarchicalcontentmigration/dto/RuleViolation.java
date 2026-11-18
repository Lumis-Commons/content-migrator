package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

import java.util.Objects;

import lumis.util.IResource;

/**
 * Diagnostic details of a rule broken by a pair of service instances.
 *
 * @since %LUM_NEXT_VERSION%
 */
public final class RuleViolation
{
	/**
	 * The destination lacks a required repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_REPOSITORY_REQUIRED = "REPOSITORY_REQUIRED";

	/**
	 * The destination does not share the repository used by the origin.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_REPOSITORY_NOT_SHARED = "REPOSITORY_NOT_SHARED";

	/**
	 * The origin and destination workflows differ.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_DIFFERENT_WORKFLOW = "DIFFERENT_WORKFLOW";

	/**
	 * A service source contains an unsupported files field.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_FILES_FIELD = "FILES_FIELD";

	/**
	 * A content service contains an unsupported table source.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_TABLE_SOURCE = "TABLE_SOURCE";

	/**
	 * Code of the broken rule.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final String rule;

	/**
	 * Dependency code, source or field identifier, or null for a workflow violation.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final String subject;

	/**
	 * English diagnostic message.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final String message;

	/**
	 * Repository dependency code, when applicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final String dependencyType;

	/**
	 * Origin service instance.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final InstanceReference origin;

	/**
	 * Destination service instance.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final InstanceReference destination;

	/**
	 * Origin repository, or null when absent or inapplicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final InstanceReference originRepository;

	/**
	 * Destination repository, or null when absent or inapplicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final InstanceReference destinationRepository;

	/**
	 * Workflow used by the origin instance, or null when it has none.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final WorkflowReference originWorkflow;

	/**
	 * Workflow used by the destination instance, or null when it has none.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private final WorkflowReference destinationWorkflow;

	/**
	 * Creates a diagnostic violation without instance details.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public RuleViolation(String rule, String subject, String message)
	{
		this(builder(rule, subject, message));
	}

	/**
	 * Copies the rule and instance details from the builder.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private RuleViolation(Builder builder)
	{
		rule = builder.rule;
		subject = builder.subject;
		message = builder.message;
		dependencyType = builder.dependencyType;
		origin = builder.origin;
		destination = builder.destination;
		originWorkflow = builder.originWorkflow;
		destinationWorkflow = builder.destinationWorkflow;
		originRepository = builder.originRepository;
		destinationRepository = builder.destinationRepository;
	}

	/**
	 * Starts a violation with its rule code, subject, and English diagnostic.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static Builder builder(String rule, String subject, String message)
	{
		return new Builder(rule, subject, message);
	}

	/**
	 * Returns the code of the broken rule.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public String getRule()
	{
		return rule;
	}

	/**
	 * Returns the dependency code, source or field identifier, or null for a workflow violation.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public String getSubject()
	{
		return subject;
	}

	/**
	 * Returns the english diagnostic message.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public String getMessage()
	{
		return message;
	}

	/**
	 * Returns the repository dependency code when applicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public String getDependencyType()
	{
		return dependencyType;
	}

	/**
	 * Returns the origin service instance.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public InstanceReference getOrigin()
	{
		return origin;
	}

	/**
	 * Returns the destination service instance.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public InstanceReference getDestination()
	{
		return destination;
	}

	/**
	 * Returns the origin repository, or null when absent or inapplicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public InstanceReference getOriginRepository()
	{
		return originRepository;
	}

	/**
	 * Returns the destination repository, or null when absent or inapplicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public InstanceReference getDestinationRepository()
	{
		return destinationRepository;
	}

	/**
	 * Returns the workflow of the origin instance, or null when absent or inapplicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public WorkflowReference getOriginWorkflow()
	{
		return originWorkflow;
	}

	/**
	 * Returns the workflow of the destination instance, or null when absent or inapplicable.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public WorkflowReference getDestinationWorkflow()
	{
		return destinationWorkflow;
	}

	/** Formats the rule code and message for diagnostic logs and exception messages. */
	@Override
	public String toString()
	{
		return rule + ": " + message;
	}

	/**
	 * Identifies a service instance by its ID, name and optional path from the channel root.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public record InstanceReference(String id, String name, String channelPath)
	{
	}

	/**
	 * Identifies a workflow by its ID, its localizable name and the resource that localizes the name.
	 *
	 * @param id
	 *            workflow ID.
	 * @param name
	 *            workflow name, localizable with the resource.
	 * @param resource
	 *            workflow string resource.
	 * @since %LUM_NEXT_VERSION%
	 */
	public record WorkflowReference(String id, String name, IResource resource)
	{
	}

	/**
	 * Collects optional instance and dependency details for a violation.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final class Builder
	{
		/**
		 * Code of the broken rule.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private final String rule;

		/**
		 * Dependency code, source or field identifier, or null for a workflow violation.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private final String subject;

		/**
		 * English diagnostic message.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private final String message;

		/**
		 * Repository dependency code, when applicable.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private String dependencyType;

		/**
		 * Origin service instance.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private InstanceReference origin;

		/**
		 * Destination service instance.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private InstanceReference destination;

		/**
		 * Origin repository, or null when absent or inapplicable.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private InstanceReference originRepository;

		/**
		 * Destination repository, or null when absent or inapplicable.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private InstanceReference destinationRepository;

		/**
		 * Workflow used by the origin instance, or null when it has none.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private WorkflowReference originWorkflow;

		/**
		 * Workflow used by the destination instance, or null when it has none.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private WorkflowReference destinationWorkflow;

		/**
		 * Creates a builder for the given rule, subject and diagnostic.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private Builder(String rule, String subject, String message)
		{
			this.rule = Objects.requireNonNull(rule);
			this.subject = subject;
			this.message = Objects.requireNonNull(message);
		}

		/**
		 * Adds the pair of service instances being validated.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		public Builder instances(InstanceReference origin, InstanceReference destination)
		{
			this.origin = origin;
			this.destination = destination;
			return this;
		}

		/**
		 * Adds the dependency code and the available instances of its repositories.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		public Builder repositories(String dependencyType, InstanceReference originRepository, InstanceReference destinationRepository)
		{
			this.dependencyType = dependencyType;
			this.originRepository = originRepository;
			this.destinationRepository = destinationRepository;
			return this;
		}

		/**
		 * Adds the workflows of the origin and destination instances; either may be null.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		public Builder workflows(WorkflowReference originWorkflow, WorkflowReference destinationWorkflow)
		{
			this.originWorkflow = originWorkflow;
			this.destinationWorkflow = destinationWorkflow;
			return this;
		}

		/**
		 * Creates an immutable snapshot of the violation.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		public RuleViolation build()
		{
			return new RuleViolation(this);
		}
	}
}
