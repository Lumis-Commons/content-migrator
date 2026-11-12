package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import lumis.content.ContentManagerFactory;
import lumis.content.core.Content;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
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

	/** The only service the module migrates. */
	public static final String HIERARCHICAL_SERVICE_ID = "lumis.service.hierarchicalcontent";

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
	 * Adds a pt_BR content to a {@value #HIERARCHICAL_SERVICE_ID} instance through its source, so it has
	 * the standard workflow, and removes it again in {@link #cleanup()}.
	 *
	 * @param parentContentId
	 *            the id of the parent content, or null for a content without a parent.
	 * @param published
	 *            false leaves the content without a published version, as an archived one is.
	 * @return the id of the content created.
	 */
	public String addHierarchicalContent(ServiceInstanceConfig instance, String title, String parentContentId, boolean published) throws Exception
	{
		String itemId = inTransaction((session, transaction) ->
		{
			session.setLocale(new Locale("pt", "BR"));
			Map<String, Object> data = new HashMap<>();
			data.put("title", prefix + "-" + title);
			data.put("introduction", "introduction " + title);
			data.put("type", 4);
			data.put("position", 0);
			data.put("hidden", false);
			data.put("parentContentId", parentContentId);
			ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
			return source.addData(data).getItemId();
		});
		String contentId = inTransaction(
				(session, transaction) -> ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId).getContentLocale().getContent().getId());
		if (!published)
		{
			inTransaction((session, transaction) ->
			{
				try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection()
						.prepareStatement("update lum_ContentLocale set publishedVersionId = null where contentId = ?"))
				{
					statement.setString(1, contentId);
					statement.executeUpdate();
				}
				return null;
			});
		}
		cleaners.push((session, transaction) ->
		{
			ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
			source.deleteDataByItemIds(Collections.singleton(itemId));
			return null;
		});
		return contentId;
	}

	/** Changes the active parent through the source, including hierarchy validation. */
	public void updateHierarchicalParent(ServiceInstanceConfig instance, String contentId, String parentId) throws Exception
	{
		inTransaction((session, transaction) ->
		{
			session.setLocale(new Locale("pt", "BR"));
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(contentId).getContentLocale(new Locale("pt", "BR"), false);
			ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
			Map<String, Object> data = new HashMap<>();
			data.put(source.getPrimaryKeyField().getId(), locale.getActiveVersion().getItemId());
			data.put("parentContentId", parentId);
			source.updateData(data);
			return null;
		});
	}

	/** Sets version parents directly to simulate corrupt cyclic data that the source rejects. */
	public void forceHierarchicalParent(String contentId, String parentId) throws Exception
	{
		inTransaction((session, transaction) ->
		{
			String sql = "update lum_HierarchicalContent set parentContentId = ? where id in "
					+ "(select cv.itemId from lum_ContentVersion cv inner join lum_ContentLocale cl on cl.id = cv.contentLocaleId where cl.contentId = ?)";
			try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection().prepareStatement(sql))
			{
				statement.setString(1, parentId);
				statement.setString(2, contentId);
				statement.executeUpdate();
			}
			return null;
		});
	}

	/** Adds an en_US locale before a later-created parent to exercise translation ordering. */
	public void addHierarchicalTranslation(ServiceInstanceConfig instance, String contentId) throws Exception
	{
		addHierarchicalTranslation(instance, contentId, null);
	}

	/** Adds an en_US translation under its own parent. */
	public void addHierarchicalTranslation(ServiceInstanceConfig instance, String contentId, String parentContentId) throws Exception
	{
		String itemId = inTransaction((session, transaction) ->
		{
			session.setLocale(Locale.US);
			ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
			Map<String, Object> data = new HashMap<>();
			data.put(source.getField(lumis.content.source.field.MetaDataSourceField.Type.ContentId).getId(), contentId);
			data.put("title", prefix + "-translation");
			data.put("parentContentId", parentContentId);
			data.put("type", 4);
			data.put("position", 0);
			data.put("hidden", false);
			return source.addData(data).getItemId();
		});
		cleaners.push((session, transaction) ->
		{
			ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
			source.deleteDataByItemIds(Collections.singleton(itemId));
			return null;
		});
	}

	/** Publishes the active fixture version without advancing the workflow. */
	public void publishHierarchicalVersion(String contentId, Locale language) throws Exception
	{
		inTransaction((session, transaction) ->
		{
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(contentId).getContentLocale(language, false);
			locale.setPublishedVersion(locale.getActiveVersion());
			return null;
		});
	}

	/** Creates a new active version under another parent, preserving the published version. */
	public void addHierarchicalDraft(ServiceInstanceConfig instance, String contentId, String parentContentId) throws Exception
	{
		inTransaction((session, transaction) ->
		{
			session.setLocale(new Locale("pt", "BR"));
			ContentLocale locale = ContentManagerFactory.getContentManager().getContent(contentId).getContentLocale(new Locale("pt", "BR"), false);
			ContentVersion published = locale.getActiveVersion();
			locale.setPublishedVersion(published);
			ContentTableSource source = (ContentTableSource) SourceFactory.createSource("default", new SourceContext(session, instance, transaction));
			Map<String, Object> data = new HashMap<>();
			data.put(source.getPrimaryKeyField().getId(), locale.getActiveVersion().getItemId());
			data.put("parentContentId", parentContentId);
			source.updateData(data);
			if (published.getItemId().equals(locale.getActiveVersion().getItemId()))
			{
				throw new IllegalStateException("Expected a separate draft version");
			}
			locale.setPublishedVersion(published);
			return null;
		});
	}

	/** Waits for the global migration lock without changing another run's ownership. */
	public static void awaitGlobalLockFree() throws Exception
	{
		String lock = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.lock.GlobalLock";
		long timeoutMillis = (ModuleBridge.load(lock).getField("STALE_MINUTES").getInt(null) + 1) * 60_000L;
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		boolean announced = false;
		while (true)
		{
			boolean free = inTransaction((session, transaction) ->
			{
				try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection()
						.prepareStatement("select runId from lum_cmig_HMigLock where id = 'global'");
						java.sql.ResultSet rows = statement.executeQuery())
				{
					return !rows.next() || rows.getString(1) == null;
				}
			});
			if (free)
			{
				return;
			}
			if (System.nanoTime() >= deadline)
			{
				throw new AssertionError("Global migration lock remained held for " + timeoutMillis + " ms");
			}
			if (!announced)
			{
				System.out.println("PROGRESS waiting for the global migration lock to be released");
				announced = true;
			}
			Thread.sleep(5_000L);
		}
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
