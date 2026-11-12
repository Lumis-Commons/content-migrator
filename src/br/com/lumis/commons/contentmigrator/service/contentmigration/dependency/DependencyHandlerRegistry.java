package br.com.lumis.commons.contentmigrator.service.contentmigration.dependency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.categorization.CategorizationDependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.document.DocumentDependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.media.MediaDependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.widget.WidgetDependencyHandler;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;

/** Registry of the {@link DependencyHandler}s, by dependency type. */
public final class DependencyHandlerRegistry
{
	/**
	 * Order in which the handlers run, so html is rewritten for media, then documents, then
	 * widgets; categorization terms have no html references. Types not listed run after these, by
	 * type name.
	 */
	private static final List<String> HANDLER_ORDER = Arrays.asList(
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY,
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY,
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY,
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY);

	/** Dependency handlers, by dependency type. */
	private static final Map<String, DependencyHandler> HANDLERS = new ConcurrentHashMap<>();

	static
	{
		registerHandler(new MediaDependencyHandler());
		registerHandler(new DocumentDependencyHandler());
		registerHandler(new WidgetDependencyHandler());
		registerHandler(new CategorizationDependencyHandler());
	}

	private DependencyHandlerRegistry()
	{
	}

	/**
	 * Registers the handler of a dependency type, replacing the one registered for that type, if
	 * any.
	 */
	public static void registerHandler(DependencyHandler handler)
	{
		HANDLERS.put(handler.getDependencyType(), handler);
	}

	/**
	 * Removes the handler of a dependency type.
	 */
	public static void unregisterHandler(String dependencyType)
	{
		HANDLERS.remove(dependencyType);
	}

	/**
	 * Returns the handler of a dependency type, or null.
	 */
	public static DependencyHandler getHandler(String dependencyType)
	{
		return HANDLERS.get(dependencyType);
	}

	/**
	 * Returns the registered handlers in the order they run: media, document, widget and
	 * categorization repositories, then any other type by name.
	 */
	public static List<DependencyHandler> getHandlers()
	{
		List<DependencyHandler> handlers = new ArrayList<>(HANDLERS.values());
		// The closing brace of the lambda stays alone on its line, as the style checker requires.
		// @formatter:off
		handlers.sort(Comparator.comparingInt((DependencyHandler handler) ->
		{
			int index = HANDLER_ORDER.indexOf(handler.getDependencyType());
			return index < 0 ? HANDLER_ORDER.size() : index;
		}
		).thenComparing(DependencyHandler::getDependencyType));
		// @formatter:on
		return Collections.unmodifiableList(handlers);
	}
}
