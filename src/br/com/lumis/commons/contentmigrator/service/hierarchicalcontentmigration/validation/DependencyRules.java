package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.validation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.w3c.dom.Node;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation.InstanceReference;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation.WorkflowReference;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.RulesViolatedException;
import lumis.content.ContentManagerFactory;
import lumis.content.workflow.WorkflowConfig;
import lumis.doui.DouiManagerFactory;
import lumis.portal.PortalException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.privacyterm.IPrivacyTermManagerSPI;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.util.XmlUtil;

/**
 * Rules checked when a migration is created, with no handler logic: the pair of instances is
 * rejected up front when the destination cannot receive what the origin references.
 * <ul>
 * <li>{@value #RULE_REPOSITORY_REQUIRED}: the origin depends on a media, document, widget, tag or
 * categorization repository and the destination has no repository of the same type;</li>
 * <li>{@value #RULE_REPOSITORY_NOT_SHARED}: the origin depends on an e-mail template or privacy
 * term repository that the destination does not share;</li>
 * <li>{@value #RULE_DIFFERENT_WORKFLOW}: origin and destination have different workflows;</li>
 * <li>{@value #RULE_FILES_FIELD}: a source of the service has a {@code files} field;</li>
 * <li>{@value #RULE_TABLE_SOURCE}: the service is of type {@value #SERVICE_TYPE_CONTENT} and has a
 * source of type {@code table}.</li>
 * </ul>
 */
public final class DependencyRules
{
	/**
	 * Alias for {@link RuleViolation#RULE_REPOSITORY_REQUIRED}.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_REPOSITORY_REQUIRED = RuleViolation.RULE_REPOSITORY_REQUIRED;

	/**
	 * Alias for {@link RuleViolation#RULE_REPOSITORY_NOT_SHARED}.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_REPOSITORY_NOT_SHARED = RuleViolation.RULE_REPOSITORY_NOT_SHARED;

	/**
	 * Alias for {@link RuleViolation#RULE_DIFFERENT_WORKFLOW}.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_DIFFERENT_WORKFLOW = RuleViolation.RULE_DIFFERENT_WORKFLOW;

	/**
	 * Alias for {@link RuleViolation#RULE_FILES_FIELD}.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_FILES_FIELD = RuleViolation.RULE_FILES_FIELD;

	/**
	 * Alias for {@link RuleViolation#RULE_TABLE_SOURCE}.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static final String RULE_TABLE_SOURCE = RuleViolation.RULE_TABLE_SOURCE;

	public static final String SERVICE_TYPE_CONTENT = "lum_content";

	/** Repository types whose items are migrated: the destination needs one of the same type. */
	public static final List<String> REQUIRED_REPOSITORY_TYPES = Collections.unmodifiableList(
			Arrays.asList(
					ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY,
					ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY,
					ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY,
					ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_TAG_REPOSITORY,
					ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY));

	/** Repository types not migrated: the destination must use the same instance. */
	public static final List<String> SHARED_REPOSITORY_TYPES = Collections.unmodifiableList(
			Arrays.asList(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_EMAIL_TEMPLATE_REPOSITORY, IPrivacyTermManagerSPI.DEPENDENCY_TYPE_PRIVACYTERM_REPOSITORY));

	private DependencyRules()
	{
	}

	/**
	 * Checks all creation rules for a migration from the origin to the destination instance so an
	 * incompatible pair is rejected before any row is persisted.
	 *
	 * @throws RulesViolatedException
	 *             if any rule is broken, carrying every violation found.
	 */
	public static void check(SessionConfig session, String originServiceInstanceId, String destinationServiceInstanceId, ITransaction transaction) throws Exception
	{
		List<RuleViolation> violations = validate(session, originServiceInstanceId, destinationServiceInstanceId, transaction);
		if (!violations.isEmpty())
		{
			throw new RulesViolatedException(violations);
		}
	}

	/**
	 * Evaluates every creation rule for a migration from the origin to the destination instance and
	 * returns the broken rules, or an empty list when the migration can be created.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static List<RuleViolation> validate(SessionConfig session, String originServiceInstanceId, String destinationServiceInstanceId, ITransaction transaction)
			throws Exception
	{
		List<RuleViolation.Builder> violations = new ArrayList<>();
		checkRequiredRepositories(session, originServiceInstanceId, destinationServiceInstanceId, transaction, violations);
		checkSharedRepositories(session, originServiceInstanceId, destinationServiceInstanceId, transaction, violations);
		checkWorkflowCompatibility(session, originServiceInstanceId, destinationServiceInstanceId, transaction, violations);
		ServiceInstanceConfig origin = ManagerFactory.getServiceInstanceManager().get(originServiceInstanceId, transaction);
		checkServiceDefinitionSources(session, origin, transaction, violations);
		if (violations.isEmpty())
		{
			return List.of();
		}

		ServiceInstanceConfig destination = ManagerFactory.getServiceInstanceManager().get(destinationServiceInstanceId, transaction);
		InstanceReference originReference = instanceReference(session, origin, transaction);
		InstanceReference destinationReference = instanceReference(session, destination, transaction);
		return violations.stream().map(violation -> violation.instances(originReference, destinationReference).build()).toList();
	}

	/**
	 * Collects violations for required repositories that are missing from the destination.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void checkRequiredRepositories(
			SessionConfig session,
			String originServiceInstanceId,
			String destinationServiceInstanceId,
			ITransaction transaction,
			List<RuleViolation.Builder> violations) throws Exception
	{
		for (String type : REQUIRED_REPOSITORY_TYPES)
		{
			ServiceInstanceConfig originRepository = repositoryConfig(session, originServiceInstanceId, type, transaction);
			ServiceInstanceConfig destinationRepository = repositoryConfig(session, destinationServiceInstanceId, type, transaction);
			if (originRepository != null && destinationRepository == null)
			{
				violations.add(RuleViolation.builder(
						RULE_REPOSITORY_REQUIRED, type, "The origin instance uses a " + type + " and the destination instance has none.")
						.repositories(type, instanceReference(session, originRepository, transaction), null));
			}
		}
	}

	/**
	 * Collects violations for repositories that the destination must share.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void checkSharedRepositories(
			SessionConfig session,
			String originServiceInstanceId,
			String destinationServiceInstanceId,
			ITransaction transaction,
			List<RuleViolation.Builder> violations) throws Exception
	{
		for (String type : SHARED_REPOSITORY_TYPES)
		{
			ServiceInstanceConfig originRepository = repositoryConfig(session, originServiceInstanceId, type, transaction);
			ServiceInstanceConfig destinationRepository = repositoryConfig(session, destinationServiceInstanceId, type, transaction);
			if (originRepository != null && (destinationRepository == null || !originRepository.getId().equals(destinationRepository.getId())))
			{
				violations.add(
						RuleViolation.builder(
								RULE_REPOSITORY_NOT_SHARED,
								type,
								"The " + type + " is not migrated: the destination instance must use the same one as the origin instance.")
								.repositories(type, instanceReference(session, originRepository, transaction),
										instanceReference(session, destinationRepository, transaction)));
			}
		}
	}

	/**
	 * Collects a violation when the workflow configurations differ.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void checkWorkflowCompatibility(
			SessionConfig session,
			String originServiceInstanceId,
			String destinationServiceInstanceId,
			ITransaction transaction,
			List<RuleViolation.Builder> violations) throws Exception
	{
		WorkflowConfig originWorkflow = ContentManagerFactory.getWorkflowManager().getServiceInstanceWorkflow(session, originServiceInstanceId, transaction);
		WorkflowConfig destinationWorkflow = ContentManagerFactory.getWorkflowManager().getServiceInstanceWorkflow(session, destinationServiceInstanceId,
				transaction);
		if (!Objects.equals(originWorkflow == null ? null : originWorkflow.getId(), destinationWorkflow == null ? null : destinationWorkflow.getId()))
		{
			violations.add(
					RuleViolation.builder(
							RULE_DIFFERENT_WORKFLOW,
							null,
							"The origin instance uses the workflow " + workflowLabel(originWorkflow) + " and the destination instance uses "
									+ workflowLabel(destinationWorkflow) + "; both must use the same workflow.")
							.workflows(workflowReference(originWorkflow), workflowReference(destinationWorkflow)));
		}
	}

	/**
	 * Describes a workflow for the English diagnostic message.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String workflowLabel(WorkflowConfig workflow)
	{
		return workflow == null ? "none" : workflow.getId();
	}

	/**
	 * Captures the workflow identity and the resource that localizes its name.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static WorkflowReference workflowReference(WorkflowConfig workflow) throws PortalException
	{
		return workflow == null ? null : new WorkflowReference(workflow.getId(), workflow.getName(), workflow.getResource());
	}

	/**
	 * Collects violations for unsupported sources and fields.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void checkServiceDefinitionSources(SessionConfig session, ServiceInstanceConfig origin, ITransaction transaction,
			List<RuleViolation.Builder> violations) throws Exception
	{
		Node definition = DouiManagerFactory.getDouiManager().getDouiServiceDefinitionNode(origin.getServiceId(), transaction);
		String serviceType = ManagerFactory.getServiceManager().get(session, origin.getServiceId(), transaction).getServiceType();
		boolean isContentService = SERVICE_TYPE_CONTENT.equals(serviceType);
		for (Node sourceNode : XmlUtil.selectNodes("service/sources/source", definition))
		{
			String sourceId = XmlUtil.readAttributeString("id", sourceNode);
			boolean isTableSource = "table".equals(XmlUtil.readAttributeString("type", sourceNode));
			if (isContentService && isTableSource)
			{
				violations.add(
						RuleViolation.builder(
								RULE_TABLE_SOURCE,
								sourceId,
								"The source " + sourceId + " is of type table, which is not migrated in a " + SERVICE_TYPE_CONTENT + " service."));
			}
			for (Node fieldNode : XmlUtil.selectNodes("fields/field[@dataType='files']", sourceNode))
			{
				String fieldId = sourceId + "." + XmlUtil.readAttributeString("id", fieldNode);
				violations.add(RuleViolation.builder(RULE_FILES_FIELD, fieldId, "The field " + fieldId + " is of type files, which is not migrated."));
			}
		}
	}

	/**
	 * Returns the id of the repository instance of the given dependency type linked to the service
	 * instance, or {@code null} when none is configured.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	public static String repository(SessionConfig session, String serviceInstanceId, String dependencyType, ITransaction transaction) throws Exception
	{
		ServiceInstanceConfig repository = repositoryConfig(session, serviceInstanceId, dependencyType, transaction);
		return repository == null ? null : repository.getId();
	}

	/**
	 * Finds the configured repository without discarding its name and channel.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static ServiceInstanceConfig repositoryConfig(SessionConfig session, String serviceInstanceId, String dependencyType, ITransaction transaction)
			throws PortalException
	{
		return ManagerFactory.getServiceInstanceManager().getServiceInstanceByDependency(session, serviceInstanceId, dependencyType, transaction);
	}

	/**
	 * Captures instance details, keeping the name available when the channel path cannot be read.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static InstanceReference instanceReference(SessionConfig session, ServiceInstanceConfig instance, ITransaction transaction)
	{
		if (instance == null)
		{
			return null;
		}

		String path = null;
		try
		{
			List<String> names = ManagerFactory.getChannelManager().getPathNames(session, instance.getChannelId(), false, transaction);
			if (!names.isEmpty())
			{
				path = String.join(" / ", names);
			}
		}
		catch (PortalException exception)
		{
			// The optional path should not prevent the validation from reporting the violation.
		}
		return new InstanceReference(instance.getId(), instance.getName(), path);
	}
}
