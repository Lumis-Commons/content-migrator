package br.com.lumis.commons.contentmigrator.service.contentmigration.test.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentTag;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.user.UserConfig;

/** Proves that tags are recreated by name in the destination tag repository. */
public class TagMigrationTest
{
	private static final String TAG_SERVICE_ID = "lumis.service.tag";

	private static final String TAG_REPOSITORY = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_TAG_REPOSITORY;

	private static final String AWAIT_MILLIS = "120000";

	private static final String TAG_NAME = "migration-tag-by-name";

	private PortalFixture fixture;

	private ChannelConfig channel;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig dest;

	private ServiceInstanceConfig originTagRepository;

	private ServiceInstanceConfig destTagRepository;

	private String originItemId;

	private String migrationId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		channel = fixture.createChannel();
		originTagRepository = fixture.createServiceInstance(channel, TAG_SERVICE_ID);
		destTagRepository = fixture.createServiceInstance(channel, TAG_SERVICE_ID);
		origin = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		dest = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		fixture.setDependency(originTagRepository, origin, TAG_REPOSITORY);
		fixture.setDependency(destTagRepository, dest, TAG_REPOSITORY);
		originItemId = fixture.addNewsContent(origin).getItemId();
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId).getContentLocale();
			assertEquals(true, originLocale.addTag(TAG_NAME));
			return null;
		});
	}

	@After
	public void tearDown() throws Exception
	{
		if (migrationId != null)
		{
			ModuleBridge.api("stop", migrationId);
			ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
		}
		if (!PortalFixture.keepTestData())
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				Connection connection = ((ITransactionJdbc) transaction).getConnection();
				try (PreparedStatement statement = connection
						.prepareStatement("select destItemId from lum_cmig_MigItemMap where originServiceInstanceId = ? " + "and destServiceInstanceId = ?"))
				{
					statement.setString(1, origin.getId());
					statement.setString(2, dest.getId());
					try (java.sql.ResultSet rs = statement.executeQuery())
					{
						List<String> ids = new ArrayList<>();
						while (rs.next())
						{
							ids.add(rs.getString(1));
						}
						if (!ids.isEmpty())
						{
							((lumis.content.table.ContentTableSource) lumis.doui.source.SourceFactory
									.createSource("default", new lumis.doui.source.SourceContext(session, dest, transaction))).deleteDataByItemIds(ids);
						}
					}
				}
				try (PreparedStatement statement = connection
						.prepareStatement("delete from lum_cmig_MigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ?"))
				{
					statement.setString(1, origin.getId());
					statement.setString(2, dest.getId());
					statement.executeUpdate();
				}
				if (migrationId != null)
				{
					try (PreparedStatement statement = connection.prepareStatement("delete from lum_cmig_Migration where id = ?"))
					{
						statement.setString(1, migrationId);
						statement.executeUpdate();
					}
				}
				try (PreparedStatement statement = connection.prepareStatement("update lum_cmig_MigLock set runId = null, migrationId = null, heartbeat = null"))
				{
					statement.executeUpdate();
				}
				return null;
			});
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void copiesTagsByNameIntoDestinationRepository() throws Exception
	{
		Map<String, Object> create = new HashMap<>();
		create.put("name", "contentmigrator-tag-migration");
		create.put("originServiceInstanceId", origin.getId());
		create.put("destServiceInstanceId", dest.getId());
		Map<String, Object> migration = (Map<String, Object>) ModuleBridge.api("create", create);
		migrationId = (String) migration.get("id");

		Map<String, Object> start = new HashMap<>();
		start.put("migrationId", migrationId);
		start.put("userId", UserConfig.USER_ADMINISTRATOR_ID);
		ModuleBridge.api("start", start);
		Map<String, Object> result = (Map<String, Object>) ModuleBridge.api("awaitTermination", migrationId, AWAIT_MILLIS);
		assertEquals("FINISHED", result.get("status"));

		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			String localeId;
			try (PreparedStatement statement = connection.prepareStatement(
					"select destContentLocaleId from lum_cmig_MigItemMap where originServiceInstanceId = ? "
							+ "and destServiceInstanceId = ? and originContentLocaleId = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, dest.getId());
				statement.setString(3, ContentManagerFactory.getContentManager().getContentVersionByItemId(originItemId).getContentLocale().getId());
				try (java.sql.ResultSet rs = statement.executeQuery())
				{
					assertEquals(true, rs.next());
					localeId = rs.getString(1);
				}
			}
			ContentLocale destLocale = ContentManagerFactory.getContentManager().getContentLocale(localeId);
			List<ContentTag> tags = new ArrayList<>(destLocale.getTags());
			assertEquals(1, tags.size());
			assertEquals(TAG_NAME, tags.get(0).getName());
			assertNotEquals(
					"tag content is created in destination repository",
					originTagRepository.getId(),
					tags.get(0).getContentVersion().getContentLocale().getContent().getServiceInstanceId());
			assertEquals(destTagRepository.getId(), tags.get(0).getContentVersion().getContentLocale().getContent().getServiceInstanceId());
			return null;
		});
	}
}
