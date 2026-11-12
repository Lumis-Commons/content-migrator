package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.copy;

import java.lang.reflect.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope;
import lumis.content.core.ContentUniqueSlug;
import lumis.content.source.field.MetaDataSourceField;

/**
 * Copy policy for the metadata exposed by a content source. Metadata tied to workflow, authorship,
 * integrations or generated destination ids is handled elsewhere or deliberately omitted so
 * destination items do not inherit origin-only references.
 */
final class MetadataPolicy
{
	/** Maximum length of a slug, enforced by {@code ContentVersion#setSlug}. */
	private static final int MAX_SLUG_LENGTH = 100;

	private MetadataPolicy()
	{
	}

	/**
	 * Returns whether a raw metadata field value contains at least one non-empty string entry.
	 */
	static boolean hasValue(Object value)
	{
		return value != null && !extractStringValues(value).isEmpty();
	}

	private static List<String> extractStringValues(Object value)
	{
		List<String> result = new ArrayList<>();
		if (value == null)
		{
			return result;
		}
		if (value instanceof Collection<?>)
		{
			for (Object item : (Collection<?>) value)
			{
				if (item != null)
				{
					result.add(item.toString());
				}
			}
			return result;
		}
		if (value.getClass().isArray())
		{
			int length = Array.getLength(value);
			for (int index = 0; index < length; index++)
			{
				Object item = Array.get(value, index);
				if (item != null)
				{
					result.add(item.toString());
				}
			}
			return result;
		}
		String text = value.toString();
		if (text.isEmpty())
		{
			return result;
		}
		for (String item : text.split(","))
		{
			if (!item.isEmpty())
			{
				result.add(item);
			}
		}
		return result;
	}

	/**
	 * Chooses an available destination slug. The native ContentLocale rejects duplicates, so the
	 * migration appends a numeric suffix before calling addData/updateData and records a warning. A
	 * slug cut to fit the suffix loses a trailing slash, which the native format does not accept
	 * before the suffix.
	 */
	static String resolveUniqueSlug(MigrationScope scope, String requestedSlug, String currentDestinationContentLocaleId) throws SQLException
	{
		if (requestedSlug == null || requestedSlug.trim().isEmpty())
		{
			return requestedSlug;
		}
		if (!isSlugInUse(scope, requestedSlug, currentDestinationContentLocaleId))
		{
			return requestedSlug;
		}

		for (int suffix = 1; suffix < 10000; suffix++)
		{
			String tail = "-" + suffix;
			String base = requestedSlug;
			if (base.length() + tail.length() > MAX_SLUG_LENGTH)
			{
				base = removeTrailingSlash(base.substring(0, MAX_SLUG_LENGTH - tail.length()));
			}
			String candidate = base + tail;
			if (!isSlugInUse(scope, candidate, currentDestinationContentLocaleId))
			{
				scope.addWarning("Slug '" + requestedSlug + "' already exists in the destination; copied as '" + candidate + "'.");
				return candidate;
			}
		}
		throw new IllegalStateException("Could not find an available destination slug for '" + requestedSlug + "'.");
	}

	private static boolean isSlugInUse(MigrationScope scope, String slug, String currentContentLocaleId) throws SQLException
	{
		String uniqueSlug = new ContentUniqueSlug(scope.getDestinationServiceInstanceId(), scope.getOriginSource().getId(), slug).getUniqueSlug();
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_ContentLocale "
				+ "where uniqueSlug = ?"
				+ (currentContentLocaleId == null ? "" : " and id <> ?");
		// @formatter:on
		try (PreparedStatement statement = scope.getConnection().prepareStatement(sql))
		{
			statement.setString(1, uniqueSlug);
			if (currentContentLocaleId != null)
			{
				statement.setString(2, currentContentLocaleId);
			}
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1) > 0;
			}
		}
	}

	private static String removeTrailingSlash(String slug)
	{
		return slug.endsWith("/") ? slug.substring(0, slug.length() - 1) : slug;
	}

	/**
	 * Returns the destination instance when the origin instance was a publication target, or an
	 * empty value, published in no instance, otherwise. A migrated item must not carry publications
	 * to any of the origin's other instances.
	 */
	static String resolvePublicationTargets(Object value, String originServiceInstanceId, String destinationServiceInstanceId)
	{
		List<String> instanceIds = extractStringValues(value);
		return instanceIds.contains(originServiceInstanceId) ? destinationServiceInstanceId : "";
	}

	/**
	 * Returns whether the field is handled by a dedicated copy step or dependency handler, or
	 * deliberately omitted, rather than copied as a plain parameter.
	 */
	static boolean isHandledSeparately(MetaDataSourceField field)
	{
		switch (field.getType())
		{
			case Language :
			case ContentId :
			case Slug :
			case Tag :
			case Association :
			case Categorization :
			case SocialImage :
			case PublishToSocialNetwork :
				return true;
			default :
				return false;
		}
	}

	/**
	 * Returns whether a metadata field is copied as a source parameter. The caller takes the
	 * publication targets and principals from the origin version and handles language, content id,
	 * slug, tags, associations and dependency metadata separately.
	 * Friendly URL regeneration and redirect flags are transient destination commands and are omitted.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	static boolean shouldCopyAsParameter(MetaDataSourceField field)
	{
		return shouldCopyAsParameter(field.getType());
	}

	/**
	 * Applies the parameter policy to a metadata type, including transient SEO commands.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	static boolean shouldCopyAsParameter(MetaDataSourceField.Type type)
	{
		switch (type)
		{
			case RecalculateFriendlyURLs :
			case AddFriendlyURLsRedirects :
				return false;
			case PublishToServiceInstances :
			case PublishToPrincipals :
			case PublishStartDate :
			case PublishEndDate :
			case Highlight :
			case HighlightEndDate :
			case PageSimpleTitle :
			case PageTitlePattern :
			case UrlTitle :
			case UrlPath :
			case SeoDescription :
			case SocialTitle :
			case SocialDescription :
			case PrimaryContent :
			case RobotsConfiguration :
				return true;
			default :
				return false;
		}
	}
}
