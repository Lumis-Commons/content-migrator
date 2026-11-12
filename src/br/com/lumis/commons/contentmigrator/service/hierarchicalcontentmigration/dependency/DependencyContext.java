package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency;

import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RepositoryPair;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.transaction.ITransaction;

/**
 * What a handler needs to know about the migration and the repositories of its type.
 */
public class DependencyContext
{
	private final MigrationScope scope;

	private final RepositoryPair repositories;

	/** The file cloner of the item; null in an undo and in tests. */
	private final FileCloner fileCloner;

	/**
	 * Creates a per-item context. Warnings are collected into the warnings of the scope, which are
	 * written to the item map, and file clones are retained until the item transaction commits or
	 * rolls back.
	 *
	 * @param repositories
	 *            the repository instances of the handler type the origin and the destination depend
	 *            on; either id may be null.
	 * @param fileCloner
	 *            the file cloner of the item, or null.
	 */
	public DependencyContext(MigrationScope scope, RepositoryPair repositories, FileCloner fileCloner)
	{
		this.scope = Objects.requireNonNull(scope, "scope");
		this.repositories = Objects.requireNonNull(repositories, "repositories");
		this.fileCloner = fileCloner;
	}

	public SessionConfig getSession()
	{
		return scope.getSession();
	}

	public ITransaction getTransaction()
	{
		return scope.getTransaction();
	}

	public Connection getConnection() throws Exception
	{
		return ((ITransactionJdbc) scope.getTransaction()).getConnection();
	}

	public String getServiceId()
	{
		return scope.getServiceId();
	}

	public String getOriginServiceInstanceId()
	{
		return scope.getOriginServiceInstanceId();
	}

	public String getDestServiceInstanceId()
	{
		return scope.getDestinationServiceInstanceId();
	}

	public String getOriginRepositoryInstanceId()
	{
		return repositories.getOriginRepositoryId();
	}

	public String getDestRepositoryInstanceId()
	{
		return repositories.getDestinationRepositoryId();
	}

	/**
	 * Returns whether origin and destination use the same repository instance, in which case
	 * the references are kept as they are.
	 */
	public boolean isSameRepository()
	{
		String originRepositoryInstanceId = repositories.getOriginRepositoryId();
		return originRepositoryInstanceId != null && originRepositoryInstanceId.equals(repositories.getDestinationRepositoryId());
	}

	public boolean isUpdateMode()
	{
		return scope.isUpdateMode();
	}

	public void addWarning(String warning)
	{
		boolean isBlank = warning == null || warning.isEmpty();
		List<String> warnings = scope.getWarnings();
		if (!isBlank && !warnings.contains(warning))
		{
			warnings.add(warning);
		}
	}

	public List<String> getWarnings()
	{
		return Collections.unmodifiableList(scope.getWarnings());
	}

	public FileCloner getFileCloner()
	{
		return fileCloner;
	}
}
