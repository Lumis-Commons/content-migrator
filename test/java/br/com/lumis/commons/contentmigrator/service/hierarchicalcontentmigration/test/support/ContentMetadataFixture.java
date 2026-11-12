package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture.Work;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.content.table.ContentTableSource;
import lumis.content.workflow.ContentWorkflowMetaData;
import lumis.content.workflow.WorkflowConfig;
import lumis.content.workflow.WorkflowPermission;
import lumis.content.workflow.WorkflowController;
import lumis.doui.source.ISourceData;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;
import lumis.portal.user.UserConfig;

/**
 * Creates {@value PortalFixture#NEWS_SERVICE_ID} instances with the standard workflow, users with
 * workflow roles and news contents through the {@code default} content table source, and removes
 * them at the end.
 *
 * <p>Contents are written as the given user, through
 * {@link #asUser(String, Work)}; workflow transitions of the origin are executed natively, with
 * the {@link WorkflowController} parameters. Contents and users are removed by {@link #cleanup()}
 * in the inverse order of creation, as the system user; call it before
 * {@link PortalFixture#cleanup()}, which removes the service instances. The system property
 * {@value PortalFixture#KEEP_TEST_DATA} keeps them, as in {@link PortalFixture}.
 */
public class ContentMetadataFixture
{
	/** Content table source of the news service, rows of {@code lum_News}. */
	public static final String NEWS_SOURCE_ID = "default";

	/** Workflow of {@code lumis/workflow/standard/workflowdefinition.xml}. */
	public static final String STANDARD_WORKFLOW_ID = "lumis.standardworkflow";

	/** Start state of the standard workflow; roles author, editor (assignedTo) and chiefEditor. */
	public static final String STATE_AUTHORING = "authoring";

	/** Published state of the standard workflow; roles editor (assignedTo) and chiefEditor. */
	public static final String STATE_APPROVED = "approved";

	/** Not published state; roles author, editor (assignedTo) and chiefEditor. */
	public static final String STATE_REVIEWING = "reviewing";

	public static final String ROLE_AUTHOR = "author";

	public static final String ROLE_EDITOR = "editor";

	/** Transition ids are prefixed by their initial state ({@code WorkflowTransitionConfig}). */
	public static final String TRANSITION_APPROVE = STATE_AUTHORING + ".approve";

	public static final String TRANSITION_REVIEW = STATE_APPROVED + ".review";

	/** Locale of the contents created; the impersonated session has none. */
	public static final Locale LOCALE = new Locale("pt", "BR");

	private final Deque<Work<Void>> cleaners = new ArrayDeque<>();

	private final String prefix = "contentmigrator-meta-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

	/**
	 * Runs the given work in a new committed transaction, as the given user. When the work throws,
	 * the transaction is rolled back.
	 */
	public static <T> T asUser(String userId, Work<T> work) throws Exception
	{
		SessionConfig sessionConfig = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			sessionConfig.setLocale(LOCALE);
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
	 * Creates a news instance in the given channel, with the standard workflow.
	 */
	public static ServiceInstanceConfig createNewsInstanceWithWorkflow(PortalFixture fixture, ChannelConfig channel) throws Exception
	{
		ServiceInstanceConfig instance = fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getWorkflowManager().setServiceInstanceWorkflow(session, instance.getId(), STANDARD_WORKFLOW_ID, transaction);
			return null;
		});
		return instance;
	}

	/**
	 * Creates a portal user, not administrator, without portal login.
	 *
	 * @return the user id.
	 */
	public String createUser(String suffix) throws Exception
	{
		UserConfig user = new UserConfig();
		user.setLogin(prefix + "-" + suffix);
		user.setFirstName(user.getLogin());
		// no portal login: the tests impersonate it, and a login requires a password by the policy
		user.setPortalLogin(false);
		user.setMultiSession(true);
		String userId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
		cleaners.push((session, transaction) ->
		{
			ManagerFactory.getUserManager().delete(session, userId, transaction);
			return null;
		});
		return userId;
	}

	/**
	 * Replaces the workflow permissions of the instance: each principal gets the given role.
	 */
	public static void setRoles(ServiceInstanceConfig instance, Map<String, String> roleByPrincipalId) throws Exception
	{
		Collection<WorkflowPermission> permissions = new ArrayList<>();
		for (Map.Entry<String, String> entry : roleByPrincipalId.entrySet())
		{
			WorkflowPermission permission = new WorkflowPermission();
			permission.setPrincipalId(entry.getKey());
			permission.setRole(entry.getValue());
			permission.setServiceInstanceId(instance.getId());
			permission.setWorkflowId(STANDARD_WORKFLOW_ID);
			permission.setType(WorkflowPermission.PERMISSION_TYPE_ALLOW);
			permissions.add(permission);
		}
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getWorkflowManager().setWorkflowPermissions(session, instance.getId(), permissions, transaction);
			return null;
		});
	}

	/**
	 * Returns the values of a {@code lum_News} row for {@code addData} or {@code updateData}.
	 */
	public static Map<String, Object> news(String title, String introduction, String content)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("title", title);
		data.put("introduction", introduction);
		data.put("content", content);
		return data;
	}

	/**
	 * Adds a news content as the given user, executing the given transition natively, if any, with
	 * the given editorial comment, if any.
	 *
	 * @return the item id of the version added.
	 */
	public String addNews(String userId, ServiceInstanceConfig instance, Map<String, Object> data, String transitionId, String assignedTo, String comment)
			throws Exception
	{
		Map<String, Object> parameters = withWorkflowAction(data, transitionId, assignedTo);
		if (comment != null)
		{
			parameters.put("comments", comment);
		}
		String itemId = asUser(userId, (session, transaction) -> newsSource(session, transaction, instance.getId()).addData(parameters).getItemId());
		track(instance.getId(), itemId);
		return itemId;
	}

	/**
	 * Updates a news content as the given user, executing the given transition natively, if any. The
	 * news source has versioning: a new version is added and made active.
	 *
	 * @return the item id of the new active version.
	 */
	public static String updateNews(String userId, ServiceInstanceConfig instance, String itemId, Map<String, Object> data, String transitionId, String assignedTo)
			throws Exception
	{
		Map<String, Object> parameters = withWorkflowAction(data, transitionId, assignedTo);
		parameters.put("id", itemId);
		return asUser(userId, (session, transaction) -> newsSource(session, transaction, instance.getId()).updateData(parameters).getItemId());
	}

	private static Map<String, Object> withWorkflowAction(Map<String, Object> data, String transitionId, String assignedTo)
	{
		Map<String, Object> parameters = new HashMap<>(data);
		if (transitionId != null)
		{
			parameters.put(WorkflowController.TRANSITION_PARAMETER_NAME, transitionId);
		}
		if (assignedTo != null)
		{
			parameters.put(WorkflowController.ASSIGNED_TO_PARAMETER_NAME, assignedTo);
		}
		return parameters;
	}

	/**
	 * Returns the {@value #NEWS_SOURCE_ID} source of a news instance, for the given session.
	 */
	public static ContentTableSource newsSource(SessionConfig session, ITransaction transaction, String instanceId) throws Exception
	{
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(instanceId, transaction);
		return (ContentTableSource) SourceFactory.createSource(NEWS_SOURCE_ID, new SourceContext(session, instance, transaction));
	}

	/**
	 * Reads the {@code lum_News} values of the given version. Call inside a transaction.
	 */
	public static Map<String, Object> readNews(SessionConfig session, ITransaction transaction, String instanceId, String itemId) throws Exception
	{
		List<ISourceData> rows = newsSource(session, transaction, instanceId).readData(itemId);
		if (rows.size() != 1)
		{
			throw new IllegalStateException(rows.size() + " rows for itemId=" + itemId);
		}
		ISourceData row = rows.get(0);
		return news(row.get("title", String.class), row.get("introduction", String.class), row.get("content", String.class));
	}

	/**
	 * Returns the content locale of the given version. Call inside a transaction.
	 */
	public static ContentLocale contentLocale(String itemId) throws Exception
	{
		return ContentManagerFactory.getContentManager().getContentVersionByItemId(itemId).getContentLocale();
	}

	/**
	 * Returns the content locale with the given id, or null. Call inside a transaction.
	 */
	public static ContentLocale findContentLocale(String contentLocaleId) throws Exception
	{
		try
		{
			return ContentManagerFactory.getContentManager().getContentLocale(contentLocaleId);
		}
		catch (PortalObjectNotFoundException e)
		{
			return null;
		}
	}

	/**
	 * Returns the {@code lum_WorkflowMetaData} of a content locale. Call inside a transaction.
	 */
	public static ContentWorkflowMetaData workflowMetaData(SessionConfig session, ITransaction transaction, ContentLocale contentLocale) throws Exception
	{
		return ContentManagerFactory.getWorkflowManager().getContentWorkflowMetaData(session, contentLocale, transaction);
	}

	/**
	 * Returns the workflow of the given instance. Call inside a transaction.
	 */
	public static WorkflowConfig workflow(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance) throws Exception
	{
		return ContentManagerFactory.getWorkflowManager().getServiceInstanceWorkflow(session, instance.getId(), transaction);
	}

	/**
	 * Registers a content created outside the fixture (for example, a clone) for removal by
	 * {@link #cleanup()}. Contents already removed are skipped.
	 */
	public void track(String instanceId, String itemId)
	{
		cleaners.push((session, transaction) ->
		{
			String contentLocaleId;
			try
			{
				contentLocaleId = contentLocale(itemId).getId();
			}
			catch (PortalObjectNotFoundException e)
			{
				return null;
			}
			// the system user passes the workflow edit permission in any state
			String activeItemId = findContentLocale(contentLocaleId).getActiveVersion().getItemId();
			if (session.getLocale() == null)
			{
				session.setLocale(LOCALE);
			}
			newsSource(session, transaction, instanceId).deleteDataByItemIds(Collections.singleton(activeItemId));
			return null;
		});
	}

	/**
	 * Deletes everything this fixture created or tracked, in the inverse order of creation, each
	 * step in its own transaction. A failing step is reported and does not stop the following ones.
	 *
	 * @return the number of steps that failed.
	 */
	public int cleanup()
	{
		if (PortalFixture.keepTestData())
		{
			System.out.println("[ContentMetadataFixture] " + PortalFixture.KEEP_TEST_DATA + "=true: keeping " + cleaners.size() + " objects named " + prefix + "*");
			cleaners.clear();
			return 0;
		}

		int failures = 0;
		while (!cleaners.isEmpty())
		{
			try
			{
				PortalFixture.inTransaction(cleaners.pop());
			}
			catch (Exception e)
			{
				failures++;
				System.err.println("[ContentMetadataFixture] cleanup step failed: " + e);
				e.printStackTrace();
			}
		}
		return failures;
	}
}
