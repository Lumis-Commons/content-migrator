package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.config;

import lumis.util.log.ILogger;
import lumis.util.log.LoggerFactory;

/**
 * System properties of the background executions of migrations, read at each start:
 * <ul>
 * <li>{@value #PROPERTY_HEARTBEAT_SECONDS}: interval of the heartbeat timer, from 1 to 60 seconds
 * (default 60).</li>
 * <li>{@value #PROPERTY_ITEM_TIME_LIMIT_SECONDS}: time an item may take; while the current item is
 * above it the heartbeat is not renewed, so a stuck run can be taken over after
 * {@code GlobalLock.STALE_MINUTES} minutes (default 1800).</li>
 * <li>{@value #PROPERTY_FAIL_AT_ITEM}: fault injection, for tests only, off by default. When set to
 * N, the N-th item copied by a run fails right before its commit.</li>
 * <li>{@value #PROPERTY_ITEM_DELAY_MILLIS}: fault injection, for tests only, off by default. Each
 * copied item waits this many milliseconds right before its commit.</li>
 * <li>{@value #PROPERTY_MAX_SELECTION}: maximum number of content ids submitted to the selection
 * REST resource or to create, counted before duplicates are dropped (default 2000).</li>
 * <li>{@value #PROPERTY_BROWSER_CACHE_SECONDS}: lifetime of the hierarchy snapshot the content
 * browser keeps per instance on each cluster node; 0 disables the cache (default 60).</li>
 * <li>{@value #PROPERTY_BROWSER_CACHE_MAX_NODES}: an instance with more contents than this is
 * read from the database on every browser request instead of being cached (default 200000).</li>
 * </ul>
 * A value that is not an integer is ignored with a warning and the default applies, because the
 * properties are read after the run has taken the global lock. The browser and selection
 * properties are read on each request.
 */
public final class ExecutionSettings
{
	public static final String PROPERTY_HEARTBEAT_SECONDS = "contentmigrator.hierarchicalcontentmigration.heartbeatSeconds";

	public static final String PROPERTY_ITEM_TIME_LIMIT_SECONDS = "contentmigrator.hierarchicalcontentmigration.itemTimeLimitSeconds";

	public static final String PROPERTY_FAIL_AT_ITEM = "contentmigrator.hierarchicalcontentmigration.faultInjection.failAtItem";

	public static final String PROPERTY_ITEM_DELAY_MILLIS = "contentmigrator.hierarchicalcontentmigration.faultInjection.itemDelayMillis";

	public static final String PROPERTY_MAX_SELECTION = "contentmigrator.hierarchicalcontentmigration.maxSelection";

	public static final String PROPERTY_BROWSER_CACHE_SECONDS = "contentmigrator.hierarchicalcontentmigration.browserCacheSeconds";

	public static final String PROPERTY_BROWSER_CACHE_MAX_NODES = "contentmigrator.hierarchicalcontentmigration.browserCacheMaxNodes";

	private static final ILogger LOGGER = LoggerFactory.getLogger(ExecutionSettings.class);

	private ExecutionSettings()
	{
	}

	/**
	 * Reads an integer system property, returning {@code defaultValue} when absent, blank, or not
	 * an integer.
	 */
	public static int intProperty(String name, int defaultValue)
	{
		String value = System.getProperty(name);
		if (value == null || value.trim().isEmpty())
		{
			return defaultValue;
		}
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (NumberFormatException exception)
		{
			LOGGER.warn("System property " + name + " is not an integer (" + value + "); using " + defaultValue);
			return defaultValue;
		}
	}

	/** Maximum number of submitted marks, before reducing them to minimal selection. */
	public static int maxSelection()
	{
		return Math.max(1, intProperty(PROPERTY_MAX_SELECTION, 2000));
	}

	/** Browser snapshot lifetime in milliseconds; zero disables caching. */
	public static long browserCacheMillis()
	{
		return Math.max(0, intProperty(PROPERTY_BROWSER_CACHE_SECONDS, 60)) * 1000L;
	}

	/** Maximum number of nodes in a retained browser snapshot. */
	public static int browserCacheMaxNodes()
	{
		return Math.max(0, intProperty(PROPERTY_BROWSER_CACHE_MAX_NODES, 200000));
	}

	/**
	 * Returns the heartbeat timer interval in seconds, clamped between 1 and 60.
	 */
	public static long heartbeatSeconds()
	{
		return Math.max(1, Math.min(60, intProperty(PROPERTY_HEARTBEAT_SECONDS, 60)));
	}

	/**
	 * Returns the maximum time in milliseconds an item may run before heartbeat renewal pauses.
	 */
	public static long itemTimeLimitMillis()
	{
		return intProperty(PROPERTY_ITEM_TIME_LIMIT_SECONDS, 1800) * 1000L;
	}
}
