package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.portal.PortalException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.user.UserConfig;

/** Exercises the real DOUI adapter with an empty response definition. */
public class MigrationProcessActionHandlerTest
{
	private static final String HANDLER = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.processaction.MigrationProcessActionHandler";
	private PortalFixture fixture;
	private ServiceInstanceConfig origin;
	private ServiceInstanceConfig destination;
	private String migrationId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		origin = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.HIERARCHICAL_SERVICE_ID);
	}

	@After
	public void tearDown() throws Exception
	{
		if (!PortalFixture.keepTestData() && migrationId != null)
		{
			ModuleBridge.api("delete", migrationId);
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void createPassesTheSelectedContentIds() throws Exception
	{
		String root = fixture.addHierarchicalContent(origin, "selected root", null, true);
		fixture.addHierarchicalContent(origin, "unselected root", null, true);
		invokeCreate(root);
		PortalFixture.inTransaction((session, transaction) ->
		{
			var connection = ((ITransactionJdbc) transaction).getConnection();
			try (var statement = connection.prepareStatement("select id from lum_cmig_HMigration where originServiceInstanceId = ? and destServiceInstanceId = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				try (var rows = statement.executeQuery())
				{
					org.junit.Assert.assertTrue(rows.next());
					migrationId = rows.getString(1);
				}
			}
			assertEquals(List.of(root), ModuleBridge.invokeStatic(
					"br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.SelectionDao", "listContentIds", connection, migrationId));
			return null;
		});
	}

	@Test
	public void apiFailureBecomesALocalizedPortalException() throws Exception
	{
		try
		{
			invokeCreate("00000000000000000000000000000000");
			fail("invalid selection must fail");
		}
		catch (PortalException exception)
		{
			assertEquals("STR_MIGRATION_INVALID_SELECTION", exception.getMessage());
			assertEquals("INVALID_SELECTION", ModuleBridge.errorCode(exception.getCause()));
			assertTranslated(exception);
		}
	}

	@Test
	public void everyApiCodeHasTranslationsInBothLanguages() throws Exception
	{
		Field strings = ModuleBridge.load(HANDLER).getDeclaredField("ERROR_STRINGS");
		strings.setAccessible(true);
		Map<?, ?> mapping = (Map<?, ?>) strings.get(null);
		var resource = new lumis.portal.localization.GenericStringResource(
				"br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/strings/strings");
		Class<?> apiException = ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException");
		for (Field field : apiException.getFields())
		{
			if (field.getName().startsWith("ERROR_"))
			{
				String key = (String) mapping.get(field.get(null));
				org.junit.Assert.assertNotNull(field.getName(), key);
				assertTranslated(new PortalException(key, resource));
			}
		}
	}

	private static void assertTranslated(PortalException exception)
	{
		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			String text = exception.getLocalizedMessage(locale);
			assertNotEquals(exception.getMessage(), text);
			assertFalse(text.trim().isEmpty());
		}
	}

	private void invokeCreate(String selection) throws Exception
	{
		Object handler = ModuleBridge.load(HANDLER).getConstructor().newInstance();
		Class<?> base = Class.forName("lumis.doui.processaction.ProcessActionHandler");
		set(base, handler, "id", "createMigration");
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		set(base, handler, "sessionConfig", session);
		set(base, handler, "parameters", Map.of("name", "handler test", "originServiceInstanceId", origin.getId(),
				"destServiceInstanceId", destination.getId(), "selectedContentIds", selection));
		set(base, handler, "processActionNode", DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement("processAction"));
		try
		{
			handler.getClass().getMethod("processAction").invoke(handler);
		}
		catch (InvocationTargetException exception)
		{
			throw (Exception) exception.getCause();
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	private static void set(Class<?> type, Object target, String name, Object value) throws Exception
	{
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
