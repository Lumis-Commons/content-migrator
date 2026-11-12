package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.user.UserConfig;
import lumis.util.PortalUtil;

/**
 * The REST resource behind the content browser: it lists every content, archived ones included,
 * level by level with the breadcrumb, resolves a selection, and answers only to portal administrators.
 */
public class HierarchyBrowserRestTest
{
	private static final String RESOURCE = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.rest.HierarchicalContentBrowserResource";

	private PortalFixture fixture;

	private ServiceInstanceConfig instance;

	private String root;

	private String child;

	private String archivedGrandchild;

	private String otherRoot;

	private String plainUserId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		ChannelConfig channel = fixture.createChannel();
		instance = fixture.createServiceInstance(channel, PortalFixture.HIERARCHICAL_SERVICE_ID);
		root = fixture.addHierarchicalContent(instance, "root", null, true);
		child = fixture.addHierarchicalContent(instance, "child", root, true);
		archivedGrandchild = fixture.addHierarchicalContent(instance, "archived", child, false);
		otherRoot = fixture.addHierarchicalContent(instance, "other", null, true);

		UserConfig user = new UserConfig();
		String guid = PortalUtil.generateNewGuid().toLowerCase();
		String login = "contentmigrator-rest-" + guid.substring(guid.length() - 12);
		user.setLogin(login);
		user.setFirstName(login);
		user.setEmail(login + "@example.com");
		user.setPassword("contentmigrator#9" + guid + "Q!");
		plainUserId = PortalFixture.inTransaction((session, transaction) -> ManagerFactory.getUserManager().add(session, user, transaction));
	}

	@After
	public void tearDown() throws Exception
	{
		if (!PortalFixture.keepTestData() && plainUserId != null)
		{
			PortalFixture.inTransaction((session, transaction) ->
			{
				ManagerFactory.getUserManager().delete(session, plainUserId, transaction);
				return null;
			});
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void theResourceIsMountedUnderTheModulePath() throws Exception
	{
		assertEquals("/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration", ModuleBridge.load(RESOURCE).getAnnotation(Path.class).value());
	}

	@Test
	public void listsTheContentsWithoutParentFirst() throws Exception
	{
		JSONObject page = ok(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), null));

		assertEquals(0, page.getJSONArray("breadcrumb").length());
		List<String> ids = ids(page.getJSONArray("items"));
		assertEquals(2, ids.size());
		assertTrue(ids.contains(root) && ids.contains(otherRoot));
		JSONObject rootItem = item(page, root);
		assertTrue(rootItem.getBoolean("hasChildren"));
		assertEquals(1, rootItem.getInt("childCount"));
		assertFalse(item(page, otherRoot).getBoolean("hasChildren"));
	}

	@Test
	public void listsTheDirectChildrenWithTheBreadcrumbAndShowsArchivedContents() throws Exception
	{
		JSONObject level1 = ok(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), root));
		assertEquals(List.of(child), ids(level1.getJSONArray("items")));
		assertEquals(List.of(root), ids(level1.getJSONArray("breadcrumb")));

		JSONObject level2 = ok(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), child));
		assertEquals(List.of(root, child), ids(level2.getJSONArray("breadcrumb")));
		assertEquals("the content without a published version is listed", List.of(archivedGrandchild), ids(level2.getJSONArray("items")));
		assertFalse(item(level2, archivedGrandchild).getBoolean("published"));
	}

	@Test
	public void resolvesASelectionToItsMinimalMarksAndCounts() throws Exception
	{
		JSONObject answer = ok(
				select(
						UserConfig.USER_ADMINISTRATOR_ID,
						new JSONObject().put("serviceInstanceId", instance.getId()).put("contentIds", new JSONArray(List.of(archivedGrandchild, root, otherRoot)))
								.toString()));

		assertEquals(List.of(root, otherRoot), new ArrayList<>(strings(answer.getJSONArray("markedContentIds"))));
		assertEquals("root, child, archived and other", 4, answer.getInt("contentCount"));
		assertEquals(4, answer.getInt("localeCount"));
	}

	@Test
	public void refusesUsersThatAreNotPortalAdministrators() throws Exception
	{
		for (String userId : new String[]{plainUserId, UserConfig.USER_GUEST_ID})
		{
			Response listing = browse(userId, instance.getId(), null);
			Response selection = select(userId, new JSONObject().put("serviceInstanceId", instance.getId()).put("contentIds", new JSONArray(List.of(root))).toString());
			try
			{
				assertEquals(userId, 403, listing.getStatus());
				assertEquals(userId, 403, selection.getStatus());
			}
			finally
			{
				listing.close();
				selection.close();
			}
		}
	}

	@Test
	public void rejectsUnauthorizedMalformedRequestsWithAnEmptyBody() throws Exception
	{
		for (String userId : new String[]{plainUserId, UserConfig.USER_GUEST_ID})
		{
			try (Response listing = browse(userId, "", null); Response selection = select(userId, "not json"))
			{
				assertEquals(403, listing.getStatus());
				assertEquals(403, selection.getStatus());
				assertNull(listing.getEntity());
				assertNull(selection.getEntity());
			}
		}
	}

	@Test
	public void rejectsBadRequestsAndUnknownTargets() throws Exception
	{
		assertEquals(400, status(browse(UserConfig.USER_ADMINISTRATOR_ID, "", null)));
		assertEquals(404, status(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), "00000000000000000000000000000000")));
		assertEquals(404, status(browse(UserConfig.USER_ADMINISTRATOR_ID, "00000000000000000000000000000000", null)));
		assertEquals(400, status(select(UserConfig.USER_ADMINISTRATOR_ID, "not json")));
		ServiceInstanceConfig other = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.NEWS_SERVICE_ID);
		assertEquals("only hierarchical content instances can be browsed", 400, status(browse(UserConfig.USER_ADMINISTRATOR_ID, other.getId(), null)));
	}

	@Test
	public void paginatesLevelsAndKeepsTheSnapshotBetweenRequests() throws Exception
	{
		JSONObject first = ok(as(UserConfig.USER_ADMINISTRATOR_ID, "browseForCurrentUser", instance.getId(), null, 0, 1));
		JSONObject second = ok(as(UserConfig.USER_ADMINISTRATOR_ID, "browseForCurrentUser", instance.getId(), null, 1, 1));
		assertEquals(2, first.getInt("total"));
		assertEquals(0, first.getInt("offset"));
		assertEquals(1, first.getInt("limit"));
		assertEquals(List.of(otherRoot), ids(first.getJSONArray("items")));
		assertEquals(List.of(root), ids(second.getJSONArray("items")));
		assertEquals(first.getLong("snapshotMillis"), second.getLong("snapshotMillis"));
		assertTrue(first.getLong("snapshotMillis") > 0);
		JSONObject empty = ok(as(UserConfig.USER_ADMINISTRATOR_ID, "browseForCurrentUser", instance.getId(), null, Integer.MAX_VALUE, 500));
		assertEquals(2, empty.getInt("total"));
		assertEquals(0, empty.getJSONArray("items").length());
		JSONObject defaults = ok(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), null));
		assertEquals(0, defaults.getInt("offset"));
		assertEquals(200, defaults.getInt("limit"));
	}

	@Test
	public void listsAnArchivedContentWhoseParentIsNotInTheInstanceAmongTheRoots() throws Exception
	{
		String orphan = fixture.addHierarchicalContent(instance, "orphan", child, false);
		fixture.forceHierarchicalParent(orphan, "ffffffffffffffffffffffffffffffff");

		JSONObject roots = ok(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), null));
		assertEquals(3, roots.getInt("total"));
		assertFalse(item(roots, orphan).getBoolean("published"));
		assertFalse(ids(ok(browse(UserConfig.USER_ADMINISTRATOR_ID, instance.getId(), child)).getJSONArray("items")).contains(orphan));
		JSONObject answer = ok(select(UserConfig.USER_ADMINISTRATOR_ID,
				new JSONObject().put("serviceInstanceId", instance.getId()).put("contentIds", new JSONArray(List.of(orphan))).toString()));
		assertEquals(List.of(), ids(answer.getJSONObject("paths").getJSONArray(orphan)));
		assertEquals(1, answer.getInt("contentCount"));
	}

	@Test
	public void rejectsInvalidPageBounds() throws Exception
	{
		for (int[] page : new int[][]{{0, 501}, {0, 0}, {0, -1}, {-1, 200}})
		{
			assertEquals(400, status(as(UserConfig.USER_ADMINISTRATOR_ID, "browseForCurrentUser", instance.getId(), null, page[0], page[1])));
		}
	}

	@Test
	public void returnsAncestorPathsAndUnknownIdsWithoutLosingKnownMarks() throws Exception
	{
		String unknown = "00000000000000000000000000000000";
		JSONObject answer = ok(select(UserConfig.USER_ADMINISTRATOR_ID, new JSONObject().put("serviceInstanceId", instance.getId())
				.put("contentIds", new JSONArray(List.of(archivedGrandchild, root, unknown))).toString()));
		assertEquals(List.of(root), ids(answer.getJSONArray("markedContentIds")));
		assertEquals(List.of(root, child), ids(answer.getJSONObject("paths").getJSONArray(archivedGrandchild)));
		assertEquals(List.of(), ids(answer.getJSONObject("paths").getJSONArray(root)));
		assertEquals(List.of(unknown), ids(answer.getJSONArray("unknownContentIds")));
		assertFalse(answer.getJSONObject("paths").has(unknown));
		assertEquals(3, answer.getInt("contentCount"));
		assertEquals(3, answer.getInt("localeCount"));
	}

	@Test
	public void rejectsTooManySubmittedIdsBeforeDeduplicationAndLimitsUtf8BodyBytes() throws Exception
	{
		String atLimit = new JSONObject().put("serviceInstanceId", instance.getId())
				.put("contentIds", new JSONArray(java.util.Collections.nCopies(2000, root))).toString();
		assertEquals(200, status(select(UserConfig.USER_ADMINISTRATOR_ID, atLimit)));
		String tooMany = new JSONObject(atLimit).put("contentIds", new JSONArray(java.util.Collections.nCopies(2001, root))).toString();
		assertSelectionTooLarge(tooMany);
		String prefix = new JSONObject().put("serviceInstanceId", instance.getId()).put("contentIds", new JSONArray(List.of(root))).toString();
		String exact = prefix + " ".repeat(256 * 1024 - prefix.length());
		assertEquals(200, status(select(UserConfig.USER_ADMINISTRATOR_ID, exact)));
		assertSelectionTooLarge(exact + " ");
		String multibyte = new JSONObject(prefix).put("padding", "é".repeat(132000)).toString();
		assertTrue(multibyte.length() < 256 * 1024);
		assertSelectionTooLarge(multibyte);
	}

	@Test
	public void appliesTheConfiguredSelectionLimitToRestAndCreateBeforeDatabaseWork() throws Exception
	{
		String property = "contentmigrator.hierarchicalcontentmigration.maxSelection";
		String previous = System.setProperty(property, "2");
		try
		{
			assertSelectionTooLarge(new JSONObject().put("serviceInstanceId", instance.getId())
					.put("contentIds", new JSONArray(List.of(root, child, archivedGrandchild))).toString());
			for (String submitted : List.of(root + "," + child + "," + root, "x".repeat(67), "a,b,c"))
			{
				try
				{
					ModuleBridge.api("create", java.util.Map.of("name", "too large", "originServiceInstanceId", instance.getId(),
							"destServiceInstanceId", "does-not-exist", "selectedContentIds", submitted));
					throw new AssertionError("oversized selection was accepted");
				}
				catch (RuntimeException exception)
				{
					assertEquals("SELECTION_TOO_LARGE", ModuleBridge.errorCode(exception));
				}
			}
		}
		finally
		{
			if (previous == null)
			{
				System.clearProperty(property);
			}
			else
			{
				System.setProperty(property, previous);
			}
		}
	}

	private void assertSelectionTooLarge(String body) throws Exception
	{
		try (Response response = select(UserConfig.USER_ADMINISTRATOR_ID, body))
		{
			assertEquals(400, response.getStatus());
			assertEquals("SELECTION_TOO_LARGE", new JSONObject((String) response.getEntity()).getString("error"));
		}
	}

	private Response browse(String userId, String instanceId, String parentContentId) throws Exception
	{
		return as(userId, "browseForCurrentUser", instanceId, parentContentId);
	}

	private Response select(String userId, String json) throws Exception
	{
		return as(userId, "resolveSelectionForCurrentUser", json);
	}

	private Response as(String userId, String method, Object... args) throws Exception
	{
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(userId);
		try
		{
			return (Response) ModuleBridge.invokeStatic(RESOURCE, method, args);
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	private static JSONObject ok(Response response)
	{
		try
		{
			assertEquals(200, response.getStatus());
			return new JSONObject((String) response.getEntity());
		}
		finally
		{
			response.close();
		}
	}

	private static int status(Response response)
	{
		try
		{
			return response.getStatus();
		}
		finally
		{
			response.close();
		}
	}

	private static JSONObject item(JSONObject page, String contentId)
	{
		JSONArray items = page.getJSONArray("items");
		for (int index = 0; index < items.length(); index++)
		{
			if (contentId.equals(items.getJSONObject(index).getString("contentId")))
			{
				return items.getJSONObject(index);
			}
		}
		throw new AssertionError("content " + contentId + " is not listed");
	}

	private static List<String> ids(JSONArray array)
	{
		List<String> ids = new ArrayList<>();
		for (int index = 0; index < array.length(); index++)
		{
			ids.add(array.get(index) instanceof JSONObject ? array.getJSONObject(index).getString("contentId") : array.getString(index));
		}
		return ids;
	}

	private static List<String> strings(JSONArray array)
	{
		return ids(array);
	}
}
