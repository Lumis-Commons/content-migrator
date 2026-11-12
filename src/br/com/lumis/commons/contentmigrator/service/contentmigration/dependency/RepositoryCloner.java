package br.com.lumis.commons.contentmigrator.service.contentmigration.dependency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.ItemMapDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.FileCopy;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.ItemMapRow;
import lumis.doui.source.field.type.FileDataType;
import lumis.util.PortalUtil;

/**
 * Resolves repository items through the migration map, cloning their folder hierarchy before the
 * item. The repository-specific adapter owns source fields and native add/update operations; this
 * class owns map identity, stale-map recovery, folder reuse, update decisions and fingerprints.
 *
 * @param <T> the repository entry type.
 */
public final class RepositoryCloner<T>
{
	public static final String SOURCE_DEFAULT = "default";

	public static final String KIND_FOLDER = "FOLDER";

	/** One-shot duplicate-map fault used by the media migration integration test. */
	public static final String PROPERTY_FAULT_UNIQUE_MAP_ONCE = "contentmigrator.contentmigration.faultInjection.repositoryMapUniqueOnce";

	/** The repository-specific operations needed by the generic map and hierarchy algorithm. */
	public interface Adapter<T>
	{
		String repositoryServiceId();

		/** Source used by the repository map and by the native table source. */
		default String sourceId()
		{
			return SOURCE_DEFAULT;
		}

		T loadOrigin(String itemId) throws Exception;

		T loadDestination(String itemId) throws Exception;

		String itemId(T item);

		String kind(T item);

		boolean isFolder(T item);

		String contentId(T item);

		String contentLocaleId(T item);

		String locale(T item);

		String parentId(T item);

		String title(T item);

		FileCopy copyFile(T item) throws Exception;

		T findFolder(String destinationParentId, String title) throws Exception;

		T createFolder(T origin, String destinationParentId, String destinationContentId) throws Exception;

		T updateFolder(T origin, T destination, String destinationParentId) throws Exception;

		T createItem(T origin, String destinationParentId, String destinationContentId, FileDataType.Data file) throws Exception;

		T updateItem(T origin, T destination, String destinationParentId, FileDataType.Data file, boolean fileChanged) throws Exception;
	}

	private final DependencyContext context;

	private final Adapter<T> adapter;

	public RepositoryCloner(DependencyContext context, Adapter<T> adapter)
	{
		this.context = context;
		this.adapter = adapter;
	}

	/** Returns the destination item id, creating or updating it as required by the context. */
	public String resolve(String originItemId) throws Exception
	{
		T origin = adapter.loadOrigin(originItemId);
		if (origin == null)
		{
			throw new IllegalStateException("Repository item " + originItemId + " no longer exists.");
		}
		return resolve(origin, new HashSet<>());
	}

	private String resolve(T origin, Set<String> foldersBeingResolved) throws Exception
	{
		if (adapter.isFolder(origin))
		{
			return resolveFolder(origin, foldersBeingResolved);
		}

		String destParentId = resolveParent(origin, foldersBeingResolved);
		MapRow map = findMap(origin);
		T destination = map == null ? null : adapter.loadDestination(map.destItemId);
		if (destination != null && !context.isUpdateMode())
		{
			return adapter.itemId(destination);
		}

		FileCopy copiedFile = adapter.copyFile(origin);
		String fingerprint = copiedFile.getFingerprint();
		boolean created = destination == null;
		if (created)
		{
			destination = adapter.createItem(origin, destParentId, mappedDestinationContentId(origin), copiedFile.getData());
		}
		else
		{
			destination = adapter.updateItem(origin, destination, destParentId, copiedFile.getData(), !fingerprint.equals(map.originFileFingerprint));
		}
		saveMap(origin, destination, map, created || map != null && map.destCreated, fingerprint);
		return adapter.itemId(destination);
	}

	private String resolveFolder(T origin, Set<String> foldersBeingResolved) throws Exception
	{
		String sourceId = adapter.itemId(origin);
		if (!foldersBeingResolved.add(sourceId))
		{
			throw new IllegalStateException("Folder hierarchy contains a cycle at " + sourceId + ".");
		}
		try
		{
			String destParentId = resolveParent(origin, foldersBeingResolved);
			MapRow map = findMap(origin);
			T destination = map == null ? null : adapter.loadDestination(map.destItemId);
			if (destination == null)
			{
				destination = adapter.findFolder(destParentId, adapter.title(origin));
				if (destination != null)
				{
					saveMap(origin, destination, map, false, null);
					return adapter.itemId(destination);
				}
				destination = adapter.createFolder(origin, destParentId, mappedDestinationContentId(origin));
				saveMap(origin, destination, map, true, null);
				return adapter.itemId(destination);
			}

			if (context.isUpdateMode())
			{
				destination = adapter.updateFolder(origin, destination, destParentId);
			}
			saveMap(origin, destination, map, map.destCreated, null);
			return adapter.itemId(destination);
		}
		finally
		{
			foldersBeingResolved.remove(sourceId);
		}
	}

	private String resolveParent(T origin, Set<String> foldersBeingResolved) throws Exception
	{
		String parentId = adapter.parentId(origin);
		if (parentId == null)
		{
			return null;
		}
		T parent = adapter.loadOrigin(parentId);
		if (parent == null || !adapter.isFolder(parent))
		{
			context.addWarning("Repository parent folder " + parentId + " no longer exists; item was placed at root.");
			return null;
		}
		return resolveFolder(parent, foldersBeingResolved);
	}

	private MapRow findMap(T origin) throws Exception
	{
		// @formatter:off
		String sql = "select id, destItemId, destContentLocaleId, originFileFingerprint, destCreated "
				+ "from lum_cmig_MigItemMap where originServiceInstanceId = ? and destServiceInstanceId = ? "
				+ "and sourceId = ? and originContentLocaleId = ? and kind = ?";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(sql))
		{
			statement.setString(1, context.getOriginRepositoryInstanceId());
			statement.setString(2, context.getDestRepositoryInstanceId());
			statement.setString(3, adapter.sourceId());
			statement.setString(4, adapter.contentLocaleId(origin));
			statement.setString(5, adapter.kind(origin));
			try (ResultSet rs = statement.executeQuery())
			{
				if (!rs.next())
				{
					return null;
				}
				return new MapRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5));
			}
		}
	}

	private String mappedDestinationContentId(T origin) throws Exception
	{
		// @formatter:off
		String sql = "select m.destContentId from lum_cmig_MigItemMap m where m.originServiceInstanceId = ? "
				+ "and m.destServiceInstanceId = ? and m.originContentId = ? and m.destContentId is not null "
				+ "and m.kind <> ? and exists (select 1 from lum_Content c where c.id = m.destContentId)";
		// @formatter:on
		try (PreparedStatement statement = context.getConnection().prepareStatement(sql))
		{
			statement.setString(1, context.getOriginRepositoryInstanceId());
			statement.setString(2, context.getDestRepositoryInstanceId());
			statement.setString(3, adapter.contentId(origin));
			statement.setString(4, "CONTENT");
			try (ResultSet rs = statement.executeQuery())
			{
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private void saveMap(T origin, T destination, MapRow existing, boolean destCreated, String fingerprint) throws Exception
	{
		ItemMapRow values = new ItemMapRow();
		values.setId(existing == null ? PortalUtil.generateNewGuid() : existing.id);
		values.setKind(adapter.kind(origin));
		values.setServiceId(adapter.repositoryServiceId());
		values.setSourceId(adapter.sourceId());
		values.setLocale(adapter.locale(origin));
		values.setWarnings("");
		values.setDestCreated(destCreated);
		values.setOriginServiceInstanceId(context.getOriginRepositoryInstanceId());
		values.setOriginContentId(adapter.contentId(origin));
		values.setOriginContentLocaleId(adapter.contentLocaleId(origin));
		values.setOriginItemId(adapter.itemId(origin));
		values.setOriginFileFingerprint(fingerprint);
		values.setDestServiceInstanceId(context.getDestRepositoryInstanceId());
		values.setDestContentId(adapter.contentId(destination));
		values.setDestContentLocaleId(adapter.contentLocaleId(destination));
		values.setDestItemId(adapter.itemId(destination));
		if (existing == null)
		{
			if (Boolean.getBoolean(PROPERTY_FAULT_UNIQUE_MAP_ONCE))
			{
				System.clearProperty(PROPERTY_FAULT_UNIQUE_MAP_ONCE);
				throw new SQLException("Simulated duplicate key LUM_UN_CMIG_MIGITEMMAP.", "23000");
			}
			ItemMapDao.insertItemMap(context.getConnection(), values);
		}
		else
		{
			updateMap(context.getConnection(), values);
		}
	}

	private static void updateMap(Connection connection, ItemMapRow values) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_MigItemMap set kind = ?, serviceId = ?, locale = ?, warnings = ?, "
				+ "destCreated = ?, originContentId = ?, originItemId = ?, originFileFingerprint = ?, "
				+ "destContentId = ?, destContentLocaleId = ?, destItemId = ? where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, values.getKind());
			statement.setString(2, values.getServiceId());
			statement.setString(3, values.getLocale());
			statement.setString(4, values.getWarnings());
			statement.setBoolean(5, values.isDestCreated());
			statement.setString(6, values.getOriginContentId());
			statement.setString(7, values.getOriginItemId());
			statement.setString(8, values.getOriginFileFingerprint());
			statement.setString(9, values.getDestContentId());
			statement.setString(10, values.getDestContentLocaleId());
			statement.setString(11, values.getDestItemId());
			statement.setString(12, values.getId());
			if (statement.executeUpdate() != 1)
			{
				throw new SQLException("Repository map " + values.getId() + " was not updated.");
			}
		}
	}

	/**
	 * Returns true when the message of the error, of one of its causes or of a chained SQL exception
	 * names the unique key LUM_UN_CMIG_MIGITEMMAP, in any case.
	 */
	public static boolean isUniqueMapConflict(Throwable error)
	{
		Set<Throwable> seen = new HashSet<>();
		for (Throwable current = error; current != null && seen.add(current); current = current.getCause())
		{
			if (mentionsUniqueMapKey(current))
			{
				return true;
			}
			if (current instanceof SQLException)
			{
				for (SQLException next = ((SQLException) current).getNextException(); next != null; next = next.getNextException())
				{
					if (mentionsUniqueMapKey(next))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean mentionsUniqueMapKey(Throwable error)
	{
		String message = error.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("lum_un_cmig_migitemmap");
	}

	private static final class MapRow
	{
		final String id;

		final String destItemId;

		@SuppressWarnings("unused")
		final String destContentLocaleId;

		final String originFileFingerprint;

		final boolean destCreated;

		MapRow(String id, String destItemId, String destContentLocaleId, String originFileFingerprint, boolean destCreated)
		{
			this.id = id;
			this.destItemId = destItemId;
			this.destContentLocaleId = destContentLocaleId;
			this.originFileFingerprint = originFileFingerprint;
			this.destCreated = destCreated;
		}
	}
}
