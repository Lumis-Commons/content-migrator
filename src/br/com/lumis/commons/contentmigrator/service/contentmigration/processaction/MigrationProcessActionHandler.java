package br.com.lumis.commons.contentmigrator.service.contentmigration.processaction;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.api.MigrationApi;
import lumis.doui.processaction.GenericProcessActionHandler;
import lumis.portal.PortalException;

/** DOUI adapter for the migration API; validation and lifecycle rules remain in the API. */
public class MigrationProcessActionHandler extends GenericProcessActionHandler
{
	@Override
	public void processAction() throws PortalException
	{
		String migrationId = (String) getParameter("migrationId");
		switch (getId())
		{
			case "createMigration" :
				Map<String, Object> create = new LinkedHashMap<>();
				create.put("name", getParameter("name", String.class));
				create.put("serviceId", getParameter("serviceId", String.class));
				create.put("originServiceInstanceId", getParameter("originServiceInstanceId", String.class));
				create.put("destServiceInstanceId", getParameter("destServiceInstanceId", String.class));
				create.put("author", sessionConfig.getUserId());
				MigrationApi.create(create);
				break;
			case "startIncremental" :
				MigrationApi.start(startParams(migrationId, "ADD_ONLY"));
				break;
			case "startUpdate" :
				MigrationApi.start(startParams(migrationId, "UPDATE"));
				break;
			case "stopMigration" :
				MigrationApi.stop(migrationId);
				break;
			case "deleteMigration" :
				MigrationApi.delete(migrationId);
				break;
			case "undoMigration" :
				Map<String, Object> undo = new LinkedHashMap<>();
				undo.put("migrationId", migrationId);
				undo.put("userId", sessionConfig.getUserId());
				MigrationApi.undo(undo);
				break;
			default :
				throw new IllegalArgumentException("Unknown migration action " + getId());
		}
		addDefaultResponse();
	}

	private Map<String, Object> startParams(String migrationId, String updateMode) throws PortalException
	{
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("migrationId", migrationId);
		params.put("updateMode", updateMode);
		params.put("userId", sessionConfig.getUserId());
		return params;
	}
}
