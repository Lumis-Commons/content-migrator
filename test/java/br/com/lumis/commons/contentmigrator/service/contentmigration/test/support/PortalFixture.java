package br.com.lumis.commons.contentmigrator.service.contentmigration.test.support;

import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;

import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.portal.PortalConfiguration;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.hibernate.ITransactionHibernate;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/**
 * Creates portal structure for the module tests and removes it at the end.
 *
 * <p>Modeled on {@code lumis.lptf.portal.structure.StructureHelper}, using only public portal
 * APIs. Unlike it, every create method runs in its own transaction, as the system user, and is
 * committed before returning, so the created data is visible to any code under test. Everything
 * created is deleted by {@link #cleanup()} in the inverse order of creation, unless the system
 * property {@value #KEEP_TEST_DATA} is {@code true}.
 *
 * <p>Typical use: one instance per test, created in {@code @Before} and cleaned in
 * {@code @After}.
 */
public class PortalFixture
{
	/** System property that keeps the created data in the database, for inspection. */
	public static final String KEEP_TEST_DATA = "contentmigrator.keepTestData";

	/** Generic content service whose items are rows of {@code lum_News}. */
	public static final String NEWS_SERVICE_ID = "lumis.service.news";

	/** Media repository service. */
	public static final String MEDIA_SERVICE_ID = "lumis.service.media";

	/** Dependency type of a content service on its media repository. */
	public static final String MEDIA_REPOSITORY_DEPENDENCY = "media_repository";

	static
	{
		// boots the portal in this JVM (lumis/portal/PortalConfiguration.properties)
		PortalConfiguration.getCurrentConfiguration();
	}

	/** Work done inside a fixture transaction. */
	@FunctionalInterface
	public interface Work<T>
	{
		T run(SessionConfig sessionConfig, ITransaction transaction) throws Exception;
	}

	private final Deque<Work<Void>> cleaners = new ArrayDeque<>();

	/** Prefix of the names of everything this fixture creates. */
	private final String prefix = "contentmigrator-test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

	/**
	 * Runs the given work in a new committed transaction, as the system user.
	 */
	public static <T> T inTransaction(Work<T> work) throws Exception
	{
		SessionConfig sessionConfig = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		try
		{
			ITransaction transaction = PortalTransactionFactory.createTransaction();
			try
			{
				transaction.begin();
				T result = work.run(sessionConfig, transaction);
				transaction.commit();
				return result;
			}
			finally
			{
				transaction.close();
			}
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(sessionConfig);
		}
	}

	/**
	 * Returns whether the given action throws {@link PortalObjectNotFoundException}.
	 */
	public static boolean isNotFound(Work<?> lookup) throws Exception
	{
		try
		{
			inTransaction(lookup);
			return false;
		}
		catch (PortalObjectNotFoundException e)
		{
			return true;
		}
	}

	/**
	 * Creates a channel under the root channel.
	 */
	public ChannelConfig createChannel() throws Exception
	{
		return createChannel(ChannelConfig.PORTAL_CHANNEL_ID);
	}

	/**
	 * Creates a channel under the given parent channel.
	 */
	public ChannelConfig createChannel(String parentChannelId) throws Exception
	{
		ChannelConfig channel = new ChannelConfig();
		channel.setName(prefix + "-channel-" + cleaners.size());
		channel.setParentId(parentChannelId);
		inTransaction((session, transaction) ->
		{
			ManagerFactory.getChannelManager().add(session, channel, transaction);
			return null;
		});
		cleaners.push((session, transaction) ->
		{
			ManagerFactory.getChannelManager().delete(session, channel.getId(), transaction);
			return null;
		});
		return channel;
	}

	/**
	 * Creates a service instance of the given service in the given channel.
	 */
	public ServiceInstanceConfig createServiceInstance(ChannelConfig channel, String serviceId) throws Exception
	{
		ServiceInstanceConfig serviceInstance = new ServiceInstanceConfig();
		serviceInstance.setName(prefix + "-" + serviceId.substring(serviceId.lastIndexOf('.') + 1));
		serviceInstance.setServiceId(serviceId);
		serviceInstance.setChannelId(channel.getId());
		inTransaction((session, transaction) ->
		{
			ManagerFactory.getServiceInstanceManager().add(session, serviceInstance, transaction);
			return null;
		});
		cleaners.push((session, transaction) ->
		{
			ManagerFactory.getServiceInstanceManager().delete(session, serviceInstance.getId(), true, transaction);
			return null;
		});
		return serviceInstance;
	}

	/**
	 * Makes {@code dependent} depend on {@code serviceInstance} with the given dependency type,
	 * replacing the dependency of that type the portal may have created automatically when the
	 * dependent instance was added.
	 */
	public ServiceInstanceDependencyConfig setDependency(ServiceInstanceConfig serviceInstance, ServiceInstanceConfig dependent, String dependencyType) throws Exception
	{
		ServiceInstanceDependencyConfig dependency = new ServiceInstanceDependencyConfig();
		dependency.setId(PortalUtil.generateNewGuid());
		dependency.setServiceInstanceId(serviceInstance.getId());
		dependency.setDependentServiceInstanceId(dependent.getId());
		dependency.setDependencyType(dependencyType);
		inTransaction((session, transaction) ->
		{
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(dependency, transaction);
			return null;
		});
		cleaners.push((session, transaction) ->
		{
			// without a service instance id, the dependency of that type is removed
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(dependent.getId());
			removal.setDependencyType(dependencyType);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
		return dependency;
	}

	/**
	 * Adds a published pt_BR content to a {@value #NEWS_SERVICE_ID} instance, with its
	 * {@code lum_News} row.
	 *
	 * @return the content version created.
	 */
	public ContentVersion addNewsContent(ServiceInstanceConfig newsInstance) throws Exception
	{
		ContentVersion version = inTransaction((session, transaction) ->
		{
			Content content = new Content();
			content.setServiceInstanceId(newsInstance.getId());
			content.setSourceId("default");
			ContentLocale contentLocale = content.addContentLocale(new Locale("pt", "BR"));
			ContentVersion contentVersion = contentLocale.addVersion();
			String itemId = PortalUtil.generateNewGuid();
			contentVersion.setItemId(itemId);
			contentVersion.setPrimaryName(prefix + "-news-" + itemId);
			contentLocale.setPublishedVersion(contentVersion);
			ContentManagerFactory.getContentManager().addContent(content);

			try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection()
					.prepareStatement("insert into lum_News (id, title, content) values (?,?,?)"))
			{
				statement.setString(1, itemId);
				statement.setString(2, prefix + "-title-" + itemId);
				statement.setString(3, prefix + "-body-" + itemId);
				statement.executeUpdate();
			}
			return contentVersion;
		});

		String contentId = version.getContentLocale().getContent().getId();
		String itemId = version.getItemId();
		cleaners.push((session, transaction) ->
		{
			try
			{
				ContentManagerFactory.getContentManager().deleteContent(ContentManagerFactory.getContentManager().getContent(contentId));
			}
			catch (PortalObjectNotFoundException e)
			{
				// already removed with its service instance
			}
			try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection().prepareStatement("delete from lum_News where id = ?"))
			{
				statement.setString(1, itemId);
				statement.executeUpdate();
			}
			return null;
		});
		return version;
	}

	/**
	 * Returns whether {@link #cleanup()} keeps the created data.
	 */
	public static boolean keepTestData()
	{
		return Boolean.getBoolean(KEEP_TEST_DATA);
	}

	/**
	 * Deletes everything this fixture created, in the inverse order of creation, each step in its
	 * own transaction. A failing step is reported and does not stop the following ones.
	 *
	 * @return the number of steps that failed.
	 */
	public int cleanup()
	{
		if (keepTestData())
		{
			System.out.println("[PortalFixture] " + KEEP_TEST_DATA + "=true: keeping " + cleaners.size() + " objects named " + prefix + "*");
			cleaners.clear();
			return 0;
		}

		int failures = 0;
		while (!cleaners.isEmpty())
		{
			Work<Void> cleaner = cleaners.pop();
			try
			{
				inTransaction(cleaner);
			}
			catch (Exception e)
			{
				failures++;
				System.err.println("[PortalFixture] cleanup step failed: " + e);
				e.printStackTrace();
			}
		}
		return failures;
	}
}
