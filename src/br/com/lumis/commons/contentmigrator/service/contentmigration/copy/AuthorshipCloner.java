package br.com.lumis.commons.contentmigrator.service.contentmigration.copy;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import lumis.content.core.ContentComment;
import lumis.content.core.ContentLocale;
import lumis.content.core.ContentVersion;

/**
 * Restores origin locale and version authorship plus editorial comments after content writes,
 * because native source operations stamp the migrating user and current timestamp.
 */
final class AuthorshipCloner
{
	private AuthorshipCloner()
	{
	}

	/**
	 * Copies creation metadata, version modification metadata and missing editorial comments from
	 * the origin locale and its published and active versions to their destination counterparts.
	 */
	static void copy(ContentLocale originLocale, ContentLocale destinationLocale, ContentVersion destinationPublished, ContentVersion destinationActive)
	{
		ContentVersion originPublished = originLocale.getPublishedVersion();
		ContentVersion originActive = originLocale.getActiveVersion();
		destinationLocale.setCreatedBy(originLocale.getCreatedBy());
		destinationLocale.setCreatedDateTime(copyDate(originLocale.getCreatedDateTime()));

		boolean hasPublishedPair = originPublished != null && destinationPublished != null;
		if (hasPublishedPair)
		{
			copyVersion(originPublished, destinationPublished);
		}

		boolean hasActivePair = originActive != null && destinationActive != null;
		boolean isDistinctActiveVersion = !isSameVersion(originActive, originPublished);
		if (hasActivePair && isDistinctActiveVersion)
		{
			copyVersion(originActive, destinationActive);
		}

		copyComments(originLocale, destinationLocale);
	}

	private static Date copyDate(Date date)
	{
		return date == null ? null : new Date(date.getTime());
	}

	private static void copyVersion(ContentVersion origin, ContentVersion destination)
	{
		destination.setLastModifiedBy(origin.getLastModifiedBy());
		destination.setLastModifiedDateTime(copyDate(origin.getLastModifiedDateTime()));
	}

	private static boolean isSameVersion(ContentVersion first, ContentVersion second)
	{
		boolean hasBothVersions = first != null && second != null;
		return hasBothVersions && Objects.equals(first.getItemId(), second.getItemId());
	}

	private static void copyComments(ContentLocale originLocale, ContentLocale destinationLocale)
	{
		List<ContentComment> destinationComments = destinationLocale.getComments();
		for (ContentComment originComment : originLocale.getComments())
		{
			if (hasMatchingComment(destinationComments, originComment))
			{
				continue;
			}
			ContentComment destinationComment = destinationLocale.addComment();
			destinationComment.setCommentText(originComment.getCommentText());
			destinationComment.setCreatedBy(originComment.getCreatedBy());
			destinationComment.setCreatedDateTime(copyDate(originComment.getCreatedDateTime()));
			destinationComments = destinationLocale.getComments();
		}
	}

	private static boolean hasMatchingComment(List<ContentComment> comments, ContentComment candidate)
	{
		for (ContentComment comment : comments)
		{
			boolean isSameTextAndAuthor = Objects.equals(comment.getCommentText(), candidate.getCommentText())
					&& Objects.equals(comment.getCreatedBy(), candidate.getCreatedBy());
			boolean isSameCreatedDate = Objects.equals(comment.getCreatedDateTime(), candidate.getCreatedDateTime());
			if (isSameTextAndAuthor && isSameCreatedDate)
			{
				return true;
			}
		}
		return false;
	}
}
