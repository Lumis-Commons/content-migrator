package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.page.PageConfig;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;

/** Exercises each link policy against persisted destination rows and item-map warnings. */
public class ContentTypesMigrationTest
{
	private PortalFixture fixture;
	private ChannelConfig channel;
	private ServiceInstanceConfig origin;
	private ServiceInstanceConfig destination;
	private String migrationId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		channel = fixture.createChannel();
		origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		PortalFixture.awaitGlobalLockFree();
	}

	@After
	public void tearDown() throws Exception
	{
		try
		{
			if (migrationId != null)
			{
				ModuleBridge.api("stop", migrationId);
				ModuleBridge.api("awaitTermination", migrationId, "120000");
				if (!PortalFixture.keepTestData())
				{
					Map<String, Object> params = runParams();
					ModuleBridge.api("undo", params);
					assertEquals("FINISHED", map(ModuleBridge.api("awaitTermination", migrationId, "120000")).get("status"));
					ModuleBridge.api("delete", migrationId);
				}
			}
		}
		finally
		{
			TestCleanup.assertNoFailures(fixture::cleanup);
		}
	}

	@Test
	public void typeTwoKeepsExistingPageIdAsString() throws Exception
	{
		String pageId = PortalFixture.inTransaction((session, transaction) ->
		{
			PageConfig page = new PageConfig();
			page.setName("link policy page");
			page.setChannelId(channel.getId());
			return ManagerFactory.getPageManager().add(session, page, transaction);
		});
		assertLink(2, "linkPageId", pageId, pageId, false);
	}

	@Test
	public void typeTwoClearsDeletedPageAndWarns() throws Exception
	{
		assertLink(2, "linkPageId", "00000000000000000000000000000000", null, true);
	}

	@Test
	public void typeFiveKeepsExistingChannelIdAsString() throws Exception
	{
		assertLink(5, "linkChannelId", channel.getId(), channel.getId(), false);
	}

	@Test
	public void typeFiveClearsDeletedChannelAndWarns() throws Exception
	{
		assertLink(5, "linkChannelId", "00000000000000000000000000000000", null, true);
	}

	@Test
	public void typeSixRemapsContentAlreadyCopiedInSelection() throws Exception
	{
		String target = fixture.addHierarchicalContent(origin, "target", null, true);
		String link = fixture.addHierarchicalContent(origin, "link", target, true);
		setField(link, 6, "linkContentId", target);
		migrate(target);
		assertEquals(result(target, "destContentId"), result(link, "linkContentId"));
		assertWarning(link, false);
	}

	@Test
	public void typeSixKeepsForwardReferenceWithWarningThenRemapsOnUpdate() throws Exception
	{
		String link = fixture.addHierarchicalContent(origin, "forward link", null, true);
		String target = fixture.addHierarchicalContent(origin, "later target", link, true);
		setField(link, 6, "linkContentId", target);
		migrate(link);
		assertEquals(target, result(link, "linkContentId"));
		assertWarning(link, true);
		run();
		assertEquals(result(target, "destContentId"), result(link, "linkContentId"));
		assertWarning(link, false);
	}

	@Test
	public void typeSixKeepsContentOfAnotherInstance() throws Exception
	{
		ServiceInstanceConfig other = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		String target = fixture.addHierarchicalContent(other, "external target", null, true);
		assertLink(6, "linkContentId", target, target, false);
	}

	@Test
	public void typeSixClearsMissingContentAndWarns() throws Exception
	{
		assertLink(6, "linkContentId", "00000000000000000000000000000000", null, true);
	}

	@Test
	public void pageLayoutPresentInDestinationIsKept() throws Exception
	{
		setLayouts("[{\"accepted\":{}}]");
		assertLink(0, "pageLayoutId", "accepted", "accepted", false);
	}

	@Test
	public void pageLayoutOnlyInOriginIsClearedWithWarning() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ManagerFactory.getServiceInstanceManager().setCustomProperty(session, origin.getId(), "pageLayouts", "[{\"originOnly\":{}}]", transaction);
			return null;
		});
		setLayouts("[{\"accepted\":{}}]");
		assertLink(0, "pageLayoutId", "originOnly", null, true);
	}

	@Test
	public void pageLayoutWithoutDestinationConfigurationIsClearedWithWarning() throws Exception
	{
		assertLink(0, "pageLayoutId", "missing", null, true);
	}

	@Test
	public void websiteComparisonUsesWebsiteIdentity() throws Exception
	{
		String policy = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.copy.LinkFieldPolicy";
		assertFalse((Boolean) ModuleBridge.invokeStatic(policy, "isDifferentWebsite", "destination", "destination"));
		assertTrue((Boolean) ModuleBridge.invokeStatic(policy, "isDifferentWebsite", "other", "destination"));
		assertTrue((Boolean) ModuleBridge.invokeStatic(policy, "isDifferentWebsite", null, "destination"));
		assertFalse((Boolean) ModuleBridge.invokeStatic(policy, "isDifferentWebsite", null, null));
	}

	private void setLayouts(String layouts) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ManagerFactory.getServiceInstanceManager().setCustomProperty(session, destination.getId(), "pageLayouts", layouts, transaction);
			return null;
		});
	}

	private void assertLink(int type, String field, String sourceId, String expected, boolean warning) throws Exception
	{
		String link = fixture.addHierarchicalContent(origin, "link", null, true);
		setField(link, type, field, sourceId);
		migrate(link);
		assertEquals(expected, result(link, field));
		assertEquals(Integer.toString(type), result(link, "type"));
		assertWarning(link, warning);
	}

	private void setField(String contentId, int type, String field, String value) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			String sql = "update lum_HierarchicalContent set type = ?, " + field + " = ? where id in "
					+ "(select cv.itemId from lum_ContentVersion cv inner join lum_ContentLocale cl on cl.id = cv.contentLocaleId where cl.contentId = ?)";
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(sql))
			{
				statement.setInt(1, type);
				statement.setString(2, value);
				statement.setString(3, contentId);
				statement.executeUpdate();
			}
			return null;
		});
	}

	private void migrate(String selection) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "content types policy");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", selection);
		params.put("updateMode", "UPDATE");
		migrationId = (String) map(ModuleBridge.api("create", params)).get("id");
		run();
	}

	private void run() throws Exception
	{
		ModuleBridge.api("start", runParams());
		Map<String, Object> finished = map(ModuleBridge.api("awaitTermination", migrationId, "120000"));
		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
	}

	private Map<String, Object> runParams()
	{
		Map<String, Object> params = new HashMap<>();
		params.put("migrationId", migrationId);
		params.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		return params;
	}

	private void assertWarning(String contentId, boolean expected) throws Exception
	{
		String warning = result(contentId, "warnings");
		if (expected)
		{
			assertTrue("warning must be persisted on item map", warning != null && !warning.isEmpty());
		}
		else
		{
			assertTrue("unexpected warning: " + warning, warning == null || warning.isEmpty());
		}
	}

	private String result(String contentId, String field) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			String alias = "warnings".equals(field) || "destContentId".equals(field) ? "m" : "h";
			String sql = "select " + alias + "." + field + " from lum_cmig_HMigItemMap m "
					+ "inner join lum_HierarchicalContent h on h.id = m.destItemId "
					+ "where m.originServiceInstanceId = ? and m.originContentId = ? and m.kind = 'CONTENT'";
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(sql))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, contentId);
				try (ResultSet result = statement.executeQuery())
				{
					assertTrue("destination item must exist", result.next());
					return result.getString(1);
				}
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value)
	{
		return (Map<String, Object>) value;
	}
}
