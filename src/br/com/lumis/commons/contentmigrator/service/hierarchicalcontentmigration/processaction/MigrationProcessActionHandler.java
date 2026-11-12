package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.processaction;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.api.MigrationApi;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException;
import lumis.doui.processaction.GenericProcessActionHandler;
import lumis.portal.PortalException;
import lumis.portal.localization.GenericStringResource;

/** DOUI adapter for the migration API; validation and lifecycle rules remain in the API. */
public class MigrationProcessActionHandler extends GenericProcessActionHandler
{
	private static final Map<String, String> ERROR_STRINGS = Map.ofEntries(
			Map.entry(MigrationApiException.ERROR_REQUIRED, "STR_MIGRATION_REQUIRED"),
			Map.entry(MigrationApiException.ERROR_INSTANCE_NOT_FOUND, "STR_MIGRATION_INSTANCE_NOT_FOUND"),
			Map.entry(MigrationApiException.ERROR_SAME_INSTANCE, "STR_MIGRATION_SAME_INSTANCE"),
			Map.entry(MigrationApiException.ERROR_DOUI_SERVICE, "STR_MIGRATION_DOUI_SERVICE"),
			Map.entry(MigrationApiException.ERROR_REPOSITORY_SERVICE, "STR_MIGRATION_REPOSITORY_SERVICE"),
			Map.entry(MigrationApiException.ERROR_DIFFERENT_SERVICES, "STR_MIGRATION_DIFFERENT_SERVICES"),
			Map.entry(MigrationApiException.ERROR_CONTENTMIGRATION_CONFLICT, "STR_MIGRATION_CONTENTMIGRATION_CONFLICT"),
			Map.entry(MigrationApiException.ERROR_DUPLICATE_PAIR, "STR_MIGRATION_DUPLICATE_PAIR"),
			Map.entry(MigrationApiException.ERROR_UNSUPPORTED_SERVICE, "STR_MIGRATION_UNSUPPORTED_SERVICE"),
			Map.entry(MigrationApiException.ERROR_RULES_VIOLATED, "STR_MIGRATION_RULES_VIOLATED"),
			Map.entry(MigrationApiException.ERROR_INVALID_UPDATE_MODE, "STR_MIGRATION_INVALID_UPDATE_MODE"),
			Map.entry(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "STR_MIGRATION_INVALID_WIDGET_MAPPING"),
			Map.entry(MigrationApiException.ERROR_NOT_HIERARCHICAL, "STR_MIGRATION_NOT_HIERARCHICAL"),
			Map.entry(MigrationApiException.ERROR_NO_SELECTION, "STR_MIGRATION_NO_SELECTION"),
			Map.entry(MigrationApiException.ERROR_INVALID_SELECTION, "STR_MIGRATION_INVALID_SELECTION"),
			Map.entry(MigrationApiException.ERROR_NOT_FOUND, "STR_MIGRATION_NOT_FOUND"),
			Map.entry(MigrationApiException.ERROR_FORBIDDEN, "STR_MIGRATION_FORBIDDEN"),
			Map.entry("SELECTION_TOO_LARGE", "STR_MIGRATION_SELECTION_TOO_LARGE"),
			Map.entry(MigrationApiException.ERROR_LOCKED, "STR_MIGRATION_LOCKED"));

	@Override
	public void processAction() throws PortalException
	{
		try
		{
			String migrationId = (String) getParameter("migrationId");
			switch (getId())
			{
				case "createMigration" :
					Map<String, Object> create = new LinkedHashMap<>();
					create.put("name", getParameter("name", String.class));
					create.put("originServiceInstanceId", getParameter("originServiceInstanceId", String.class));
					create.put("destServiceInstanceId", getParameter("destServiceInstanceId", String.class));
					create.put("selectedContentIds", getParameter("selectedContentIds", String.class));
					create.put("author", sessionConfig.getUserId());
					MigrationApi.create(create);
					break;
				case "updateSelection" :
					Map<String, Object> selection = new LinkedHashMap<>();
					selection.put("migrationId", migrationId);
					selection.put("selectedContentIds", getParameter("selectedContentIds", String.class));
					MigrationApi.updateSelection(selection);
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
		}
		catch (MigrationApiException exception)
		{
			String key = ERROR_STRINGS.getOrDefault(exception.getCode(), "STR_MIGRATION_ERROR");
			throw new PortalException(key,
					new GenericStringResource("br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/strings/strings"), exception);
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
