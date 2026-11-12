package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentAssociation;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.doui.source.field.type.HtmlDataTypeUtil.HtmlDataToRenderArea;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.user.UserConfig;
import lumis.service.media.Media;
import lumis.service.pagepersonalization.bean.HtmlFieldWidgetInstance;

/** Builds the paired channels and shared dependency repositories for the full migration test. */
public class FullScenarioFixture
{
	public static final String HIERARCHICAL_SERVICE_ID = "lumis.service.hierarchicalcontent";

	public static final int NEWS_CONTENT_COUNT = 20;

	private static final byte[] MEDIA_BYTES = new byte[]{2, 3, 5, 7, 11, 13};

	private static final byte[] DOCUMENT_BYTES = new byte[]{17, 19, 23, 29, 31};

	public final PortalFixture originPortal = new PortalFixture();

	public final PortalFixture destinationPortal = new PortalFixture();

	public final ContentMetadataFixture content = new ContentMetadataFixture();

	public final MediaFixture media = new MediaFixture();

	public final DocumentFixture documents = new DocumentFixture();

	public final CategorizationFixture categories = new CategorizationFixture();

	public final WidgetFixture widgets = new WidgetFixture();

	public ChannelConfig originChannel;

	public ChannelConfig destinationChannel;

	public ServiceInstanceConfig originNews;

	public ServiceInstanceConfig destinationNews;

	public ServiceInstanceConfig originHierarchy;

	public ServiceInstanceConfig destinationHierarchy;

	public ServiceInstanceConfig originMediaRepository;

	public ServiceInstanceConfig destinationMediaRepository;

	public ServiceInstanceConfig originDocumentRepository;

	public ServiceInstanceConfig destinationDocumentRepository;

	public ServiceInstanceConfig originCategorizationRepository;

	public ServiceInstanceConfig destinationCategorizationRepository;

	public ServiceInstanceConfig originWidgetRepository;

	public ServiceInstanceConfig destinationWidgetRepository;

	public String originMediaId;

	public String originMediaFileId;

	public String originDocumentId;

	public String originDocumentFileId;

	public String originRootTermId;

	public String originSharedTermId;

	public String originWidgetContentId;

	public String destinationWidgetContentId;

	public String hierarchyParentItemId;

	public String hierarchyChildItemId;

	public final List<String> originNewsItemIds = new ArrayList<>();

	public final List<String> originNewsContentIds = new ArrayList<>();

	private String originAuthorId;

	private String originEditorId;

	public void create() throws Exception
	{
		originChannel = originPortal.createChannel();
		destinationChannel = destinationPortal.createChannel();
		createInstances();
		createDependencies();
		createSourceContent();
	}

	public String updateFirstNews() throws Exception
	{
		String oldActiveItemId = originNewsItemIds.get(0);
		String updatedHtml = htmlWithDependencies();
		String updatedItemId = ContentMetadataFixture.updateNews(
				UserConfig.USER_SYSTEM_ID,
				originNews,
				oldActiveItemId,
				ContentMetadataFixture.news("news 00 updated", "updated introduction", updatedHtml),
				null,
				originEditorId);
		content.track(originNews.getId(), updatedItemId);
		addSharedTermToVersions(updatedItemId);
		originNewsItemIds.set(0, updatedItemId);
		return updatedItemId;
	}

	public List<ServiceInstanceConfig[]> migrationPairs()
	{
		return Arrays.asList(new ServiceInstanceConfig[]{originNews, destinationNews}, new ServiceInstanceConfig[]{originHierarchy, destinationHierarchy});
	}

	public List<ServiceInstanceConfig[]> allMapPairs()
	{
		return Arrays.asList(
				new ServiceInstanceConfig[]{originNews, destinationNews},
				new ServiceInstanceConfig[]{originHierarchy, destinationHierarchy},
				new ServiceInstanceConfig[]{originMediaRepository, destinationMediaRepository},
				new ServiceInstanceConfig[]{originDocumentRepository, destinationDocumentRepository},
				new ServiceInstanceConfig[]{originCategorizationRepository, destinationCategorizationRepository},
				new ServiceInstanceConfig[]{originWidgetRepository, destinationWidgetRepository});
	}

	public String sourceContentId(String itemId) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) -> ContentMetadataFixture.contentLocale(itemId).getContent().getId());
	}

	public void cleanup()
	{
		TestCleanup.assertNoFailures(
				content::cleanup,
				widgets::cleanup,
				documents::cleanup,
				media::cleanup,
				categories::cleanup,
				destinationPortal::cleanup,
				originPortal::cleanup);
	}

	private void createInstances() throws Exception
	{
		originNews = ContentMetadataFixture.createNewsInstanceWithWorkflow(originPortal, originChannel);
		destinationNews = ContentMetadataFixture.createNewsInstanceWithWorkflow(destinationPortal, destinationChannel);
		originHierarchy = originPortal.createServiceInstance(originChannel, HIERARCHICAL_SERVICE_ID);
		destinationHierarchy = destinationPortal.createServiceInstance(destinationChannel, HIERARCHICAL_SERVICE_ID);

		originMediaRepository = originPortal.createServiceInstance(originChannel, PortalFixture.MEDIA_SERVICE_ID);
		destinationMediaRepository = destinationPortal.createServiceInstance(destinationChannel, PortalFixture.MEDIA_SERVICE_ID);
		originDocumentRepository = originPortal.createServiceInstance(originChannel, DocumentFixture.DOCUMENT_SERVICE_ID);
		destinationDocumentRepository = destinationPortal.createServiceInstance(destinationChannel, DocumentFixture.DOCUMENT_SERVICE_ID);
		originCategorizationRepository = originPortal.createServiceInstance(originChannel, CategorizationFixture.SERVICE_ID);
		destinationCategorizationRepository = destinationPortal.createServiceInstance(destinationChannel, CategorizationFixture.SERVICE_ID);
		originWidgetRepository = originPortal.createServiceInstance(originChannel, WidgetFixture.WIDGET_SERVICE_ID);
		destinationWidgetRepository = destinationPortal.createServiceInstance(destinationChannel, WidgetFixture.WIDGET_SERVICE_ID);

		ServiceInstanceConfig[] originContent = {originNews, originHierarchy};
		ServiceInstanceConfig[] destinationContent = {destinationNews, destinationHierarchy};
		for (ServiceInstanceConfig instance : originContent)
		{
			originPortal.setDependency(originMediaRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY);
			originPortal.setDependency(originDocumentRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY);
			originPortal.setDependency(originCategorizationRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY);
			originPortal.setDependency(originWidgetRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY);
		}
		for (ServiceInstanceConfig instance : destinationContent)
		{
			destinationPortal.setDependency(destinationMediaRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY);
			destinationPortal.setDependency(destinationDocumentRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY);
			destinationPortal.setDependency(destinationCategorizationRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY);
			destinationPortal.setDependency(destinationWidgetRepository, instance, ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY);
		}
	}

	private void createDependencies() throws Exception
	{
		String originMediaFolder = media.addFolder(originMediaRepository, null, "shared media root");
		originMediaId = media.addMedia(originMediaRepository, originMediaFolder, "shared migration media", "shared-image.png", "image/png", MEDIA_BYTES);
		FileConfig originMediaFile = PortalFixture.inTransaction((session, transaction) ->
		{
			Media sourceMedia = MediaFixture.getMedia(originMediaId);
			return MediaFixture.getFile(session, transaction, sourceMedia.getMediaFile());
		});
		originMediaFileId = originMediaFile.getId();

		String originDocumentFolder = documents.addFolder(originDocumentRepository, null, "shared document root");
		originDocumentId = documents.addDocument(
				originDocumentRepository,
				originDocumentFolder,
				"shared migration document",
				"shared document description",
				"shared-document.txt",
				"text/plain",
				DOCUMENT_BYTES);
		originDocumentFileId = PortalFixture.inTransaction((session, transaction) ->
		{
			lumis.service.document.DocumentConfig document = DocumentFixture.getDocument(session, transaction, originDocumentId);
			return MediaFixture.getFile(session, transaction, document.getDocumentFile()).getId();
		});

		originRootTermId = categories.addTerm(originCategorizationRepository, null, "Full Migration");
		originSharedTermId = categories.addTerm(originCategorizationRepository, originRootTermId, "Shared");

		ServiceInstanceConfig originHtml = originPortal.createServiceInstance(originChannel, WidgetFixture.HTML_SERVICE_ID);
		ServiceInstanceConfig destinationHtml = destinationPortal.createServiceInstance(destinationChannel, WidgetFixture.HTML_SERVICE_ID);
		originWidgetContentId = widgets.addWidget(originWidgetRepository, "origin shared widget", widgets.addBaseInterfaceInstance(originHtml));
		destinationWidgetContentId = widgets.addWidget(destinationWidgetRepository, "destination parallel widget", widgets.addBaseInterfaceInstance(destinationHtml));
	}

	private void createSourceContent() throws Exception
	{
		originAuthorId = content.createUser("full-author");
		originEditorId = content.createUser("full-editor");
		Map<String, String> roles = new HashMap<>();
		roles.put(originAuthorId, ContentMetadataFixture.ROLE_AUTHOR);
		roles.put(originEditorId, ContentMetadataFixture.ROLE_EDITOR);
		roles.put(UserConfig.USER_ADMINISTRATOR_ID, ContentMetadataFixture.ROLE_AUTHOR);
		ContentMetadataFixture.setRoles(originNews, roles);
		ContentMetadataFixture.setRoles(destinationNews, roles);

		for (int index = 0; index < NEWS_CONTENT_COUNT; index++)
		{
			String suffix = String.format("%02d", index);
			String html = htmlWithDependencies();
			Map<String, Object> data = ContentMetadataFixture.news("news " + suffix, "introduction " + suffix, html);
			data.put("introductionImage", originMediaId);
			data.put("contentImage", originMediaId);
			data.put("socialImage", originMediaId);
			String itemId = content.addNews(UserConfig.USER_SYSTEM_ID, originNews, data, ContentMetadataFixture.TRANSITION_APPROVE, originEditorId, null);
			if (index == 0)
			{
				String activeHtml = htmlWithDependencies();
				String activeItemId = ContentMetadataFixture.updateNews(
						UserConfig.USER_SYSTEM_ID,
						originNews,
						itemId,
						ContentMetadataFixture.news("news 00 active", "active introduction", activeHtml),
						ContentMetadataFixture.TRANSITION_REVIEW,
						originEditorId);
				content.track(originNews.getId(), activeItemId);
				itemId = activeItemId;
			}
			addSharedTermToVersions(itemId);
			originNewsItemIds.add(itemId);
			originNewsContentIds.add(sourceContentId(itemId));
		}

		hierarchyParentItemId = widgets.addContent(originHierarchy, "default", hierarchicalData("hierarchy root", 4, null, null));
		String parentContentId = sourceContentId(hierarchyParentItemId);
		String html = htmlWithDependencies();
		Map<String, Object> childData = hierarchicalData("hierarchy child", 3, parentContentId, html);
		childData.put("linkDocumentId", originDocumentId);
		childData.put("linkOpeningType", 0);
		hierarchyChildItemId = widgets.addContent(originHierarchy, "default", childData);
		addSharedTermToVersions(hierarchyChildItemId);
	}

	private String htmlWithDependencies() throws Exception
	{
		HtmlFieldWidgetInstance widgetInstance = widgets.addEditorInstance(originWidgetContentId);
		FileConfig mediaFile = PortalFixture
				.inTransaction((session, transaction) -> MediaFixture.getFile(session, transaction, MediaFixture.getMedia(originMediaId).getMediaFile()));
		FileConfig documentFile = PortalFixture.inTransaction((session, transaction) ->
		{
			lumis.service.document.DocumentConfig document = DocumentFixture.getDocument(session, transaction, originDocumentId);
			return MediaFixture.getFile(session, transaction, document.getDocumentFile());
		});
		String mediaPath = mediaFile.getFullPath().substring("files/".length()).replace(" ", "%20");
		String html = "<p><a href=\"/lumis/portal/file/fileDownload.jsp?fileId=" + documentFile.getId() + "&amp;inline=true\">shared document</a> <img src=\"/data/files/"
				+ mediaPath + "\" /> " + WidgetFixture.embed(widgetInstance.getId(), widgetInstance.getInterfaceInstanceId()) + "</p>";
		return HtmlDataTypeUtil.buildContentValue(Collections.singletonList(new HtmlDataToRenderArea("main", html)));
	}

	private static Map<String, Object> hierarchicalData(String title, int type, String parentContentId, String html)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", title);
		data.put("introduction", "introduction " + title);
		data.put("type", type);
		data.put("position", 0);
		data.put("hidden", false);
		data.put("parentContentId", parentContentId);
		if (html != null)
		{
			data.put("linkContent", html);
		}
		return data;
	}

	private void addSharedTermToVersions(String itemId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentMetadataFixture.contentLocale(itemId);
			Collection<ContentVersion> versions = locale.getVersions();
			for (ContentVersion version : versions)
			{
				boolean hasTerm = version.getContentAssociations().stream()
						.filter(association -> ContentAssociation.TYPE_CATEGORIZATION_TERM.equals(association.getType()))
						.anyMatch(association -> originSharedTermId.equals(association.getAssociatedContent().getId()));
				if (!hasTerm)
				{
					ContentAssociation association = version.addContentAssociation();
					association.setType(ContentAssociation.TYPE_CATEGORIZATION_TERM);
					association.setAssociatedContent(ContentManagerFactory.getContentManager().getContent(originSharedTermId));
				}
			}
			return null;
		});
	}
}
