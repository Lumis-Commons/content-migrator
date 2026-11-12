package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao;

import java.sql.Connection;

import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;

/** Runs short, self-contained JDBC work in a new committed transaction. */
public final class Transactions
{
	private Transactions()
	{
	}

	/**
	 * Runs the given work in a new transaction that is committed on return, using the session of
	 * the calling thread without impersonating.
	 */
	public static <T> T inShortTransaction(SqlWork<T> work) throws Exception
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			T result = work.run(((ITransactionJdbc) transaction).getConnection());
			transaction.commit();
			return result;
		}
	}

	/** JDBC work executed inside a short transaction of its own. */
	@FunctionalInterface
	public interface SqlWork<T>
	{
		/** Executes the JDBC statements on the transaction's connection and returns the result. */
		T run(Connection connection) throws Exception;
	}
}
