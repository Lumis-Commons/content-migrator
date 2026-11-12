package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.contract;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.MediaFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.table.ContentTableSource;
import lumis.content.workflow.WorkflowConfig;
import lumis.doui.source.SourceContext;
import lumis.doui.source.SourceFactory;
import lumis.doui.source.field.type.FileDataType;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.channel.ChannelConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.file.FileConfig;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.privacyterm.IPrivacyTermManagerSPI;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.transaction.ITransaction;

/**
 * Proves the PAT002 SPI pieces that do not depend on a concrete handler: the creation rules of
 * {@code DependencyRules}, one message per broken rule, the handler registry of
 * {@code DependencyHandlerRegistry}, and the {@code FileCloner} over the {@code file} fields of the
 * native {@code lumis.service.userpost} service.
 * <p>
 * The module classes are not on the test classpath: they are reached through {@link ModuleBridge}
 * by reflection, and the registry is exercised with a {@link Proxy} of the module interface.
 */
public class SpiRulesTest
{
	private static final String USERPOST_SERVICE_ID = "lumis.service.userpost";

	private static final String USERPOST_SOURCE_ID = "post";

	private static final String BLOG_SERVICE_ID = "lumis.service.blog";

	private static final String COMMENT_SERVICE_ID = "lumis.service.comment";

	private static final String RULES = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.validation.DependencyRules";

	private static final String REGISTRY = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandlerRegistry";

	private static final String HANDLER = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyHandler";

	private static final String CONTEXT = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.DependencyContext";

	private static final String FILE_CLONER = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dependency.FileCloner";

	private static final String RULE_REPOSITORY_REQUIRED = "REPOSITORY_REQUIRED";

	private static final String RULE_REPOSITORY_NOT_SHARED = "REPOSITORY_NOT_SHARED";

	private static final String RULE_DIFFERENT_WORKFLOW = "DIFFERENT_WORKFLOW";

	private static final String RULE_FILES_FIELD = "FILES_FIELD";

	private static final String RULE_TABLE_SOURCE = "TABLE_SOURCE";

	private static final String MEDIA = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY;

	private static final String DOCUMENT = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY;

	private static final String WIDGET = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY;

	private static final String CATEGORIZATION = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY;

	/** A rule broken, read from a module Violation. */
	private static class Violation
	{
		final String rule;

		final String subject;

		final String message;

		Violation(Object violation) throws Exception
		{
			rule = (String) violation.getClass().getMethod("getRule").invoke(violation);
			subject = (String) violation.getClass().getMethod("getSubject").invoke(violation);
			message = (String) violation.getClass().getMethod("getMessage").invoke(violation);
		}

		@Override
		public String toString()
		{
			return rule + ": " + message;
		}
	}

	private PortalFixture fixture;

	private ChannelConfig channel;

	private final List<String[]> posts = new ArrayList<>();

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		channel = fixture.createChannel();
	}

	@After
	public void tearDown()
	{
		TestCleanup cleanup = new TestCleanup();
		if (!PortalFixture.keepTestData())
		{
			// through the source, so the file fields remove their lum_File and stored files
			Collections.reverse(posts);
			for (String[] post : posts)
			{
				try
				{
					PortalFixture.inTransaction((session, transaction) ->
					{
						postSource(session, transaction, post[0]).deleteDataByItemIds(Collections.singleton(post[1]));
						return null;
					});
				}
				catch (Exception e)
				{
					System.err.println("[SpiRulesTest] could not delete post " + post[1] + ": " + e);
					cleanup.recordFailure("could not delete post " + post[1], e);
				}
			}
		}
		cleanup.run(fixture::cleanup);
		cleanup.assertNoFailures();
	}

	@Test
	public void destinationWithoutRequiredRepositoryIsRejected() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig dest = news();
		ServiceInstanceConfig repository = news();
		for (String type : requiredTypes())
		{
			fixture.setDependency(repository, origin, type);
			removeDependency(dest, type);
		}

		List<Violation> violations = validate(origin, dest);
		for (String type : requiredTypes())
		{
			Violation violation = find(violations, RULE_REPOSITORY_REQUIRED, type);
			assertTrue(violation.message, violation.message.contains(type));
		}
		assertEquals(violations.toString(), requiredTypes().size(), count(violations, RULE_REPOSITORY_REQUIRED));

		// any repository of the same type is enough, cloning is the handlers' job
		ServiceInstanceConfig destRepository = news();
		for (String type : requiredTypes())
		{
			fixture.setDependency(destRepository, dest, type);
		}
		assertEquals(Collections.emptyList(), messages(validate(origin, dest)));
	}

	@Test
	public void notSharedRepositoryIsRejected() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig dest = news();
		ServiceInstanceConfig repository = news();
		ServiceInstanceConfig otherRepository = news();
		for (String type : sharedTypes())
		{
			fixture.setDependency(repository, origin, type);
			fixture.setDependency(otherRepository, dest, type);
		}

		List<Violation> violations = validate(origin, dest);
		String[] sharedTypes = {ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_EMAIL_TEMPLATE_REPOSITORY,
				IPrivacyTermManagerSPI.DEPENDENCY_TYPE_PRIVACYTERM_REPOSITORY};
		for (String type : sharedTypes)
		{
			Violation violation = find(violations, RULE_REPOSITORY_NOT_SHARED, type);
			assertTrue(violation.message, violation.message.contains(type));
		}

		for (String type : sharedTypes())
		{
			fixture.setDependency(repository, dest, type);
		}
		assertEquals(0, count(validate(origin, dest), RULE_REPOSITORY_NOT_SHARED));
	}

	@Test
	public void differentWorkflowsAreRejected() throws Exception
	{
		ServiceInstanceConfig origin = news();
		ServiceInstanceConfig dest = news();
		// a workflow other than the one the destination may have received when it was created
		WorkflowConfig workflow = PortalFixture.inTransaction((session, transaction) ->
		{
			WorkflowConfig destWorkflow = ContentManagerFactory.getWorkflowManager().getServiceInstanceWorkflow(session, dest.getId(), transaction);
			Collection<WorkflowConfig> workflows = ContentManagerFactory.getWorkflowManager().getAllWorkflows(session, transaction);
			return workflows.stream().filter(w -> destWorkflow == null || !w.getId().equals(destWorkflow.getId())).findFirst().orElse(null);
		});
		Assume.assumeNotNull(workflow);
		setWorkflow(origin, workflow.getId());

		Violation violation = find(validate(origin, dest), RULE_DIFFERENT_WORKFLOW, null);
		assertTrue(violation.message, violation.message.contains(workflow.getId()));

		setWorkflow(dest, workflow.getId());
		assertEquals(0, count(validate(origin, dest), RULE_DIFFERENT_WORKFLOW));
	}

	@Test
	public void filesFieldIsRejected() throws Exception
	{
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, BLOG_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, BLOG_SERVICE_ID);

		List<Violation> violations = validate(origin, dest);
		List<Violation> files = violations.stream().filter(v -> RULE_FILES_FIELD.equals(v.rule)).collect(Collectors.toList());
		assertFalse(violations.toString(), files.isEmpty());
		assertTrue(files.toString(), files.stream().anyMatch(v -> v.subject.endsWith(".contentImages") && v.message.contains(v.subject) && v.message.contains("files")));
	}

	@Test
	public void tableSourceInContentServiceIsRejected() throws Exception
	{
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, COMMENT_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, COMMENT_SERVICE_ID);

		Violation violation = find(validate(origin, dest), RULE_TABLE_SOURCE, "contentLocale");
		assertTrue(violation.message, violation.message.contains("contentLocale"));
		assertTrue(violation.message, violation.message.contains("table"));

		// news has only content table sources and no files field
		assertEquals(Collections.emptyList(), messages(validate(news(), news())));
	}

	@Test
	public void handlersAreRegisteredInTheEngineAndRunInOrder() throws Exception
	{
		Class<?> handlerType = ModuleBridge.load(HANDLER);
		List<String> types = Arrays.asList("zz_test_repository", CATEGORIZATION, WIDGET, DOCUMENT, MEDIA);
		Map<String, Object> previous = new HashMap<>();
		for (String type : types)
		{
			previous.put(type, ModuleBridge.invokeStatic(REGISTRY, "getHandler", type));
		}
		try
		{
			for (String type : types)
			{
				ModuleBridge.invokeStatic(REGISTRY, "registerHandler", noOpHandler(handlerType, type));
			}
			List<String> order = new ArrayList<>();
			for (Object handler : (List<?>) ModuleBridge.invokeStatic(REGISTRY, "getHandlers"))
			{
				String type = (String) handlerType.getMethod("getDependencyType").invoke(handler);
				if (types.contains(type))
				{
					order.add(type);
				}
			}
			assertEquals(Arrays.asList(MEDIA, DOCUMENT, WIDGET, CATEGORIZATION, "zz_test_repository"), order);
			assertEquals(
					"zz_test_repository",
					handlerType.getMethod("getDependencyType").invoke(ModuleBridge.invokeStatic(REGISTRY, "getHandler", "zz_test_repository")));

			assertTrue(isSameRepository("r", "r"));
			assertFalse(isSameRepository("r", "s"));
			assertFalse(isSameRepository(null, null));
		}
		finally
		{
			for (String type : types)
			{
				if (previous.get(type) == null)
				{
					ModuleBridge.invokeStatic(REGISTRY, "unregisterHandler", type);
				}
				else
				{
					ModuleBridge.invokeStatic(REGISTRY, "registerHandler", previous.get(type));
				}
			}
		}
	}

	@Test
	public void fileClonerCreatesNewFileWithSameBytesNameAndContentType() throws Exception
	{
		ServiceInstanceConfig origin = fixture.createServiceInstance(channel, USERPOST_SERVICE_ID);
		ServiceInstanceConfig dest = fixture.createServiceInstance(channel, USERPOST_SERVICE_ID);
		byte[] mediaBytes = "contentmigrator spi media ção".getBytes(StandardCharsets.UTF_8);
		byte[] documentBytes = "%PDF-1.4 contentmigrator spi document".getBytes(StandardCharsets.US_ASCII);
		String originPostId = addPost(origin, "imagem spi.png", "image/png", mediaBytes, "documento spi.pdf", "application/pdf", documentBytes);

		List<String> tempPaths = new ArrayList<>();
		String clonePostId;
		Class<?> clonerType = ModuleBridge.load(FILE_CLONER);
		Method copy = clonerType.getMethod("copy", SessionConfig.class, ITransaction.class, String.class);
		try (AutoCloseable cloner = (AutoCloseable) clonerType.getConstructor().newInstance())
		{
			clonePostId = PortalFixture.inTransaction((session, transaction) ->
			{
				String[] originFiles = postFiles(transaction, originPostId);
				Map<String, Object> data = new HashMap<>();
				data.put("title", "clone");
				data.put("post", "clone");
				data.put("priority", 5);
				FileDataType.Data media = (FileDataType.Data) copy.invoke(cloner, session, transaction, originFiles[0]);
				FileDataType.Data document = (FileDataType.Data) copy.invoke(cloner, session, transaction, originFiles[1]);
				tempPaths.add(media.getFullPath());
				tempPaths.add(document.getFullPath());
				data.put("media", media);
				data.put("document", document);
				return addData(session, transaction, dest, data);
			});
			posts.add(new String[]{dest.getId(), clonePostId});
			for (String path : tempPaths)
			{
				assertTrue("temporary file deleted before close: " + path, new File(path).exists());
			}
		}
		for (String path : tempPaths)
		{
			assertFalse("temporary file kept after close: " + path, new File(path).exists());
		}

		PortalFixture.inTransaction((session, transaction) ->
		{
			String[] originFiles = postFiles(transaction, originPostId);
			String[] cloneFiles = postFiles(transaction, clonePostId);
			for (int i = 0; i < 2; i++)
			{
				assertNotNull("clone file " + i + " not set", cloneFiles[i]);
				assertNotEquals("clone reuses the origin fileId", originFiles[i], cloneFiles[i]);
				FileConfig originFile = MediaFixture.getFile(session, transaction, originFiles[i]);
				FileConfig cloneFile = MediaFixture.getFile(session, transaction, cloneFiles[i]);
				assertNotNull("clone lum_File not found", cloneFile);
				assertEquals(originFile.getName(), cloneFile.getName());
				assertEquals(originFile.getContentType(), cloneFile.getContentType());
				assertEquals(originFile.getFileSize(), cloneFile.getFileSize());
				assertEquals(dest.getId(), cloneFile.getServiceInstanceId());
				assertNotEquals(originFile.getFullPath(), cloneFile.getFullPath());
				assertArrayEquals(MediaFixture.readStoredFile(originFile.getFullPath()), MediaFixture.readStoredFile(cloneFile.getFullPath()));
			}
			assertEquals("imagem spi.png", MediaFixture.getFile(session, transaction, cloneFiles[0]).getName());
			assertEquals("image/png", MediaFixture.getFile(session, transaction, cloneFiles[0]).getContentType());
			assertArrayEquals(mediaBytes, MediaFixture.readStoredFile(MediaFixture.getFile(session, transaction, cloneFiles[0]).getFullPath()));
			assertEquals("documento spi.pdf", MediaFixture.getFile(session, transaction, cloneFiles[1]).getName());
			assertEquals("application/pdf", MediaFixture.getFile(session, transaction, cloneFiles[1]).getContentType());
			assertArrayEquals(documentBytes, MediaFixture.readStoredFile(MediaFixture.getFile(session, transaction, cloneFiles[1]).getFullPath()));
			return null;
		});
	}


	/**
	 * Keeps transient friendly URL commands out of both insert and update parameter maps.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void friendlyUrlCommandsAreExplicitlyOmitted() throws Exception
	{
		Class<?> policy = ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.copy.MetadataPolicy");
		Method copy = policy.getDeclaredMethod("shouldCopyAsParameter", lumis.content.source.field.MetaDataSourceField.Type.class);
		copy.setAccessible(true);
		assertFalse((Boolean) copy.invoke(null, lumis.content.source.field.MetaDataSourceField.Type.RecalculateFriendlyURLs));
		assertFalse((Boolean) copy.invoke(null, lumis.content.source.field.MetaDataSourceField.Type.AddFriendlyURLsRedirects));
		assertTrue((Boolean) copy.invoke(null, lumis.content.source.field.MetaDataSourceField.Type.RobotsConfiguration));
		assertTrue((Boolean) copy.invoke(null, lumis.content.source.field.MetaDataSourceField.Type.UrlTitle));
	}

	private ServiceInstanceConfig news() throws Exception
	{
		return fixture.createServiceInstance(channel, PortalFixture.NEWS_SERVICE_ID);
	}

	private static List<Violation> validate(ServiceInstanceConfig origin, ServiceInstanceConfig dest) throws Exception
	{
		List<?> violations = PortalFixture
				.inTransaction((session, transaction) -> (List<?>) ModuleBridge.invokeStatic(RULES, "validate", session, origin.getId(), dest.getId(), transaction));
		List<Violation> result = new ArrayList<>();
		for (Object violation : violations)
		{
			result.add(new Violation(violation));
		}
		return result;
	}

	private static Violation find(List<Violation> violations, String rule, String subject)
	{
		return violations.stream().filter(v -> rule.equals(v.rule) && Objects.equals(subject, v.subject)).findFirst()
				.orElseThrow(() -> new AssertionError("no " + rule + " for " + subject + " in " + violations));
	}

	private static long count(List<Violation> violations, String rule)
	{
		return violations.stream().filter(v -> rule.equals(v.rule)).count();
	}

	private static List<String> messages(List<Violation> violations)
	{
		return violations.stream().map(Violation::toString).collect(Collectors.toList());
	}

	private static void removeDependency(ServiceInstanceConfig dependent, String type) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			// without a service instance id, the dependency of that type is removed
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(dependent.getId());
			removal.setDependencyType(type);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
	}

	private static void setWorkflow(ServiceInstanceConfig instance, String workflowId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getWorkflowManager().setServiceInstanceWorkflow(session, instance.getId(), workflowId, transaction);
			return null;
		});
	}

	private String addPost(
			ServiceInstanceConfig instance,
			String mediaName,
			String mediaType,
			byte[] mediaBytes,
			String documentName,
			String documentType,
			byte[] documentBytes) throws Exception
	{
		Path tempDir = Files.createTempDirectory("contentmigrator-spi-");
		try
		{
			File media = Files.write(tempDir.resolve(mediaName), mediaBytes).toFile();
			File document = Files.write(tempDir.resolve(documentName), documentBytes).toFile();
			Map<String, Object> data = new HashMap<>();
			data.put("title", "origem");
			data.put("post", "origem");
			data.put("priority", 5);
			data.put("media", MediaFixture.fileData(mediaName, mediaType, media));
			data.put("document", MediaFixture.fileData(documentName, documentType, document));
			String postId = PortalFixture.inTransaction((session, transaction) -> addData(session, transaction, instance, data));
			posts.add(new String[]{instance.getId(), postId});
			return postId;
		}
		finally
		{
			MediaFixture.deleteRecursively(tempDir);
		}
	}

	private static String addData(SessionConfig session, ITransaction transaction, ServiceInstanceConfig instance, Map<String, Object> data) throws Exception
	{
		if (session.getLocale() == null)
		{
			session.setLocale(MediaFixture.LOCALE);
		}
		return postSource(session, transaction, instance.getId()).addData(data).getItemId();
	}

	private static ContentTableSource postSource(SessionConfig session, ITransaction transaction, String instanceId) throws Exception
	{
		if (session.getLocale() == null)
		{
			session.setLocale(MediaFixture.LOCALE);
		}
		ServiceInstanceConfig instance = ManagerFactory.getServiceInstanceManager().get(instanceId, transaction);
		return (ContentTableSource) SourceFactory.createSource(USERPOST_SOURCE_ID, new SourceContext(session, instance, transaction));
	}

	/**
	 * Returns the media and document fileIds of a lum_UPPost row.
	 */
	private static String[] postFiles(ITransaction transaction, String postId) throws Exception
	{
		try (PreparedStatement st = ((ITransactionJdbc) transaction).getConnection().prepareStatement("select media, document from lum_UPPost where id = ?"))
		{
			st.setString(1, postId);
			try (ResultSet rs = st.executeQuery())
			{
				assertTrue("post " + postId + " not found", rs.next());
				return new String[]{rs.getString(1), rs.getString(2)};
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<String> requiredTypes() throws Exception
	{
		return (List<String>) ModuleBridge.load(RULES).getField("REQUIRED_REPOSITORY_TYPES").get(null);
	}

	@SuppressWarnings("unchecked")
	private static List<String> sharedTypes() throws Exception
	{
		return (List<String>) ModuleBridge.load(RULES).getField("SHARED_REPOSITORY_TYPES").get(null);
	}

	private static boolean isSameRepository(String originRepository, String destRepository) throws Exception
	{
		return PortalFixture.inTransaction((session, transaction) ->
		{
			Map<String, Object> scope = new HashMap<>();
			scope.put("session", session);
			scope.put("transaction", transaction);
			scope.put("connection", ((ITransactionJdbc) transaction).getConnection());
			scope.put("serviceId", PortalFixture.NEWS_SERVICE_ID);
			scope.put("originServiceInstanceId", "o");
			scope.put("destinationServiceInstanceId", "d");
			Object context = ModuleBridge.newDependencyContext(scope, originRepository, destRepository);
			return (Boolean) ModuleBridge.load(CONTEXT).getMethod("isSameRepository").invoke(context);
		});
	}

	/**
	 * Returns a handler of the given type that does nothing, to exercise the registry.
	 */
	private static Object noOpHandler(Class<?> handlerType, String type)
	{
		return Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "getDependencyType" :
					return type;
				case "handles" :
				case "isCloneReferenced" :
					return false;
				case "resolve" :
					return args[2];
				case "rewriteHtml" :
					return args[1];
				case "toString" :
					return "NoOpHandler(" + type + ")";
				case "hashCode" :
					return System.identityHashCode(proxy);
				case "equals" :
					return proxy == args[0];
				default :
					return null;
			}
		});
	}
}
