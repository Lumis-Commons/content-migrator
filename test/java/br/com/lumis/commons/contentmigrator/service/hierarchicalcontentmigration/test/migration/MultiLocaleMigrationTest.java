package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
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
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;

/** Parent mapping follows each version, with the discovery parent as fallback across locales. */
public class MultiLocaleMigrationTest
{
	private static final String AWAIT_MILLIS = "120000";

	private PortalFixture fixture;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig destination;

	private String p1;

	private String p2;

	private String child;

	private String migrationId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		ChannelConfig channel = fixture.createChannel();
		origin = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		p1 = fixture.addHierarchicalContent(origin, "P1", null, true);
		p2 = fixture.addHierarchicalContent(origin, "P2", null, true);
		child = fixture.addHierarchicalContent(origin, "C", p1, true);
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
				ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
				if (!PortalFixture.keepTestData())
				{
					Map<String, Object> undo = new HashMap<>();
					undo.put("migrationId", migrationId);
					undo.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
					ModuleBridge.api("undo", undo);
					Map<String, Object> result = map(ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS));
					assertEquals("Undo error: " + result.get("errorMessage"), "FINISHED", result.get("status"));
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
	public void translationFallsBackToReferenceParentAndCountsAsAnotherItem() throws Exception
	{
		fixture.addHierarchicalTranslation(origin, child, p2);
		fixture.publishHierarchicalVersion(child, new java.util.Locale("pt", "BR"));
		fixture.publishHierarchicalVersion(child, java.util.Locale.US);
		assertSelectionCounts(p1, 2, 3);
		migrate(p1, 3);
		assertNull(mappedContent(p2));
		assertParents("pt_BR", mappedContent(p1), mappedContent(p1));
		assertParents("en_US", mappedContent(p1), mappedContent(p1));
		assertWarning("en_US", p2, p1);
		assertAllChildVersionsHaveParents(2);
		// The same resolution applies when existing destination locales are updated.
		Map<String, Object> update = new HashMap<>();
		update.put("migrationId", migrationId);
		update.put("updateMode", "UPDATE");
		update.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		ModuleBridge.api("start", update);
		assertFinished(3);
		assertParents("en_US", mappedContent(p1), mappedContent(p1));
		assertAllChildVersionsHaveParents(2);
	}

	@Test
	public void publishedAndDraftKeepTheirOwnMappedParents() throws Exception
	{
		fixture.addHierarchicalDraft(origin, child, p2);
		assertSelectionCounts(p1 + "," + p2, 3, 3);
		migrate(p1 + "," + p2, 3);
		assertParents("pt_BR", mappedContent(p1), mappedContent(p2));
		assertFalse(warnings("pt_BR").contains("reference parent"));
		assertAllChildVersionsHaveParents(2);
	}

	@Test
	public void publishedFallsBackToDraftReferenceParentWhenOnlyP2IsMarked() throws Exception
	{
		fixture.addHierarchicalDraft(origin, child, p2);
		migrate(p2, 2);
		assertNull(mappedContent(p1));
		assertParents("pt_BR", mappedContent(p2), mappedContent(p2));
		assertWarning("pt_BR", p1, p2);
		assertAllChildVersionsHaveParents(2);
	}

	private void assertSelectionCounts(String marks, int contents, int locales) throws Exception
	{
		Map<String, Object> counts = PortalFixture.inTransaction((session, transaction) -> map(ModuleBridge.invokeStatic(
				"br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.HierarchyBrowser", "resolveSelection",
				origin.getId(), Arrays.asList(marks.split(",")), transaction)));
		assertEquals(contents, counts.get("contentCount"));
		assertEquals(locales, counts.get("localeCount"));
	}

	private void migrate(String marks, int count) throws Exception
	{
		Map<String, Object> params = new HashMap<>();
		params.put("name", "contentmigrator-multi-locale");
		params.put("originServiceInstanceId", origin.getId());
		params.put("destServiceInstanceId", destination.getId());
		params.put("selectedContentIds", marks);
		Map<String, Object> created = map(ModuleBridge.api("create", params));
		migrationId = (String) created.get("id");
		assertEquals(String.valueOf(count), created.get("totalCount"));
		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		ModuleBridge.api("start", start);
		assertFinished(count);
	}

	private void assertFinished(int count) throws Exception
	{
		Map<String, Object> finished = map(ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS));
		assertEquals("Migration error: " + finished.get("errorMessage"), "FINISHED", finished.get("status"));
		assertEquals(String.valueOf(count), finished.get("totalCount"));
		assertEquals(String.valueOf(count), finished.get("migratedCount"));
	}

	private String mappedContent(String contentId) throws Exception
	{
		return query("select destContentId from lum_cmig_HMigItemMap where originServiceInstanceId = ? and originContentId = ? and kind = 'CONTENT'",
				origin.getId(), contentId);
	}

	private void assertParents(String locale, String published, String active) throws Exception
	{
		assertNotNull("reference parent was migrated", active);
		String base = " from lum_cmig_HMigItemMap m inner join lum_ContentLocale cl on cl.id = m.destContentLocaleId "
				+ "inner join lum_ContentVersion cv on cv.id = cl.%s "
				+ "inner join lum_HierarchicalContent h on h.id = cv.itemId "
				+ "where m.originServiceInstanceId = ? and m.originContentId = ? and cl.locale = ? and m.kind = 'CONTENT'";
		assertEquals(published, query("select h.parentContentId" + String.format(base, "publishedVersionId"), origin.getId(), child, locale));
		assertEquals(active, query("select h.parentContentId" + String.format(base, "activeVersionId"), origin.getId(), child, locale));
	}

	private String warnings(String locale) throws Exception
	{
		String result = query("select m.warnings from lum_cmig_HMigItemMap m inner join lum_ContentLocale cl on cl.id = m.destContentLocaleId "
				+ "where m.originServiceInstanceId = ? and m.originContentId = ? and cl.locale = ? and m.kind = 'CONTENT'", origin.getId(), child, locale);
		return result == null ? "" : result;
	}

	private void assertWarning(String locale, String ownParent, String referenceParent) throws Exception
	{
		String warning = warnings(locale);
		assertTrue(warning, warning.contains(ownParent));
		assertTrue(warning, warning.contains("used reference parent " + referenceParent));
	}

	private void assertAllChildVersionsHaveParents(int minimumVersions) throws Exception
	{
		String base = " from lum_ContentVersion cv inner join lum_ContentLocale cl on cl.id = cv.contentLocaleId "
				+ "inner join lum_HierarchicalContent h on h.id = cv.itemId where cl.contentId = ?";
		assertTrue(Integer.parseInt(query("select count(*)" + base, mappedContent(child))) >= minimumVersions);
		assertEquals("no version may lose its parent when the reference parent was migrated", "0",
				query("select count(*)" + base + " and h.parentContentId is null", mappedContent(child)));
	}

	private static String query(String sql, String... values) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			try (PreparedStatement statement = ((ITransactionJdbc) transaction).getConnection().prepareStatement(sql))
			{
				for (int i = 0; i < values.length; i++)
				{
					statement.setString(i + 1, values[i]);
				}
				try (ResultSet rows = statement.executeQuery())
				{
					return rows.next() ? rows.getString(1) : null;
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
