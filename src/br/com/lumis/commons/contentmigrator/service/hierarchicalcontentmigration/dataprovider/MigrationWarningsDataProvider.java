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

/** Supplies the authorized migration's item warning list. */
public class MigrationWarningsDataProvider implements IDataProvider<TabularSource<ITabularSourceField>>
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
			for (Map<String, Object> warning : MigrationAdminQueries.listWarnings(session, migrationId, transaction))
			{
				ISourceData row = source.getData().addRow();
				row.put("id", warning.get("id"));
				row.put("title", warning.get("title"));
				row.put("warnings", warning.get("warnings"));
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
