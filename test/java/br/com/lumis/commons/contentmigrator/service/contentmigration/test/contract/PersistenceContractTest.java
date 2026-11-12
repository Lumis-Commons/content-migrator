package br.com.lumis.commons.contentmigrator.service.contentmigration.test.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
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

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.WidgetFixture;
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
	private static final String SETUP = "src/br/com/lumis/commons/contentmigrator/service/contentmigration/componentfiles/setup/";

	private static final String DOUI = "src/br/com/lumis/commons/contentmigrator/service/contentmigration/douidefinition.xml";

	private static final String[] TABLES = {"lum_cmig_Migration", "lum_cmig_MigItemMap", "lum_cmig_MigRepoMap", "lum_cmig_MigWidgetMap", "lum_cmig_MigLock"};

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
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			for (String id : migrationIds)
			{
				delete(connection, "lum_cmig_Migration", "id", id);
			}
			for (String id : itemMapIds)
			{
				delete(connection, "lum_cmig_MigItemMap", "id", id);
			}
			for (String id : widgetOrigins)
			{
				delete(connection, "lum_cmig_MigWidgetMap", "originWidgetContentId", id);
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

	@Test
	public void douiSourcesAreTableSourcesWithoutContentMetadata() throws Exception
	{
		String doui = read(DOUI);
		Set<String> sourceTypes = matches("<source id=\"[^\"]+\" type=\"([^\"]+)\"", doui);
		assertEquals("source types", new LinkedHashSet<>(java.util.Arrays.asList("table")), sourceTypes);
		assertEquals(TABLES.length, matches("<table>([^<]+)</table>", doui).size());
		for (String table : TABLES)
		{
			assertTrue(table, doui.contains("<table>" + table + "</table>"));
		}
		assertFalse(doui.contains("contentTable"));
		assertFalse(doui.contains("<metaData>"));
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
		return params;
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
		ServiceInstanceConfig link = fixture.createServiceInstance(channel, "lumis.service.link");
		ServiceInstanceConfig media1 = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig media2 = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig douiInstance = fixture.createServiceInstance(channel, "br.com.lumis.commons.contentmigrator.service.contentmigration");

		assertRejected("SAME_INSTANCE", news1, news1);
		assertRejected("DOUI_SERVICE", douiInstance, news1);
		assertRejected("REPOSITORY_SERVICE", media1, media2);
		assertRejected("DIFFERENT_SERVICES", news1, link);

		Map<String, Object> created = create(news1, news2);
		assertEquals("STOPPED", created.get("status"));
		assertEquals("MIGRATE", created.get("operation"));
		assertEquals(Boolean.FALSE, created.get("stopRequested"));
		assertEquals("0", created.get("migratedCount"));
		assertEquals(news1.getServiceId(), created.get("serviceId"));

		assertRejected("DUPLICATE_PAIR", news1, news2);

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
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
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
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
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
