package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collection;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentVersion;
import lumis.portal.PortalConfiguration;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;

/**
 * Proves the test harness: the portal boots in the test JVM over portal_install/lumisdata, and
 * {@link PortalFixture} creates a channel with service instances, a dependency and a content, and
 * removes all of it.
 */
public class HarnessSmokeTest
{
	private PortalFixture fixture;

	@Before
	public void setUp()
	{
		fixture = new PortalFixture();
	}

	@After
	public void tearDown()
	{
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void portalBootsOverPortalInstall()
	{
		PortalConfiguration configuration = PortalConfiguration.getCurrentConfiguration();
		assertNotNull(configuration);
		String lumisDataPath = configuration.getLumisDataPath().replace('\\', '/');
		assertTrue(lumisDataPath, lumisDataPath.endsWith("/portal_install/lumisdata"));
	}

	@Test
	public void fixtureCreatesAndRemovesChannelWithInstanceAndDependency() throws Exception
	{
		ChannelConfig channel = fixture.createChannel();
		ServiceInstanceConfig media = fixture.createServiceInstance(channel, PortalFixture.MEDIA_SERVICE_ID);
		ServiceInstanceConfig news = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceDependencyConfig dependency = fixture.setDependency(media, news, PortalFixture.MEDIA_REPOSITORY_DEPENDENCY);
		ContentVersion version = fixture.addNewsContent(news);
		String contentId = version.getContentLocale().getContent().getId();

		PortalFixture.inTransaction((session, transaction) ->
		{
			assertEquals(channel.getName(), ManagerFactory.getChannelManager().get(session, channel.getId(), transaction).getName());
			assertEquals(channel.getId(), ManagerFactory.getServiceInstanceManager().get(news.getId(), transaction).getChannelId());
			Collection<ServiceInstanceConfig> repositories = ManagerFactory.getServiceInstanceManager()
					.getServiceInstancesByDependency(session, news.getId(), PortalFixture.MEDIA_REPOSITORY_DEPENDENCY, transaction);
			assertTrue("dependency " + dependency.getId() + " not found", repositories.stream().anyMatch(si -> si.getId().equals(media.getId())));
			assertEquals(news.getId(), ContentManagerFactory.getContentManager().getContent(contentId).getServiceInstanceId());
			return null;
		});

		Assume.assumeFalse("keeping test data", PortalFixture.keepTestData());
		assertEquals("cleanup failures", 0, fixture.cleanup());

		assertTrue(
				"channel not removed",
				PortalFixture.isNotFound((session, transaction) -> ManagerFactory.getChannelManager().get(session, channel.getId(), transaction)));
		assertTrue(
				"news instance not removed",
				PortalFixture.isNotFound((session, transaction) -> ManagerFactory.getServiceInstanceManager().get(news.getId(), transaction)));
		assertTrue(
				"media instance not removed",
				PortalFixture.isNotFound((session, transaction) -> ManagerFactory.getServiceInstanceManager().get(media.getId(), transaction)));
		assertTrue("content not removed", PortalFixture.isNotFound((session, transaction) -> ContentManagerFactory.getContentManager().getContent(contentId)));
	}
}
