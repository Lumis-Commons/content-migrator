package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lumis.content.table.ContentTableSource;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.transaction.ITransaction;

/**
 * What every step of one item transaction needs to know: the session, the transaction and its
 * connection, the service and the origin and destination instances, their content sources and the
 * update mode. One scope is created per item transaction, so a retried item starts with no
 * warnings.
 */
public final class MigrationScope
{
	private final SessionConfig session;

	private final ITransaction transaction;

	private final Connection connection;

	private final String serviceId;

	private final String originServiceInstanceId;

	private final String destinationServiceInstanceId;

	/** The content source of the item in the origin instance; null in an undo and in tests. */
	private final ContentTableSource originSource;

	/** The content source of the item in the destination instance; null exactly when {@link #originSource} is. */
	private final ContentTableSource destinationSource;

	private final boolean updateMode;

	private final String referenceParentContentId;

	/** The warnings of the item, shared with its dependency contexts and written to its map row. */
	private final List<String> warnings = new ArrayList<>();

	private MigrationScope(Builder builder)
	{
		this.session = Objects.requireNonNull(builder.session, "session");
		this.transaction = Objects.requireNonNull(builder.transaction, "transaction");
		this.connection = Objects.requireNonNull(builder.connection, "connection");
		this.serviceId = Objects.requireNonNull(builder.serviceId, "serviceId");
		this.originServiceInstanceId = Objects.requireNonNull(builder.originServiceInstanceId, "originServiceInstanceId");
		this.destinationServiceInstanceId = Objects.requireNonNull(builder.destinationServiceInstanceId, "destinationServiceInstanceId");
		if ((builder.originSource == null) != (builder.destinationSource == null))
		{
			throw new IllegalStateException("The origin and destination sources must be both set or both null.");
		}
		this.originSource = builder.originSource;
		this.destinationSource = builder.destinationSource;
		this.updateMode = builder.updateMode;
		this.referenceParentContentId = builder.referenceParentContentId;
	}

	/** Returns a builder of a scope; session, transaction, connection, service and instance ids are required. */
	public static Builder builder()
	{
		return new Builder();
	}

	/** Returns the session of the user running the migration. */
	public SessionConfig getSession()
	{
		return session;
	}

	/** Returns the item transaction. */
	public ITransaction getTransaction()
	{
		return transaction;
	}

	/** Returns the JDBC connection of the item transaction. */
	public Connection getConnection()
	{
		return connection;
	}

	/** Returns the Lumis service id shared by the origin and destination instances. */
	public String getServiceId()
	{
		return serviceId;
	}

	/** Returns the id of the origin service instance. */
	public String getOriginServiceInstanceId()
	{
		return originServiceInstanceId;
	}

	/** Returns the id of the destination service instance. */
	public String getDestinationServiceInstanceId()
	{
		return destinationServiceInstanceId;
	}

	/** Returns the content source of the item in the origin instance, or null in an undo and in tests. */
	public ContentTableSource getOriginSource()
	{
		return originSource;
	}

	/** Returns the content source of the item in the destination instance, or null when the origin source is null. */
	public ContentTableSource getDestinationSource()
	{
		return destinationSource;
	}

	/** Returns whether a mapped destination that still exists is written over instead of skipped. */
	public boolean isUpdateMode()
	{
		return updateMode;
	}

	/** Returns the parent chosen by the reference locale of the discovery tree, or null. */
	public String getReferenceParentContentId()
	{
		return referenceParentContentId;
	}

	/**
	 * Returns the warnings of the item itself, not a copy: the dependency contexts of the item add
	 * to the same list.
	 */
	public List<String> getWarnings()
	{
		return warnings;
	}

	/** Adds a warning to the item, without removing duplicates. */
	public void addWarning(String warning)
	{
		warnings.add(warning);
	}

	/** Builds a {@link MigrationScope}; {@link #build()} validates the required fields. */
	public static final class Builder
	{
		private SessionConfig session;

		private ITransaction transaction;

		private Connection connection;

		private String serviceId;

		private String originServiceInstanceId;

		private String destinationServiceInstanceId;

		private ContentTableSource originSource;

		private ContentTableSource destinationSource;

		private boolean updateMode;

		private String referenceParentContentId;

		private Builder()
		{
		}

		/** Sets the session of the user running the migration. */
		public Builder session(SessionConfig session)
		{
			this.session = session;
			return this;
		}

		/** Sets the item transaction. */
		public Builder transaction(ITransaction transaction)
		{
			this.transaction = transaction;
			return this;
		}

		/** Sets the JDBC connection of the item transaction. */
		public Builder connection(Connection connection)
		{
			this.connection = connection;
			return this;
		}

		/** Sets the Lumis service id shared by the origin and destination instances. */
		public Builder serviceId(String serviceId)
		{
			this.serviceId = serviceId;
			return this;
		}

		/** Sets the id of the origin service instance. */
		public Builder originServiceInstanceId(String originServiceInstanceId)
		{
			this.originServiceInstanceId = originServiceInstanceId;
			return this;
		}

		/** Sets the id of the destination service instance. */
		public Builder destinationServiceInstanceId(String destinationServiceInstanceId)
		{
			this.destinationServiceInstanceId = destinationServiceInstanceId;
			return this;
		}

		/** Sets the content sources of the item; both are set or both are null. */
		public Builder sources(ContentTableSource origin, ContentTableSource destination)
		{
			this.originSource = origin;
			this.destinationSource = destination;
			return this;
		}

		/** Sets whether a mapped destination that still exists is written over; the default is false. */
		public Builder updateMode(boolean updateMode)
		{
			this.updateMode = updateMode;
			return this;
		}

		/** Sets the optional parent chosen by the discovery tree. */
		public Builder referenceParentContentId(String referenceParentContentId)
		{
			this.referenceParentContentId = referenceParentContentId;
			return this;
		}

		/**
		 * Creates the scope with an empty warning list.
		 *
		 * @throws NullPointerException
		 *             if a required field is null.
		 * @throws IllegalStateException
		 *             if only one of the sources is null.
		 */
		public MigrationScope build()
		{
			return new MigrationScope(this);
		}
	}
}
