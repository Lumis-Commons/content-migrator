package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.MigrationItem;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationBlockedException;
import lumis.doui.source.field.ISourceField;

/**
 * Migrates the references of a content to one type of repository (PAT002). There is one handler
 * per dependency type ({@code media_repository}, {@code document_repository},
 * {@code categorization_repository} and {@code widget_repository}), registered with
 * {@link DependencyHandlerRegistry#registerHandler(DependencyHandler)}.
 * <p>
 * For each item the engine calls, in the item transaction and in the order of
 * {@link DependencyHandlerRegistry#getHandlers()} (media, document, widget, categorization):
 * <ol>
 * <li>{@link #precheck(DependencyContext, MigrationItem)} of every handler, before anything is
 * written; blocks that require an action of the user are thrown here as
 * {@link MigrationBlockedException};</li>
 * <li>for each field of the source, in the order of the source, while the data of each version is
 * built: {@link #resolve(DependencyContext, String, Object)} of the first handler that
 * {@link #handles(ISourceField) handles} the field, and then, on an html value,
 * {@link #rewriteHtml(DependencyContext, String)} of every handler;</li>
 * <li>after each version is written, {@link #resolve(DependencyContext, String, Object)} of the
 * categorization handler for each {@code categorizationTerm} association, with the association
 * type as {@code fieldOrAssociationId}.</li>
 * </ol>
 * When the origin and the destination use the same repository instance
 * ({@link DependencyContext#isSameRepository()}), handlers return the references as they are,
 * without clones and without map rows; only references the destination cannot store, such as the
 * embed of a widget instance that no longer exists, are removed with a warning.
 * <p>
 * For the undo, {@link #isCloneReferenced(DependencyContext, String)} and
 * {@link #deleteClone(DependencyContext, String)} work on the destination clones mapped in
 * lum_cmig_HMigItemMap.
 */
public interface DependencyHandler
{
	/**
	 * Returns the dependency type handled, one of the
	 * {@code ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_*} constants.
	 */
	String getDependencyType();

	/**
	 * Returns whether the given field of a content source is resolved by this handler.
	 */
	boolean handles(ISourceField field) throws Exception;

	/**
	 * Checks, before the item is written, whether its references can be migrated.
	 *
	 * @throws MigrationBlockedException
	 *             if a mapping requires an action of the user.
	 */
	void precheck(DependencyContext context, MigrationItem item) throws MigrationBlockedException, Exception;

	/**
	 * Returns the destination value of a field or association, cloning on demand the referenced
	 * repository items and writing their lum_cmig_HMigItemMap rows in the item transaction. A null
	 * return omits the reference.
	 *
	 * @param fieldOrAssociationId
	 *            the id of the source field or the type of the association.
	 * @param value
	 *            the origin value.
	 */
	Object resolve(DependencyContext context, String fieldOrAssociationId, Object value) throws MigrationBlockedException, Exception;

	/**
	 * Rewrites the references of an html value, including the JSON of {@code lumHtmlContent}, to
	 * the destination. Returns the value unchanged when there is nothing to rewrite.
	 *
	 * @throws MigrationBlockedException
	 *             if a mapping checked by the precheck is no longer valid.
	 */
	String rewriteHtml(DependencyContext context, String html) throws MigrationBlockedException, Exception;

	/**
	 * Returns whether a destination clone is referenced by any content.
	 *
	 * @param destContentLocaleId
	 *            the destination ContentLocale of the clone.
	 */
	boolean isCloneReferenced(DependencyContext context, String destContentLocaleId) throws Exception;

	/**
	 * Deletes a destination clone through the native hooks of its source.
	 *
	 * @param destContentLocaleId
	 *            the destination ContentLocale of the clone.
	 */
	void deleteClone(DependencyContext context, String destContentLocaleId) throws Exception;
}
