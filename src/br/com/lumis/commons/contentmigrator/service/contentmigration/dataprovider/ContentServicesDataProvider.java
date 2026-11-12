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
import lumis.portal.authentication.SessionConfig;
import lumis.portal.localization.ILocalizationManager;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.page.PageWebResource;
import lumis.portal.service.ServiceConfig;
import lumis.util.IResource;
import lumis.util.ITransaction;

/** Supplies the eligible content services for the chained migration form. */
public class ContentServicesDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
{
	@Override
	public void loadData(SessionConfig session, TabularSource<ITabularSourceField> source, ITransaction transaction) throws PortalException
	{
		List<Map<String, Object>> services = MigrationAdminQueries.listContentServices(session, transaction);
		TabularData data = source.getData();
		PageWebResource page = source.getDouiContext() == null ? null : source.getDouiContext().getRequest().getPageWebResource();
		ILocalizationManager localization = ManagerFactory.getLocalizationManager();
		for (Map<String, Object> service : services)
		{
			String serviceId = (String) service.get("serviceId");
			ServiceConfig config = ManagerFactory.getServiceManager().get(session, serviceId, transaction);
			IResource resource = config.getResource();
			ISourceData row = data.addRow();
			row.put("serviceId", serviceId);
			row.put("serviceName", localization.localize((String) service.get("serviceName"), resource, page));
		}
		source.applyPostLoadFilters();
		data.sort(source);
		source.applyPostLoadPagination();
	}
}
