package br.com.lumis.commons.contentmigrator.service.contentmigration.dataprovider;

import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.query.MigrationAdminQueries;
import lumis.doui.source.IDataProvider;
import lumis.doui.source.ISourceData;
import lumis.doui.source.TabularData;
import lumis.doui.source.TabularSource;
import lumis.doui.source.field.ITabularSourceField;
import lumis.portal.PortalException;
import lumis.portal.UnexpectedException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.localization.ILocalizationManager;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.page.PageWebResource;
import lumis.portal.service.ServiceConfig;
import lumis.util.IResource;
import lumis.util.ITransaction;

/** Supplies the readable migration rows shown in the administration list. */
public class MigrationAdminDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
{
	@Override
	public void loadData(SessionConfig session, TabularSource<ITabularSourceField> source, ITransaction transaction) throws PortalException
	{
		try
		{
			List<Map<String, Object>> migrations = MigrationAdminQueries.listMigrations(session, transaction);
			TabularData data = source.getData();
			PageWebResource page = source.getDouiContext() == null ? null : source.getDouiContext().getRequest().getPageWebResource();
			ILocalizationManager localization = ManagerFactory.getLocalizationManager();
			for (Map<String, Object> migration : migrations)
			{
				ISourceData row = data.addRow();
				String serviceId = (String) migration.get("serviceId");
				ServiceConfig service = ManagerFactory.getServiceManager().get(session, serviceId, transaction);
				IResource resource = service.getResource();
				row.put("id", migration.get("id"));
				row.put("name", migration.get("name"));
				row.put("serviceId", serviceId);
				row.put("serviceName", localization.localize((String) migration.get("serviceName"), resource, page));
				row.put("originServiceInstanceId", migration.get("originServiceInstanceId"));
				row.put("originName", migration.get("originName"));
				row.put("destServiceInstanceId", migration.get("destServiceInstanceId"));
				row.put("destinationName", migration.get("destinationName"));
				String updateMode = (String) migration.get("updateMode");
				row.put("updateMode", updateMode);
				row.put(
						"updateModeLabel",
						localization.localize(
								"STR_MODE_" + String.valueOf(updateMode).toUpperCase(),
								source.getDouiContext().getRequest().getServiceConfig().getResource(),
								page));
				String status = (String) migration.get("status");
				row.put("status", status);
				row.put("statusLabel", localization.localize("STR_STATUS_" + status, source.getDouiContext().getRequest().getServiceConfig().getResource(), page));
				row.put("totalCount", migration.get("totalCount"));
				row.put("migratedCount", migration.get("migratedCount"));
				row.put("errorMessage", migration.get("errorMessage"));
				row.put("errorStack", migration.get("errorStack"));
			}
			source.applyPostLoadFilters();
			data.sort(source);
			source.applyPostLoadPagination();
		}
		catch (Exception e)
		{
			if (e instanceof PortalException)
			{
				throw (PortalException) e;
			}
			throw new UnexpectedException(e);
		}
	}
}
