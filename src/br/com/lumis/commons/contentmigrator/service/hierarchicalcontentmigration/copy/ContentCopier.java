package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.copy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.VersionCopy;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationBlockedException;
import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentPublication;
import lumis.content.core.ContentTag;
import lumis.content.core.ContentVersion;
import lumis.content.source.field.MetaDataSourceField;
import lumis.content.source.field.MetaDataSourceField.Type;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.field.ISourceField;
import lumis.doui.source.field.ITabularSourceField;
import lumis.doui.source.field.type.FileDataType;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.util.LocaleUtil;

/**
 * Builds destination source parameters from one origin content version and applies associations and
 * tags that are stored on {@link ContentLocale} or {@link ContentVersion} rather than in the
 * content table.
 */
final class ContentCopier
{
	private static final Set<String> SIMPLE_DATA_TYPES = new HashSet<>(
			Arrays.asList("string", "text", "html", "integer", "long", "double", "boolean", "date", "dateTime", "time", "email"));

	private ContentCopier()
	{
	}

	/**
	 * Verifies that the destination channel accepts the locale of the origin item before any
	 * content or dependency writes happen.
	 */
	static void validateLocale(SessionConfig session, ITransaction transaction, ServiceInstanceConfig destinationInstance, String locale, String originItemId)
			throws Exception
	{
		Locale parsedLocale = LocaleUtil.fromString(locale);
		ChannelConfig channel = ManagerFactory.getChannelManager().get(session, destinationInstance.getChannelId(), transaction);
		Collection<Locale> acceptedLocales = channel.getAcceptableLocales();
		if (!acceptedLocales.contains(parsedLocale))
		{
			throw new MigrationBlockedException(
					MigrationBlockedException.ERROR_TYPE_LOCALE_NOT_ACCEPTED,
					originItemId,
					"Language " + locale + " is not accepted by the destination channel.");
		}
	}

	/**
	 * Returns the destination content id mapped for any locale of the given origin content, or
	 * null. Translated locales use it to share one destination {@link Content}, and parent and
	 * association references use it to point to the migrated content. It does not check that the
	 * destination content still exists.
	 */
	static String findMappedDestinationContentId(Connection connection, String originServiceInstanceId, String destinationServiceInstanceId, String originContentId)
			throws Exception
	{
		// @formatter:off
		String sql = "select destContentId "
				+ "from lum_cmig_HMigItemMap "
				+ "where kind = ? "
				+ "and originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? "
				+ "and originContentId = ? "
				+ "and destContentId is not null";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, ItemProcessor.KIND_CONTENT);
			statement.setString(2, originServiceInstanceId);
			statement.setString(3, destinationServiceInstanceId);
			statement.setString(4, originContentId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				return resultSet.next() ? resultSet.getString(1) : null;
			}
		}
	}

	/**
	 * Builds the parameter map for {@code addData} or {@code updateData} from the source row of one
	 * origin version by copying compatible fields, resolving repository dependencies and rewriting
	 * HTML links. The publication targets and principals come from the origin version itself,
	 * because the source row does not carry them.
	 */
	static Map<String, Object> buildData(MigrationScope scope, ItemDependencies dependencies, VersionCopy version, LinkFieldPolicy linkPolicy) throws Exception
	{
		Map<String, Object> data = new LinkedHashMap<>();
		putIdentityParameters(scope, version, data);
		ContentTableSource destinationSource = scope.getDestinationSource();
		for (ISourceField field : scope.getOriginSource().getFields())
		{
			if (!shouldCopyField(destinationSource, field))
			{
				continue;
			}
			boolean isHandledMetadataField = field instanceof MetaDataSourceField
					&& tryHandleSpecialMetadataField(scope, dependencies, version, data, (MetaDataSourceField) field);
			if (isHandledMetadataField)
			{
				continue;
			}
			Object value = version.getOriginRow().get(field.getId());
			String dataType = field.getDataType().getId();
			Object resolvedValue = LinkFieldPolicy.handles(field.getId(), dataType)
					? linkPolicy.resolve(scope, field.getId(), dataType, value, version.getOriginVersion().getItemId())
					: resolveFieldValue(scope, dependencies, field, value);
			data.put(field.getId(), prepareClonedValue(dependencies, field, resolvedValue));
		}
		return data;
	}

	private static void putIdentityParameters(MigrationScope scope, VersionCopy version, Map<String, Object> data)
	{
		ContentTableSource destinationSource = scope.getDestinationSource();
		String currentDestinationItemId = version.getDestinationItemId();
		if (currentDestinationItemId != null)
		{
			data.put(destinationSource.getPrimaryKeyField().getId(), currentDestinationItemId);
		}
		MetaDataSourceField languageField = destinationSource.getField(Type.Language);
		if (languageField != null)
		{
			ContentLocale originLocale = version.getOriginVersion().getContentLocale();
			Locale locale = originLocale.getLocale();
			data.put(languageField.getId(), locale);
			Locale defaultLocale = originLocale.getContent().getDefaultLocale();
			data.put(languageField.getId() + ".isDefaultLocale", defaultLocale != null && defaultLocale.equals(locale));
		}
		MetaDataSourceField contentIdField = destinationSource.getField(Type.ContentId);
		String destinationContentId = version.getDestinationContentId();
		if (contentIdField != null && destinationContentId != null)
		{
			data.put(contentIdField.getId(), destinationContentId);
		}
	}

	/**
	 * Returns the publication that the native controller writes, the first one of the version
	 * ({@code ContentMetaDataController#setContentVersionProperties}), or null.
	 */
	private static ContentPublication firstPublication(ContentVersion version)
	{
		Iterator<ContentPublication> publications = version.getPublications().iterator();
		return publications.hasNext() ? publications.next() : null;
	}

	private static boolean shouldCopyField(ContentTableSource destinationSource, ISourceField field)
	{
		if (destinationSource.getField(field.getId()) == null)
		{
			return false;
		}
		if (field.isExternalData() || field.isReadOnly())
		{
			return false;
		}
		boolean isPrimaryKey = field instanceof ITabularSourceField && ((ITabularSourceField) field).isPrimaryKey();
		return !isPrimaryKey;
	}

	private static boolean tryHandleSpecialMetadataField(
			MigrationScope scope,
			ItemDependencies dependencies,
			VersionCopy version,
			Map<String, Object> data,
			MetaDataSourceField metadata) throws Exception
	{
		Type metadataType = metadata.getType();
		Object value = version.getOriginRow().get(metadata.getId());
		if (metadataType == Type.Language || metadataType == Type.ContentId)
		{
			return true;
		}
		if (metadataType == Type.PublishToSocialNetwork)
		{
			if (MetadataPolicy.hasValue(value))
			{
				scope.addWarning("Social network publications were not copied.");
			}
			return true;
		}
		if (metadataType == Type.Slug)
		{
			String slug = value == null ? null : value.toString();
			Object resolvedSlug = slug == null ? null : MetadataPolicy.resolveUniqueSlug(scope, slug, version.getDestinationContentLocaleId());
			data.put(metadata.getId(), resolvedSlug);
			return true;
		}
		// The publication fields have getData="false": the source row never carries their values.
		if (metadataType == Type.PublishToServiceInstances)
		{
			ContentPublication originPublication = firstPublication(version.getOriginVersion());
			Collection<String> originTargets = originPublication == null ? null : originPublication.getServiceInstanceIds();
			if (MetadataPolicy.hasValue(originTargets))
			{
				data.put(
						metadata.getId(),
						MetadataPolicy.resolvePublicationTargets(originTargets, scope.getOriginServiceInstanceId(), scope.getDestinationServiceInstanceId()));
			}
			return true;
		}
		if (metadataType == Type.PublishToPrincipals)
		{
			ContentPublication originPublication = firstPublication(version.getOriginVersion());
			if (originPublication != null)
			{
				data.put(metadata.getId(), String.join(",", originPublication.getPrincipalIds()));
			}
			return true;
		}
		if (dependencies.hasHandler(metadata))
		{
			return false;
		}
		if (MetadataPolicy.isHandledSeparately(metadata))
		{
			return true;
		}
		return !MetadataPolicy.shouldCopyAsParameter(metadata);
	}

	private static Object resolveFieldValue(MigrationScope scope, ItemDependencies dependencies, ISourceField field, Object value) throws Exception
	{
		DependencyHandler handler = dependencies.findHandler(field);
		if (handler != null)
		{
			return handler.resolve(dependencies.getContext(handler), field.getId(), value);
		}
		if (field.isParentId())
		{
			return resolveParentContentId(scope, value);
		}
		if ("file".equals(field.getDataType().getId()))
		{
			return dependencies.getFileCloner().copy(scope.getSession(), scope.getTransaction(), extractFileId(value));
		}
		boolean isUnhandledTableField = !(field instanceof MetaDataSourceField) && value != null;
		if (isUnhandledTableField && !isSimpleField(field))
		{
			throw new IllegalStateException("No migration handler for field " + field.getId() + " (" + field.getDataType().getId() + ").");
		}
		return value;
	}

	private static Object resolveParentContentId(MigrationScope scope, Object value) throws Exception
	{
		String parentId = value == null ? null : value.toString();
		String mappedParentId = mappedParent(scope, parentId);
		if (mappedParentId != null)
		{
			return mappedParentId;
		}
		String referenceParentId = scope.getReferenceParentContentId();
		String mappedReferenceParentId = mappedParent(scope, referenceParentId);
		if (mappedReferenceParentId != null)
		{
			scope.addWarning("Parent content " + parentId + " had not been migrated; used reference parent " + referenceParentId + ".");
			return mappedReferenceParentId;
		}
		if ((parentId != null && !parentId.isEmpty()) || (referenceParentId != null && !referenceParentId.isEmpty()))
		{
			scope.addWarning("Parent content " + parentId + " and reference parent " + referenceParentId + " had not been migrated; parent was left empty.");
		}
		return null;
	}

	private static String mappedParent(MigrationScope scope, String parentId) throws Exception
	{
		if (parentId == null || parentId.isEmpty())
		{
			return null;
		}
		return findMappedDestinationContentId(scope.getConnection(), scope.getOriginServiceInstanceId(), scope.getDestinationServiceInstanceId(), parentId);
	}

	private static String extractFileId(Object value)
	{
		if (value == null)
		{
			return null;
		}
		if (value instanceof FileDataType.Data)
		{
			return ((FileDataType.Data) value).getFileId();
		}
		return value.toString();
	}

	private static boolean isSimpleField(ISourceField field) throws Exception
	{
		if (field instanceof MetaDataSourceField)
		{
			return false;
		}
		if (field.isExternalData() || field.isReadOnly())
		{
			return false;
		}
		return SIMPLE_DATA_TYPES.contains(field.getDataType().getId());
	}

	private static Object prepareClonedValue(ItemDependencies dependencies, ISourceField field, Object value) throws Exception
	{
		if (value == null)
		{
			return null;
		}
		Object prepared = field.prepareValueForDataCloning(value);
		if (prepared instanceof CharSequence)
		{
			prepared = prepared.toString();
		}
		if ("html".equals(field.getDataType().getId()))
		{
			for (DependencyHandler htmlHandler : dependencies.getHandlers())
			{
				prepared = htmlHandler.rewriteHtml(dependencies.getContext(htmlHandler), prepared.toString());
			}
		}
		return prepared;
	}

	/**
	 * Copies standard content associations and categorization term associations onto a written
	 * destination version.
	 */
	static void applyAfterWrite(MigrationScope scope, ItemDependencies dependencies, ContentVersion originVersion, ContentVersion destinationVersion)
			throws Exception
	{
		copyStandardAssociations(scope, originVersion, destinationVersion);
		copyCategorizationAssociations(scope, dependencies, originVersion, destinationVersion);
	}

	private static void copyStandardAssociations(MigrationScope scope, ContentVersion originVersion, ContentVersion destinationVersion) throws Exception
	{
		for (ContentAssociation existing : new ArrayList<>(destinationVersion.getContentAssociations()))
		{
			if (!"categorizationTerm".equals(existing.getType()))
			{
				existing.delete();
			}
		}

		for (ContentAssociation association : originVersion.getContentAssociations())
		{
			if ("categorizationTerm".equals(association.getType()))
			{
				continue;
			}
			Content associated = resolveStandardAssociationTarget(scope, association.getAssociatedContent());
			if (associated == null)
			{
				continue;
			}
			ContentAssociation clone = destinationVersion.addContentAssociation();
			clone.setType(association.getType());
			clone.setAssociatedContent(associated);
		}
	}

	private static Content resolveStandardAssociationTarget(MigrationScope scope, Content associated) throws Exception
	{
		if (associated == null)
		{
			return null;
		}
		String originServiceInstanceId = scope.getOriginServiceInstanceId();
		if (!originServiceInstanceId.equals(associated.getServiceInstanceId()))
		{
			return associated;
		}
		String mappedId = findMappedDestinationContentId(
				scope.getConnection(),
				originServiceInstanceId,
				scope.getDestinationServiceInstanceId(),
				associated.getId());
		if (mappedId == null)
		{
			scope.addWarning("Association to origin content " + associated.getId() + " had not been migrated and was omitted.");
			return null;
		}
		try
		{
			return ContentManagerFactory.getContentManager().getContent(mappedId);
		}
		catch (PortalObjectNotFoundException exception)
		{
			scope.addWarning("Association destination content " + mappedId + " no longer exists and was omitted.");
			return null;
		}
	}

	private static void copyCategorizationAssociations(
			MigrationScope scope,
			ItemDependencies dependencies,
			ContentVersion originVersion,
			ContentVersion destinationVersion) throws Exception
	{
		for (ContentAssociation existing : new ArrayList<>(destinationVersion.getContentAssociations()))
		{
			if ("categorizationTerm".equals(existing.getType()))
			{
				existing.delete();
			}
		}

		DependencyHandler handler = dependencies.getHandler(ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY);
		if (handler == null)
		{
			return;
		}
		DependencyContext context = dependencies.getContext(handler);
		for (ContentAssociation association : originVersion.getContentAssociations())
		{
			if (!"categorizationTerm".equals(association.getType()))
			{
				continue;
			}
			String originTermId = association.getAssociatedContent() == null ? null : association.getAssociatedContent().getId();
			String termId = (String) handler.resolve(context, association.getType(), originTermId);
			if (termId == null)
			{
				continue;
			}
			Content term;
			try
			{
				term = ContentManagerFactory.getContentManager().getContent(termId);
			}
			catch (PortalObjectNotFoundException exception)
			{
				scope.addWarning("Categorization term " + termId + " no longer exists and was omitted.");
				continue;
			}
			ContentAssociation clone = destinationVersion.addContentAssociation();
			clone.setType("categorizationTerm");
			clone.setAssociatedContent(term);
		}
	}

	/**
	 * Replaces the tags on the destination locale with the origin locale's tags when the source
	 * exposes a tag metadata field.
	 */
	static void applyTags(ContentLocale originLocale, ContentLocale destinationLocale, boolean shouldCopyTags)
	{
		if (shouldCopyTags)
		{
			copyTags(originLocale, destinationLocale);
		}
	}

	private static void copyTags(ContentLocale originLocale, ContentLocale destinationLocale)
	{
		if (destinationLocale.getContent() == null)
		{
			return;
		}
		for (ContentTag tag : new ArrayList<>(destinationLocale.getTags()))
		{
			destinationLocale.removeTag(tag);
		}
		for (ContentTag tag : originLocale.getTags())
		{
			destinationLocale.addTag(tag.getName());
		}
	}
}
