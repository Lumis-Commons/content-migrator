package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ContentMetadataFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.core.ContentVersion;
import lumis.content.table.ContentTableSource;
import lumis.doui.source.ISourceData;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;

/**
 * Proves the contract of the two DTOs of the item copy: {@code MigrationScope} validates only its
 * required fields, whether passed as null or never set on the builder, keeps every field of a
 * complete builder, keeps the sources null together and owns one warning list per item, and
 * {@code VersionCopy} requires its origin and keeps the destination locale and item null together.
 * Both are final, with private final fields and no setters. The module classes are loaded through
 * {@link ModuleBridge}.
 */
public class MigrationDtoTest
{
	private static final String SCOPE = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope";

	private static final String VERSION_COPY = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.VersionCopy";

	private static final List<String> REQUIRED_SCOPE_FIELDS = Arrays.asList(
			"session",
			"transaction",
			"connection",
			"serviceId",
			"originServiceInstanceId",
			"destinationServiceInstanceId");

	private PortalFixture fixture;

	@Before
	public void setUp()
	{
		fixture = new PortalFixture();
	}

	@After
	public void tearDown()
	{
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void scopeKeepsItsValuesAndOwnsOneWarningListWithoutDeduplication() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Object scope = build(requiredScopeValues(session, transaction));
			assertSame(session, call(scope, "getSession"));
			assertSame(transaction, call(scope, "getTransaction"));
			assertSame(connection(transaction), call(scope, "getConnection"));
			assertEquals(PortalFixture.NEWS_SERVICE_ID, call(scope, "getServiceId"));
			assertEquals("origin-instance", call(scope, "getOriginServiceInstanceId"));
			assertEquals("destination-instance", call(scope, "getDestinationServiceInstanceId"));
			assertNull("sources are optional", call(scope, "getOriginSource"));
			assertNull(call(scope, "getDestinationSource"));
			assertEquals("update mode defaults to false", Boolean.FALSE, call(scope, "isUpdateMode"));

			List<?> warnings = (List<?>) call(scope, "getWarnings");
			assertTrue("a new scope has no warnings", warnings.isEmpty());
			call(scope, "addWarning", "same warning");
			call(scope, "addWarning", "same warning");
			assertSame("the scope returns its own list, shared with the item contexts", warnings, call(scope, "getWarnings"));
			assertEquals(Arrays.asList("same warning", "same warning"), warnings);

			Map<String, Object> values = requiredScopeValues(session, transaction);
			values.put("updateMode", Boolean.TRUE);
			Object updateScope = build(values);
			assertEquals(Boolean.TRUE, call(updateScope, "isUpdateMode"));
			assertTrue("each scope starts with its own empty list", ((List<?>) call(updateScope, "getWarnings")).isEmpty());
			return null;
		});
	}

	@Test
	public void scopeRejectsEachMissingRequiredField() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			for (String field : REQUIRED_SCOPE_FIELDS)
			{
				Map<String, Object> values = requiredScopeValues(session, transaction);
				values.put(field, null);
				try
				{
					build(values);
					fail("expected the scope without " + field + " to be rejected");
				}
				catch (NullPointerException e)
				{
					assertEquals(field, e.getMessage());
				}
			}
			return null;
		});
	}

	@Test
	public void incompleteScopeBuilderRejectsEachRequiredFieldNeverSet() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			for (String field : REQUIRED_SCOPE_FIELDS)
			{
				Map<String, Object> values = requiredScopeValues(session, transaction);
				values.remove(field);
				try
				{
					build(values);
					fail("expected the scope whose builder never set " + field + " to be rejected");
				}
				catch (NullPointerException e)
				{
					assertEquals(field, e.getMessage());
				}
			}
			try
			{
				build(new LinkedHashMap<>());
				fail("expected an empty builder to be rejected");
			}
			catch (NullPointerException e)
			{
				assertEquals("the first required field is checked first", REQUIRED_SCOPE_FIELDS.get(0), e.getMessage());
			}
			return null;
		});
	}

	@Test
	public void completeScopeBuilderKeepsEveryField() throws Exception
	{
		ServiceInstanceConfig news = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.NEWS_SERVICE_ID);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentTableSource origin = ContentMetadataFixture.newsSource(session, transaction, news.getId());
			ContentTableSource destination = ContentMetadataFixture.newsSource(session, transaction, news.getId());
			Map<String, Object> values = requiredScopeValues(session, transaction);
			values.put("sources", new Object[]{origin, destination});
			values.put("updateMode", Boolean.TRUE);
			Object scope = build(values);
			assertSame(session, call(scope, "getSession"));
			assertSame(transaction, call(scope, "getTransaction"));
			assertSame(connection(transaction), call(scope, "getConnection"));
			assertEquals(PortalFixture.NEWS_SERVICE_ID, call(scope, "getServiceId"));
			assertEquals("origin-instance", call(scope, "getOriginServiceInstanceId"));
			assertEquals("destination-instance", call(scope, "getDestinationServiceInstanceId"));
			assertSame(origin, call(scope, "getOriginSource"));
			assertSame(destination, call(scope, "getDestinationSource"));
			assertEquals(Boolean.TRUE, call(scope, "isUpdateMode"));
			assertTrue(((List<?>) call(scope, "getWarnings")).isEmpty());

			values.put("sources", new Object[]{null, null});
			Object withoutSources = build(values);
			assertNull("both sources set to null are accepted", call(withoutSources, "getOriginSource"));
			assertNull(call(withoutSources, "getDestinationSource"));
			return null;
		});
	}

	@Test
	public void scopeSourcesAreBothSetOrBothNull() throws Exception
	{
		ServiceInstanceConfig news = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.NEWS_SERVICE_ID);
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentTableSource origin = ContentMetadataFixture.newsSource(session, transaction, news.getId());
			ContentTableSource destination = ContentMetadataFixture.newsSource(session, transaction, news.getId());
			Map<String, Object> values = requiredScopeValues(session, transaction);
			values.put("sources", new Object[]{origin, destination});
			Object scope = build(values);
			assertSame(origin, call(scope, "getOriginSource"));
			assertSame(destination, call(scope, "getDestinationSource"));

			for (Object[] sources : Arrays.asList(new Object[]{origin, null}, new Object[]{null, destination}))
			{
				values.put("sources", sources);
				try
				{
					build(values);
					fail("expected a scope with only one source to be rejected");
				}
				catch (IllegalStateException e)
				{
					assertTrue(e.getMessage(), e.getMessage().contains("both"));
				}
			}
			return null;
		});
	}

	@Test
	public void versionCopyRequiresItsOriginAndKeepsTheDestinationLocaleAndItemTogether() throws Exception
	{
		ISourceData row = sourceRow();
		ContentVersion version = new ContentVersion();

		Object added = newVersionCopy(row, version, null, null, null);
		assertSame(row, call(added, "getOriginRow"));
		assertSame(version, call(added, "getOriginVersion"));
		assertNull(call(added, "getDestinationContentId"));
		assertEquals("a new locale is an addData", Boolean.FALSE, call(added, "hasDestinationLocale"));

		Object translated = newVersionCopy(row, version, "content", null, null);
		assertEquals("content", call(translated, "getDestinationContentId"));
		assertEquals(Boolean.FALSE, call(translated, "hasDestinationLocale"));

		Object updated = newVersionCopy(row, version, "content", "locale", "item");
		assertEquals("locale", call(updated, "getDestinationContentLocaleId"));
		assertEquals("item", call(updated, "getDestinationItemId"));
		assertEquals("an existing locale is an updateData", Boolean.TRUE, call(updated, "hasDestinationLocale"));

		assertRejected(NullPointerException.class, "originRow", null, version, null, null, null);
		assertRejected(NullPointerException.class, "originVersion", row, null, null, null, null);
		assertRejected(IllegalArgumentException.class, "both", row, version, "content", "locale", null);
		assertRejected(IllegalArgumentException.class, "both", row, version, "content", null, "item");
	}

	@Test
	public void dtosAreFinalWithPrivateFinalFieldsNoSettersAndOneWayToCreate() throws Exception
	{
		Class<?> scope = ModuleBridge.load(SCOPE);
		Class<?> builder = ModuleBridge.load(SCOPE + "$Builder");
		Class<?> versionCopy = ModuleBridge.load(VERSION_COPY);
		for (Class<?> type : Arrays.asList(scope, versionCopy))
		{
			assertImmutable(type);
		}
		assertEquals("the scope is created only by its builder", 0, scope.getConstructors().length);
		assertEquals("the builder is obtained only by MigrationScope.builder()", 0, builder.getConstructors().length);
		assertTrue(Modifier.isFinal(builder.getModifiers()));
		assertSame(builder, ModuleBridge.invokeStatic(SCOPE, "builder").getClass());
		assertEquals(1, versionCopy.getConstructors().length);
		assertEquals("five fields, so a constructor and no builder", 5, versionCopy.getConstructors()[0].getParameterCount());
	}

	private static void assertImmutable(Class<?> type)
	{
		assertTrue(type.getName() + " is final", Modifier.isFinal(type.getModifiers()));
		for (Field field : type.getDeclaredFields())
		{
			if (field.isSynthetic())
			{
				continue;
			}
			int modifiers = field.getModifiers();
			assertTrue(type.getName() + "." + field.getName() + " is private", Modifier.isPrivate(modifiers));
			assertTrue(type.getName() + "." + field.getName() + " is final", Modifier.isFinal(modifiers));
		}
		for (Method method : type.getMethods())
		{
			assertFalse(type.getName() + "." + method.getName() + " is a setter", method.getName().startsWith("set"));
		}
	}

	private static Map<String, Object> requiredScopeValues(SessionConfig session, ITransaction transaction) throws Exception
	{
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("session", session);
		values.put("transaction", transaction);
		values.put("connection", connection(transaction));
		values.put("serviceId", PortalFixture.NEWS_SERVICE_ID);
		values.put("originServiceInstanceId", "origin-instance");
		values.put("destinationServiceInstanceId", "destination-instance");
		return values;
	}

	private static Connection connection(ITransaction transaction) throws Exception
	{
		return ((ITransactionJdbc) transaction).getConnection();
	}

	/**
	 * Builds a scope by calling the builder method of the same name as each key; {@code sources}
	 * takes an array with the origin and destination sources.
	 */
	private static Object build(Map<String, Object> values) throws Exception
	{
		Object builder = ModuleBridge.invokeStatic(SCOPE, "builder");
		for (Map.Entry<String, Object> entry : values.entrySet())
		{
			String name = entry.getKey();
			Object value = entry.getValue();
			if ("sources".equals(name))
			{
				Object[] sources = (Object[]) value;
				invoke(builder.getClass().getMethod(name, ContentTableSource.class, ContentTableSource.class), builder, sources[0], sources[1]);
				continue;
			}
			invoke(builderMethod(builder.getClass(), name), builder, value);
		}
		return invoke(builder.getClass().getMethod("build"), builder);
	}

	private static Method builderMethod(Class<?> builderType, String name) throws NoSuchMethodException
	{
		for (Method method : builderType.getMethods())
		{
			if (method.getName().equals(name) && method.getParameterCount() == 1)
			{
				return method;
			}
		}
		throw new NoSuchMethodException(builderType.getName() + "." + name);
	}

	private static Object newVersionCopy(Object... args) throws Exception
	{
		Constructor<?> constructor = ModuleBridge.load(VERSION_COPY)
				.getConstructor(ISourceData.class, ContentVersion.class, String.class, String.class, String.class);
		try
		{
			return constructor.newInstance(args);
		}
		catch (InvocationTargetException e)
		{
			throw unwrap(e);
		}
	}

	private static void assertRejected(Class<? extends Exception> expected, String messagePart, Object... args) throws Exception
	{
		try
		{
			newVersionCopy(args);
			fail("expected " + expected.getSimpleName() + " for " + Arrays.toString(args));
		}
		catch (Exception e)
		{
			assertEquals(expected, e.getClass());
			assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
		}
	}

	private static Object call(Object target, String methodName, Object... args) throws Exception
	{
		for (Method method : target.getClass().getMethods())
		{
			if (method.getName().equals(methodName) && method.getParameterCount() == args.length)
			{
				return invoke(method, target, args);
			}
		}
		throw new NoSuchMethodException(target.getClass().getName() + "." + methodName + "/" + args.length);
	}

	private static Object invoke(Method method, Object target, Object... args) throws Exception
	{
		try
		{
			return method.invoke(target, args);
		}
		catch (InvocationTargetException e)
		{
			throw unwrap(e);
		}
	}

	private static Exception unwrap(InvocationTargetException e)
	{
		Throwable cause = e.getCause();
		if (cause instanceof Exception)
		{
			return (Exception) cause;
		}
		return e;
	}

	/** A source row that is only stored, never read. */
	private static ISourceData sourceRow()
	{
		return (ISourceData) Proxy.newProxyInstance(ISourceData.class.getClassLoader(), new Class<?>[]{ISourceData.class}, (proxy, method, args) ->
		{
			if ("equals".equals(method.getName()))
			{
				return proxy == args[0];
			}
			if ("hashCode".equals(method.getName()))
			{
				return System.identityHashCode(proxy);
			}
			if ("toString".equals(method.getName()))
			{
				return "row";
			}
			throw new UnsupportedOperationException(method.getName());
		});
	}
}
