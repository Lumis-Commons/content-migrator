package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dataprovider;

import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.query.MigrationAdminQueries;
import lumis.doui.source.IDataProvider;
import lumis.doui.source.ISourceData;
import lumis.doui.source.TabularSource;
import lumis.doui.source.field.ITabularSourceField;
import lumis.portal.PortalException;
import lumis.portal.UnexpectedException;
import lumis.portal.authentication.SessionConfig;
import lumis.util.ITransaction;

/** Supplies an authorized migration's error message and stack for the details dialog. */
public class MigrationErrorDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
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
			Map<String, Object> error = MigrationAdminQueries.getMigrationError(session, migrationId, transaction);
			if (error.isEmpty())
			{
				return;
			}
			ISourceData row = source.getData().addRow();
			row.put("errorMessage", error.get("errorMessage"));
			row.put("errorStack", error.get("errorStack"));
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
