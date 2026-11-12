package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.categorization;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.FileCopy;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationItem;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.source.field.MetaDataSourceField;
import lumis.content.source.field.MetaDataSourceField.Type;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.ISourceData;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.doui.source.field.ISourceField;
import lumis.doui.source.field.type.FileDataType;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.service.categorization.CategorizationUtil;

/**
 * Resolves the {@code categorizationTerm} associations of a content to terms of the destination
 * categorization repository, by path. {@link RepositoryCloner} maps each term locale
 * ({@value #KIND_TERM} rows of lum_cmig_HMigItemMap) and resolves its parent first; a destination term
 * with the same name under the same destination parent is reused, otherwise one is created through
 * the native {@code categorizationTerm} source. The name and the row of a term are read in the
 * locale of the content being migrated, or in the default locale of the term when it does not have
 * that one, and a term locale whose term was already mapped is added to the same destination term.
 * <p>
 * When the migration crosses repositories, a term outside the origin categorization repository,
 * including when the origin has no categorization repository, is kept with a warning; with a shared
 * repository the reference is preserved unchanged. A term that no longer exists is omitted.
 */
public class CategorizationDependencyHandler implements DependencyHandler
{
	private static final String SERVICE_ID = "lumis.service.categorization";

	private static final String SOURCE_ID = "categorizationTerm";

	private static final String KIND_TERM = "TERM";

	@Override
	public String getDependencyType()
	{
		return ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY;
	}

	@Override
	public boolean handles(ISourceField field)
	{
		return false;
	}

	@Override
	public void precheck(DependencyContext context, MigrationItem item) throws Exception
	{
		if (context.isSameRepository() || context.getOriginRepositoryInstanceId() == null)
		{
			return;
		}
		ContentLocale locale = ContentManagerFactory.getContentManager().getContentLocale(item.getContentLocaleId());
		for (ContentVersion version : versionsToCopy(locale))
		{
			for (ContentAssociation association : version.getContentAssociations())
			{
				if (!ContentAssociation.TYPE_CATEGORIZATION_TERM.equals(association.getType()) || association.getAssociatedContent() == null)
				{
					continue;
				}
				Content term = association.getAssociatedContent();
				if (!context.getOriginRepositoryInstanceId().equals(term.getServiceInstanceId()))
				{
					context.addWarning("Categorization term " + term.getId() + " belongs to another repository and was left unchanged.");
					continue;
				}
				try
				{
					CategorizationUtil.getTermPathNames(term.getId());
				}
				catch (PortalObjectNotFoundException e)
				{
					context.addWarning("Categorization term " + term.getId() + " no longer exists and was omitted.");
				}
			}
		}
	}

	@Override
	public Object resolve(DependencyContext context, String fieldOrAssociationId, Object value) throws Exception
	{
		String originTermId = contentId(value);
		boolean isMissingId = originTermId == null || originTermId.isEmpty();
		if (isMissingId || context.isSameRepository())
		{
			return originTermId;
		}
		Content origin;
		try
		{
			origin = ContentManagerFactory.getContentManager().getContent(originTermId);
		}
		catch (PortalObjectNotFoundException e)
		{
			context.addWarning("Categorization term " + originTermId + " no longer exists and was omitted.");
			return null;
		}
		if (!origin.getServiceInstanceId().equals(context.getOriginRepositoryInstanceId()))
		{
			context.addWarning("Categorization term " + originTermId + " belongs to another repository and was left unchanged.");
			return originTermId;
		}
		return new RepositoryCloner<>(context, new TermAdapter(context)).resolve(originTermId);
	}

	@Override
	public String rewriteHtml(DependencyContext context, String html)
	{
		return html;
	}

	@Override
	public boolean isCloneReferenced(DependencyContext context, String destContentLocaleId) throws Exception
	{
		// The select reads from lum_ContentLocale because Oracle does not accept a select without from.
		// @formatter:off
		String sql = "select count(*) from lum_ContentLocale cl where cl.id = ? "
				+ "and (exists (select 1 from lum_ContentAssociation a where a.type = ? and a.associatedContentId = cl.contentId) "
				+ "or exists (select 1 from lum_CategorizationTerm child where child.parentTermContentId = cl.contentId))";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(sql))
		{
			statement.setString(1, destContentLocaleId);
			statement.setString(2, ContentAssociation.TYPE_CATEGORIZATION_TERM);
			try (ResultSet rs = statement.executeQuery())
			{
				rs.next();
				return rs.getInt(1) != 0;
			}
		}
	}

	@Override
	public void deleteClone(DependencyContext context, String destContentLocaleId) throws Exception
	{
		// For terms, the item id of the map is the content id (TermAdapter#itemId).
		String destContentId = null;
		boolean created = false;
		// @formatter:off
		String select = "select destItemId, destCreated from lum_cmig_HMigItemMap where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? and destContentLocaleId = ? and sourceId = ? and kind = ?";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(select))
		{
			statement.setString(1, context.getOriginRepositoryInstanceId());
			statement.setString(2, context.getDestRepositoryInstanceId());
			statement.setString(3, destContentLocaleId);
			statement.setString(4, SOURCE_ID);
			statement.setString(5, KIND_TERM);
			try (ResultSet rs = statement.executeQuery())
			{
				while (rs.next())
				{
					if (destContentId == null)
					{
						destContentId = rs.getString(1);
					}
					created |= rs.getBoolean(2);
				}
			}
		}
		boolean isNewlyCreatedTerm = created && destContentId != null;
		if (isNewlyCreatedTerm && !isCloneReferenced(context, destContentLocaleId))
		{
			ContentLocale locale = ContentManagerFactory.getContentManager().getContentLocale(destContentLocaleId);
			ContentVersion active = locale.getActiveVersion();
			if (active != null)
			{
				ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
				source(context.getSession(), context.getTransaction(), repository).deleteDataByItemIds(Collections.singleton(active.getItemId()));
			}
		}
		// @formatter:off
		String delete = "delete from lum_cmig_HMigItemMap where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? and destContentLocaleId = ? and sourceId = ? and kind = ?";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(delete))
		{
			statement.setString(1, context.getOriginRepositoryInstanceId());
			statement.setString(2, context.getDestRepositoryInstanceId());
			statement.setString(3, destContentLocaleId);
			statement.setString(4, SOURCE_ID);
			statement.setString(5, KIND_TERM);
			statement.executeUpdate();
		}
	}

	private static List<ContentVersion> versionsToCopy(ContentLocale locale)
	{
		List<ContentVersion> versions = new ArrayList<>();
		ContentVersion published = locale.getPublishedVersion();
		ContentVersion active = locale.getActiveVersion();
		if (published != null)
		{
			versions.add(published);
		}
		if (active != null && (published == null || !published.getItemId().equals(active.getItemId())))
		{
			versions.add(active);
		}
		return versions;
	}

	private static String contentId(Object value)
	{
		if (value == null)
		{
			return null;
		}
		return value instanceof Content ? ((Content) value).getId() : value.toString();
	}

	private static ContentTableSource source(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance) throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource(SOURCE_ID, new SourceContext(session, instance, transaction));
	}

	private static final class TermEntry
	{
		/** The term content, the one of {@link #locale}. */
		final Content content;

		final ContentLocale locale;

		final ContentVersion version;

		final String name;

		final String description;

		final String parentTermContentId;

		TermEntry(ContentLocale locale, ContentVersion version, String name, String description, String parentTermContentId)
		{
			this.content = locale.getContent();
			this.locale = locale;
			this.version = version;
			this.name = name;
			this.description = description;
			this.parentTermContentId = parentTermContentId;
		}
	}

	private static final class TermAdapter implements RepositoryCloner.Adapter<TermEntry>
	{
		private final DependencyContext context;

		TermAdapter(DependencyContext context)
		{
			this.context = context;
		}

		@Override
		public String repositoryServiceId()
		{
			return SERVICE_ID;
		}

		@Override
		public String sourceId()
		{
			return SOURCE_ID;
		}

		@Override
		public TermEntry loadOrigin(String itemId) throws Exception
		{
			TermEntry entry = load(itemId);
			return entry != null && context.getOriginRepositoryInstanceId().equals(entry.content.getServiceInstanceId()) ? entry : null;
		}

		@Override
		public TermEntry loadDestination(String itemId) throws Exception
		{
			TermEntry entry = load(itemId);
			return entry != null && context.getDestRepositoryInstanceId().equals(entry.content.getServiceInstanceId()) ? entry : null;
		}

		private TermEntry load(String contentId) throws Exception
		{
			Content content;
			try
			{
				content = ContentManagerFactory.getContentManager().getContent(contentId);
			}
			catch (PortalObjectNotFoundException e)
			{
				return null;
			}
			ContentLocale locale = content.getContentLocale(context.getSession().getLocale(), true);
			if (locale == null && !content.getContentLocales().isEmpty())
			{
				locale = content.getContentLocales().iterator().next();
			}
			if (locale == null)
			{
				return null;
			}
			ContentVersion version = locale.getPublishedVersion() == null ? locale.getActiveVersion() : locale.getPublishedVersion();
			if (version == null)
			{
				return null;
			}
			ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(content.getServiceInstanceId(), context.getTransaction());
			List<ISourceData> rows = source(context.getSession(), context.getTransaction(), instance).readData(version.getItemId());
			if (rows.isEmpty())
			{
				return null;
			}
			Object parent = rows.get(0).get("parentTermContentId");
			Object description = rows.get(0).get("description");
			Locale previousLocale = context.getSession().getLocale();
			String name;
			try
			{
				context.getSession().setLocale(locale.getLocale());
				List<String> names = CategorizationUtil.getTermPathNames(contentId);
				if (names.isEmpty())
				{
					return null;
				}
				name = names.get(names.size() - 1);
			}
			finally
			{
				context.getSession().setLocale(previousLocale);
			}
			return new TermEntry(locale, version, name, description == null ? null : description.toString(), parent == null ? null : parent.toString());
		}

		@Override
		public String itemId(TermEntry item)
		{
			return item.content.getId();
		}

		@Override
		public String kind(TermEntry item)
		{
			return KIND_TERM;
		}

		@Override
		public boolean isFolder(TermEntry item)
		{
			return true;
		}

		@Override
		public String contentId(TermEntry item)
		{
			return item.content.getId();
		}

		@Override
		public String contentLocaleId(TermEntry item)
		{
			return item.locale.getId();
		}

		@Override
		public String locale(TermEntry item)
		{
			return item.locale.getLocale().toString();
		}

		@Override
		public String parentId(TermEntry item)
		{
			return item.parentTermContentId;
		}

		@Override
		public String title(TermEntry item)
		{
			return item.name;
		}

		@Override
		public FileCopy copyFile(TermEntry item)
		{
			throw new UnsupportedOperationException("Categorization terms do not have files.");
		}

		@Override
		public TermEntry findFolder(String destinationParentId, String title) throws Exception
		{
			// @formatter:off
			String sql = "select distinct c.id from lum_Content c inner join lum_ContentLocale cl on cl.contentId = c.id "
					+ "inner join lum_ContentVersion cv on cv.contentLocaleId = cl.id "
					+ "inner join lum_CategorizationTerm t on t.termId = cv.itemId "
					+ "where c.serviceInstanceId = ? and c.sourceId = ? and "
					+ (destinationParentId == null ? "t.parentTermContentId is null"
							: "t.parentTermContentId = ?");
			// @formatter:on
			List<String> candidates = new ArrayList<>();
			try (PreparedStatement statement = context.getConnection().prepareStatement(sql))
			{
				statement.setString(1, context.getDestRepositoryInstanceId());
				statement.setString(2, SOURCE_ID);
				if (destinationParentId != null)
				{
					statement.setString(3, destinationParentId);
				}
				try (ResultSet rs = statement.executeQuery())
				{
					while (rs.next())
					{
						candidates.add(rs.getString(1));
					}
				}
			}
			Collections.sort(candidates);
			List<TermEntry> matches = new ArrayList<>();
			for (String candidate : candidates)
			{
				TermEntry entry = loadDestination(candidate);
				if (entry != null && title.equals(entry.name))
				{
					matches.add(entry);
				}
			}
			if (matches.size() > 1)
			{
				context.addWarning(
						"Multiple destination categorization terms named '" + title + "' have the same parent; the term with the smallest content id was reused.");
			}
			return matches.isEmpty() ? null : matches.get(0);
		}

		@Override
		public TermEntry createFolder(TermEntry origin, String destinationParentId, String destinationContentId) throws Exception
		{
			return write(origin, null, destinationParentId, destinationContentId);
		}

		@Override
		public TermEntry updateFolder(TermEntry origin, TermEntry destination, String destinationParentId) throws Exception
		{
			boolean isSameText = Objects.equals(origin.name, destination.name) && Objects.equals(origin.description, destination.description);
			boolean isSameParent = Objects.equals(destination.parentTermContentId, destinationParentId);
			if (isSameText && isSameParent)
			{
				return destination;
			}
			return write(origin, destination, destinationParentId, null);
		}

		@Override
		public TermEntry createItem(TermEntry origin, String destinationParentId, String destinationContentId, FileDataType.Data file)
		{
			throw new UnsupportedOperationException("Categorization terms are hierarchy nodes.");
		}

		@Override
		public TermEntry updateItem(TermEntry origin, TermEntry destination, String destinationParentId, FileDataType.Data file, boolean fileChanged)
		{
			throw new UnsupportedOperationException("Categorization terms are hierarchy nodes.");
		}

		/**
		 * Adds a destination term locale, when {@code destination} is null, or updates the given one,
		 * and publishes the version written.
		 *
		 * @param destinationContentId
		 *            on an add, the destination term that receives the new locale, or null to create
		 *            a new term.
		 */
		private TermEntry write(TermEntry origin, TermEntry destination, String parentContentId, String destinationContentId) throws Exception
		{
			boolean add = destination == null;
			SessionConfig session = context.getSession();
			Locale previousLocale = session.getLocale();
			try
			{
				session.setLocale(origin.locale.getLocale());
				ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
				ContentTableSource source = source(session, context.getTransaction(), repository);
				Map<String, Object> data = new LinkedHashMap<>();
				if (!add)
				{
					data.put("id", destination.version.getItemId());
				}
				data.put("name", origin.name);
				data.put("description", origin.description);
				data.put("parentTermContentId", parentContentId);
				if (add)
				{
					MetaDataSourceField language = source.getField(Type.Language);
					if (language != null)
					{
						data.put(language.getId(), origin.locale.getLocale());
						Locale defaultLocale = origin.content.getDefaultLocale();
						data.put(language.getId() + ".isDefaultLocale", defaultLocale == null || defaultLocale.equals(origin.locale.getLocale()));
					}
					MetaDataSourceField contentIdField = source.getField(Type.ContentId);
					if (contentIdField != null && destinationContentId != null)
					{
						data.put(contentIdField.getId(), destinationContentId);
					}
				}
				String itemId = add ? source.addData(data).getItemId() : source.updateData(data).getItemId();
				ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId);
				version.getContentLocale().setPublishedVersion(version);
				ManagerFactory.getEntityManager().flush();
				String contentId = version.getContentLocale().getContent().getId();
				return loadDestination(contentId);
			}
			finally
			{
				session.setLocale(previousLocale);
			}
		}
	}
}
