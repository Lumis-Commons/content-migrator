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
import lumis.util.ITransaction;

/** Supplies visible origin or editable destination instances for the selected service. */
public class InstancesByServiceDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
{
	@Override
	public void loadData(SessionConfig session, TabularSource<ITabularSourceField> source, ITransaction transaction) throws PortalException
	{
		String serviceId = (String) source.getParameterValue("serviceId");
		String excludedInstanceId = (String) source.getParameterValue("originServiceInstanceId");
		boolean destination = "destinationInstances".equals(source.getId());
		List<Map<String, Object>> instances = MigrationAdminQueries.listInstancesByService(session, serviceId, excludedInstanceId, destination, transaction);
		TabularData data = source.getData();
		for (Map<String, Object> instance : instances)
		{
			ISourceData row = data.addRow();
			row.put("serviceInstanceId", instance.get("serviceInstanceId"));
			row.put("serviceId", instance.get("serviceId"));
			row.put("serviceInstanceName", instance.get("serviceInstanceName"));
		}
		source.applyPostLoadFilters();
		data.sort(source);
		source.applyPostLoadPagination();
	}
}
