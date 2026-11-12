package br.com.lumis.commons.contentmigrator.service.contentmigration.dataprovider;

import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.query.WidgetMappingQueries;
import lumis.doui.source.IDataProvider;
import lumis.doui.source.ISourceData;
import lumis.doui.source.TabularData;
import lumis.doui.source.TabularSource;
import lumis.doui.source.field.ITabularSourceField;
import lumis.portal.PortalException;
import lumis.portal.UnexpectedException;
import lumis.portal.authentication.SessionConfig;
import lumis.util.ITransaction;

/** Supplies the authorized blocked content and origin widget to the widget mapping modal. */
public class WidgetMappingDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
{
	@Override
	public void loadData(SessionConfig session, TabularSource<ITabularSourceField> source, ITransaction transaction) throws PortalException
	{
		String migrationId = (String) source.getParameterValue("migrationId");
		if (migrationId == null || migrationId.trim().isEmpty())
		{
			return;
		}
		try
		{
			Map<String, Object> blocked = WidgetMappingQueries.getBlockedMigration(session, migrationId, (lumis.portal.transaction.ITransaction) transaction);
			ISourceData row = source.getData().addRow();
			for (Map.Entry<String, Object> field : blocked.entrySet())
			{
				row.put(field.getKey(), field.getValue());
			}
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
