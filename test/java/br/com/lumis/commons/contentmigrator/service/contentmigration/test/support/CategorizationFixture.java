package br.com.lumis.commons.contentmigrator.service.contentmigration.test.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture.Work;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.source.field.MetaDataSourceField;
import lumis.content.source.field.MetaDataSourceField.Type;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.service.categorization.CategorizationUtil;

/** Creates and removes categorization terms through their native content table source. */
public class CategorizationFixture
{
	public static final String SERVICE_ID = "lumis.service.categorization";

	public static final String SOURCE_ID = "categorizationTerm";

	private final Deque<Work<Void>> cleaners = new ArrayDeque<>();

	/** Adds one term and returns its stable Content id. */
	public String addTerm(ServiceInstanceConfig repository, String parentContentId, String name) throws Exception
	{
		String contentId = PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			ContentTableSource source = source(session, transaction, repository);
			Map<String, Object> data = new HashMap<>();
			data.put("name", name);
			data.put("description", "description " + name);
			data.put("parentTermContentId", parentContentId);
			MetaDataSourceField language = source.getField(Type.Language);
			if (language != null)
			{
				data.put(language.getId(), ContentMetadataFixture.LOCALE);
				data.put(language.getId() + ".isDefaultLocale", Boolean.TRUE);
			}
			String itemId = source.addData(data).getItemId();
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			version.getContentLocale().setPublishedVersion(version);
			return version.getContentLocale().getContent().getId();
		});
		track(repository.getId(), contentId);
		return contentId;
	}

	/**
	 * Adds a published locale to an existing term, with the given parent, and returns the item id of
	 * its version. The term is removed with all its locales by the cleanup of {@link #addTerm}.
	 */
	public String addTermLocale(ServiceInstanceConfig repository, String contentId, String parentContentId, Locale locale, String name) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(locale);
			ContentTableSource source = source(session, transaction, repository);
			Map<String, Object> data = new HashMap<>();
			data.put("name", name);
			data.put("description", "description " + name);
			data.put("parentTermContentId", parentContentId);
			MetaDataSourceField language = source.getField(Type.Language);
			data.put(language.getId(), locale);
			data.put(language.getId() + ".isDefaultLocale", Boolean.FALSE);
			data.put(source.getField(Type.ContentId).getId(), contentId);
			String itemId = source.addData(data).getItemId();
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
			version.getContentLocale().setPublishedVersion(version);
			return itemId;
		});
	}

	/** Tracks an existing term, including a term created by the migration engine, with all its locales. */
	public void track(String repositoryId, String contentId)
	{
		if (contentId == null)
		{
			return;
		}
		cleaners.push((session, transaction) ->
		{
			try
			{
				Content content = ContentManagerFactory.getContentManager().getContent(contentId);
				List<String> itemIds = new ArrayList<>();
				for (ContentLocale locale : content.getContentLocales())
				{
					if (locale.getActiveVersion() != null)
					{
						itemIds.add(locale.getActiveVersion().getItemId());
					}
				}
				if (!itemIds.isEmpty())
				{
					ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(repositoryId, transaction);
					source(session, transaction, repository).deleteDataByItemIds(itemIds);
				}
			}
			catch (PortalObjectNotFoundException e)
			{
				// Term or instance was already removed.
			}
			return null;
		});
	}

	/** Returns the term names from root to the given term. */
	public static java.util.List<String> path(String contentId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			session.setLocale(ContentMetadataFixture.LOCALE);
			return CategorizationUtil.getTermPathNames(contentId);
		});
	}

	private static ContentTableSource source(SessionConfig session, ITransaction transaction, ServiceInstanceConfig repository) throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource(SOURCE_ID, new SourceContext(session, repository, transaction));
	}

	/** Deletes in reverse creation order, so child terms are removed before parents. */
	public int cleanup()
	{
		if (PortalFixture.keepTestData())
		{
			System.out.println("[CategorizationFixture] " + PortalFixture.KEEP_TEST_DATA + "=true: keeping " + cleaners.size() + " terms");
			cleaners.clear();
			return 0;
		}
		int failures = 0;
		while (!cleaners.isEmpty())
		{
			try
			{
				PortalFixture.inTransaction(cleaners.pop());
			}
			catch (Exception e)
			{
				failures++;
				System.err.println("[CategorizationFixture] cleanup step failed: " + e);
				e.printStackTrace();
			}
		}
		return failures;
	}
}
