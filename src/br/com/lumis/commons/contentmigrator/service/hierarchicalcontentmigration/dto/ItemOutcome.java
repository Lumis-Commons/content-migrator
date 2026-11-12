package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/** Result of processing one {@link MigrationItem}. */
public enum ItemOutcome
{
	/** Already mapped to an existing destination in add-only mode. */
	SKIPPED,
	/** Copied or updated and mapped. */
	COPIED,
	/** The run lost the lock; the item was rolled back. */
	LOCK_LOST
}
