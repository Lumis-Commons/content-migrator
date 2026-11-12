package br.com.lumis.commons.contentmigrator.service.contentmigration.copy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Node;

import br.com.lumis.commons.contentmigrator.service.contentmigration.config.ExecutionSettings;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dao.ItemMapDao;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.DependencyHandler;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.FileCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dependency.RepositoryCloner;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.ItemMapRow;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.ItemOutcome;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationItem;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationRow;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.MigrationScope;
import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.VersionCopy;
import br.com.lumis.commons.contentmigrator.service.contentmigration.lock.GlobalLock;
import lumis.content.ContentManagerFactory;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;
import lumis.content.core.LockType;
import lumis.content.source.field.MetaDataSourceField.Type;
import lumis.content.table.ContentTableSource;
import lumis.content.table.IContentTableSourceAddDataResult;
import lumis.content.table.IContentTableSourceUpdateDataResult;
import lumis.content.workflow.ContentWorkflowMetaData;
import lumis.doui.DouiManagerFactory;
import lumis.doui.source.ISourceData;
import lumis.doui.source.Source;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.transaction.ITransaction;
import lumis.portal.transaction.PortalTransactionFactory;
import lumis.util.PortalUtil;
import lumis.util.XmlUtil;

/**
 * Copies one origin {@link ContentLocale} to the destination in an isolated item transaction that
 * also writes its {@code lum_cmig_MigItemMap} row and confirms, before commit, that the run still
 * holds the {@link GlobalLock}.
 */
public class ItemProcessor
{
	public static final String KIND_CONTENT = "CONTENT";

	private final String runId;

	private final String serviceId;

	private final String originServiceInstanceId;

	private final String destinationServiceInstanceId;

	private final SessionConfig session;

	private final String updateMode;

	/**
	 * Creates an item processor for the service, instances and update mode of the given migration:
	 * {@code ADD_ONLY} (also when null) skips an item whose mapped destination still exists, and any
	 * other value ({@code UPDATE} is the other one accepted by {@code MigrationApi}) writes the origin
	 * over it. The values are copied, so later changes to the row do not affect the processor.
	 */
	public ItemProcessor(String runId, SessionConfig session, MigrationRow migration)
	{
		this.runId = runId;
		this.session = session;
		this.serviceId = migration.getServiceId();
		this.originServiceInstanceId = migration.getOriginServiceInstanceId();
		this.destinationServiceInstanceId = migration.getDestServiceInstanceId();
		this.updateMode = migration.getUpdateMode() == null ? "ADD_ONLY" : migration.getUpdateMode();
	}

	/**
	 * Discovers the {@link ContentLocale} rows of the content sources of the origin instance. The
	 * default locales of all contents come first, so each destination {@code Content} is created by
	 * its default locale and the translations join it; within each group, the oldest come first.
	 */
	public List<MigrationItem> discover() throws Exception
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			ServiceInstanceConfig origin = ManagerFactory.getServiceInstanceManager().get(originServiceInstanceId, transaction);
			Set<String> sourceIds = findContentSourceIds(session, origin, transaction);
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			List<MigrationItem> items = queryOriginItems(connection, sourceIds);
			transaction.commit();
			return items;
		}
	}

	private static Set<String> findContentSourceIds(SessionConfig session, ServiceInstanceConfig instance, ITransaction transaction) throws Exception
	{
		Node definition = DouiManagerFactory.getDouiManager().getDouiServiceDefinitionNode(instance.getServiceId(), transaction);
		Set<String> ids = new LinkedHashSet<>();
		for (Node sourceNode : XmlUtil.selectNodes("service/sources/source", definition))
		{
			String id = XmlUtil.readAttributeString("id", sourceNode);
			Source<?> source = SourceFactory.createSource(id, new SourceContext(session, instance, transaction));
			if (source instanceof ContentTableSource)
			{
				ids.add(id);
			}
		}
		return ids;
	}

	private List<MigrationItem> queryOriginItems(Connection connection, Set<String> sourceIds) throws SQLException
	{
		List<MigrationItem> items = new ArrayList<>();
		// @formatter:off
		String sql = "select cl.id, c.id, c.sourceId, cl.locale "
				+ "from lum_ContentLocale cl "
				+ "inner join lum_Content c on c.id = cl.contentId "
				+ "where c.serviceInstanceId = ? "
				+ "order by case when cl.locale = c.defaultLocale then 0 else 1 end, cl.createdDateTime, cl.id";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originServiceInstanceId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				while (resultSet.next())
				{
					if (sourceIds.contains(resultSet.getString(3)))
					{
						items.add(new MigrationItem(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4)));
					}
				}
			}
		}
		return items;
	}

	/**
	 * Copies or updates one item in its own transaction. Returns {@link ItemOutcome#SKIPPED} when
	 * {@code ADD_ONLY} finds the mapped destination, {@link ItemOutcome#LOCK_LOST} when the run no
	 * longer holds the lock (the item is rolled back) and {@link ItemOutcome#COPIED} otherwise. The
	 * item is retried once, in a new transaction, when a repository clone map row hits the unique
	 * key {@code LUM_UN_CMIG_MIGITEMMAP}.
	 */
	public ItemOutcome process(MigrationItem item, int ordinal) throws Exception
	{
		try
		{
			return processOnce(item, ordinal);
		}
		catch (Exception exception)
		{
			if (RepositoryCloner.isUniqueMapConflict(exception))
			{
				return processOnce(item, ordinal);
			}
			throw exception;
		}
	}

	private ItemOutcome processOnce(MigrationItem item, int ordinal) throws Exception
	{
		try (ITransaction transaction = PortalTransactionFactory.createTransaction())
		{
			transaction.begin();
			Connection connection = ((ITransactionJdbc) transaction).getConnection();
			ItemMapRow map = findItemMap(connection, item);
			String mappedLocaleId = map == null ? null : map.getDestContentLocaleId();
			boolean isDestinationPresent = mappedLocaleId != null && hasDestinationContentLocale(connection, mappedLocaleId);
			if (isDestinationPresent && !isUpdateMode())
			{
				return ItemOutcome.SKIPPED;
			}

			ContentLocale originLocale = ContentManagerFactory.getContentManager().getContentLocale(item.getContentLocaleId());
			ContentVersion originPublishedVersion = originLocale.getPublishedVersion();
			ContentVersion originActiveVersion = requireActiveVersion(originLocale, "ContentLocale " + item.getContentLocaleId());

			ServiceInstanceConfig origin = ManagerFactory.getServiceInstanceManager().get(originServiceInstanceId, transaction);
			ServiceInstanceConfig destination = ManagerFactory.getServiceInstanceManager().get(destinationServiceInstanceId, transaction);
			ContentCopier.validateLocale(session, transaction, destination, item.getLocale(), originActiveVersion.getItemId());
			// A source without a language field takes the locale of new content from the session.
			session.setLocale(originLocale.getLocale());

			ContentLocale existingDestinationLocale = isDestinationPresent ? loadEditableDestinationLocale(mappedLocaleId) : null;
			String destinationContentId = resolveDestinationContentId(connection, item, map, existingDestinationLocale);

			MigrationScope scope = MigrationScope.builder()
					.session(session)
					.transaction(transaction)
					.connection(connection)
					.serviceId(serviceId)
					.originServiceInstanceId(originServiceInstanceId)
					.destinationServiceInstanceId(destinationServiceInstanceId)
					.sources(createContentTableSource(item.getSourceId(), origin, transaction), createContentTableSource(item.getSourceId(), destination, transaction))
					.updateMode(isUpdateMode())
					.build();
			try (FileCloner fileCloner = new FileCloner())
			{
				ItemDependencies dependencies = ItemDependencies.create(scope, fileCloner);
				runDependencyPrechecks(dependencies, item);

				ContentWorkflowMetaData originWorkflow = WorkflowCloner.readWorkflowMetaData(session, transaction, originLocale);
				String mappedAssignedTo = WorkflowCloner.resolveAssignedTo(scope, originWorkflow);
				if (existingDestinationLocale != null)
				{
					WorkflowCloner.returnToStartState(session, transaction, connection, existingDestinationLocale);
				}

				List<ContentVersion> originVersions = buildOriginVersionsToCopy(originPublishedVersion, originActiveVersion);
				List<ContentVersion> destinationVersions = copyVersions(scope, dependencies, originVersions, existingDestinationLocale, destinationContentId);
				copyLocaleMetadata(scope, originLocale, destinationVersions, originWorkflow, mappedAssignedTo);
				saveItemMap(scope, item, map, originActiveVersion, last(destinationVersions));

				injectFaults(ordinal);
				if (!GlobalLock.confirm(connection, runId))
				{
					transaction.rollback();
					return ItemOutcome.LOCK_LOST;
				}
				transaction.commit();
				return ItemOutcome.COPIED;
			}
		}
	}

	private ItemMapRow findItemMap(Connection connection, MigrationItem item) throws SQLException
	{
		// @formatter:off
		String sql = "select id, destContentId, destContentLocaleId, destItemId "
				+ "from lum_cmig_MigItemMap "
				+ "where originServiceInstanceId = ? "
				+ "and destServiceInstanceId = ? "
				+ "and sourceId = ? "
				+ "and originContentLocaleId = ? "
				+ "and kind = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originServiceInstanceId);
			statement.setString(2, destinationServiceInstanceId);
			statement.setString(3, item.getSourceId());
			statement.setString(4, item.getContentLocaleId());
			statement.setString(5, KIND_CONTENT);
			try (ResultSet resultSet = statement.executeQuery())
			{
				if (!resultSet.next())
				{
					return null;
				}
				ItemMapRow map = new ItemMapRow();
				map.setId(resultSet.getString(1));
				map.setDestContentId(resultSet.getString(2));
				map.setDestContentLocaleId(resultSet.getString(3));
				map.setDestItemId(resultSet.getString(4));
				return map;
			}
		}
	}

	private static boolean hasDestinationContentLocale(Connection connection, String destinationContentLocaleId) throws SQLException
	{
		if (destinationContentLocaleId == null)
		{
			return false;
		}
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_ContentLocale "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, destinationContentLocaleId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1) > 0;
			}
		}
	}

	private boolean isUpdateMode()
	{
		return !"ADD_ONLY".equalsIgnoreCase(updateMode);
	}

	private static ContentVersion requireActiveVersion(ContentLocale locale, String description)
	{
		ContentVersion activeVersion = locale.getActiveVersion();
		if (activeVersion == null)
		{
			throw new IllegalStateException(description + " has no active version.");
		}
		return activeVersion;
	}

	private static ContentLocale loadEditableDestinationLocale(String mappedLocaleId) throws Exception
	{
		ContentLocale destinationLocale = ContentManagerFactory.getContentManager().getContentLocale(mappedLocaleId);
		ContentVersion activeVersion = requireActiveVersion(destinationLocale, "Destination content " + mappedLocaleId);
		boolean isLocked = !LockType.NONE.equals(destinationLocale.getLockType());
		if (isLocked)
		{
			throw new IllegalStateException("Destination content '" + activeVersion.getPrimaryName() + "' is locked.");
		}
		return destinationLocale;
	}

	private String resolveDestinationContentId(Connection connection, MigrationItem item, ItemMapRow map, ContentLocale existingDestinationLocale) throws Exception
	{
		if (existingDestinationLocale != null)
		{
			return existingDestinationLocale.getContent().getId();
		}
		String destinationContentId = map == null ? null : map.getDestContentId();
		if (destinationContentId == null)
		{
			destinationContentId = ContentCopier.findMappedDestinationContentId(connection, originServiceInstanceId, destinationServiceInstanceId, item.getContentId());
		}
		if (destinationContentId != null && !hasDestinationContent(connection, destinationContentId))
		{
			return null;
		}
		return destinationContentId;
	}

	private static boolean hasDestinationContent(Connection connection, String contentId) throws SQLException
	{
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_Content "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, contentId);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1) > 0;
			}
		}
	}

	private static void runDependencyPrechecks(ItemDependencies dependencies, MigrationItem item) throws Exception
	{
		for (DependencyHandler handler : dependencies.getHandlers())
		{
			handler.precheck(dependencies.getContext(handler), item);
		}
	}

	/**
	 * Writes the origin versions in order, the first one creating the destination locale when it
	 * does not exist, and returns the written destination versions in the same order.
	 */
	private static List<ContentVersion> copyVersions(
			MigrationScope scope,
			ItemDependencies dependencies,
			List<ContentVersion> originVersions,
			ContentLocale existingDestinationLocale,
			String destinationContentId) throws Exception
	{
		ContentLocale currentDestinationLocale = existingDestinationLocale;
		String destinationItemId = currentDestinationLocale == null ? null : currentDestinationLocale.getActiveVersion().getItemId();
		List<ContentVersion> destinationVersions = new ArrayList<>();
		for (ContentVersion originVersion : originVersions)
		{
			ISourceData row = readOriginRow(scope, originVersion);
			String destinationContentLocaleId = currentDestinationLocale == null ? null : currentDestinationLocale.getId();
			String currentDestinationItemId = currentDestinationLocale == null ? null : destinationItemId;
			VersionCopy version = new VersionCopy(row, originVersion, destinationContentId, destinationContentLocaleId, currentDestinationItemId);
			ContentVersion destinationVersion = copySingleVersion(scope, dependencies, version);
			currentDestinationLocale = destinationVersion.getContentLocale();
			destinationItemId = destinationVersion.getItemId();
			destinationVersions.add(destinationVersion);
		}
		return destinationVersions;
	}

	/**
	 * Restores the workflow state, the published version, the tags and the authorship of the
	 * destination locale, in this order: {@code copyState} flushes before its direct SQL, so it runs
	 * before the published version changes.
	 */
	private static void copyLocaleMetadata(
			MigrationScope scope,
			ContentLocale originLocale,
			List<ContentVersion> destinationVersions,
			ContentWorkflowMetaData originWorkflow,
			String mappedAssignedTo) throws Exception
	{
		// The first written version is the published one whenever the origin has one.
		ContentVersion destinationPublishedVersion = originLocale.getPublishedVersion() == null ? null : destinationVersions.get(0);
		ContentVersion destinationActiveVersion = last(destinationVersions);
		ContentLocale destinationLocale = destinationActiveVersion.getContentLocale();
		WorkflowCloner.copyState(scope.getConnection(), destinationLocale, originWorkflow, mappedAssignedTo);
		destinationLocale.setPublishedVersion(destinationPublishedVersion);
		ContentCopier.applyTags(originLocale, destinationLocale, scope.getDestinationSource().getField(Type.Tag) != null);
		AuthorshipCloner.copy(originLocale, destinationLocale, destinationPublishedVersion, destinationActiveVersion);
	}

	private static ContentVersion last(List<ContentVersion> versions)
	{
		return versions.get(versions.size() - 1);
	}

	private ContentTableSource createContentTableSource(String sourceId, ServiceInstanceConfig instance, ITransaction transaction) throws Exception
	{
		return (ContentTableSource) SourceFactory.createSource(sourceId, new SourceContext(session, instance, transaction));
	}

	private static List<ContentVersion> buildOriginVersionsToCopy(ContentVersion originPublishedVersion, ContentVersion originActiveVersion)
	{
		ContentVersion firstVersion = originPublishedVersion == null ? originActiveVersion : originPublishedVersion;
		List<ContentVersion> originVersions = new ArrayList<>();
		originVersions.add(firstVersion);
		if (!isSameVersion(firstVersion, originActiveVersion))
		{
			originVersions.add(originActiveVersion);
		}
		return originVersions;
	}

	private static boolean isSameVersion(ContentVersion first, ContentVersion second)
	{
		boolean hasBothVersions = first != null && second != null;
		return hasBothVersions && first.getItemId().equals(second.getItemId());
	}

	private static ISourceData readOriginRow(MigrationScope scope, ContentVersion originVersion) throws Exception
	{
		List<ISourceData> rows = scope.getOriginSource().readData(originVersion.getItemId());
		if (rows.isEmpty())
		{
			throw new IllegalStateException(
					"Item " + originVersion.getItemId() + " of ContentLocale " + originVersion.getContentLocale().getId() + " not found in source "
							+ scope.getOriginSource().getId());
		}
		return rows.get(0);
	}

	private static ContentVersion copySingleVersion(MigrationScope scope, ItemDependencies dependencies, VersionCopy version) throws Exception
	{
		Map<String, Object> data = ContentCopier.buildData(scope, dependencies, version);
		ContentVersion destinationVersion = writeVersionData(scope, version, data);
		ContentCopier.applyAfterWrite(scope, dependencies, version.getOriginVersion(), destinationVersion);
		recordSlugAdjustmentWarning(version.getOriginRow(), destinationVersion, scope.getWarnings());
		return destinationVersion;
	}

	private static ContentVersion writeVersionData(MigrationScope scope, VersionCopy version, Map<String, Object> data) throws Exception
	{
		ContentTableSource destinationSource = scope.getDestinationSource();
		if (version.hasDestinationLocale())
		{
			IContentTableSourceUpdateDataResult result = destinationSource.updateData(data);
			return ContentManagerFactory.getContentManager().getContentVersionByItemId(result.getItemId());
		}
		IContentTableSourceAddDataResult result = destinationSource.addData(data);
		return result.getContentVersion();
	}

	private static void recordSlugAdjustmentWarning(ISourceData row, ContentVersion destinationVersion, List<String> warnings)
	{
		String requestedSlug = (String) row.get("slug");
		String destinationSlug = destinationVersion.getSlug();
		boolean wasSlugAdjusted = requestedSlug != null && destinationSlug != null;
		boolean isDifferentSlug = wasSlugAdjusted && !requestedSlug.equals(destinationSlug);
		if (isDifferentSlug && !hasWarningForSlug(warnings, requestedSlug))
		{
			warnings.add("Slug '" + requestedSlug + "' was adjusted to '" + destinationSlug + "'.");
		}
	}

	private static boolean hasWarningForSlug(List<String> warnings, String requestedSlug)
	{
		String marker = "Slug '" + requestedSlug + "'";
		for (String warning : warnings)
		{
			if (warning.contains(marker))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Inserts or updates the map row of the item. The destination locale and item come from the
	 * last written version, the destination active version.
	 */
	private static void saveItemMap(
			MigrationScope scope,
			MigrationItem item,
			ItemMapRow existingMap,
			ContentVersion originActiveVersion,
			ContentVersion destinationActiveVersion) throws SQLException
	{
		ContentLocale destinationLocale = destinationActiveVersion.getContentLocale();
		ItemMapRow values = new ItemMapRow();
		values.setKind(KIND_CONTENT);
		values.setServiceId(scope.getServiceId());
		values.setSourceId(item.getSourceId());
		values.setLocale(item.getLocale());
		values.setWarnings(String.join("\n", scope.getWarnings()));
		values.setDestCreated(true);
		values.setOriginServiceInstanceId(scope.getOriginServiceInstanceId());
		values.setOriginContentId(item.getContentId());
		values.setOriginContentLocaleId(item.getContentLocaleId());
		values.setOriginItemId(originActiveVersion.getItemId());
		values.setDestServiceInstanceId(scope.getDestinationServiceInstanceId());
		values.setDestContentId(destinationLocale.getContent().getId());
		values.setDestContentLocaleId(destinationLocale.getId());
		values.setDestItemId(destinationActiveVersion.getItemId());
		if (existingMap == null)
		{
			values.setId(PortalUtil.generateNewGuid());
			ItemMapDao.insertItemMap(scope.getConnection(), values);
			return;
		}
		values.setId(existingMap.getId());
		updateItemMap(scope.getConnection(), values);
	}

	private static void updateItemMap(Connection connection, ItemMapRow values) throws SQLException
	{
		// @formatter:off
		String sql = "update lum_cmig_MigItemMap "
				+ "set locale = ?, warnings = ?, destCreated = ?, originContentId = ?, originItemId = ?, "
				+ "destContentId = ?, destContentLocaleId = ?, destItemId = ? "
				+ "where id = ?";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, values.getLocale());
			statement.setString(2, values.getWarnings());
			statement.setBoolean(3, true);
			statement.setString(4, values.getOriginContentId());
			statement.setString(5, values.getOriginItemId());
			statement.setString(6, values.getDestContentId());
			statement.setString(7, values.getDestContentLocaleId());
			statement.setString(8, values.getDestItemId());
			statement.setString(9, values.getId());
			statement.executeUpdate();
		}
	}

	private static void injectFaults(int ordinal) throws InterruptedException
	{
		int delay = ExecutionSettings.intProperty(ExecutionSettings.PROPERTY_ITEM_DELAY_MILLIS, 0);
		if (delay > 0)
		{
			Thread.sleep(delay);
		}
		int failAt = ExecutionSettings.intProperty(ExecutionSettings.PROPERTY_FAIL_AT_ITEM, 0);
		if (failAt > 0 && failAt == ordinal)
		{
			throw new IllegalStateException("Injected failure at item " + ordinal + " (" + ExecutionSettings.PROPERTY_FAIL_AT_ITEM + ")");
		}
	}

	/**
	 * Counts mapped {@code CONTENT} rows whose destination {@link ContentLocale} still exists so
	 * manually removed destination items are recopied on the next run.
	 */
	public static int countMigrated(Connection connection, String originServiceInstanceId, String destinationServiceInstanceId) throws SQLException
	{
		// @formatter:off
		String sql = "select count(*) "
				+ "from lum_cmig_MigItemMap m "
				+ "where m.originServiceInstanceId = ? "
				+ "and m.destServiceInstanceId = ? "
				+ "and m.kind = ? "
				+ "and exists ("
				+ "select 1 "
				+ "from lum_ContentLocale cl "
				+ "where cl.id = m.destContentLocaleId)";
		// @formatter:on
		try (PreparedStatement statement = connection.prepareStatement(sql))
		{
			statement.setString(1, originServiceInstanceId);
			statement.setString(2, destinationServiceInstanceId);
			statement.setString(3, KIND_CONTENT);
			try (ResultSet resultSet = statement.executeQuery())
			{
				resultSet.next();
				return resultSet.getInt(1);
			}
		}
	}
}
