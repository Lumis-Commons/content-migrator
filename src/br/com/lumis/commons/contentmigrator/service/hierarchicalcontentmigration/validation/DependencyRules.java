package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.validation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.w3c.dom.Node;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.RulesViolatedException;
import lumis.content.ContentManagerFactory;
import lumis.content.workflow.WorkflowConfig;
import lumis.doui.DouiManagerFactory;
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
	public static final String RULE_REPOSITORY_REQUIRED = "REPOSITORY_REQUIRED";

	public static final String RULE_REPOSITORY_NOT_SHARED = "REPOSITORY_NOT_SHARED";

	public static final String RULE_DIFFERENT_WORKFLOW = "DIFFERENT_WORKFLOW";

	public static final String RULE_FILES_FIELD = "FILES_FIELD";

	public static final String RULE_TABLE_SOURCE = "TABLE_SOURCE";

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
	 */
	public static List<RuleViolation> validate(SessionConfig session, String originServiceInstanceId, String destinationServiceInstanceId, ITransaction transaction)
			throws Exception
	{
		List<RuleViolation> violations = new ArrayList<>();
		checkRequiredRepositories(session, originServiceInstanceId, destinationServiceInstanceId, transaction, violations);
		checkSharedRepositories(session, originServiceInstanceId, destinationServiceInstanceId, transaction, violations);
		checkWorkflowCompatibility(session, originServiceInstanceId, destinationServiceInstanceId, transaction, violations);
		checkServiceDefinitionSources(session, originServiceInstanceId, transaction, violations);
		return violations;
	}

	private static void checkRequiredRepositories(
			SessionConfig session,
			String originServiceInstanceId,
			String destinationServiceInstanceId,
			ITransaction transaction,
			List<RuleViolation> violations) throws Exception
	{
		for (String type : REQUIRED_REPOSITORY_TYPES)
		{
			boolean hasOriginRepository = repository(session, originServiceInstanceId, type, transaction) != null;
			boolean hasDestinationRepository = repository(session, destinationServiceInstanceId, type, transaction) != null;
			if (hasOriginRepository && !hasDestinationRepository)
			{
				violations.add(new RuleViolation(RULE_REPOSITORY_REQUIRED, type, "The origin instance uses a " + type + " and the destination instance has none."));
			}
		}
	}

	private static void checkSharedRepositories(
			SessionConfig session,
			String originServiceInstanceId,
			String destinationServiceInstanceId,
			ITransaction transaction,
			List<RuleViolation> violations) throws Exception
	{
		for (String type : SHARED_REPOSITORY_TYPES)
		{
			String originRepository = repository(session, originServiceInstanceId, type, transaction);
			String destinationRepository = repository(session, destinationServiceInstanceId, type, transaction);
			if (originRepository != null && !originRepository.equals(destinationRepository))
			{
				violations.add(
						new RuleViolation(
								RULE_REPOSITORY_NOT_SHARED,
								type,
								"The " + type + " is not migrated: the destination instance must use the same one as the origin instance."));
			}
		}
	}

	private static void checkWorkflowCompatibility(
			SessionConfig session,
			String originServiceInstanceId,
			String destinationServiceInstanceId,
			ITransaction transaction,
			List<RuleViolation> violations) throws Exception
	{
		String originWorkflow = getWorkflowId(session, originServiceInstanceId, transaction);
		String destinationWorkflow = getWorkflowId(session, destinationServiceInstanceId, transaction);
		if (!Objects.equals(originWorkflow, destinationWorkflow))
		{
			violations.add(
					new RuleViolation(
							RULE_DIFFERENT_WORKFLOW,
							null,
							"The origin instance uses the workflow " + originWorkflow + " and the destination instance uses " + destinationWorkflow
									+ "; both must use the same workflow."));
		}
	}

	private static String getWorkflowId(SessionConfig session, String serviceInstanceId, ITransaction transaction) throws Exception
	{
		WorkflowConfig workflow = ContentManagerFactory.getWorkflowManager().getServiceInstanceWorkflow(session, serviceInstanceId, transaction);
		return workflow == null ? null : workflow.getId();
	}

	private static void checkServiceDefinitionSources(SessionConfig session, String originServiceInstanceId, ITransaction transaction, List<RuleViolation> violations)
			throws Exception
	{
		ServiceInstanceConfig origin = ManagerFactory.getServiceInstanceManager().get(originServiceInstanceId, transaction);
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
						new RuleViolation(
								RULE_TABLE_SOURCE,
								sourceId,
								"The source " + sourceId + " is of type table, which is not migrated in a " + SERVICE_TYPE_CONTENT + " service."));
			}
			for (Node fieldNode : XmlUtil.selectNodes("fields/field[@dataType='files']", sourceNode))
			{
				String fieldId = sourceId + "." + XmlUtil.readAttributeString("id", fieldNode);
				violations.add(new RuleViolation(RULE_FILES_FIELD, fieldId, "The field " + fieldId + " is of type files, which is not migrated."));
			}
		}
	}

	/**
	 * Returns the id of the repository instance of the given dependency type linked to the service
	 * instance, or {@code null} when none is configured.
	 */
	public static String repository(SessionConfig session, String serviceInstanceId, String dependencyType, ITransaction transaction) throws Exception
	{
		ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager()
				.getServiceInstanceByDependency(session, serviceInstanceId, dependencyType, transaction);
		return repository == null ? null : repository.getId();
	}
}
