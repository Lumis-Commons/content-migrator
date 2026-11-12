package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support;

import static org.junit.Assert.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.Test;

import lumis.portal.dao.jdbc.ITransactionJdbc;

/** Final integration check: fixture records must not survive the test suite. */
public class NoLeftoverDataTest
{
	@Test
	public void noTestRecordsOrOrphanSelectionsRemain() throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			assertEmpty(connection, "lum_HierarchicalContent", "title");
			assertEmpty(connection, "lum_Channel", "name");
			assertEmpty(connection, "lum_ServiceInstance", "name");
			assertEmpty(connection, "lum_User", "login");
			assertEmpty(connection, "lum_cmig_Migration", "name");
			assertEmpty(connection, "lum_cmig_HMigration", "name");
			assertCountZero(connection, "orphan hierarchical selections", "select count(*) from lum_cmig_HMigSelection s "
					+ "where not exists (select 1 from lum_cmig_HMigration m where m.id = s.migrationId)");
			return null;
		});
	}

	private static void assertEmpty(Connection connection, String table, String column) throws Exception
	{
		assertCountZero(connection, table, "select count(*) from " + table + " where " + column + " like 'contentmigrator-test-%'");
	}

	private static void assertCountZero(Connection connection, String description, String sql) throws Exception
	{
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery())
		{
			rows.next();
			assertEquals("Leftover data in " + description, 0, rows.getLong(1));
		}
	}
}
