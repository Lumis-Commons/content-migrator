package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.rest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.config.ExecutionSettings;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.hierarchy.HierarchyBrowser;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.security.MigrationPermissions;
import lumis.portal.PortalObjectNotFoundException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;
import lumis.util.log.ILogger;
import lumis.util.log.LoggerFactory;

/**
 * REST resource of the content browser in the form that creates a migration. Only administrators of
 * the portal may call it: any other user, and the guest, gets 403 before the request is read. An
 * administrator gets 400 for an invalid request and 404 for an unknown instance or content.
 */
@Path("/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration")
public class HierarchicalContentBrowserResource
{
	private static final ILogger logger = LoggerFactory.getLogger(HierarchicalContentBrowserResource.class);

	/** Largest selection request body, in UTF-8 bytes. */
	private static final int MAX_SELECTION_BODY_BYTES = 256 * 1024;

	/**
	 * Lists the direct children of a content of the instance, or the contents without a parent when
	 * {@code parentContentId} is absent, and the path from the root down to that content. Archived
	 * contents, drafts and hidden contents are listed too.
	 */
	@GET
	@Path("/contents")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listContents(@QueryParam("serviceInstanceId") String serviceInstanceId, @QueryParam("parentContentId") String parentContentId,
			@QueryParam("offset") @DefaultValue("0") int offset, @QueryParam("limit") @DefaultValue("" + HierarchyBrowser.DEFAULT_LIMIT) int limit)
	{
		return browseForCurrentUser(serviceInstanceId, parentContentId, offset, limit);
	}

	/**
	 * Tells what a selection migrates. The request is a JSON object with {@code serviceInstanceId}
	 * and {@code contentIds}, the marked contents; a content marked together with one of its
	 * ancestors is not in the {@code markedContentIds} of the answer.
	 */
	@POST
	@Path("/selection")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response resolveSelection(InputStream body) throws IOException
	{
		// Reads one byte past the limit, so an oversized body is rejected without reading all of it.
		byte[] bytes = body == null ? new byte[0] : body.readNBytes(MAX_SELECTION_BODY_BYTES + 1);
		return resolveSelectionForCurrentUser(new String(bytes, StandardCharsets.UTF_8));
	}

	/** Public bridge for integration tests and the JAX-RS endpoint. */
	public static Response browseForCurrentUser(String serviceInstanceId, String parentContentId)
	{
		return browseForCurrentUser(serviceInstanceId, parentContentId, 0, HierarchyBrowser.DEFAULT_LIMIT);
	}

	/** Public bridge with explicit pagination for integration tests and the endpoint. */
	public static Response browseForCurrentUser(String serviceInstanceId, String parentContentId, int offset, int limit)
	{
		return runAsAdministrator(transaction ->
		{
			if (!HierarchyBrowser.isValidPage(offset, limit))
			{
				return error(Status.BAD_REQUEST, "offset must be nonnegative and limit must be between 1 and " + HierarchyBrowser.MAX_LIMIT);
			}
			if (isBlank(serviceInstanceId))
			{
				return error(Status.BAD_REQUEST, "serviceInstanceId is required");
			}
			return Response.ok(new JSONObject(HierarchyBrowser.browse(serviceInstanceId,
					isBlank(parentContentId) ? null : parentContentId, offset, limit, transaction)).toString(), MediaType.APPLICATION_JSON).build();
		});
	}

	/** Public bridge for integration tests and the JAX-RS endpoint. */
	public static Response resolveSelectionForCurrentUser(String jsonRequest)
	{
		return runAsAdministrator(transaction ->
		{
			if (jsonRequest != null && isBodyTooLarge(jsonRequest))
			{
				return error(Status.BAD_REQUEST, "SELECTION_TOO_LARGE");
			}
			JSONObject request;
			List<String> contentIds = new ArrayList<>();
			try
			{
				request = new JSONObject(jsonRequest);
				JSONArray ids = request.getJSONArray("contentIds");
				if (ids.length() > ExecutionSettings.maxSelection())
				{
					return error(Status.BAD_REQUEST, "SELECTION_TOO_LARGE");
				}
				for (int index = 0; index < ids.length(); index++)
				{
					contentIds.add(ids.getString(index));
				}
			}
			catch (JSONException | NullPointerException e)
			{
				return error(Status.BAD_REQUEST, "the request must be a JSON object with serviceInstanceId and contentIds");
			}
			String serviceInstanceId = request.optString("serviceInstanceId", null);
			if (isBlank(serviceInstanceId))
			{
				return error(Status.BAD_REQUEST, "serviceInstanceId is required");
			}
			Map<String, Object> selection = HierarchyBrowser.resolveSelection(serviceInstanceId, contentIds, transaction);
			return Response.ok(new JSONObject(selection).toString(), MediaType.APPLICATION_JSON).build();
		});
	}

	private interface AdministratorWork
	{
		Response run(ITransaction transaction) throws Exception;
	}

	private static Response runAsAdministrator(AdministratorWork work)
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			SessionConfig session = SessionConfig.getCurrentSessionConfig();
			MigrationPermissions.requirePortalAdministrator(session == null ? null : session.getUserId(), transaction);
			Response response = work.run(transaction);
			transaction.commit();
			return response;
		}
		catch (PortalObjectNotFoundException e)
		{
			return error(Status.NOT_FOUND, "service instance not found");
		}
		catch (MigrationApiException e)
		{
			if (MigrationApiException.ERROR_FORBIDDEN.equals(e.getCode()))
			{
				return Response.status(Status.FORBIDDEN).build();
			}
			boolean isNotFound = MigrationApiException.ERROR_NOT_FOUND.equals(e.getCode());
			return error(isNotFound ? Status.NOT_FOUND : Status.BAD_REQUEST, e.getMessage());
		}
		catch (Exception e)
		{
			logger.error("Unexpected failure browsing hierarchical contents", e);
			throw new WebApplicationException(e, Status.INTERNAL_SERVER_ERROR);
		}
	}

	private static boolean isBodyTooLarge(String body)
	{
		// Each char takes at least one UTF-8 byte, so a long body is rejected before it is encoded.
		boolean tooManyChars = body.length() > MAX_SELECTION_BODY_BYTES;
		return tooManyChars || body.getBytes(StandardCharsets.UTF_8).length > MAX_SELECTION_BODY_BYTES;
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static Response error(Status status, String message)
	{
		return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new JSONObject().put("error", message).toString()).build();
	}
}
