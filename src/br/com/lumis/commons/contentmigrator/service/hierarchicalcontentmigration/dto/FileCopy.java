package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto;

import lumis.doui.source.field.type.FileDataType;

/** A temporary file value and the fingerprint of the source bytes copied into it. */
public final class FileCopy
{
	private final FileDataType.Data data;

	private final String fingerprint;

	/** Creates a file copy holding the cloned file data and the SHA-256 fingerprint of its source bytes. */
	public FileCopy(FileDataType.Data data, String fingerprint)
	{
		this.data = data;
		this.fingerprint = fingerprint;
	}

	/** Returns the temporary file data to assign to the destination field. */
	public FileDataType.Data getData()
	{
		return data;
	}

	/** Returns the SHA-256 fingerprint of the source file bytes. */
	public String getFingerprint()
	{
		return fingerprint;
	}
}
