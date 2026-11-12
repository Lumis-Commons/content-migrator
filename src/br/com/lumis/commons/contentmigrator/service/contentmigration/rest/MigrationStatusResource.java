package br.com.lumis.commons.contentmigrator.service.contentmigration.rest;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import br.com.lumis.commons.contentmigrator.service.contentmigration.exception.StatusAccessDeniedException;
import br.com.lumis.commons.contentmigrator.service.contentmigration.query.MigrationStatusQueries;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;

/** Read-only REST resource used by the migration administration status poller. */
@Path("/br/com/lumis/commons/contentmigrator/contentmigration")
public class MigrationStatusResource
{
	@GET
	@Path("/status")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStatus()
	{
		return getStatusForCurrentUser();
	}

	/** Public bridge for integration tests and the JAX-RS endpoint. */
	public static Response getStatusForCurrentUser()
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			List<Map<String, Object>> statuses = MigrationStatusQueries.listStatuses(SessionConfig.getCurrentSessionConfig(), transaction);
			transaction.commit();
			JSONArray jsonStatuses = new JSONArray();
			for (Map<String, Object> status : statuses)
			{
				JSONObject jsonStatus = new JSONObject();
				for (Map.Entry<String, Object> field : status.entrySet())
				{
					jsonStatus.put(field.getKey(), field.getValue() == null ? JSONObject.NULL : field.getValue());
				}
				jsonStatuses.put(jsonStatus);
			}
			return Response.ok(jsonStatuses.toString(), MediaType.APPLICATION_JSON).build();
		}
		catch (StatusAccessDeniedException e)
		{
			return Response.status(Response.Status.FORBIDDEN).build();
		}
		catch (Exception e)
		{
			throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
		}
	}
}
