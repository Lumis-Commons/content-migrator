package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.document;

import java.lang.reflect.Array;
import java.net.URI;
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

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.FileCopy;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationItem;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationBlockedException;
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
import lumis.portal.PortalException;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.filesystem.PortalBaseFolder;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.manager.ManagerFactoryInternal;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.website.IWebsite;
import lumis.portal.website.IWebsiteBaseURL;
import lumis.portal.website.IWebsiteMainBaseURL;
import lumis.service.document.DocumentConfig;
import lumis.service.document.DocumentManagerFactory;
import lumis.service.document.DocumentUtil;
import lumis.service.document.IDocumentManager;
import lumis.service.document.field.DocumentDataType;

/** Migrates references to documents and document-file links embedded in HTML. */
public class DocumentDependencyHandler implements DependencyHandler
{
	private static final String SERVICE_ID = "lumis.service.document";

	private static final String KIND_DOCUMENT = "DOCUMENT";

	/** The source field of the stored file of a document; the data carries it only when the file is written. */
	private static final String DOCUMENT_FILE_FIELD = "documentFile";

	private static final Pattern DOWNLOAD_URL = Pattern
			.compile("(?i)(https?://[^/'\"<>\\s]+)?([^'\"<>\\s]*?lumis/portal/file/fileDownload\\.jsp\\?[^'\"<>\\s#]*?\\bfileId=)([a-f0-9]{32})([^'\"<>\\s#]*)");

	private static final Pattern PUBLIC_FILE_URL = Pattern.compile("(?i)(?:https?://[^'\"<>\\s]+)?[^'\"<>\\s]*?/data/files/([^?&#'\"<>\\s]+)");

	private static final Pattern PUBLIC_TRANSFORMED_URL = Pattern.compile("(?i)(?:https?://[^'\"<>\\s]+)?[^'\"<>\\s]*?/data/((?!files/)[^?&#'\"<>\\s]+)");

	private static final Pattern HTML_FILE_ID = Pattern.compile("(?i)lumis/portal/file/fileDownload\\.jsp\\?[^'\"<>\\s#]*?\\bfileId=([a-f0-9]{32})");

	@Override
	public String getDependencyType()
	{
		return ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY;
	}

	@Override
	public boolean handles(ISourceField field) throws Exception
	{
		return "document".equals(field.getDataType().getId());
	}

	@Override
	public void precheck(DependencyContext context, MigrationItem item) throws Exception
	{
		if (!clonesReferences(context))
		{
			return;
		}

		ContentLocale hostLocale = ContentManagerFactory.getContentManager().getContentLocale(item.getContentLocaleId());
		Set<String> documentIds = new LinkedHashSet<>();
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
					addReference(value, documentIds);
				}
				else if ("html".equals(field.getDataType().getId()) && value != null)
				{
					collectHtmlDocumentIds(value.toString(), documentIds);
				}
			}
		}

		ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
		ChannelConfig channel = ManagerFactory.getChannelManager().get(context.getSession(), repository.getChannelId(), context.getTransaction());
		Collection<Locale> acceptedLocales = channel.getAcceptableLocales();
		for (String documentId : documentIds)
		{
			DocumentEntry entry = load(context, documentId);
			if (entry == null)
			{
				continue;
			}
			if (!context.getOriginRepositoryInstanceId().equals(entry.serviceInstanceId))
			{
				context.addWarning("Document " + documentId + " belongs to another repository and was left unchanged.");
				continue;
			}
			if (!acceptedLocales.contains(entry.locale.getLocale()))
			{
				throw new MigrationBlockedException(
						MigrationBlockedException.ERROR_TYPE_LOCALE_NOT_ACCEPTED,
						hostLocale.getActiveVersion() == null ? item.getContentLocaleId() : hostLocale.getActiveVersion().getItemId(),
						"Document " + documentId + " uses language " + entry.locale.getLocale() + ", which is not accepted by the destination document repository.");
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
		String documentId = documentId(value);
		if (documentId == null || documentId.isEmpty())
		{
			return null;
		}
		DocumentEntry origin = load(context, documentId);
		if (origin == null)
		{
			context.addWarning("Document " + documentId + " referenced by " + fieldOrAssociationId + " no longer exists and was left empty.");
			return null;
		}
		if (!context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
		{
			context.addWarning("Document " + documentId + " belongs to another repository and was left unchanged.");
			return value;
		}
		if (origin.document.getType() == DocumentConfig.TYPE_FOLDER)
		{
			context.addWarning("Document field " + fieldOrAssociationId + " references folder " + documentId + "; its destination folder was preserved.");
		}
		return new RepositoryCloner<>(context, new DocumentAdapter(context)).resolve(documentId);
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
				throw new PortalException("Could not rewrite document URLs in HTML.", e);
			}
		});
	}

	private String rewriteDownloadUrls(DependencyContext context, String html) throws Exception
	{
		Matcher matcher = DOWNLOAD_URL.matcher(html);
		StringBuffer result = new StringBuffer(html.length());
		while (matcher.find())
		{
			String replacement = matcher.group();
			String documentId = DocumentUtil.getDocumentIdFromFileId(matcher.group(3));
			if (documentId != null)
			{
				DocumentEntry origin = load(context, documentId);
				if (origin != null && context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
				{
					String destId = new RepositoryCloner<>(context, new DocumentAdapter(context)).resolve(documentId);
					DocumentEntry destination = loadDestination(context, destId);
					String destFileId = requireFile(destination).getId();
					String withDestinationFile = (matcher.group(1) == null ? "" : matcher.group(1)) + matcher.group(2) + destFileId + matcher.group(4);
					replacement = matcher.group(1) == null ? withDestinationFile : rewriteAbsoluteUrl(context, withDestinationFile);
				}
				else if (origin != null)
				{
					context.addWarning("Document " + documentId + " belongs to another repository and was left unchanged.");
				}
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Moves an absolute download url, which already carries the destination file id, to the base url
	 * of the website of the destination repository. Returns the url unchanged, with a warning when
	 * needed, if it already belongs to that website or cannot be moved.
	 */
	private String rewriteAbsoluteUrl(DependencyContext context, String url) throws Exception
	{
		URI uri;
		try
		{
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e)
		{
			context.addWarning("An absolute document URL could not be parsed; its original website was kept.");
			return url;
		}
		boolean secure = "https".equalsIgnoreCase(uri.getScheme());
		IWebsiteBaseURL sourceBase = ManagerFactory.getWebsiteManager().getWebsiteBaseURL(uri);
		ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
		IWebsite destinationWebsite = repository.getChannelId() == null
				? null
				: ManagerFactoryInternal.getWebsiteManager().getWebsiteByChannelId(repository.getChannelId());
		if (destinationWebsite == null)
		{
			context.addWarning("No destination website is configured for an absolute document URL; its original host was kept.");
			return url;
		}
		if (sourceBase == null || sourceBase.getWebsite() == null)
		{
			context.addWarning("The source website for an absolute document URL could not be identified; its original host was kept.");
			return url;
		}
		if (sourceBase.getWebsite().getId().equals(destinationWebsite.getId()))
		{
			return url;
		}

		IWebsiteMainBaseURL destinationBase = secure ? destinationWebsite.getMainSecureBaseURL() : destinationWebsite.getMainNonSecureBaseURL();
		if (destinationBase == null)
		{
			context.addWarning("The destination website has no matching base URL for an absolute document URL; its original host was kept.");
			return url;
		}
		URI sourceBaseUri = sourceBase.toURI(secure);
		URI destinationBaseUri = destinationBase.toURI(secure);
		String sourcePath = basePath(sourceBaseUri.getRawPath());
		String originalPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
		if (!originalPath.startsWith(sourcePath))
		{
			context.addWarning("An absolute document URL is outside its source website base path; its original host was kept.");
			return url;
		}
		String relativePath = originalPath.substring(sourcePath.length());
		String destinationPath = basePath(destinationBaseUri.getRawPath()) + relativePath;
		StringBuilder rewritten = new StringBuilder(destinationBaseUri.getScheme()).append("://").append(destinationBaseUri.getRawAuthority()).append(destinationPath);
		if (uri.getRawQuery() != null)
		{
			rewritten.append('?').append(uri.getRawQuery());
		}
		if (uri.getRawFragment() != null)
		{
			rewritten.append('#').append(uri.getRawFragment());
		}
		return rewritten.toString();
	}

	private static String basePath(String path)
	{
		if (path == null || path.isEmpty())
		{
			return "/";
		}
		return path.endsWith("/") ? path : path + "/";
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
				String documentId = DocumentUtil.getDocumentIdFromFileId(transformed.fileId);
				DocumentEntry origin = documentId == null ? null : load(context, documentId);
				if (origin != null && context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
				{
					String destId = new RepositoryCloner<>(context, new DocumentAdapter(context)).resolve(documentId);
					DocumentEntry destination = loadDestination(context, destId);
					if (!hasTransformation(context, transformed.transformationName))
					{
						context.addWarning(
								"Document transformation " + transformed.transformationName
										+ " is not configured in the destination repository; the transform link may not work.");
					}
					replacement = "lumis/portal/file/fileDownload.jsp?fileId=" + requireFile(destination).getId() + "&transform=" + transformed.transformationName;
				}
				else if (origin != null)
				{
					context.addWarning("Document " + documentId + " belongs to another repository and was left unchanged.");
				}
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	private String rewriteFile(DependencyContext context, FileConfig file, String originalUrl) throws Exception
	{
		String documentId = DocumentUtil.getDocumentIdFromFileId(file.getId());
		if (documentId == null)
		{
			return originalUrl;
		}
		DocumentEntry origin = load(context, documentId);
		if (origin == null || !context.getOriginRepositoryInstanceId().equals(origin.serviceInstanceId))
		{
			if (origin != null)
			{
				context.addWarning("Document " + documentId + " belongs to another repository and was left unchanged.");
			}
			return originalUrl;
		}
		String destId = new RepositoryCloner<>(context, new DocumentAdapter(context)).resolve(documentId);
		DocumentEntry destination = loadDestination(context, destId);
		return ManagerFactory.getFileManager().getFileHref(context.getSession(), requireFile(destination).getId(), context.getTransaction()).toString();
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

	@Override
	public boolean isCloneReferenced(DependencyContext context, String destContentLocaleId) throws Exception
	{
		// The select reads from lum_ContentLocale because Oracle does not accept a select without from.
		String itemIds = "select v.itemId from lum_ContentVersion v where v.contentLocaleId = cl.id";
		// @formatter:off
		String sql = "select count(*) from lum_ContentLocale cl where cl.id = ? "
				+ "and (exists (select 1 from lum_DocumentUsage u where u.documentId in (" + itemIds + ")) "
				+ "or exists (select 1 from lum_Document child where child.parentFolder in (" + itemIds + ")))";
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
		String select = "select destItemId, destCreated from lum_cmig_HMigItemMap where destServiceInstanceId = ? "
				+ "and destContentLocaleId = ? and kind in (?, ?)";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(select))
		{
			statement.setString(1, context.getDestRepositoryInstanceId());
			statement.setString(2, destContentLocaleId);
			statement.setString(3, RepositoryCloner.KIND_FOLDER);
			statement.setString(4, KIND_DOCUMENT);
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
				.prepareStatement("delete from lum_cmig_HMigItemMap where destServiceInstanceId = ? and destContentLocaleId = ? and kind in (?, ?)"))
		{
			statement.setString(1, context.getDestRepositoryInstanceId());
			statement.setString(2, destContentLocaleId);
			statement.setString(3, RepositoryCloner.KIND_FOLDER);
			statement.setString(4, KIND_DOCUMENT);
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
			String documentId = documentId(value);
			if (documentId != null && !documentId.isEmpty())
			{
				result.add(documentId);
			}
		}
	}

	private static String documentId(Object value)
	{
		if (value instanceof DocumentConfig)
		{
			return ((DocumentConfig) value).getId();
		}
		if (value instanceof DocumentDataType.Data)
		{
			return ((DocumentDataType.Data) value).getDocumentId();
		}
		return value.toString();
	}

	private static void collectHtmlDocumentIds(String html, Set<String> documentIds) throws Exception
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
				String documentId = DocumentUtil.getDocumentIdFromFileId(matcher.group(1));
				if (documentId != null)
				{
					documentIds.add(documentId);
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

	private static DocumentEntry load(DependencyContext context, String documentId) throws Exception
	{
		try
		{
			DocumentConfig document = DocumentManagerFactory.getDocumentManager().get(context.getSession(), documentId, context.getTransaction());
			ContentVersion version = ContentManagerFactory.getContentManager().getContentVersionByItemId(documentId);
			ContentLocale locale = version.getContentLocale();
			String serviceInstanceId = locale.getContent().getServiceInstanceId();
			FileConfig file = document.getType() == DocumentConfig.TYPE_DOCUMENT && document.getDocumentFile() != null
					? ManagerFactory.getFileManager().get(context.getSession(), document.getDocumentFile(), context.getTransaction())
					: null;
			return new DocumentEntry(document, version, locale, serviceInstanceId, file);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	private static DocumentEntry loadDestination(DependencyContext context, String documentId) throws Exception
	{
		DocumentEntry entry = load(context, documentId);
		return entry != null && context.getDestRepositoryInstanceId().equals(entry.serviceInstanceId) ? entry : null;
	}

	private static FileConfig requireFile(DocumentEntry entry)
	{
		if (entry == null || entry.file == null)
		{
			throw new IllegalStateException("Document has no stored file.");
		}
		return entry.file;
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

	private static IllegalStateException missingFile(DocumentEntry item)
	{
		return new IllegalStateException(
				"Stored file for document " + item.document.getId() + " is missing: " + (item.file == null ? "<no lum_File>" : item.file.getFullPath()));
	}

	private static String principals(DependencyContext context, DocumentEntry origin, String destinationParentId) throws Exception
	{
		Collection<String> principals;
		if (origin.document.isInheritPublishTo())
		{
			principals = DocumentManagerFactory.getDocumentManager().getDocumentPublishToPrincipals(destinationParentId);
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

	private static final class DocumentEntry
	{
		final DocumentConfig document;

		final ContentVersion version;

		final ContentLocale locale;

		final String serviceInstanceId;

		final FileConfig file;

		DocumentEntry(DocumentConfig document, ContentVersion version, ContentLocale locale, String serviceInstanceId, FileConfig file)
		{
			this.document = document;
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

	private static final class DocumentAdapter implements RepositoryCloner.Adapter<DocumentEntry>
	{
		private final DependencyContext context;

		private final IDocumentManager documentManager = DocumentManagerFactory.getDocumentManager();

		DocumentAdapter(DependencyContext context)
		{
			this.context = context;
		}

		@Override
		public String repositoryServiceId()
		{
			return SERVICE_ID;
		}

		@Override
		public DocumentEntry loadOrigin(String itemId) throws Exception
		{
			return load(context, itemId);
		}

		@Override
		public DocumentEntry loadDestination(String itemId) throws Exception
		{
			return DocumentDependencyHandler.loadDestination(context, itemId);
		}

		@Override
		public String itemId(DocumentEntry item)
		{
			return item.document.getId();
		}

		@Override
		public String kind(DocumentEntry item)
		{
			return item.document.getType() == DocumentConfig.TYPE_FOLDER ? RepositoryCloner.KIND_FOLDER : KIND_DOCUMENT;
		}

		@Override
		public boolean isFolder(DocumentEntry item)
		{
			return item.document.getType() == DocumentConfig.TYPE_FOLDER;
		}

		@Override
		public String contentId(DocumentEntry item)
		{
			return item.locale.getContent().getId();
		}

		@Override
		public String contentLocaleId(DocumentEntry item)
		{
			return item.locale.getId();
		}

		@Override
		public String locale(DocumentEntry item)
		{
			return item.locale.getLocale().toString();
		}

		@Override
		public String parentId(DocumentEntry item)
		{
			return item.document.getParentFolder();
		}

		@Override
		public String title(DocumentEntry item)
		{
			return item.document.getTitle();
		}

		@Override
		public FileCopy copyFile(DocumentEntry item) throws Exception
		{
			if (item.file == null || !PortalBaseFolder.DATA.toPortalFilePath(item.file.getFullPath()).toFile().exists())
			{
				throw missingFile(item);
			}
			try
			{
				return context.getFileCloner().copyWithFingerprint(context.getSession(), context.getTransaction(), item.document.getDocumentFile());
			}
			catch (Exception e)
			{
				throw new IllegalStateException("Could not copy stored file for document " + item.document.getId() + " at " + item.file.getFullPath() + ".", e);
			}
		}

		@Override
		public DocumentEntry findFolder(String destinationParentId, String title) throws Exception
		{
			Collection<String> ids = documentManager
					.getFolderIdsByParentAndName(context.getSession(), context.getDestRepositoryInstanceId(), destinationParentId, title, context.getTransaction());
			List<String> sortedIds = new ArrayList<>(ids);
			Collections.sort(sortedIds);
			List<DocumentEntry> matches = new ArrayList<>();
			for (String id : sortedIds)
			{
				DocumentEntry entry = DocumentDependencyHandler.loadDestination(context, id);
				if (entry != null)
				{
					matches.add(entry);
				}
			}
			if (matches.size() > 1)
			{
				context.addWarning(
						"Multiple destination document folders named '" + title + "' exist under the same parent; the folder with the smallest id was reused.");
			}
			return matches.isEmpty() ? null : matches.get(0);
		}

		@Override
		public DocumentEntry createFolder(DocumentEntry origin, String destinationParentId, String destinationContentId) throws Exception
		{
			return save(origin, null, buildDocumentData(origin, null, destinationParentId, null, origin.document.getTitle()), destinationContentId);
		}

		@Override
		public DocumentEntry updateFolder(DocumentEntry origin, DocumentEntry destination, String destinationParentId) throws Exception
		{
			DocumentEntry collision = findFolder(destinationParentId, origin.document.getTitle());
			boolean canRename = collision == null || collision.document.getId().equals(destination.document.getId());
			if (!canRename)
			{
				context.addWarning(
						"Document folder " + origin.document.getId() + " was not renamed because a folder named '" + origin.document.getTitle()
								+ "' already exists in the destination.");
			}
			String titleToWrite = canRename ? origin.document.getTitle() : destination.document.getTitle();
			return save(origin, destination, buildDocumentData(origin, destination, destinationParentId, null, titleToWrite), null);
		}

		@Override
		public DocumentEntry createItem(DocumentEntry origin, String destinationParentId, String destinationContentId, FileDataType.Data file) throws Exception
		{
			if (file == null)
			{
				throw missingFile(origin);
			}
			return save(origin, null, buildDocumentData(origin, null, destinationParentId, file, origin.document.getTitle()), destinationContentId);
		}

		@Override
		public DocumentEntry updateItem(DocumentEntry origin, DocumentEntry destination, String destinationParentId, FileDataType.Data file, boolean fileChanged)
				throws Exception
		{
			if (fileChanged && file == null)
			{
				throw missingFile(origin);
			}
			FileDataType.Data fileToWrite = fileChanged ? file : null;
			return save(origin, destination, buildDocumentData(origin, destination, destinationParentId, fileToWrite, origin.document.getTitle()), null);
		}

		/**
		 * Builds the document data of the destination item.
		 *
		 * @param destination
		 *            the destination item to update, or null to add one.
		 * @param fileToWrite
		 *            the copied file to write, or null to keep the file of the destination.
		 */
		private Map<String, Object> buildDocumentData(
				DocumentEntry origin,
				DocumentEntry destination,
				String parentId,
				FileDataType.Data fileToWrite,
				String titleToWrite) throws Exception
		{
			Map<String, Object> data = new LinkedHashMap<>();
			if (destination != null)
			{
				data.put("id", destination.document.getId());
			}
			data.put("title", titleToWrite);
			data.put("description", origin.document.getDescription());
			data.put("type", origin.document.getType());
			data.put("parentFolder", parentId);
			data.put("inheritPublishTo", origin.document.isInheritPublishTo());
			data.put("publishToPrincipals", principals(context, origin, parentId));
			if (fileToWrite != null)
			{
				data.put(DOCUMENT_FILE_FIELD, fileToWrite);
			}
			return data;
		}

		/**
		 * Writes the document data in the destination repository, adding a new item when there is
		 * no destination yet, with the session in the locale of the origin item.
		 *
		 * @param destinationContentId
		 *            the destination content of a new item, or null.
		 */
		private DocumentEntry save(DocumentEntry origin, DocumentEntry destination, Map<String, Object> data, String destinationContentId) throws Exception
		{
			boolean isAdd = destination == null;
			SessionConfig session = context.getSession();
			Locale previousLocale = session.getLocale();
			try
			{
				session.setLocale(origin.locale.getLocale());
				ServiceInstanceConfig repository = ManagerFactory.getServiceInstanceManager().get(context.getDestRepositoryInstanceId(), context.getTransaction());
				ContentTableSource source = source(session, context.getTransaction(), repository, RepositoryCloner.SOURCE_DEFAULT);
				if (isAdd)
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
				String itemId = isAdd ? source.addData(data).getItemId() : source.updateData(data).getItemId();
				DocumentConfig target = documentManager.get(session, itemId, context.getTransaction());
				if (target.getType() == DocumentConfig.TYPE_DOCUMENT)
				{
					if (isAdd || data.containsKey(DOCUMENT_FILE_FIELD))
					{
						String contentLocaleId = ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId).getContentLocale().getId();
						ManagerFactory.getFileManager().setFileContentLocaleId(target.getDocumentFile(), contentLocaleId);
					}
					else if (!origin.file.getName().equals(destination.file.getName()))
					{
						ManagerFactory.getFileManager().changeFilename(session, target.getDocumentFile(), origin.file.getName(), context.getTransaction());
					}
				}
				return DocumentDependencyHandler.loadDestination(context, itemId);
			}
			finally
			{
				session.setLocale(previousLocale);
			}
		}
	}
}
