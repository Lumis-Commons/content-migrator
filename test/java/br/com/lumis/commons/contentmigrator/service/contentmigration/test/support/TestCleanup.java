package br.com.lumis.commons.contentmigrator.service.contentmigration.test.support;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

/** Collects cleanup results and fails the test after every registered cleanup has run. */
public final class TestCleanup
{
	@FunctionalInterface
	public interface Cleanup
	{
		int run();
	}

	private final List<Throwable> exceptions = new ArrayList<>();

	private int failures;

	/** Runs every cleanup, collecting its reported failures and runtime exceptions. */
	public void run(Cleanup... cleanups)
	{
		for (Cleanup cleanup : cleanups)
		{
			try
			{
				failures += cleanup.run();
			}
			catch (RuntimeException | AssertionError e)
			{
				failures++;
				exceptions.add(e);
			}
		}
	}

	/** Records a cleanup error while allowing remaining cleanup steps to run. */
	public void recordFailure(String description, Throwable cause)
	{
		AssertionError failure = new AssertionError(description);
		failure.initCause(cause);
		exceptions.add(failure);
		failures++;
	}

	/** Fails after cleanup if a fixture returned failures or threw an exception. */
	public void assertNoFailures()
	{
		if (!exceptions.isEmpty())
		{
			AssertionError failure = new AssertionError("cleanup had " + failures + " failure(s)");
			for (Throwable exception : exceptions)
			{
				failure.addSuppressed(exception);
			}
			throw failure;
		}
		assertEquals("fixture cleanup failures", 0, failures);
	}

	/** Runs every cleanup and then asserts that cleanup succeeded. */
	public static void assertNoFailures(Cleanup... cleanups)
	{
		TestCleanup result = new TestCleanup();
		result.run(cleanups);
		result.assertNoFailures();
	}
}
