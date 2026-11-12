package br.com.lumis.commons.contentmigrator.service.contentmigration.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import br.com.lumis.commons.contentmigrator.service.contentmigration.dto.FileCopy;
import lumis.doui.source.field.type.FileDataType;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.file.FileConfig;
import lumis.portal.filesystem.PortalBaseFolder;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.transaction.ITransaction;
import lumis.util.log.ILogger;
import lumis.util.log.LoggerFactory;

/**
 * Prepares the value of a {@code file} field that clones an origin {@code lum_File} in the
 * destination: the stored file is copied to a temporary file, and the returned
 * {@link FileDataType.Data} carries the origin name and content type and the path of the copy,
 * with no file id, so the destination source creates a new {@code lum_File}.
 * <p>
 * {@code FileManager} copies the temporary file to its storage only after the commit, so the
 * temporary files are deleted by {@link #close()}, which must be called after the commit (or
 * the rollback) of the transaction that used the values:
 *
 * <pre>
 * try (FileCloner cloner = new FileCloner())
 * {
 * 	data.put("media", cloner.copy(session, transaction, originFileId));
 * 	source.addData(data);
 * 	transaction.commit();
 * }
 * </pre>
 */
public class FileCloner implements AutoCloseable
{
	private static final ILogger LOGGER = LoggerFactory.getLogger(FileCloner.class);

	private Path tempDir;

	private int count;

	/**
	 * Returns the value of a {@code file} field that clones the given origin file, or null if the
	 * file id is null.
	 */
	public FileDataType.Data copy(SessionConfig session, ITransaction transaction, String originFileId) throws Exception
	{
		FileCopy copy = copyWithFingerprint(session, transaction, originFileId);
		return copy == null ? null : copy.getData();
	}

	/**
	 * Copies a stored file to the temporary area and computes the stable fingerprint used by
	 * repository item maps. The bytes are hashed while they are copied, so large files are streamed.
	 */
	public FileCopy copyWithFingerprint(SessionConfig session, ITransaction transaction, String originFileId) throws Exception
	{
		if (originFileId == null)
		{
			return null;
		}
		FileConfig originFile = ManagerFactory.getFileManager().get(session, originFileId, transaction);
		if (tempDir == null)
		{
			tempDir = Files.createTempDirectory("contentmigrator-file-clone-");
		}

		// same physical file name as the origin, in a directory of its own
		String fullPath = originFile.getFullPath();
		Path copy = Files.createDirectory(tempDir.resolve(Integer.toString(count++))).resolve(fullPath.substring(fullPath.lastIndexOf('/') + 1));
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		long size = 0;
		try (InputStream in = PortalBaseFolder.DATA.toPortalFilePath(fullPath).toFile().getInputStream(); OutputStream out = Files.newOutputStream(copy))
		{
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1)
			{
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
				size += read;
			}
		}

		FileDataType.Data data = new FileDataType.Data();
		data.setName(originFile.getName());
		data.setContentType(originFile.getContentType());
		data.setFullPath(copy.toFile().getAbsolutePath());
		return new FileCopy(data, "sha256:" + hex(digest.digest()) + ":" + size);
	}

	private static String hex(byte[] bytes)
	{
		StringBuilder value = new StringBuilder(bytes.length * 2);
		for (byte item : bytes)
		{
			value.append(Character.forDigit((item >>> 4) & 0xf, 16)).append(Character.forDigit(item & 0xf, 16));
		}
		return value.toString();
	}

	/**
	 * Deletes the temporary files. Call after the commit of the transaction that used them.
	 */
	@Override
	public void close()
	{
		if (tempDir == null)
		{
			return;
		}
		try (Stream<Path> paths = Files.walk(tempDir))
		{
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		catch (IOException e)
		{
			LOGGER.warn("Could not delete the temporary files of " + tempDir, e);
		}
		tempDir = null;
	}
}
