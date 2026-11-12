package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

/**
 * One hierarchical content as the tree sees it: the row of its default locale (or, without one, of
 * its oldest locale), read from the active version, or from the published one when there is no
 * active version. Archived contents and contents that were never published are nodes too.
 */
public final class HierarchyNode
{
	private final String contentId;

	private final String parentContentId;

	private final String title;

	private final boolean hidden;

	private final boolean published;

	private final int position;

	private final String referenceContentLocaleId;

	private final String activeParentId;

	private final String publishedParentId;

	/**
	 * Creates a visible node whose active version has {@code parentContentId} as its parent, which is
	 * null for a content without a parent. {@link #builder()} sets the other fields.
	 */
	public HierarchyNode(String contentId, String parentContentId, String title, boolean published, int position)
	{
		this(builder().contentId(contentId).parentContentId(parentContentId).title(title)
				.published(published).position(position).activeParentId(parentContentId));
	}

	private HierarchyNode(Builder builder)
	{
		this.contentId = builder.contentId;
		this.parentContentId = builder.parentContentId;
		this.title = builder.title;
		this.hidden = builder.hidden;
		this.published = builder.published;
		this.position = builder.position;
		this.referenceContentLocaleId = builder.referenceContentLocaleId;
		this.activeParentId = builder.activeParentId;
		this.publishedParentId = builder.publishedParentId;
	}

	/** Returns the locale used to choose the displayed hierarchy and the first copy item. */
	public String getReferenceContentLocaleId()
	{
		return referenceContentLocaleId;
	}

	/** Returns the parent of the reference locale's active version, if any. */
	public String getActiveParentId()
	{
		return activeParentId;
	}

	/** Returns the parent of the reference locale's published version, if any. */
	public String getPublishedParentId()
	{
		return publishedParentId;
	}

	/** Creates a builder for a hierarchy node. */
	public static Builder builder()
	{
		return new Builder();
	}

	/** Builds a node with its reference locale and both version parents. */
	public static final class Builder
	{
		private String contentId;
		private String parentContentId;
		private String title;
		private boolean hidden;
		private boolean published;
		private int position;
		private String referenceContentLocaleId;
		private String activeParentId;
		private String publishedParentId;

		/** Sets contentId. */
		public Builder contentId(String value)
		{
			this.contentId = value;
			return this;
		}

		/** Sets parentContentId. */
		public Builder parentContentId(String value)
		{
			this.parentContentId = value;
			return this;
		}

		/** Sets title. */
		public Builder title(String value)
		{
			this.title = value;
			return this;
		}

		/** Sets hidden. */
		public Builder hidden(boolean value)
		{
			this.hidden = value;
			return this;
		}

		/** Sets published. */
		public Builder published(boolean value)
		{
			this.published = value;
			return this;
		}

		/** Sets position. */
		public Builder position(int value)
		{
			this.position = value;
			return this;
		}

		/** Sets referenceContentLocaleId. */
		public Builder referenceContentLocaleId(String value)
		{
			this.referenceContentLocaleId = value;
			return this;
		}

		/** Sets activeParentId. */
		public Builder activeParentId(String value)
		{
			this.activeParentId = value;
			return this;
		}

		/** Sets publishedParentId. */
		public Builder publishedParentId(String value)
		{
			this.publishedParentId = value;
			return this;
		}

		/** Returns the configured node. */
		public HierarchyNode build()
		{
			return new HierarchyNode(this);
		}
	}

	/** Returns the id of the content ({@code lum_Content.id}), which is what a child holds as its parent. */
	public String getContentId()
	{
		return contentId;
	}

	/** Returns the id of the parent content, or null when the content has no parent. */
	public String getParentContentId()
	{
		return parentContentId;
	}

	/** Returns the title of the content. */
	public String getTitle()
	{
		return title;
	}

	/** Returns whether the content is hidden from the navigation. */
	public boolean isHidden()
	{
		return hidden;
	}

	/** Returns whether the locale shown has a published version; false for drafts and archived contents. */
	public boolean isPublished()
	{
		return published;
	}

	/** Returns the position of the content among its siblings. */
	public int getPosition()
	{
		return position;
	}
}
