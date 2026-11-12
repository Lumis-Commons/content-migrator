package br.com.lumis.commons.contentmigrator.service.contentmigration.copy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyContext;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandlerRegistry;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.FileCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationScope;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.RepositoryPair;
import br.com.lumis.commons.contentmigrator.service.contentmigration.validation.DependencyRules;
import lumis.doui.source.field.ISourceField;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.transaction.ITransaction;

/**
 * The dependency handlers of one item, in the order they run, with the context of each one and the
 * {@link FileCloner} of the item. The contexts share the warnings of the {@link MigrationScope}.
 * The file cloner belongs to the caller, which closes it after the commit or rollback.
 */
final class ItemDependencies
{
	private final List<DependencyHandler> handlers;

	/** Context of each handler, by dependency type. */
	private final Map<String, DependencyContext> contexts;

	private final FileCloner fileCloner;

	private ItemDependencies(List<DependencyHandler> handlers, Map<String, DependencyContext> contexts, FileCloner fileCloner)
	{
		this.handlers = handlers;
		this.contexts = contexts;
		this.fileCloner = fileCloner;
	}

	/**
	 * Takes the handlers registered now and builds the context of each one between the origin and
	 * destination repositories of its type.
	 */
	static ItemDependencies create(MigrationScope scope, FileCloner fileCloner) throws Exception
	{
		List<DependencyHandler> handlers = DependencyHandlerRegistry.getHandlers();
		Map<String, DependencyContext> contexts = new LinkedHashMap<>();
		for (DependencyHandler handler : handlers)
		{
			String dependencyType = handler.getDependencyType();
			contexts.put(dependencyType, new DependencyContext(scope, repositories(scope, dependencyType), fileCloner));
		}
		return new ItemDependencies(handlers, contexts, fileCloner);
	}

	/**
	 * Returns the repository instances of a dependency type that the origin and the destination
	 * instances depend on.
	 */
	private static RepositoryPair repositories(MigrationScope scope, String dependencyType) throws Exception
	{
		SessionConfig session = scope.getSession();
		ITransaction transaction = scope.getTransaction();
		String originRepository = DependencyRules.repository(session, scope.getOriginServiceInstanceId(), dependencyType, transaction);
		String destinationRepository = DependencyRules.repository(session, scope.getDestinationServiceInstanceId(), dependencyType, transaction);
		return new RepositoryPair(originRepository, destinationRepository, dependencyType);
	}

	/** Returns the handlers in the order they run. */
	List<DependencyHandler> getHandlers()
	{
		return handlers;
	}

	/** Returns the handler of a dependency type, or null when the type is not registered. */
	DependencyHandler getHandler(String dependencyType)
	{
		for (DependencyHandler handler : handlers)
		{
			if (handler.getDependencyType().equals(dependencyType))
			{
				return handler;
			}
		}
		return null;
	}

	/** Returns the context of one of the handlers of the item. */
	DependencyContext getContext(DependencyHandler handler)
	{
		return contexts.get(handler.getDependencyType());
	}

	/** Returns the first handler that resolves the field, or null. */
	DependencyHandler findHandler(ISourceField field) throws Exception
	{
		for (DependencyHandler handler : handlers)
		{
			if (handler.handles(field))
			{
				return handler;
			}
		}
		return null;
	}

	/** Returns whether a handler resolves the field. */
	boolean hasHandler(ISourceField field) throws Exception
	{
		return findHandler(field) != null;
	}

	/** Returns the file cloner of the item. */
	FileCloner getFileCloner()
	{
		return fileCloner;
	}
}
