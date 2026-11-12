package br.com.lumis.commons.contentmigrator.service.contentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.WidgetFixture;
import lumis.doui.source.field.type.HtmlDataTypeUtil;
import lumis.doui.source.field.type.HtmlDataTypeUtil.HtmlDataToRenderArea;
import lumis.portal.PortalConfiguration;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/** Integration coverage for the blocked-widget mapping modal and its save action. */
public class WidgetMappingAdminTest
{
	private static final String QUERY = "br.com.lumis.commons.contentmigrator.service.contentmigration.query.WidgetMappingQueries";

	private static final String ACTION = "br.com.lumis.commons.contentmigrator.service.contentmigration.processaction.WidgetMappingProcessActionHandler";

	private static final Pattern STRING_ID_PATTERN = Pattern.compile("<string\\s+id=\"([^\"]+)\"");

	private PortalFixture portal;

	private WidgetFixture widgets;

	private ChannelConfig channel;

	private ServiceInstanceConfig originRepository;

	private ServiceInstanceConfig destinationRepository;

	private ServiceInstanceConfig otherRepository;

	private ServiceInstanceConfig originNews;

	private ServiceInstanceConfig destinationNews;

	private String originWidget;

	private String destinationWidget;

	private String secondDestinationWidget;

	private String otherRepositoryWidget;

	private String migrationId;

	private String userId;

	@Before
	public void setUp() throws Exception
	{
		PortalConfiguration.getCurrentConfiguration();
		portal = new PortalFixture();
		widgets = new WidgetFixture();
		channel = portal.createChannel();
		createBlockedMigration();
	}

	@After
	public void tearDown() throws Exception
	{
		if (!PortalFixture.keepTestData())
		{
			if (migrationId != null)
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					try (PreparedStatement statement = connection(transaction).prepareStatement(
							"update lum_cmig_Migration set status = ?, errorType = null, blockingOriginItemId = null, " + "blockingWidgetContentId = null where id = ?"))
					{
						statement.setString(1, "STOPPED");
						statement.setString(2, migrationId);
						statement.executeUpdate();
					}
					return null;
				});
				ModuleBridge.api("delete", migrationId);
			}
			if (userId != null)
			{
				PortalFixture.inTransaction((session, transaction) ->
				{
					ManagerFactory.getUserManager().delete(session, userId, transaction);
					return null;
				});
			}
		}
		TestCleanup.assertNoFailures(widgets::cleanup, portal::cleanup);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void showsBlockedContentAndOnlyDestinationRepositoryWidgetsAndSavesMapping() throws Exception
	{
		Map<String, Object> blocked = (Map<String, Object>) PortalFixture
				.inTransaction((session, transaction) -> ModuleBridge.invokeStatic(QUERY, "getBlockedMigration", session, migrationId, transaction));
		assertEquals("Blocked widget migration", blocked.get("title"));
		assertEquals("The widget is configured for the origin.", blocked.get("introduction"));
		assertEquals(originWidget, blocked.get("originWidgetContentId"));
		assertEquals("origin widget", blocked.get("originWidgetName"));
		assertEquals(destinationRepository.getId(), blocked.get("destRepoInstanceId"));

		List<Map<String, Object>> options = (List<Map<String, Object>>) PortalFixture
				.inTransaction((session, transaction) -> ModuleBridge.invokeStatic(QUERY, "listDestinationWidgets", session, migrationId, transaction));
		assertEquals("only widgets in the linked destination repository are options", 2, options.size());
		assertTrue(options.stream().anyMatch(row -> destinationWidget.equals(row.get("widgetContentId"))));
		assertTrue(options.stream().anyMatch(row -> secondDestinationWidget.equals(row.get("widgetContentId"))));
		assertFalse(options.stream().anyMatch(row -> otherRepositoryWidget.equals(row.get("widgetContentId"))));

		SessionConfig systemSession = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		try
		{
			ModuleBridge.invokeStatic(ACTION, "saveMapping", migrationId, destinationWidget);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(systemSession);
		}
		String mappedWidget = PortalFixture.inTransaction(
				(session, transaction) -> (String) ModuleBridge
						.invokeStatic(ModuleBridge.WIDGET_MAP_DAO, "getWidgetMapping", connection(transaction), originWidget, destinationRepository.getId()));
		assertEquals(destinationWidget, mappedWidget);

		assertWidgetMappingInstructionIsLocalizedAndShown();
	}

	@Test
	public void saveActionRefusesUserWithoutEditPermissionOnDestinationRepository() throws Exception
	{
		createUnprivilegedUser();
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			try
			{
				ModuleBridge.invokeStatic(ACTION, "saveMapping", migrationId, secondDestinationWidget);
				fail("expected the user without destination repository edit permission to be refused");
			}
			catch (Exception e)
			{
				assertEquals("FORBIDDEN", ModuleBridge.errorCode(e));
			}
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}

		String mapping = PortalFixture.inTransaction(
				(sessionConfig, transaction) -> (String) ModuleBridge
						.invokeStatic(ModuleBridge.WIDGET_MAP_DAO, "getWidgetMapping", connection(transaction), originWidget, destinationRepository.getId()));
		assertEquals("a denied action must not write a mapping", null, mapping);
	}

	@Test
	public void queryChecksPermissionsBeforeReportingMigrationState() throws Exception
	{
		createUnprivilegedUser();
		markMigrationNotBlocked();
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			try
			{
				PortalFixture.inTransaction(
						(currentSession, transaction) -> ModuleBridge.invokeStatic(QUERY, "getBlockedMigration", session, migrationId, transaction));
				fail("expected the user without migration permissions to be refused before checking the migration state");
			}
			catch (Exception e)
			{
				assertEquals("FORBIDDEN", ModuleBridge.errorCode(e));
			}
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	@Test
	public void saveActionChecksPermissionsBeforeReportingMigrationState() throws Exception
	{
		createUnprivilegedUser();
		markMigrationNotBlocked();
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			try
			{
				ModuleBridge.invokeStatic(ACTION, "saveMapping", migrationId, secondDestinationWidget);
				fail("expected the user without migration permissions to be refused before checking the migration state");
			}
			catch (Exception e)
			{
				assertEquals("FORBIDDEN", ModuleBridge.errorCode(e));
			}
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	private void createBlockedMigration() throws Exception
	{
		ServiceInstanceConfig html = portal.createServiceInstance(channel, WidgetFixture.HTML_SERVICE_ID);
		originRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		destinationRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		otherRepository = portal.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		originNews = portal.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		destinationNews = portal.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		portal.setDependency(originRepository, originNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);
		portal.setDependency(destinationRepository, destinationNews, WidgetFixture.WIDGET_REPOSITORY_DEPENDENCY);

		String baseInterface = widgets.addBaseInterfaceInstance(html);
		originWidget = widgets.addWidget(originRepository, "origin widget", baseInterface);
		destinationWidget = widgets.addWidget(destinationRepository, "destination widget one", baseInterface);
		secondDestinationWidget = widgets.addWidget(destinationRepository, "destination widget two", baseInterface);
		otherRepositoryWidget = widgets.addWidget(otherRepository, "widget from unrelated repository", baseInterface);

		var embed = widgets.addEditorInstance(originWidget);
		String htmlValue = HtmlDataTypeUtil
				.buildContentValue(Arrays.asList(new HtmlDataToRenderArea("main", WidgetFixture.embed(embed.getId(), embed.getInterfaceInstanceId()))));
		Map<String, Object> content = new HashMap<>();
		content.put("title", "Blocked widget migration");
		content.put("introduction", "The widget is configured for the origin.");
		content.put("content", htmlValue);
		String originItemId = widgets.addContent(originNews, "default", content);

		Map<String, Object> create = new HashMap<>();
		create.put("name", "widget mapping admin test");
		create.put("originServiceInstanceId", originNews.getId());
		create.put("destServiceInstanceId", destinationNews.getId());
		@SuppressWarnings("unchecked")
		Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
		migrationId = (String) migration.get("id");
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"update lum_cmig_Migration set status = ?, errorType = ?, blockingOriginItemId = ?, " + "blockingWidgetContentId = ?, errorMessage = ? where id = ?"))
			{
				statement.setString(1, "STOPPED_WITH_ERROR");
				statement.setString(2, "WIDGET_MAPPING_REQUIRED");
				statement.setString(3, originItemId);
				statement.setString(4, originWidget);
				statement.setString(5, "Widget mapping required for this content.");
				statement.setString(6, migrationId);
				statement.executeUpdate();
			}
			return null;
		});
	}

	/** Last 12 characters of a new GUID: the leading part is the same for every GUID generated by this JVM. */
	private static String uniqueSuffix()
	{
		String guid = PortalUtil.generateNewGuid().toLowerCase();
		return guid.substring(guid.length() - 12);
	}

	private void createUnprivilegedUser() throws Exception
	{
		UserConfig user = new UserConfig();
		String login = "contentmigrator-test-" + uniqueSuffix();
		user.setLogin(login);
		user.setFirstName(login);
		user.setEmail(login + "@example.com");
		user.setPassword("contentmigrator#9" + PortalUtil.generateNewGuid().toLowerCase() + "Q!");
		userId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
	}

	private void markMigrationNotBlocked() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = connection(transaction).prepareStatement(
					"update lum_cmig_Migration set status = ?, errorType = null where id = ?"))
			{
				statement.setString(1, "STOPPED");
				statement.setString(2, migrationId);
				statement.executeUpdate();
			}
			return null;
		});
	}

	private void assertWidgetMappingInstructionIsLocalizedAndShown() throws Exception
	{
		String definition = readResource("br/com/lumis/commons/contentmigrator/service/contentmigration/douidefinition.xml");
		String portuguese = readResource("br/com/lumis/commons/contentmigrator/service/contentmigration/strings/strings.pt_br.xml");
		String english = readResource("br/com/lumis/commons/contentmigrator/service/contentmigration/strings/strings.en_us.xml");
		assertTrue(definition.contains("<control:lum_label text=\"STR_WIDGET_MAPPING_INSTRUCTION\""));
		assertTrue(portuguese.contains("STR_WIDGET_MAPPING_INSTRUCTION"));
		assertTrue(english.contains("STR_WIDGET_MAPPING_INSTRUCTION"));
		assertTrue(portuguese.contains("Crie um widget paralelo"));
		assertTrue(english.contains("Create a parallel widget"));
		assertEquals("Portuguese and English string keys must stay in sync", stringIds(english), stringIds(portuguese));
		assertTrue(portuguese.contains("<string id=\"STR_BLOCKINGORIGINITEMID\">ID do item de origem que bloqueou</string>"));
		assertTrue(portuguese.contains("<string id=\"STR_DESTCREATED\">Destino criado pela migração</string>"));
	}

	private Set<String> stringIds(String strings)
	{
		Set<String> ids = new HashSet<>();
		Matcher matcher = STRING_ID_PATTERN.matcher(strings);
		while (matcher.find())
		{
			ids.add(matcher.group(1));
		}
		return ids;
	}

	private String readResource(String resource) throws Exception
	{
		return Files.readString(Paths.get("src", resource), StandardCharsets.UTF_8);
	}

	private static java.sql.Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}
}
