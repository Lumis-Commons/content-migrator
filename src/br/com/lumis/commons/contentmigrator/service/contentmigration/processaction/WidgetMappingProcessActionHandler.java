package br.com.lumis.commons.contentmigrator.service.contentmigration.processaction;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.api.MigrationApi;
import lumis.doui.processaction.GenericProcessActionHandler;
import lumis.portal.PortalException;

/** DOUI adapter for saving the selected widget mapping through the permission-checked API. */
public class WidgetMappingProcessActionHandler extends GenericProcessActionHandler
{
	@Override
	public void processAction() throws PortalException
	{
		saveMapping((String) getParameter("migrationId"), (String) getParameter("destWidgetContentId"));
		addDefaultResponse();
	}

	/** Test seam used by the form action; the API reloads the blocked widget and repository server-side. */
	public static Map<String, Object> saveMapping(String migrationId, String destWidgetContentId)
	{
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("migrationId", migrationId);
		params.put("destWidgetContentId", destWidgetContentId);
		return MigrationApi.saveWidgetMapping(params);
	}
}
