package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.lang.reflect.Method;

import org.junit.Test;

/** Tests deterministic overload selection for the portal classloader bridge. */
public class ModuleBridgeTest
{
	@Test
	public void selectsTheMostSpecificReferenceParameter() throws Exception
	{
		Method method = ModuleBridge.findCompatibleMethod(OverloadedMethods.class, "select", new Object[]{"value"}, true);
		assertEquals(String.class, method.getParameterTypes()[0]);
	}

	@Test
	public void prefersTheNarrowestPrimitiveWidening() throws Exception
	{
		Method method = ModuleBridge.findCompatibleMethod(OverloadedMethods.class, "widen", new Object[]{Integer.valueOf(1)}, true);
		assertEquals(long.class, method.getParameterTypes()[0]);
	}

	@Test
	public void rejectsEquallySpecificOverloads() throws Exception
	{
		try
		{
			ModuleBridge.findCompatibleMethod(OverloadedMethods.class, "ambiguous", new Object[]{"value"}, true);
			fail("expected ambiguous overload to be rejected");
		}
		catch (NoSuchMethodException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("ambiguous method"));
		}
	}

	public static class OverloadedMethods
	{
		public static String select(Object value)
		{
			return value.toString();
		}

		public static String select(String value)
		{
			return value;
		}

		public static String widen(long value)
		{
			return Long.toString(value);
		}

		public static String widen(double value)
		{
			return Double.toString(value);
		}

		public static String ambiguous(Serializable value)
		{
			return value.toString();
		}

		public static String ambiguous(CharSequence value)
		{
			return value.toString();
		}
	}
}
