package br.com.lumis.commons.contentmigrator.service.contentmigration.test.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Tests that cleanup failures are reported after all cleanup steps have run. */
public class TestCleanupTest
{
	@Test
	public void runsEveryCleanupAndReportsFailures()
	{
		AtomicInteger calls = new AtomicInteger();
		TestCleanup cleanup = new TestCleanup();
		cleanup.run(
				calls::incrementAndGet,
				() -> failCleanup(calls),
				() -> calls.incrementAndGet() - 3);

		boolean reported = false;
		try
		{
			cleanup.assertNoFailures();
		}
		catch (AssertionError e)
		{
			reported = true;
			assertTrue(e.getMessage(), e.getMessage().contains("2 failure(s)"));
		}

		assertEquals(3, calls.get());
		assertTrue("cleanup failures were not reported", reported);
	}

	private static int failCleanup(AtomicInteger calls)
	{
		calls.incrementAndGet();
		throw new IllegalStateException("fixture cleanup failed");
	}
}
