package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.security;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException;
import lumis.portal.acl.PortalPermissions;
import lumis.portal.user.UserConfig;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.acl.ServiceInstancePermissions;
import lumis.portal.transaction.ITransaction;

/** Permissions checked on the origin and destination service instances of a migration. */
public final class MigrationPermissions
{
	private MigrationPermissions()
	{
	}

	/** Requires MANAGE_PORTAL for the requesting user, even inside a system transaction. */
	public static void requirePortalAdministrator(String userId, ITransaction transaction) throws Exception
	{
		if (userId == null || UserConfig.USER_GUEST_ID.equals(userId))
		{
			throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "portal administrator required");
		}
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			if (!ManagerFactory.getPortalAclManager().checkPermission(session, PortalPermissions.MANAGE_PORTAL, transaction))
			{
				throw new MigrationApiException(MigrationApiException.ERROR_FORBIDDEN, "portal administrator required");
			}
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	/** Returns whether the given user may view the origin service instance of a migration. */
	public static boolean canViewOrigin(String userId, String originServiceInstanceId, ITransaction transaction) throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			return ManagerFactory.getServiceInstanceAclManager()
					.checkServiceInstancePermission(session, originServiceInstanceId, ServiceInstancePermissions.PERMISSION_ID_VIEW_SERVICE_INSTANCE, transaction);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	/** Returns whether the given user may edit the contents of the destination service instance. */
	public static boolean canEditDestination(String userId, String destServiceInstanceId, ITransaction transaction) throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			return ManagerFactory.getServiceInstanceAclManager()
					.checkServiceInstancePermission(session, destServiceInstanceId, ServiceInstancePermissions.PERMISSION_ID_EDIT_SERVICE_INSTANCE_CONTENT, transaction);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	/** Returns whether the given user has a permission on a service instance, or {@code false} if the id is {@code null}. */
	public static boolean hasServiceInstancePermission(SessionConfig session, String userId, String serviceInstanceId, String permission, ITransaction transaction)
			throws Exception
	{
		return serviceInstanceId != null
				&& ManagerFactory.getServiceInstanceAclManager().checkServiceInstancePermission(session, userId, serviceInstanceId, permission, transaction);
	}
}
