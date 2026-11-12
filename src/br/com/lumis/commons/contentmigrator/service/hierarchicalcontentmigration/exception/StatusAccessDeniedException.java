package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception;

/** Authorization failure kept separate so the REST layer can return HTTP 403 without details. */
public final class StatusAccessDeniedException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	/** Creates an access denied exception for unauthorized status queries. */
	public StatusAccessDeniedException()
	{
		super("Access denied");
	}
}
