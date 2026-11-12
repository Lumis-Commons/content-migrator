package br.com.lumis.commons.contentmigrator.service.contentmigration.copy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationScope;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentLocale;
import lumis.content.table.ContentTableSource;
import lumis.content.workflow.ContentWorkflowMetaData;
import lumis.content.workflow.WorkflowConfig;
import lumis.content.workflow.WorkflowPermission;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;

/**
 * Reads and writes workflow state and assignment directly on {@code lum_WorkflowMetaData} so
 * migrated or undone items can be updated, deleted or restored without executing workflow
 * transitions or side-effect actions.
 */
public final class WorkflowCloner
{
	private WorkflowCloner()
	{
	}

	/**
	 * Places a destination locale in its workflow start state before an update or deletion so the
	 * native source allows modifying or removing archived or published content. A locale whose
	 * source declares no workflow has no workflow row and is left as is.
	 */
	public static void returnToStartState(SessionConfig session, ITransaction transaction, Connection connection, ContentLocale contentLocale) throws Exception
	{
		WorkflowConfig workflow = sourceWorkflow(session, transaction, contentLocale.getContent());
		if (workflow != null)
		{
			writeState(connection, contentLocale.getId(), workflow.getStartState().getId(), session.getUserId());
		}
	}

	/**
	 * Returns the workflow of the content's source, as {@code WorkflowManager#getSourceWorkflow}
	 * does: the instance workflow only when the source declares workflow metadata, because only
	 * then does the platform create a {@code lum_WorkflowMetaData} row for its content.
	 */
	private static WorkflowConfig sourceWorkflow(SessionConfig session, ITransaction transaction, Content content) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(content.getServiceInstanceId(), transaction);
		ContentTableSource source = (ContentTableSource) SourceFactory.createSource(content.getSourceId(), new SourceContext(session, instance, transaction));
		return source.getWorkflowConfig();
	}

	private static void writeState(Connection connection, String contentLocaleId, String stateId, String assignedTo) throws Exception
	{
		ManagerFactory.getEntityManager().flush();
		// @formatter:off
		String sql = "update lum_WorkflowMetaData "
				+ "set stateId = ?, assignedTo = ? "
				+ "where contentLocaleId = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, stateId);
			statement.setString(2, assignedTo);
			statement.setString(3, contentLocaleId);
			if (statement.executeUpdate() != 1)
			{
				throw new IllegalStateException("Expected one workflow metadata row for ContentLocale " + contentLocaleId + ".");
			}
		}
	}

	/**
	 * Reads the workflow state and assignment of an origin locale so they can be restored after
	 * destination versions are written.
	 */
	static ContentWorkflowMetaData readWorkflowMetaData(SessionConfig session, ITransaction transaction, ContentLocale contentLocale) throws Exception
	{
		return ContentManagerFactory.getWorkflowManager().getContentWorkflowMetaData(session, contentLocale, transaction);
	}

	/**
	 * Returns the origin assignment only when that principal still has an effective workflow role
	 * in the destination instance, recording a warning and leaving the item unassigned otherwise.
	 */
	static String resolveAssignedTo(MigrationScope scope, ContentWorkflowMetaData origin) throws Exception
	{
		if (origin == null || origin.getAssignedTo() == null)
		{
			return null;
		}

		String assignedTo = origin.getAssignedTo();
		SessionConfig session = scope.getSession();
		ITransaction transaction = scope.getTransaction();
		String destinationServiceInstanceId = scope.getDestinationServiceInstanceId();
		WorkflowConfig workflow = ContentManagerFactory.getWorkflowManager().getServiceInstanceWorkflow(session, destinationServiceInstanceId, transaction);
		boolean isSystemUser = UserConfig.USER_SYSTEM_ID.equals(assignedTo);
		boolean hasWorkflowRoles = workflow != null && !workflow.getRoles().isEmpty();
		if (isSystemUser && hasWorkflowRoles)
		{
			return assignedTo;
		}
		if (hasEffectiveWorkflowRole(session, transaction, destinationServiceInstanceId, assignedTo))
		{
			return assignedTo;
		}

		scope.addWarning("Assigned principal " + assignedTo + " has no workflow role in the destination instance; content was left unassigned.");
		return null;
	}

	private static boolean hasEffectiveWorkflowRole(SessionConfig session, ITransaction transaction, String destinationServiceInstanceId, String assignedTo)
			throws Exception
	{
		// Effective roles are allowed roles minus denied roles across the principal and its groups.
		Set<String> principals = ManagerFactory.getPrincipalManager().getPrincipals(session, assignedTo, transaction);
		Collection<WorkflowPermission> permissions = ContentManagerFactory.getWorkflowManager()
				.getWorkflowPermissions(session, destinationServiceInstanceId, transaction);
		Set<String> allowedRoles = new HashSet<>();
		Set<String> deniedRoles = new HashSet<>();
		for (WorkflowPermission permission : permissions)
		{
			if (!principals.contains(permission.getPrincipalId()))
			{
				continue;
			}
			if (permission.getType() == WorkflowPermission.PERMISSION_TYPE_ALLOW)
			{
				allowedRoles.add(permission.getRole());
			}
			else if (permission.getType() == WorkflowPermission.PERMISSION_TYPE_DENY)
			{
				deniedRoles.add(permission.getRole());
			}
		}
		allowedRoles.removeAll(deniedRoles);
		return !allowedRoles.isEmpty();
	}

	/**
	 * Restores the origin workflow state and validated destination assignment directly in the
	 * database without triggering transitions.
	 */
	static void copyState(Connection connection, ContentLocale destinationLocale, ContentWorkflowMetaData origin, String assignedTo) throws Exception
	{
		if (origin == null)
		{
			return;
		}
		writeState(connection, destinationLocale.getId(), origin.getStateId(), assignedTo);
	}
}
