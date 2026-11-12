package br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.media;

import java.lang.reflect.Array;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.FileCopy;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationItem;
import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.MigrationBlockedException;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentPublication;
import lumis.content.core.ContentVersion;
import lumis.content.source.field.MetaDataSourceField;
import lumis.content.source.field.MetaDataSourceField.Type;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.ISourceData;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.doui.source.field.ISourceField;
import lumis.doui.source.field.type.FileDataType;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.PortalException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.filesystem.PortalBaseFolder;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.service.media.IMediaManager;
import lumis.service.media.Media;
import lumis.service.media.MediaManagerFactory;
import lumis.service.media.MediaUtil;
import lumis.service.media.field.MediaDataType;

/** Migrates references to items in a media repository, including media links embedded in HTML. */
public class MediaDependencyHandler implements DependencyHandler
{
	private static final String SERVICE_ID = "lumis.service.media";

	private static final String KIND_MEDIA = "MEDIA";

	/** The source field of the stored file of a media item; the data carries it only when the file is written. */
	private static final String MEDIA_FILE_FIELD = "mediaFile";

	private static final Pattern DOWNLOAD_URL = Pattern
			.compile("(?i)(https?://[^/'\"<>\\s]+)?([^'\"<>\\s]*?lumis/portal/file/fileDownload\\.jsp\\?[^'\"<>\\s#]*?\\bfileId=)([a-f0-9]{32})([^'\"<>\\s#]*)");

	private static final Pattern PUBLIC_FILE_URL = Pattern.compile("(?i)(?:https?://[^'\"<>\\s]+)?[^'\"<>\\s]*?/data/files/([^?&#'\"<>\\s]+)");

	private static final Pattern PUBLIC_TRANSFORMED_URL = Pattern.compile("(?i)(?:https?://[^'\"<>\\s]+)?[^'\"<>\\s]*?/data/((?!files/)[^?&#'\"<>\\s]+)");

	private static final Pattern HTML_FILE_ID = Pattern.compile("(?i)lumis/portal/file/fileDownload\\.jsp\\?[^'\"<>\\s#]*?\\bfileId=([a-f0-9]{32})");

	@Override
	public String getDependencyType()
	{
		return ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY;
	}

	@Override
	public boolean handles(ISourceField field) throws Exception
	{
		if (field instanceof MetaDataSourceField)
		{
			return ((MetaDataSourceField) field).getType() == Type.SocialImage;
		}
		return "media".equals(field.getDataType().getId());
	}

	@Override
	public void precheck(DependencyContext context, MigrationItem item) throws Exception
	{
		if (!clonesReferences(context))
		{
			return;
		}

		ContentLocale hostLocale = ContentManagerFactory.getContentManager().getContentLocale(item.getContentLocaleId());
		Set<String> mediaIds = new LinkedHashSet<>();
		for (ContentVersion version : versionsToCopy(hostLocale))
		{
			ContentTableSource source = source(context, context.getOriginServiceInstanceId(), item.getSourceId());
			List<ISourceData> rows = source.readData(version.getItemId());
			if (rows.isEmpty())
			{
				continue;
			}
			ISourceData row = rows.get(0);
			for (ISourceField field : source.getFields())
			{
				Object value = row.get(field.getId());
				if (handles(field))
				{
					addReference(value, mediaIds);
				}
				else if ("html".equals(field.getDataType().getId()) && value != null)
				{
					collectHtmlMediaIds(value.toString(), mediaIds);
				}
			}
			if (version.getSocialImageMediaId() != null)
			{
				mediaIds.add(version.getSocialImageMediaId());
			}
		}

		ServiceInstanceConfig destRepository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
		ChannelConfig channel = ManagerFactory.getChannelManager().get(context.getSession(), destRepository.getChannelId(), context.getTransaction());
		Collection<Locale> acceptedLocales = channel.getAcceptableLocales();
		for (String mediaId : mediaIds)
		{
			MediaEntry entry = load(context.getSession(), context.getTransaction(), mediaId);
			if (entry == null)
			{
				continue;
			}
			if (!context.getOriginRepositoryInstanceId().equals(entry.serviceInstanceId))
			{
				context.addWarning("Media " + mediaId + " belongs to another repository and was left unchanged.");
				continue;
			}
			if (!acceptedLocales.contains(entry.locale.getLocale()))
			{
				throw new MigrationBlockedException(
						MigrationBlockedException.ERROR_TYPE_LOCALE_NOT_ACCEPTED,
						hostLocale.getActiveVersion() == null ? item.getContentLocaleId() : hostLocale.getActiveVersion().getItemId(),
						"Media " + mediaId + " uses language " + entry.locale.getLocale() + ", which is not accepted by the destination media repository.");
			}
		}
	}

	@Override
	public Object resolve(DependencyContext context, String fieldOrAssociationId, Object value) throws Exception
	{
		if (value == null || !clonesReferences(context))
		{
			return value;
		}
		String mediaId = mediaId(value);
		if (mediaId == null || mediaId.isEmpty())
		{
			return value;
		}
		MediaEntry origin = load(context.getSession(), context.getTransaction(), mediaId);
		if (origin == null)
		{
			context.addWarning("Media " + mediaId + " referenced by " + fieldOrAssociationId + " no longer exists and was left unchanged.");
			return value;
		}
		if (!context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
		{
			context.addWarning("Media " + mediaId + " belongs to another repository and was left unchanged.");
			return value;
		}
		String destinationMediaId = new RepositoryCloner<>(context, new MediaAdapter(context)).resolve(mediaId);
		if (value instanceof MediaDataType.Data)
		{
			((MediaDataType.Data) value).setMediaId(destinationMediaId);
			return value;
		}
		return destinationMediaId;
	}

	@Override
	public String rewriteHtml(DependencyContext context, String html) throws Exception
	{
		if (html == null || !clonesReferences(context))
		{
			return html;
		}
		return HtmlDataTypeUtil.processAreasHtml(html, area ->
		{
			String value = area.getHtmlContent();
			if (value == null)
			{
				return null;
			}
			try
			{
				value = rewriteDownloadUrls(context, value);
				value = rewritePublicFiles(context, value);
				value = rewriteTransformedFiles(context, value);
				return value;
			}
			catch (Exception e)
			{
				throw new PortalException("Could not rewrite media URLs in HTML.", e);
			}
		});
	}

	private String rewriteDownloadUrls(DependencyContext context, String html) throws Exception
	{
		Matcher matcher = DOWNLOAD_URL.matcher(html);
		StringBuffer result = new StringBuffer(html.length());
		while (matcher.find())
		{
			String destinationFileId = destinationFileId(context, matcher.group(3));
			String hostPrefix = matcher.group(1) == null ? "" : matcher.group(1);
			String replacement = hostPrefix + matcher.group(2) + destinationFileId + matcher.group(4);
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private String rewritePublicFiles(DependencyContext context, String html) throws Exception
	{
		Matcher matcher = PUBLIC_FILE_URL.matcher(html);
		StringBuffer result = new StringBuffer(html.length());
		while (matcher.find())
		{
			String path = decodePath(context, matcher);
			FileConfig file = path == null ? null : fileByFullPath(context, "files/" + path);
			String replacement = file == null ? matcher.group() : rewriteFile(context, file, matcher.group());
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private String rewriteTransformedFiles(DependencyContext context, String html) throws Exception
	{
		Matcher matcher = PUBLIC_TRANSFORMED_URL.matcher(html);
		StringBuffer result = new StringBuffer(html.length());
		while (matcher.find())
		{
			String fullPath = decodePath(context, matcher);
			TransformedRef transformed = fullPath == null ? null : transformedFile(context, fullPath);
			String replacement = matcher.group();
			if (transformed != null)
			{
				String mediaId = MediaUtil.getMediaIdFromFileId(transformed.fileId);
				MediaEntry origin = mediaId == null ? null : load(context.getSession(), context.getTransaction(), mediaId);
				if (origin != null && context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
				{
					String destMediaId = new RepositoryCloner<>(context, new MediaAdapter(context)).resolve(mediaId);
					MediaEntry destination = load(context.getSession(), context.getTransaction(), destMediaId);
					if (!hasTransformation(context, transformed.transformationName))
					{
						context.addWarning(
								"Media transformation " + transformed.transformationName
										+ " is not configured in the destination repository; the transform link may not work.");
					}
					replacement = "lumis/portal/file/fileDownload.jsp?fileId=" + requireFile(destination).getId() + "&transform=" + transformed.transformationName;
				}
				else if (origin != null)
				{
					context.addWarning("Media " + mediaId + " belongs to another repository and was left unchanged.");
				}
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private String rewriteFile(DependencyContext context, FileConfig file, String originalUrl) throws Exception
	{
		String mediaId = MediaUtil.getMediaIdFromFileId(file.getId());
		if (mediaId == null)
		{
			return originalUrl;
		}
		MediaEntry origin = load(context.getSession(), context.getTransaction(), mediaId);
		if (origin == null || !context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
		{
			if (origin != null)
			{
				context.addWarning("Media " + mediaId + " belongs to another repository and was left unchanged.");
			}
			return originalUrl;
		}
		String destMediaId = new RepositoryCloner<>(context, new MediaAdapter(context)).resolve(mediaId);
		MediaEntry destination = load(context.getSession(), context.getTransaction(), destMediaId);
		return ManagerFactory.getFileManager().getFileHref(context.getSession(), requireFile(destination).getId(), context.getTransaction()).toString();
	}

	private String destinationFileId(DependencyContext context, String fileId) throws Exception
	{
		String mediaId = MediaUtil.getMediaIdFromFileId(fileId);
		if (mediaId == null)
		{
			return fileId;
		}
		MediaEntry origin = load(context.getSession(), context.getTransaction(), mediaId);
		if (origin == null || !context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
		{
			if (origin != null)
			{
				context.addWarning("Media " + mediaId + " belongs to another repository and was left unchanged.");
			}
			return fileId;
		}
		String destMediaId = new RepositoryCloner<>(context, new MediaAdapter(context)).resolve(mediaId);
		MediaEntry destination = load(context.getSession(), context.getTransaction(), destMediaId);
		return requireFile(destination).getId();
	}

	private TransformedRef transformedFile(DependencyContext context, String fullPath) throws Exception
	{
		// @formatter:off
		String sql = "select tf.fileConfigId, ft.name from lum_FTTransformedFile tf "
				+ "inner join lum_FTTransformationFile ft on ft.id = tf.fileTnsfId where tf.fullPath = ?";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(sql))
		{
			statement.setString(1, fullPath);
			try (ResultSet rs = statement.executeQuery())
			{
				return rs.next() ? new TransformedRef(rs.getString(1), rs.getString(2)) : null;
			}
		}
	}

	private FileConfig fileByFullPath(DependencyContext context, String fullPath) throws Exception
	{
		try (PreparedStatement statement = context.getConnection().prepareStatement("select fileId from lum_File where fullPath = ?"))
		{
			statement.setString(1, fullPath);
			try (ResultSet rs = statement.executeQuery())
			{
				return rs.next() ? ManagerFactory.getFileManager().get(context.getSession(), rs.getString(1), context.getTransaction()) : null;
			}
		}
	}

	private boolean hasTransformation(DependencyContext context, String name) throws Exception
	{
		try (PreparedStatement statement = context.getConnection()
				.prepareStatement("select count(*) from lum_FTTransformationFile where serviceInstanceId = ? and name = ?"))
		{
			statement.setString(1, context.getDestRepositoryInstanceId());
			statement.setString(2, name);
			try (ResultSet rs = statement.executeQuery())
			{
				rs.next();
				return rs.getInt(1) > 0;
			}
		}
	}

	@Override
	public boolean isCloneReferenced(DependencyContext context, String destContentLocaleId) throws Exception
	{
		// The select reads from lum_ContentLocale because Oracle does not accept a select without from.
		String itemIds = "select v.itemId from lum_ContentVersion v where v.contentLocaleId = cl.id";
		// @formatter:off
		String sql = "select count(*) from lum_ContentLocale cl where cl.id = ? "
				+ "and (exists (select 1 from lum_MediaUsage u where u.mediaId in (" + itemIds + ")) "
				+ "or exists (select 1 from lum_ContentVersion cv where cv.socialImage in (" + itemIds + ")) "
				+ "or exists (select 1 from lum_Media child where child.parentFolder in (" + itemIds + ")))";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(sql))
		{
			statement.setString(1, destContentLocaleId);
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
		String itemId = null;
		boolean created = false;
		// @formatter:off
		String select = "select destItemId, destCreated from lum_cmig_MigItemMap where destServiceInstanceId = ? "
				+ "and destContentLocaleId = ? and kind in (?, ?)";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(select))
		{
			statement.setString(1, context.getDestRepositoryInstanceId());
			statement.setString(2, destContentLocaleId);
			statement.setString(3, RepositoryCloner.KIND_FOLDER);
			statement.setString(4, KIND_MEDIA);
			try (ResultSet rs = statement.executeQuery())
			{
				if (rs.next())
				{
					itemId = rs.getString(1);
					created = rs.getBoolean(2);
				}
			}
		}
		if (itemId != null && created)
		{
			ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
			ContentTableSource source = source(context.getSession(), context.getTransaction(), repository, RepositoryCloner.SOURCE_DEFAULT);
			source.deleteDataByItemIds(Collections.singleton(itemId));
		}
		try (PreparedStatement statement = context.getConnection()
				.prepareStatement("delete from lum_cmig_MigItemMap where destServiceInstanceId = ? and destContentLocaleId = ? and kind in (?, ?)"))
		{
			statement.setString(1, context.getDestRepositoryInstanceId());
			statement.setString(2, destContentLocaleId);
			statement.setString(3, RepositoryCloner.KIND_FOLDER);
			statement.setString(4, KIND_MEDIA);
			statement.executeUpdate();
		}
	}

	/**
	 * Returns whether references are cloned: the origin and the destination depend on different
	 * repositories of this type. When the repository of either side is unknown, or both use the
	 * same one, references are kept as they are.
	 */
	private static boolean clonesReferences(DependencyContext context)
	{
		boolean repositoriesUnknown = context.getOriginRepositoryInstanceId() == null || context.getDestRepositoryInstanceId() == null;
		return !repositoriesUnknown && !context.isSameRepository();
	}

	private static List<ContentVersion> versionsToCopy(ContentLocale locale)
	{
		List<ContentVersion> result = new ArrayList<>();
		ContentVersion published = locale.getPublishedVersion();
		ContentVersion active = locale.getActiveVersion();
		if (published != null)
		{
			result.add(published);
		}
		if (active != null && (published == null || !active.getItemId().equals(published.getItemId())))
		{
			result.add(active);
		}
		return result;
	}

	private static void addReference(Object value, Set<String> result)
	{
		if (value == null)
		{
			return;
		}
		if (value instanceof Collection<?>)
		{
			for (Object item : (Collection<?>) value)
			{
				addReference(item, result);
			}
		}
		else if (value.getClass().isArray())
		{
			for (int i = 0; i < Array.getLength(value); i++)
			{
				addReference(Array.get(value, i), result);
			}
		}
		else
		{
			String mediaId = mediaId(value);
			if (mediaId != null && !mediaId.isEmpty())
			{
				result.add(mediaId);
			}
		}
	}

	private static String mediaId(Object value)
	{
		return value instanceof MediaDataType.Data ? ((MediaDataType.Data) value).getMediaId() : value.toString();
	}

	private static void collectHtmlMediaIds(String html, Set<String> mediaIds) throws Exception
	{
		HtmlDataTypeUtil.processAreasHtml(html, area ->
		{
			String areaHtml = area.getHtmlContent();
			if (areaHtml == null)
			{
				return null;
			}
			Matcher matcher = HTML_FILE_ID.matcher(areaHtml);
			while (matcher.find())
			{
				String mediaId = MediaUtil.getMediaIdFromFileId(matcher.group(1));
				if (mediaId != null)
				{
					mediaIds.add(mediaId);
				}
			}
			return areaHtml;
		});
	}

	private static ContentTableSource source(DependencyContext context, String serviceInstanceId, String sourceId) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(serviceInstanceId, context.getTransaction());
		return source(context.getSession(), context.getTransaction(), instance, sourceId);
	}

	private static ContentTableSource source(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance, String sourceId) throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource(sourceId, new SourceContext(session, instance, transaction));
	}

	private static MediaEntry load(SessionConfig session, ITransaction transaction, String mediaId) throws Exception
	{
		try
		{
			Media media = MediaManagerFactory.getMediaManager().get(mediaId);
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(mediaId);
			ContentLocale locale = version.getContentLocale();
			String serviceInstanceId = locale.getContent().getServiceInstanceId();
			FileConfig file = media.getType() == Media.TYPE_MEDIA ? ManagerFactory.getFileManager().get(session, media.getMediaFile(), transaction) : null;
			return new MediaEntry(media, version, locale, serviceInstanceId, file);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	/**
	 * Returns the decoded path (group 1) of a public link matched by {@link #PUBLIC_FILE_URL} or
	 * {@link #PUBLIC_TRANSFORMED_URL}, or null, with a warning, when the link has a malformed escape
	 * and is left unchanged.
	 */
	private static String decodePath(DependencyContext context, Matcher publicLink) throws Exception
	{
		try
		{
			return URLDecoder.decode(publicLink.group(1).replace("+", "%2B"), StandardCharsets.UTF_8.name());
		}
		catch (IllegalArgumentException e)
		{
			context.addWarning("The public file link " + publicLink.group() + " has a malformed escape and was left unchanged.");
			return null;
		}
	}

	private static FileConfig requireFile(MediaEntry entry)
	{
		if (entry == null || entry.file == null)
		{
			throw new IllegalStateException("Media has no stored file.");
		}
		return entry.file;
	}

	private static String principals(DependencyContext context, MediaEntry origin, String destParentId) throws Exception
	{
		Collection<String> principals;
		if (origin.media.getInheritPublishTo())
		{
			principals = MediaManagerFactory.getMediaManager().getMediaPublishToPrincipals(destParentId);
		}
		else
		{
			Set<String> all = new LinkedHashSet<>();
			for (ContentPublication publication : origin.version.getPublications())
			{
				all.addAll(publication.getPrincipalIds());
			}
			principals = all;
		}
		return String.join(",", principals);
	}

	private static IllegalStateException missingFile(MediaEntry item)
	{
		return new IllegalStateException(
				"Stored file for media " + item.media.getId() + " is missing: " + (item.file == null ? "<no lum_File>" : item.file.getFullPath()));
	}

	private static MediaEntry loadDestination(DependencyContext context, String mediaId) throws Exception
	{
		MediaEntry entry = load(context.getSession(), context.getTransaction(), mediaId);
		return entry != null && context.getDestRepositoryInstanceId().equals(entry.serviceInstanceId) ? entry : null;
	}

	private static final class MediaEntry
	{
		final Media media;

		final ContentVersion version;

		final ContentLocale locale;

		final String serviceInstanceId;

		final FileConfig file;

		MediaEntry(Media media, ContentVersion version, ContentLocale locale, String serviceInstanceId, FileConfig file)
		{
			this.media = media;
			this.version = version;
			this.locale = locale;
			this.serviceInstanceId = serviceInstanceId;
			this.file = file;
		}
	}

	private static final class TransformedRef
	{
		final String fileId;

		final String transformationName;

		TransformedRef(String fileId, String transformationName)
		{
			this.fileId = fileId;
			this.transformationName = transformationName;
		}
	}

	private static final class MediaAdapter implements RepositoryCloner.Adapter<MediaEntry>
	{
		private final DependencyContext context;

		private final IMediaManager mediaManager = MediaManagerFactory.getMediaManager();

		MediaAdapter(DependencyContext context)
		{
			this.context = context;
		}

		@Override
		public String repositoryServiceId()
		{
			return SERVICE_ID;
		}

		@Override
		public MediaEntry loadOrigin(String itemId) throws Exception
		{
			return load(context.getSession(), context.getTransaction(), itemId);
		}

		@Override
		public MediaEntry loadDestination(String itemId) throws Exception
		{
			return MediaDependencyHandler.loadDestination(context, itemId);
		}

		@Override
		public String itemId(MediaEntry item)
		{
			return item.media.getId();
		}

		@Override
		public String kind(MediaEntry item)
		{
			return item.media.getType() == Media.TYPE_FOLDER ? RepositoryCloner.KIND_FOLDER : KIND_MEDIA;
		}

		@Override
		public boolean isFolder(MediaEntry item)
		{
			return item.media.getType() == Media.TYPE_FOLDER;
		}

		@Override
		public String contentId(MediaEntry item)
		{
			return item.locale.getContent().getId();
		}

		@Override
		public String contentLocaleId(MediaEntry item)
		{
			return item.locale.getId();
		}

		@Override
		public String locale(MediaEntry item)
		{
			return item.locale.getLocale().toString();
		}

		@Override
		public String parentId(MediaEntry item)
		{
			return item.media.getParentFolder();
		}

		@Override
		public String title(MediaEntry item)
		{
			return item.media.getTitle();
		}

		@Override
		public FileCopy copyFile(MediaEntry item) throws Exception
		{
			if (item.file == null || !PortalBaseFolder.DATA.toPortalFilePath(item.file.getFullPath()).toFile().exists())
			{
				throw missingFile(item);
			}
			try
			{
				return context.getFileCloner().copyWithFingerprint(context.getSession(), context.getTransaction(), item.media.getMediaFile());
			}
			catch (Exception e)
			{
				throw new IllegalStateException("Could not copy stored file for media " + item.media.getId() + " at " + item.file.getFullPath() + ".", e);
			}
		}

		@Override
		public MediaEntry findFolder(String destinationParentId, String title) throws Exception
		{
			// MediaManager#getFolderIdsByParentAndName returns the ids in no defined order.
			List<String> sortedIds = new ArrayList<>(mediaManager.getFolderIdsByParentAndName(context.getDestRepositoryInstanceId(), destinationParentId, title));
			Collections.sort(sortedIds);
			List<MediaEntry> matches = new ArrayList<>();
			for (String id : sortedIds)
			{
				MediaEntry entry = MediaDependencyHandler.loadDestination(context, id);
				if (entry != null)
				{
					matches.add(entry);
				}
			}
			if (matches.size() > 1)
			{
				context.addWarning("Multiple destination media folders named '" + title + "' exist under the same parent; the folder with the smallest id was reused.");
			}
			return matches.isEmpty() ? null : matches.get(0);
		}

		@Override
		public MediaEntry createFolder(MediaEntry origin, String destinationParentId, String destinationContentId) throws Exception
		{
			return save(origin, null, buildMediaData(origin, null, destinationParentId, null, origin.media.getTitle()), destinationContentId);
		}

		@Override
		public MediaEntry updateFolder(MediaEntry origin, MediaEntry destination, String destinationParentId) throws Exception
		{
			MediaEntry collision = findFolder(destinationParentId, origin.media.getTitle());
			boolean canRename = collision == null || collision.media.getId().equals(destination.media.getId());
			if (!canRename)
			{
				context.addWarning(
						"Folder " + origin.media.getId() + " was not renamed because a folder named '" + origin.media.getTitle()
								+ "' already exists in the destination.");
			}
			String titleToWrite = canRename ? origin.media.getTitle() : destination.media.getTitle();
			return save(origin, destination, buildMediaData(origin, destination, destinationParentId, null, titleToWrite), null);
		}

		@Override
		public MediaEntry createItem(MediaEntry origin, String destinationParentId, String destinationContentId, FileDataType.Data file) throws Exception
		{
			return save(origin, null, buildMediaData(origin, null, destinationParentId, file, origin.media.getTitle()), destinationContentId);
		}

		@Override
		public MediaEntry updateItem(MediaEntry origin, MediaEntry destination, String destinationParentId, FileDataType.Data file, boolean fileChanged) throws Exception
		{
			FileDataType.Data fileToWrite = fileChanged ? file : null;
			return save(origin, destination, buildMediaData(origin, destination, destinationParentId, fileToWrite, origin.media.getTitle()), null);
		}

		/**
		 * Writes the media data in the destination repository, adding a new item when there is no
		 * destination yet, with the session in the locale of the origin item.
		 *
		 * @param destinationContentId
		 *            the destination content of a new item, or null.
		 */
		private MediaEntry save(MediaEntry origin, MediaEntry destination, Map<String, Object> data, String destinationContentId) throws Exception
		{
			boolean isAdd = destination == null;
			SessionConfig session = context.getSession();
			Locale previousLocale = session.getLocale();
			try
			{
				session.setLocale(origin.locale.getLocale());
				ContentTableSource source = source(
						context.getSession(),
						context.getTransaction(),
						ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction()),
						RepositoryCloner.SOURCE_DEFAULT);
				if (isAdd)
				{
					putContentIdentity(source, data, origin, destinationContentId);
				}
				String itemId = isAdd ? source.addData(data).getItemId() : source.updateData(data).getItemId();

				Media target = mediaManager.get(itemId);
				if (origin.media.getType() == Media.TYPE_MEDIA)
				{
					syncMediaFile(origin, destination, target, itemId, data.containsKey(MEDIA_FILE_FIELD));
				}
				target.setDescription(origin.media.getDescription());
				ManagerFactory.getEntityManager().merge(target);
				return MediaDependencyHandler.loadDestination(context, itemId);
			}
			finally
			{
				session.setLocale(previousLocale);
			}
		}

		/**
		 * Builds the media data of the destination item.
		 *
		 * @param destination
		 *            the destination item to update, or null to add one.
		 * @param fileToWrite
		 *            the copied file to write, or null to keep the file of the destination.
		 */
		private Map<String, Object> buildMediaData(
				MediaEntry origin,
				MediaEntry destination,
				String parentId,
				FileDataType.Data fileToWrite,
				String titleToWrite) throws Exception
		{
			Map<String, Object> data = new LinkedHashMap<>();
			if (destination != null)
			{
				data.put("id", destination.media.getId());
			}
			data.put("title", titleToWrite);
			data.put("legend", origin.media.getLegend());
			data.put("credits", origin.media.getCredits());
			data.put("type", origin.media.getType());
			data.put("parentFolder", parentId);
			data.put("inheritPublishTo", origin.media.getInheritPublishTo());
			data.put("publishToPrincipals", principals(context, origin, parentId));
			if (fileToWrite != null)
			{
				data.put(MEDIA_FILE_FIELD, fileToWrite);
			}
			return data;
		}

		private void putContentIdentity(ContentTableSource source, Map<String, Object> data, MediaEntry origin, String destinationContentId) throws Exception
		{
			MetaDataSourceField contentIdField = source.getField(Type.ContentId);
			if (contentIdField != null && destinationContentId != null)
			{
				data.put(contentIdField.getId(), destinationContentId);
			}
			MetaDataSourceField languageField = source.getField(Type.Language);
			if (languageField != null)
			{
				data.put(languageField.getId(), origin.locale.getLocale());
				Locale defaultLocale = origin.locale.getContent().getDefaultLocale();
				data.put(languageField.getId() + ".isDefaultLocale", defaultLocale != null && defaultLocale.equals(origin.locale.getLocale()));
			}
		}

		private void syncMediaFile(MediaEntry origin, MediaEntry destination, Media target, String itemId, boolean isFileWritten) throws Exception
		{
			if (destination == null || isFileWritten)
			{
				String contentLocaleId = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId).getContentLocale().getId();
				ManagerFactory.getFileManager().setFileContentLocaleId(target.getMediaFile(), contentLocaleId);
			}
			else if (!origin.file.getName().equals(destination.file.getName()))
			{
				ManagerFactory.getFileManager().changeFilename(context.getSession(), destination.media.getMediaFile(), origin.file.getName(), context.getTransaction());
			}
		}
	}
}
