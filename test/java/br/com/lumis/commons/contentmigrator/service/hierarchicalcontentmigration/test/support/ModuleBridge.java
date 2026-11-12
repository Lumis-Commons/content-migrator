package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import lumis.portal.manager.ManagerFactory;

/**
 * Calls classes of the br.com.lumis.commons.contentmigrator module from the test JVM. The module's classes are not on
 * the test classpath; they are loaded through the class loader of the portal deployment, by
 * reflection.
 */
public class ModuleBridge
{
	public static final String API = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.api.MigrationApi";

	public static final String ITEM_MAP_DAO = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.ItemMapDao";

	public static final String WIDGET_MAP_DAO = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.WidgetMapDao";

	public static final String ITEM_MAP_ROW = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.ItemMapRow";

	public static final String MIGRATION_SCOPE = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationScope";

	public static final String REPOSITORY_PAIR = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RepositoryPair";

	public static final String DEPENDENCY_CONTEXT = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext";

	public static final String FILE_CLONER = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.FileCloner";

	static
	{
		PortalFixture.keepTestData(); // loads PortalFixture, which boots the portal
	}

	/**
	 * Loads a class of the module.
	 */
	public static Class<?> load(String className) throws ClassNotFoundException
	{
		return ManagerFactory.getDeploymentManager().getClassLoader().loadClass(className);
	}

	/**
	 * Invokes a public static method of a module class by name and compatible argument types. An
	 * exception thrown by the method is rethrown as is.
	 */
	public static Object invokeStatic(String className, String methodName, Object... args) throws Exception
	{
		Class<?> type = load(className);
		Method method = findCompatibleMethod(type, methodName, args, true);
		try
		{
			return method.invoke(null, args);
		}
		catch (InvocationTargetException e)
		{
			Throwable cause = e.getCause();
			if (cause instanceof Exception)
			{
				throw (Exception) cause;
			}
			throw e;
		}
	}

	static Method findCompatibleMethod(Class<?> type, String methodName, Object[] args, boolean requireStatic) throws NoSuchMethodException
	{
		Method best = null;
		int bestScore = Integer.MAX_VALUE;
		boolean ambiguous = false;
		for (Method candidate : type.getMethods())
		{
			if (!candidate.getName().equals(methodName))
			{
				continue;
			}
			if (candidate.getParameterCount() != args.length)
			{
				continue;
			}
			if (candidate.isBridge() || candidate.isSynthetic())
			{
				continue;
			}
			if (Modifier.isStatic(candidate.getModifiers()) != requireStatic)
			{
				continue;
			}

			int score = compatibilityScore(candidate.getParameterTypes(), args);
			if (score < 0)
			{
				continue;
			}
			if (score < bestScore)
			{
				best = candidate;
				bestScore = score;
				ambiguous = false;
			}
			else if (score == bestScore)
			{
				ambiguous = true;
			}
		}
		if (best == null || ambiguous)
		{
			String reason = ambiguous ? "ambiguous method" : "no compatible method";
			throw new NoSuchMethodException(reason + ": " + type.getName() + "." + methodName + "/" + args.length);
		}
		return best;
	}

	private static int compatibilityScore(Class<?>[] parameterTypes, Object[] args)
	{
		int score = 0;
		for (int index = 0; index < parameterTypes.length; index++)
		{
			Class<?> parameterType = parameterTypes[index];
			Object argument = args[index];
			if (argument == null)
			{
				if (parameterType.isPrimitive())
				{
					return -1;
				}
				score += 50;
				continue;
			}

			Class<?> argumentType = argument.getClass();
			if (parameterType.isPrimitive())
			{
				int wideningDistance = primitiveWideningDistance(wrapperPrimitive(argumentType), parameterType);
				if (wideningDistance < 0)
				{
					return -1;
				}
				score += wideningDistance * 10;
			}
			else if (parameterType.isAssignableFrom(argumentType))
			{
				score += referenceDistance(argumentType, parameterType);
			}
			else
			{
				return -1;
			}
		}
		return score;
	}

	private static int referenceDistance(Class<?> actualType, Class<?> expectedType)
	{
		Queue<Class<?>> pending = new ArrayDeque<>();
		Map<Class<?>, Integer> distances = new HashMap<>();
		pending.add(actualType);
		distances.put(actualType, 0);
		while (!pending.isEmpty())
		{
			Class<?> current = pending.remove();
			int distance = distances.get(current);
			if (current.equals(expectedType))
			{
				return distance;
			}
			Class<?> parent = current.getSuperclass();
			if (parent != null && !distances.containsKey(parent))
			{
				pending.add(parent);
				distances.put(parent, distance + 1);
			}
			for (Class<?> interfaceType : current.getInterfaces())
			{
				if (!distances.containsKey(interfaceType))
				{
					pending.add(interfaceType);
					distances.put(interfaceType, distance + 1);
				}
			}
		}
		return Integer.MAX_VALUE;
	}

	private static Class<?> wrapperPrimitive(Class<?> wrapperType)
	{
		if (wrapperType == Boolean.class)
		{
			return boolean.class;
		}
		if (wrapperType == Byte.class)
		{
			return byte.class;
		}
		if (wrapperType == Short.class)
		{
			return short.class;
		}
		if (wrapperType == Character.class)
		{
			return char.class;
		}
		if (wrapperType == Integer.class)
		{
			return int.class;
		}
		if (wrapperType == Long.class)
		{
			return long.class;
		}
		if (wrapperType == Float.class)
		{
			return float.class;
		}
		if (wrapperType == Double.class)
		{
			return double.class;
		}
		return wrapperType;
	}

	private static int primitiveWideningDistance(Class<?> actualType, Class<?> expectedType)
	{
		if (actualType == expectedType)
		{
			return 0;
		}
		if (actualType == boolean.class || expectedType == boolean.class)
		{
			return -1;
		}
		if (expectedType == char.class)
		{
			return -1;
		}
		int actualRank = primitiveRank(actualType);
		int expectedRank = primitiveRank(expectedType);
		if (actualRank < 0 || expectedRank <= actualRank)
		{
			return -1;
		}
		return expectedRank - actualRank;
	}

	private static int primitiveRank(Class<?> primitiveType)
	{
		if (primitiveType == byte.class)
		{
			return 0;
		}
		if (primitiveType == short.class || primitiveType == char.class)
		{
			return 1;
		}
		if (primitiveType == int.class)
		{
			return 2;
		}
		if (primitiveType == long.class)
		{
			return 3;
		}
		if (primitiveType == float.class)
		{
			return 4;
		}
		if (primitiveType == double.class)
		{
			return 5;
		}
		return -1;
	}

	/**
	 * Invokes a method of {@value #API}.
	 */
	public static Object api(String methodName, Object... args) throws Exception
	{
		return invokeStatic(API, methodName, args);
	}

	/**
	 * Inserts a row of lum_cmig_HMigItemMap through {@value #ITEM_MAP_DAO}, from the column values by
	 * name; absent ones are left null (destCreated false).
	 */
	public static void insertItemMap(Connection connection, Map<String, Object> values) throws Exception
	{
		ModuleBridge.invokeStatic(ITEM_MAP_DAO, "insertItemMap", connection, newRow(ITEM_MAP_ROW, values));
	}

	/**
	 * Creates a row DTO of the module and sets each value through the setter of the same name.
	 */
	public static Object newRow(String className, Map<String, Object> values) throws Exception
	{
		Class<?> type = load(className);
		Object row = type.getConstructor().newInstance();
		for (Map.Entry<String, Object> entry : values.entrySet())
		{
			String key = entry.getKey();
			String setter = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
			Method method = findCompatibleMethod(type, setter, new Object[]{entry.getValue()}, false);
			method.invoke(row, entry.getValue());
		}
		return row;
	}

	/**
	 * Creates a dependency context of the module, with no file cloner, between two repositories.
	 * The {@value #MIGRATION_SCOPE} is built by calling the builder method of the same name as each
	 * key, as {@link #newRow} does with the setters.
	 */
	public static Object newDependencyContext(Map<String, Object> scope, String originRepositoryId, String destinationRepositoryId) throws Exception
	{
		Class<?> scopeType = load(MIGRATION_SCOPE);
		Object builder = scopeType.getMethod("builder").invoke(null);
		for (Map.Entry<String, Object> entry : scope.entrySet())
		{
			Method method = findCompatibleMethod(builder.getClass(), entry.getKey(), new Object[]{entry.getValue()}, false);
			method.invoke(builder, entry.getValue());
		}
		Object migrationScope = builder.getClass().getMethod("build").invoke(builder);
		Object repositories = load(REPOSITORY_PAIR).getConstructor(String.class, String.class, String.class)
				.newInstance(originRepositoryId, destinationRepositoryId, null);
		return load(DEPENDENCY_CONTEXT).getConstructor(scopeType, repositories.getClass(), load(FILE_CLONER))
				.newInstance(migrationScope, repositories, null);
	}

	/**
	 * Returns the value of the {@code code} of a module exception thrown by the API, or null.
	 */
	public static String errorCode(Throwable e) throws Exception
	{
		return (String) e.getClass().getMethod("getCode").invoke(e);
	}
}
