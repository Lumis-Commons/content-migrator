package br.com.lumis.commons.contentmigrator.service.contentmigration.test.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.contentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentComment;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.content.workflow.ContentWorkflowMetaData;
import lumis.content.workflow.IWorkflowManager;
import lumis.content.workflow.TransitionScope;
import lumis.content.workflow.WorkflowConfig;
import lumis.content.workflow.WorkflowStateConfig;
import lumis.content.workflow.WorkflowTransitionConfig;
import lumis.portal.AccessDeniedException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.hibernate.ITransactionHibernate;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;

/**
 * Spike of the content metadata clone (docs/architecture/content-metadata-migration.md): the
 * destination gets the origin's workflow state, assignedTo and published/active pair without
 * executing workflow transitions, and a non administrator user whose role only reaches the start
 * state updates and deletes a content whose cloned state is restricted, by returning it to the
 * start state in the same transaction.
 *
 * <p>{@code cloneContentLocale} is the engine's write order: {@code addData} of the published
 * version, {@code updateData} of the active one while still in the start state,
 * {@code setPublishedVersion}, state and assignedTo by SQL in {@code lum_WorkflowMetaData} after
 * the flush, and authorship and editorial comments last.
 */
public class ContentMetadataSpikeTest
{
	private PortalFixture fixture;

	private ContentMetadataFixture contents;

	private ServiceInstanceConfig origin;

	private ServiceInstanceConfig dest;

	private String authorId;

	private String editorId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		contents = new ContentMetadataFixture();

		ChannelConfig channel = fixture.createChannel();
		origin = ContentMetadataFixture.createNewsInstanceWithWorkflow(fixture, channel);
		dest = ContentMetadataFixture.createNewsInstanceWithWorkflow(fixture, channel);
		authorId = contents.createUser("author");
		editorId = contents.createUser("editor");

		// the author's role only reaches the start state, or contents assigned to them
		Map<String, String> roles = new HashMap<>();
		roles.put(authorId, ContentMetadataFixture.ROLE_AUTHOR);
		roles.put(editorId, ContentMetadataFixture.ROLE_EDITOR);
		ContentMetadataFixture.setRoles(origin, roles);
		ContentMetadataFixture.setRoles(dest, roles);
	}

	@After
	public void tearDown()
	{
		TestCleanup.assertNoFailures(contents::cleanup, fixture::cleanup);
	}

	@Test
	public void cloneKeepsStateAssignedToAndPublishedActivePairWithoutTransitions() throws Exception
	{
		String originActiveItemId = addOriginInReviewing();

		// a transition path from the start state would need transitions the author cannot execute
		Set<String> reachable = ContentMetadataFixture.asUser(authorId, (session, transaction) -> statesReachableByTransitions(session, transaction, dest));
		assertFalse("author reaches " + reachable, reachable.contains(ContentMetadataFixture.STATE_REVIEWING));

		String cloneActiveItemId = ContentMetadataFixture
				.asUser(authorId, (session, transaction) -> cloneContentLocale(session, transaction, originActiveItemId, origin, dest));
		contents.track(dest.getId(), cloneActiveItemId);

		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale originLocale = ContentMetadataFixture.contentLocale(originActiveItemId);
			ContentLocale clone = ContentMetadataFixture.contentLocale(cloneActiveItemId);
			assertEquals(dest.getId(), clone.getContent().getServiceInstanceId());

			ContentWorkflowMetaData originWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, originLocale);
			ContentWorkflowMetaData cloneWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, clone);
			assertEquals(ContentMetadataFixture.STATE_REVIEWING, originWorkflow.getStateId());
			assertEquals(editorId, originWorkflow.getAssignedTo());
			assertEquals(originWorkflow.getStateId(), cloneWorkflow.getStateId());
			assertEquals(originWorkflow.getAssignedTo(), cloneWorkflow.getAssignedTo());

			// published/active pair: two versions, the published one is not the active one
			assertNotNull(originLocale.getPublishedVersion());
			assertNotEquals(originLocale.getPublishedVersion(), originLocale.getActiveVersion());
			assertEquals(2, clone.getVersions().size());
			assertNotNull("clone lost the published version", clone.getPublishedVersion());
			assertNotEquals(clone.getPublishedVersion(), clone.getActiveVersion());
			assertNotEquals(
					"origin versions need distinct timestamps to verify per-version authorship",
					originLocale.getPublishedVersion().getLastModifiedDateTime(),
					originLocale.getActiveVersion().getLastModifiedDateTime());
			assertEquals(cloneActiveItemId, clone.getActiveVersion().getItemId());
			assertEquals(news(session, transaction, origin, originLocale.getPublishedVersion()), news(session, transaction, dest, clone.getPublishedVersion()));
			assertEquals(news(session, transaction, origin, originLocale.getActiveVersion()), news(session, transaction, dest, clone.getActiveVersion()));

			// authorship and comments come from the origin, written once
			assertEquals(originLocale.getCreatedBy(), clone.getCreatedBy());
			assertEquals(originLocale.getCreatedDateTime(), clone.getCreatedDateTime());
			assertEquals(originLocale.getPublishedVersion().getLastModifiedBy(), clone.getPublishedVersion().getLastModifiedBy());
			assertEquals(originLocale.getPublishedVersion().getLastModifiedDateTime(), clone.getPublishedVersion().getLastModifiedDateTime());
			assertEquals(originLocale.getActiveVersion().getLastModifiedBy(), clone.getActiveVersion().getLastModifiedBy());
			assertEquals(originLocale.getActiveVersion().getLastModifiedDateTime(), clone.getActiveVersion().getLastModifiedDateTime());
			assertEquals(1, originLocale.getComments().size());
			assertEquals(1, clone.getComments().size());
			ContentComment originComment = originLocale.getComments().get(0);
			ContentComment cloneComment = clone.getComments().get(0);
			assertEquals(originComment.getCommentText(), cloneComment.getCommentText());
			assertEquals(originComment.getCreatedBy(), cloneComment.getCreatedBy());
			assertEquals(originComment.getCreatedDateTime(), cloneComment.getCreatedDateTime());
			return null;
		});
	}

	@Test
	public void nonAdminWithStartStateRoleUpdatesAndDeletesContentInRestrictedClonedState() throws Exception
	{
		String originItemId = contents
				.addNews(editorId, origin, ContentMetadataFixture.news("aprovada", "intro", "corpo"), ContentMetadataFixture.TRANSITION_APPROVE, editorId, null);
		String cloneItemId = ContentMetadataFixture.asUser(authorId, (session, transaction) -> cloneContentLocale(session, transaction, originItemId, origin, dest));
		contents.track(dest.getId(), cloneItemId);

		String contentLocaleId = PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale clone = ContentMetadataFixture.contentLocale(cloneItemId);
			ContentWorkflowMetaData workflow = ContentMetadataFixture.workflowMetaData(session, transaction, clone);
			assertEquals(ContentMetadataFixture.STATE_APPROVED, workflow.getStateId());
			assertEquals(editorId, workflow.getAssignedTo());
			assertEquals(clone.getActiveVersion(), clone.getPublishedVersion());
			return clone.getId();
		});

		// natively, the author may neither update nor delete it in the cloned state
		ContentMetadataFixture.asUser(authorId, (session, transaction) ->
		{
			assertFalse(ContentManagerFactory.getWorkflowManager().hasEditPermission(session, ContentMetadataFixture.findContentLocale(contentLocaleId), transaction));
			return null;
		});
		assertAccessDenied(() -> ContentMetadataFixture.updateNews(authorId, dest, cloneItemId, ContentMetadataFixture.news("negada", "intro", "corpo"), null, null));
		assertAccessDenied(() -> ContentMetadataFixture.asUser(authorId, (session, transaction) ->
		{
			ContentMetadataFixture.newsSource(session, transaction, dest.getId()).deleteDataByItemIds(Collections.singleton(cloneItemId));
			return null;
		}));

		// the migration update: start state, updateData, cloned state again, in one transaction
		String updatedItemId = ContentMetadataFixture.asUser(authorId, (session, transaction) ->
		{
			ContentLocale clone = ContentMetadataFixture.findContentLocale(contentLocaleId);
			ContentWorkflowMetaData cloned = ContentMetadataFixture.workflowMetaData(session, transaction, clone);
			returnToStartState(session, transaction, clone);

			ContentTableSource source = ContentMetadataFixture.newsSource(session, transaction, dest.getId());
			Map<String, Object> data = ContentMetadataFixture.news("atualizada", "intro", "corpo");
			data.put("id", clone.getActiveVersion().getItemId());
			String itemId = source.updateData(data).getItemId();

			writeWorkflowState(transaction, clone.getId(), cloned.getStateId(), cloned.getAssignedTo());
			return itemId;
		});

		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentLocale clone = ContentMetadataFixture.findContentLocale(contentLocaleId);
			assertEquals(updatedItemId, clone.getActiveVersion().getItemId());
			assertEquals("atualizada", news(session, transaction, dest, clone.getActiveVersion()).get("title"));
			ContentWorkflowMetaData workflow = ContentMetadataFixture.workflowMetaData(session, transaction, clone);
			assertEquals(ContentMetadataFixture.STATE_APPROVED, workflow.getStateId());
			assertEquals(editorId, workflow.getAssignedTo());
			return null;
		});

		// the migration delete (undo): start state, then deleteData, in one transaction
		ContentMetadataFixture.asUser(authorId, (session, transaction) ->
		{
			ContentLocale clone = ContentMetadataFixture.findContentLocale(contentLocaleId);
			returnToStartState(session, transaction, clone);
			ContentMetadataFixture.newsSource(session, transaction, dest.getId()).deleteDataByItemIds(Collections.singleton(clone.getActiveVersion().getItemId()));
			return null;
		});

		PortalFixture.inTransaction((session, transaction) ->
		{
			assertNull("clone not deleted", ContentMetadataFixture.findContentLocale(contentLocaleId));
			assertNotNull("origin deleted", ContentMetadataFixture.findContentLocale(ContentMetadataFixture.contentLocale(originItemId).getId()));
			return null;
		});
	}

	/**
	 * Origin with native transitions: version 1 approved (published), then version 2 sent to
	 * review, assigned to the editor, with one editorial comment.
	 *
	 * @return the item id of the active version.
	 */
	private String addOriginInReviewing() throws Exception
	{
		String publishedItemId = contents.addNews(
				editorId,
				origin,
				ContentMetadataFixture.news("publicada", "intro publicada", "corpo publicado"),
				ContentMetadataFixture.TRANSITION_APPROVE,
				editorId,
				"comentário editorial");
		// different dates for the authorship of each version
		Thread.sleep(1100);
		return ContentMetadataFixture.updateNews(
				editorId,
				origin,
				publishedItemId,
				ContentMetadataFixture.news("ativa", "intro ativa", "corpo ativo"),
				ContentMetadataFixture.TRANSITION_REVIEW,
				editorId);
	}

	/**
	 * Clones the content locale of the given origin version into the destination news instance,
	 * in the given transaction, as the given session's user.
	 *
	 * @return the item id of the clone's active version.
	 */
	static String cloneContentLocale(
			SessionConfig session,
			ITransaction transaction,
			String originItemId,
			ServiceInstanceConfig originInstance,
			ServiceInstanceConfig destInstance) throws Exception
	{
		ContentLocale originLocale = ContentMetadataFixture.contentLocale(originItemId);
		ContentVersion originPublished = originLocale.getPublishedVersion();
		ContentVersion originActive = originLocale.getActiveVersion();
		ContentWorkflowMetaData originWorkflow = ContentMetadataFixture.workflowMetaData(session, transaction, originLocale);

		// 1. addData of the published version (or the active one, if never published)
		ContentTableSource source = ContentMetadataFixture.newsSource(session, transaction, destInstance.getId());
		ContentVersion first = originPublished != null ? originPublished : originActive;
		String publishedItemId = source.addData(news(session, transaction, originInstance, first)).getItemId();
		String activeItemId = publishedItemId;

		// 2. updateData of the active version, still in the start state
		if (originActive != first)
		{
			Map<String, Object> data = news(session, transaction, originInstance, originActive);
			data.put("id", publishedItemId);
			activeItemId = source.updateData(data).getItemId();
		}

		// 3. published version as in the origin, whatever the start state published
		ContentLocale clone = ContentMetadataFixture.contentLocale(activeItemId);
		clone.setPublishedVersion(originPublished != null ? ContentManagerFactory.getContentManager().getContentVersionByItemId(publishedItemId) : null);

		// 4. state and assignedTo, without transitions
		writeWorkflowState(transaction, clone.getId(), originWorkflow.getStateId(), originWorkflow.getAssignedTo());

		// 5. authorship and editorial comments last, over what the writes above set
		clone.setCreatedBy(originLocale.getCreatedBy());
		clone.setCreatedDateTime(originLocale.getCreatedDateTime());
		if (originPublished != null)
		{
			copyLastModified(originPublished, ContentManagerFactory.getContentManager().getContentVersionByItemId(publishedItemId));
		}
		copyLastModified(originActive, clone.getActiveVersion());
		for (ContentComment originComment : originLocale.getComments())
		{
			ContentComment comment = clone.addComment();
			comment.setCommentText(originComment.getCommentText());
			comment.setCreatedBy(originComment.getCreatedBy());
			comment.setCreatedDateTime(originComment.getCreatedDateTime());
		}
		ManagerFactory.getEntityManager().flush();
		return activeItemId;
	}

	private static void copyLastModified(ContentVersion from, ContentVersion to)
	{
		to.setLastModifiedBy(from.getLastModifiedBy());
		to.setLastModifiedDateTime(from.getLastModifiedDateTime() == null ? null : new Date(from.getLastModifiedDateTime().getTime()));
	}

	/**
	 * Puts the content locale in the start state, assigned to the current user, as a native
	 * creation does, so the workflow edit permission of {@code updateData} and {@code deleteData}
	 * passes for a user with a role in the start state.
	 */
	static void returnToStartState(SessionConfig session, ITransaction transaction, ContentLocale contentLocale) throws Exception
	{
		WorkflowConfig workflow = ContentManagerFactory.getWorkflowManager()
				.getServiceInstanceWorkflow(session, contentLocale.getContent().getServiceInstanceId(), transaction);
		writeWorkflowState(transaction, contentLocale.getId(), workflow.getStartState().getId(), session.getUserId());
	}

	/**
	 * Writes state and assignedTo of a content locale in {@code lum_WorkflowMetaData}, in the given
	 * transaction, after flushing the pending entity changes. There is no public API for it: the
	 * manager only changes the state through transitions.
	 */
	static void writeWorkflowState(ITransaction transaction, String contentLocaleId, String stateId, String assignedTo) throws Exception
	{
		ManagerFactory.getEntityManager().flush();
		try (PreparedStatement statement = ((ITransactionHibernate) transaction).getConnection()
				.prepareStatement("update lum_WorkflowMetaData set stateId = ?, assignedTo = ? where contentLocaleId = ?"))
		{
			statement.setString(1, stateId);
			statement.setString(2, assignedTo);
			statement.setString(3, contentLocaleId);
			assertEquals("lum_WorkflowMetaData rows of " + contentLocaleId, 1, statement.executeUpdate());
		}
	}

	private static Map<String, Object> news(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance, ContentVersion version) throws Exception
	{
		return ContentMetadataFixture.readNews(session, transaction, instance.getId(), version.getItemId());
	}

	/**
	 * Returns the states the current user reaches from the start state by transitions they may
	 * execute, in scope all or assignedTo.
	 */
	private static Set<String> statesReachableByTransitions(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance) throws Exception
	{
		WorkflowConfig workflow = ContentMetadataFixture.workflow(session, transaction, instance);
		Set<String> reached = new HashSet<>();
		Deque<WorkflowStateConfig> pending = new ArrayDeque<>();
		pending.push(workflow.getStartState());
		reached.add(workflow.getStartState().getId());
		while (!pending.isEmpty())
		{
			WorkflowStateConfig state = pending.pop();
			EnumSet<TransitionScope> scopes = EnumSet.of(TransitionScope.all, TransitionScope.assignedTo);
			IWorkflowManager workflowManager = ContentManagerFactory.getWorkflowManager();
			Collection<WorkflowTransitionConfig> transitions = workflowManager.getAccessibleTransitions(session, state, instance.getId(), scopes, transaction);
			for (WorkflowTransitionConfig transition : transitions)
			{
				if (reached.add(transition.getFinalStateId()))
				{
					pending.push(workflow.getState(transition.getFinalStateId()));
				}
			}
		}
		return reached;
	}

	@FunctionalInterface
	private interface Action
	{
		void run() throws Exception;
	}

	private static void assertAccessDenied(Action action) throws Exception
	{
		try
		{
			action.run();
			fail("expected AccessDeniedException");
		}
		catch (AccessDeniedException e)
		{
			// expected; the transaction was rolled back
		}
	}
}
