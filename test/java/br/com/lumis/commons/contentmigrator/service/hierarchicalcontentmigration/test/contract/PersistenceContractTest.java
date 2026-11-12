package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.WidgetFixture;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.util.PortalUtil;

/**
 * Proves the persistence contract (PAT001): the schema installed, the uninstall script, the unique
 * keys, the validations and initial status of {@code MigrationApi.create}, and the format of its
 * dates. The module classes are called through {@link ModuleBridge}.
 */
public class PersistenceContractTest
{
	private static final String SETUP = "src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/componentfiles/setup/";

	private static final String DOUI = "src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/douidefinition.xml";

	private static final String STRINGS = "src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/strings/strings.";

	private static final String EXCEPTION = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException";

	private static final String SELECTION_TABLE = "lum_cmig_HMigSelection";

	private static final String[] TABLES = {
			"lum_cmig_HMigration",
			"lum_cmig_HMigItemMap",
			"lum_cmig_HMigRepoMap",
			"lum_cmig_HMigWidgetMap",
			SELECTION_TABLE,
			"lum_cmig_HMigLock"};

	private PortalFixture fixture;

	private final WidgetFixture widgets = new WidgetFixture();

	private final List<String> migrationIds = new ArrayList<>();

	private final List<String> itemMapIds = new ArrayList<>();

	private final List<String> widgetOrigins = new ArrayList<>();

	@Before
	public void setUp()
	{
		fixture = new PortalFixture();
	}

	@After
	public void tearDown() throws Exception
	{
		if (PortalFixture.keepTestData())
		{
			return;
		}
		for (String id : migrationIds)
		{
			ModuleBridge.api("delete", id);
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			for (String id : itemMapIds)
			{
				delete(connection, "lum_cmig_HMigItemMap", "id", id);
			}
			for (String id : widgetOrigins)
			{
				delete(connection, "lum_cmig_HMigWidgetMap", "originWidgetContentId", id);
			}
			return null;
		});
		TestCleanup.assertNoFailures(widgets::cleanup, fixture::cleanup);
	}

	private static void delete(Connection connection, String table, String column, String value) throws SQLException
	{
		try (PreparedStatement st = connection.prepareStatement("delete from " + table + " where " + column + " = ?"))
		{
			st.setString(1, value);
			st.executeUpdate();
		}
	}

	private static String read(String path) throws Exception
	{
		return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
	}

	private static Set<String> matches(String regex, String text)
	{
		Set<String> found = new LinkedHashSet<>();
		Matcher m = Pattern.compile(regex).matcher(text);
		while (m.find())
		{
			found.add(m.group(1));
		}
		return found;
	}

	@Test
	public void uninstallDropsEveryTableOfTheDatabaseDefinition() throws Exception
	{
		Set<String> tables = matches("<table name=\"([^\"]+)\"", read(SETUP + "databasedefinition.xml"));
		assertEquals(new LinkedHashSet<>(java.util.Arrays.asList(TABLES)), tables);
		String uninstall = read(SETUP + "component-uninstall-script.xml");
		Set<String> dropped = matches("drop table (\\w+)", uninstall);
		for (String table : tables)
		{
			assertTrue("uninstall does not drop " + table, dropped.contains(table));
		}
		assertTrue(read(SETUP + "component-install-script.xml").contains("databasedefinition.xml"));
	}

	/** The selection is read and written only by the API, so no interface has a source for it. */
	@Test
	public void douiSourcesAreTableSourcesWithoutContentMetadata() throws Exception
	{
		String doui = read(DOUI);
		Set<String> sourceTypes = matches("<source id=\"[^\"]+\" type=\"([^\"]+)\"", doui);
		assertEquals("source types", new LinkedHashSet<>(java.util.Arrays.asList("table")), sourceTypes);
		Set<String> expected = new LinkedHashSet<>(java.util.Arrays.asList(TABLES));
		expected.remove(SELECTION_TABLE);
		assertEquals(expected, matches("<table>([^<]+)</table>", doui));
		assertFalse(doui.contains("contentTable"));
		assertFalse(doui.contains("<metaData>"));
	}

	/**
	 * The process actions show each {@code MigrationApiException} code through the STR key
	 * {@code STR_MIGRATION_<code>}, so every {@code ERROR_*} constant needs that key in both locales.
	 */
	@Test
	public void everyErrorCodeHasALocalizedString() throws Exception
	{
		List<String> codes = new ArrayList<>();
		for (Field field : ModuleBridge.load(EXCEPTION).getFields())
		{
			if (field.getName().startsWith("ERROR_") && Modifier.isStatic(field.getModifiers()))
			{
				codes.add((String) field.get(null));
			}
		}
		assertFalse("no ERROR_* constant found", codes.isEmpty());
		String handler = read("src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/processaction/MigrationProcessActionHandler.java");
		for (String locale : java.util.Arrays.asList("pt_br", "en_us"))
		{
			Set<String> keys = matches("<string id=\"([^\"]+)\">[^<]+</string>", read(STRINGS + locale + ".xml"));
			for (String code : codes)
			{
				assertTrue(locale + " has no STR_MIGRATION_" + code, keys.contains("STR_MIGRATION_" + code));
			}
		}
		for (String code : codes)
		{
			assertTrue("the process action does not map " + code, handler.contains("\"STR_MIGRATION_" + code + "\""));
		}
	}

	@Test
	public void allTablesExist() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			for (String table : TABLES)
			{
				try (PreparedStatement st = connection.prepareStatement("select count(*) from " + table))
				{
					assertTrue(table, st.executeQuery().next());
				}
			}
			return null;
		});
	}

	@Test
	public void itemMapRejectsRepeatedKey() throws Exception
	{
		Map<String, Object> row = new HashMap<>();
		row.put("kind", "CONTENT");
		row.put("serviceId", "lumis.service.news");
		row.put("sourceId", "default");
		row.put("originServiceInstanceId", PortalUtil.generateNewGuid());
		row.put("destServiceInstanceId", PortalUtil.generateNewGuid());
		row.put("originContentLocaleId", PortalUtil.generateNewGuid());
		row.put("destCreated", Boolean.TRUE);

		Object[] second = new Object[1];
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			String firstId = PortalUtil.generateNewGuid();
			itemMapIds.add(firstId);
			row.put("id", firstId);
			ModuleBridge.insertItemMap(connection, row);
			return null;
		});
		try
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = ((ITransactionJdbc) transaction).getConnection();
				String secondId = PortalUtil.generateNewGuid();
				itemMapIds.add(secondId);
				row.put("id", secondId);
				ModuleBridge.insertItemMap(connection, row);
				return null;
			});
			fail("repeated key must fail");
		}
		catch (SQLException expected)
		{
			assertTrue(expected.toString(), String.valueOf(expected.getSQLState()).startsWith("23"));
			second[0] = expected;
		}
		assertNotNull(second[0]);
	}

	private Map<String, Object> params(ServiceInstanceConfig origin, ServiceInstanceConfig dest)
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-test-migration");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", dest.getId());
		params.put("selectedContentIds", PortalFixture.HIERARCHICAL_SERVICE_ID.equals(origin.getServiceId()) ? contentIn(origin) : PortalUtil.generateNewGuid());
		return params;
	}

	private String contentIn(ServiceInstanceConfig origin)
	{
		try
		{
			return fixture.addHierarchicalContent(origin, "root", null, true);
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private Map<String, Object> create(ServiceInstanceConfig origin, ServiceInstanceConfig dest) throws Exception
	{
		return create(origin, dest, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> create(ServiceInstanceConfig origin, ServiceInstanceConfig dest, Object updateMode) throws Exception
	{
		Map<String, Object> params = params(origin, dest);
		if (updateMode != null)
		{
			params.put("updateMode", updateMode);
		}
		Map<String, Object> result = (Map<String, Object>) ModuleBridge.api("create", params);
		migrationIds.add((String) result.get("id"));
		return result;
	}

	private void assertRejected(String code, ServiceInstanceConfig origin, ServiceInstanceConfig dest) throws Exception
	{
		try
		{
			create(origin, dest);
			fail("expected " + code);
		}
		catch (RuntimeException e)
		{
			assertEquals(e.toString(), code, ModuleBridge.errorCode(e));
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void createValidatesAndStartsStopped() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig news1 = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig news2 = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig hierarchy1 = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig hierarchy2 = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig media1 = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig media2 = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig douiInstance = fixture.createServiceInstance(channel, "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration");

		assertRejected("SAME_INSTANCE", hierarchy1, hierarchy1);
		assertRejected("DOUI_SERVICE", douiInstance, hierarchy1);
		assertRejected("REPOSITORY_SERVICE", media1, media2);
		assertRejected("DIFFERENT_SERVICES", hierarchy1, news1);
		assertRejected("NOT_HIERARCHICAL", news1, news2);

		Map<String, Object> created = create(hierarchy1, hierarchy2);
		assertEquals("STOPPED", created.get("status"));
		assertEquals("MIGRATE", created.get("operation"));
		assertEquals(Boolean.FALSE, created.get("stopRequested"));
		assertEquals("0", created.get("migratedCount"));
		assertEquals("1", created.get("totalCount"));
		assertEquals(PortalFixture.HIERARCHICAL_SERVICE_ID, created.get("serviceId"));

		assertRejected("DUPLICATE_PAIR", hierarchy1, hierarchy2);

		Map<String, Object> read = (Map<String, Object>) ModuleBridge.api("get", created.get("id"));
		assertEquals(created.get("id"), read.get("id"));
		Map<String, Object> list = (Map<String, Object>) ModuleBridge.api("list");
		Map<String, Object> items = (Map<String, Object>) list.get("items");
		assertTrue(items.values().stream().anyMatch(i -> created.get("id").equals(((Map<String, Object>) i).get("id"))));
	}

	/** Regression: create used to store any updateMode, and failed with ClassCastException on a non-string. */
	@Test
	public void createAcceptsOnlyTheSupportedUpdateModes() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		for (Object updateMode : new Object[]{"FOO", "", 1})
		{
			try
			{
				create(origin, dest, updateMode);
				fail("expected INVALID_UPDATE_MODE for updateMode " + updateMode);
			}
			catch (RuntimeException e)
			{
				assertEquals(e.toString(), "INVALID_UPDATE_MODE", ModuleBridge.errorCode(e));
			}
		}
		assertEquals("UPDATE", create(origin, dest, "UPDATE").get("updateMode"));
	}

	/** Regression: on MySQL the dates came as LocalDateTime text, on Oracle and SQL Server as milliseconds. */
	@Test
	public void migrationDatesAreEpochMilliseconds() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		long before = System.currentTimeMillis();

		Map<String, Object> created = create(origin, dest);
		String createdDate = (String) created.get("createdDate");
		assertTrue("createdDate " + createdDate, createdDate.matches("\\d+"));
		assertTrue("createdDate " + createdDate + " near " + before, Math.abs(Long.parseLong(createdDate) - before) < 60_000L);
		assertNull(created.get("startDate"));
		assertNull(created.get("endDate"));
		assertNull(created.get("heartbeat"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void saveWidgetMappingReplacesTheMappingOfTheSamePair() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig widgetRepository = fixture.createServiceInstance(channel, WidgetFixture.WIDGET_SERVICE_ID);
		ServiceInstanceConfig htmlInstance = fixture.createServiceInstance(channel, WidgetFixture.HTML_SERVICE_ID);
		String baseInterfaceInstance = widgets.addBaseInterfaceInstance(htmlInstance);
		String origin = widgets.addWidget(widgetRepository, "origin widget", baseInterfaceInstance);
		widgetOrigins.add(origin);
		Map<String, Object> params = new HashMap<>();
		params.put("originWidgetContentId", origin);
		params.put("destRepoInstanceId", widgetRepository.getId());
		String dest1 = widgets.addWidget(widgetRepository, "destination widget one", baseInterfaceInstance);
		String dest2 = widgets.addWidget(widgetRepository, "destination widget two", baseInterfaceInstance);
		params.put("destWidgetContentId", dest1);
		assertEquals(dest1, ((Map<String, Object>) ModuleBridge.api("saveWidgetMapping", params)).get("destWidgetContentId"));
		params.put("destWidgetContentId", dest2);
		assertEquals(dest2, ((Map<String, Object>) ModuleBridge.api("saveWidgetMapping", params)).get("destWidgetContentId"));
	}
}
