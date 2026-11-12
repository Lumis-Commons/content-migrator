package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dataprovider;

import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.query.WidgetMappingQueries;
import lumis.doui.source.IDataProvider;
import lumis.doui.source.ISourceData;
import lumis.doui.source.TabularData;
import lumis.doui.source.TabularSource;
import lumis.doui.source.field.ITabularSourceField;
import lumis.portal.PortalException;
import lumis.portal.UnexpectedException;
import lumis.portal.authentication.SessionConfig;
import lumis.util.ITransaction;

/** Supplies only active widgets from the blocked migration's linked destination repository. */
public class DestinationWidgetsDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
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
			List<Map<String, Object>> widgets = WidgetMappingQueries.listDestinationWidgets(session, migrationId, (lumis.portal.transaction.ITransaction) transaction);
			TabularData data = source.getData();
			for (Map<String, Object> widget : widgets)
			{
				ISourceData row = data.addRow();
				row.put("widgetContentId", widget.get("widgetContentId"));
				row.put("widgetName", widget.get("widgetName"));
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
