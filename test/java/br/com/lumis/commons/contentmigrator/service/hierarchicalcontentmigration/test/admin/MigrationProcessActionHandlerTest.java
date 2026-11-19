package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.DocumentFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.ModuleBridge;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.PortalFixture;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.test.support.TestCleanup;
import lumis.content.ContentManagerFactory;
import lumis.content.workflow.WorkflowConfig;
import lumis.doui.control.Control;
import lumis.doui.control.ErrorSummaryControl;
import lumis.portal.PortalException;
import lumis.portal.authentication.SessionConfig;
import lumis.portal.dao.jdbc.ITransactionJdbc;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.privacyterm.IPrivacyTermManagerSPI;
import lumis.portal.serviceinstance.ServiceInstanceConfig;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;
import lumis.portal.user.UserConfig;

/** Exercises the real DOUI adapter with an empty response definition. */
public class MigrationProcessActionHandlerTest
{
	private static final String HANDLER = "br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.processaction.MigrationProcessActionHandler";
	private PortalFixture fixture;
	private ServiceInstanceConfig origin;
	private ServiceInstanceConfig destination;
	private String migrationId;

	@Before
	public void setUp() throws Exception
	{
		fixture = new PortalFixture();
		origin = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.HIERARCHICAL_SERVICE_ID);
		destination = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.HIERARCHICAL_SERVICE_ID);
	}

	@After
	public void tearDown() throws Exception
	{
		if (!PortalFixture.keepTestData() && migrationId != null)
		{
			ModuleBridge.api("delete", migrationId);
		}
		TestCleanup.assertNoFailures(fixture::cleanup);
	}

	@Test
	public void createPassesTheSelectedContentIds() throws Exception
	{
		String root = fixture.addHierarchicalContent(origin, "selected root", null, true);
		fixture.addHierarchicalContent(origin, "unselected root", null, true);
		invokeCreate(root);
		PortalFixture.inTransaction((session, transaction) ->
		{
			var connection = ((ITransactionJdbc) transaction).getConnection();
			try (var statement = connection.prepareStatement("select id from lum_cmig_HMigration where originServiceInstanceId = ? and destServiceInstanceId = ?"))
			{
				statement.setString(1, origin.getId());
				statement.setString(2, destination.getId());
				try (var rows = statement.executeQuery())
				{
					org.junit.Assert.assertTrue(rows.next());
					migrationId = rows.getString(1);
				}
			}
			assertEquals(List.of(root), ModuleBridge.invokeStatic(
					"br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dao.SelectionDao", "listContentIds", connection, migrationId));
			return null;
		});
	}

	@Test
	public void apiFailureBecomesALocalizedPortalException() throws Exception
	{
		try
		{
			invokeCreate("00000000000000000000000000000000");
			fail("invalid selection must fail");
		}
		catch (PortalException exception)
		{
			assertEquals("STR_MIGRATION_INVALID_SELECTION", exception.getMessage());
			assertEquals("INVALID_SELECTION", ModuleBridge.errorCode(exception.getCause()));
			assertTranslated(exception);
		}
	}

	/**
	 * Runs a list button as the migration list posts it: the checked row arrives under the list data id {@code id}, as an
	 * array or as a single value, never as {@code migrationId}. Stopping a stopped migration leaves it unchanged, so the
	 * absence of an error proves that the handler found the migration.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void listButtonReadsTheCheckedRow() throws Exception
	{
		String root = fixture.addHierarchicalContent(origin, "selected root", null, true);
		Map<?, ?> created = (Map<?, ?>) ModuleBridge.api("create", Map.of("name", "handler list button", "originServiceInstanceId", origin.getId(),
				"destServiceInstanceId", destination.getId(), "selectedContentIds", root));
		migrationId = (String) created.get("id");
		invoke("stopMigration", Map.of("id", new String[] {migrationId}), Locale.US);
		invoke("stopMigration", Map.of("id", migrationId), Locale.US);
		Map<?, ?> stopped = (Map<?, ?>) ModuleBridge.api("get", migrationId);
		assertEquals("STOPPED", stopped.get("status"));
		assertEquals(Boolean.FALSE, stopped.get("stopRequested"));
	}

	/**
	 * Names the button, the form and each missing field with its instruction, in both form languages, without reaching
	 * the database: the new migration form lists its four fields in order, a list button without one checked row asks
	 * for the row, and the selection window asks to be reopened from the list.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void missingFieldsAreNamedWithTheirForm() throws Exception
	{
		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			boolean english = Locale.US.equals(locale);
			String[] create = requiredFieldLines("createMigration", Map.of(), locale);
			assertEquals(java.util.Arrays.toString(create), 6, create.length);
			assertTrue(create[0], create[0].contains(english ? "“Create”" : "“Criar”"));
			assertTrue(create[0], create[0].contains(english ? "“New migration”" : "“Nova migração”"));
			assertTrue(create[1], create[1].startsWith(english ? "• Name: " : "• Nome: "));
			assertTrue(create[2], create[2].startsWith(english ? "• Origin: " : "• Origem: "));
			assertTrue(create[3], create[3].startsWith(english ? "• Destination: " : "• Destino: "));
			assertTrue(create[4], create[4].startsWith(english ? "• Contents to migrate: " : "• Conteúdos a migrar: "));
			assertTrue(create[5], create[5].contains(english ? "“Create”" : "“Criar”"));

			String[] list = requiredFieldLines("startIncremental", Map.of("id", new String[0]), locale);
			assertEquals(java.util.Arrays.toString(list), 3, list.length);
			assertTrue(list[0], list[0].contains(english ? "“Start incremental”" : "“Iniciar incremental”"));
			assertTrue(list[0], list[0].contains(english ? "“Migration list”" : "“Lista de migrações”"));
			assertTrue(list[1], list[1].startsWith(english ? "• Selected migration: tick" : "• Migração selecionada: marque"));

			String[] edit = requiredFieldLines("updateSelection", Map.of("selectedContentIds", "unused"), locale);
			assertEquals(java.util.Arrays.toString(edit), 3, edit.length);
			assertTrue(edit[0], edit[0].contains(english ? "“Change selection”" : "“Alterar seleção”"));
			assertTrue(edit[1], edit[1].startsWith(english ? "• Selected migration: close" : "• Migração selecionada: feche"));
			assertTrue(edit[2], edit[2].contains(english ? "“Save selection”" : "“Salvar seleção”"));
		}
	}

	/**
	 * Runs an action that must fail with {@code REQUIRED} and returns its popup lines, after checking that the popup
	 * script keeps the whole message.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private String[] requiredFieldLines(String action, Map<String, ?> parameters, Locale locale) throws Exception
	{
		try
		{
			invoke(action, parameters, locale);
		}
		catch (PortalException exception)
		{
			assertEquals("REQUIRED", ModuleBridge.errorCode(exception.getCause()));
			String message = exception.getLocalizedMessage(locale);
			assertFalse(message, message.contains("STR_"));
			assertPopupScript(message);
			return message.split("\n", -1);
		}
		throw new AssertionError(action + " without its required fields must fail");
	}

	/**
	 * Shows one and then two ordered repository violations in each form language.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void repositoryViolationsAreListedInBothLanguages() throws Exception
	{
		var parent = fixture.createChannel();
		var child = fixture.createChannel(parent.getId());
		var media = fixture.createServiceInstance(child, PortalFixture.MEDIA_SERVICE_ID);
		var document = fixture.createServiceInstance(child, DocumentFixture.DOCUMENT_SERVICE_ID);
		String mediaType = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY;
		String documentType = ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY;
		String mediaName = "Mídia d'Origem \\ & ; {0} <arquivo>";
		String selection = fixture.addHierarchicalContent(origin, "selected root", null, true);
		PortalFixture.inTransaction((session, transaction) ->
		{
			media.setName(mediaName);
			ManagerFactory.getServiceInstanceManager().update(session, media, transaction);

			return null;
		});
		fixture.setDependency(media, origin, mediaType);
		removeDestinationDependency(mediaType);
		// Keep the document dependency compatible until the two-violation case.
		fixture.setDependency(document, origin, documentType);
		fixture.setDependency(document, destination, documentType);

		String pathSuffix = " / " + parent.getName() + " / " + child.getName() + ")";
		assertRepositoryViolations(selection, pathSuffix, mediaName, null);
		removeDestinationDependency(documentType);
		assertRepositoryViolations(selection, pathSuffix, mediaName, document.getName());
	}

	/**
	 * Checks the required-repository text of each migrated type and the not-shared text of each shared
	 * type in both form languages: instance, type, origin repository, effect of each path and the step.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void repositoryMessagesDescribeEachDependencyTypeInBothLanguages() throws Exception
	{
		String selection = fixture.addHierarchicalContent(origin, "selected root", null, true);
		var repository = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.MEDIA_SERVICE_ID);
		var other = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.MEDIA_SERVICE_ID);
		Locale us = Locale.US;
		Locale pt = Locale.forLanguageTag("pt-BR");
		String[][] required = {
				{ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY, "media repository", "the media are copied",
						"repositório de mídia", "as mídias são copiadas"},
				{ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY, "document repository", "the documents are copied",
						"repositório de documentos", "os documentos são copiados"},
				{ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, "widget repository", "asked to map the widgets",
						"repositório de widgets", "mapeamento dos widgets"},
				{ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_TAG_REPOSITORY, "tag repository", "the tags are created by name",
						"repositório de tags", "as tags são criadas pelo nome"},
				{ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY, "categorization repository",
						"the categorization terms are copied", "repositório de categorias", "os termos de categorização são copiados"}};
		String[][] shared = {
				{ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_EMAIL_TEMPLATE_REPOSITORY, "e-mail templates", "modelos de e-mail"},
				{IPrivacyTermManagerSPI.DEPENDENCY_TYPE_PRIVACYTERM_REPOSITORY, "privacy terms", "termos de privacidade"}};
		for (String[] type : concat(required, shared))
		{
			removeInstanceDependency(origin, type[0]);
			removeInstanceDependency(destination, type[0]);
		}
		String stepUs = "Step: in the dependencies of the ";
		String stepPt = "Passo: nas dependências da instância ";
		for (String[] type : required)
		{
			fixture.setDependency(repository, origin, type[0]);
			assertDetail(selection, type[0], us, repository, "requires a " + type[1], "origin, which uses ", type[2], "nothing is cloned", stepUs);
			assertDetail(selection, type[0], pt, repository, "precisa de um " + type[3], "a origem, que usa ", type[4], "nada é clonado", stepPt);
			removeInstanceDependency(origin, type[0]);
		}
		for (String[] type : shared)
		{
			fixture.setDependency(repository, origin, type[0]);
			assertDetail(selection, type[0], us, repository, "the " + type[1] + " instance of the origin", "currently uses none", stepUs);
			assertDetail(selection, type[0], pt, repository, "usar a instância de " + type[2], "não usa nenhuma", stepPt);
			fixture.setDependency(other, destination, type[0]);
			assertDetail(selection, type[0], us, repository, "the " + type[1] + " instance of the origin", "currently uses " + other.getName(), stepUs);
			assertDetail(selection, type[0], pt, repository, "usar a instância de " + type[2], "Hoje ela usa " + other.getName(), stepPt);
			removeInstanceDependency(origin, type[0]);
			removeInstanceDependency(destination, type[0]);
		}
	}

	private static String[][] concat(String[][] first, String[][] second)
	{
		String[][] all = java.util.Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, all, first.length, second.length);
		return all;
	}

	/**
	 * Expects a single violation line naming the destination and origin repository and containing every fragment.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void assertDetail(String selection, String type, Locale locale, ServiceInstanceConfig repository, String... fragments) throws Exception
	{
		try
		{
			invokeCreate(selection, locale);
			fail("the " + type + " violation must reject creation");
		}
		catch (PortalException exception)
		{
			String[] lines = exception.getLocalizedMessage(locale).split("\n", -1);
			assertEquals(type + " " + locale + " " + java.util.Arrays.toString(lines), 2, lines.length);
			assertTrue(lines[1], lines[1].contains(destination.getName() + " ("));
			assertTrue(lines[1], lines[1].contains(repository.getName() + " ("));
			assertFalse(lines[1], lines[1].contains(destination.getId()));
			for (String fragment : fragments)
			{
				assertTrue(type + " " + locale + ": " + lines[1], lines[1].contains(fragment));
			}
		}
	}

	private void removeInstanceDependency(ServiceInstanceConfig instance, String type) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(instance.getId());
			removal.setDependencyType(type);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
	}

	/**
	 * Removes any automatically configured repository of this type from the destination.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void removeDestinationDependency(String type) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ServiceInstanceDependencyConfig removal = new ServiceInstanceDependencyConfig();
			removal.setDependentServiceInstanceId(destination.getId());
			removal.setDependencyType(type);
			ManagerFactory.getServiceInstanceManager().setServiceInstanceDependency(removal, transaction);
			return null;
		});
	}

	/**
	 * Asserts the exact line count and verifies the localized detail and literal arguments in validation order.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void assertRepositoryViolations(String selection, String pathSuffix, String mediaName, String documentName) throws Exception
	{
		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			boolean english = Locale.US.equals(locale);
			try
			{
				invokeCreate(selection, locale);
				fail("an absent destination repository must reject creation");
			}
			catch (PortalException exception)
			{
				assertEquals("RULES_VIOLATED", ModuleBridge.errorCode(exception.getCause()));
				String message = exception.getLocalizedMessage();
				assertEquals(message, exception.getLocalizedMessage(locale));
				String[] lines = message.split("\n", -1);
				assertEquals(message, documentName == null ? 2 : 3, lines.length);
				assertEquals(english ? "The instances do not meet the migration requirements."
						: "As instâncias não atendem aos requisitos da migração.", lines[0]);
				String destinationName = destination.getName() + " (";
				assertTrue(lines[1], lines[1].startsWith(english ? "• The " + destinationName : "• A instância " + destinationName));
				assertTrue(lines[1], lines[1].contains(english ? "requires a media repository" : "precisa de um repositório de mídia"));
				assertTrue(lines[1], lines[1].contains(mediaName.replace('<', '＜') + " ("));
				assertTrue(lines[1], lines[1].contains(pathSuffix));
				assertFalse(message, message.contains(destination.getId()));
				if (documentName != null)
				{
					assertTrue(lines[2], lines[2].startsWith(english ? "• The " + destinationName : "• A instância " + destinationName));
					assertTrue(lines[2], lines[2].contains(english ? "requires a document repository" : "precisa de um repositório de documentos"));
					assertTrue(lines[2], lines[2].contains(documentName + " ("));
					assertTrue(lines[2], lines[2].contains(pathSuffix));
				}
				assertPopupScript(message);
			}
		}
	}

	/**
	 * Verifies that the platform error summary and XSL keep the entire list in the popup script.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void assertPopupScript(String message) throws Exception
	{
		var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
		var renderData = document.appendChild(document.createElement("renderData"));
		var controls = renderData.appendChild(document.createElement("controls"));
		var node = document.createElement("control");
		node.setAttribute("type", "lum_errorSummary");
		node.setAttribute("show", "popup");
		controls.appendChild(node);
		var control = new ErrorSummaryControl();
		set(Control.class, control, "controlDefinitionNode", node);
		control.addErrorMessage(message);
		control.setRenderData();

		var stylesheet = ErrorSummaryControl.class.getResource("/lumis/doui/style/DouiControls.xsl");
		try (var input = stylesheet.openStream())
		{
			var output = new StringWriter();
			TransformerFactory.newInstance().newTransformer(new StreamSource(input)).transform(new DOMSource(document), new StreamResult(output));
			String escaped = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
			assertTrue(output.toString(), output.toString().contains("LumisPortal.addMessage('" + escaped + "')"));
		}
	}

	@Test
	public void everyApiCodeHasTranslationsInBothLanguages() throws Exception
	{
		Field strings = ModuleBridge.load(HANDLER).getDeclaredField("ERROR_STRINGS");
		strings.setAccessible(true);
		Map<?, ?> mapping = (Map<?, ?>) strings.get(null);
		var resource = new lumis.portal.localization.GenericStringResource(
				"br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/strings/strings");
		Class<?> apiException = ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException");
		for (Field field : apiException.getFields())
		{
			if (field.getName().startsWith("ERROR_"))
			{
				String key = (String) mapping.get(field.get(null));
				org.junit.Assert.assertNotNull(field.getName(), key);
				assertTranslated(new PortalException(key, resource));
			}
		}
	}

	private static void assertTranslated(PortalException exception)
	{
		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			String text = exception.getLocalizedMessage(locale);
			assertNotEquals(exception.getMessage(), text);
			assertFalse(text.trim().isEmpty());
		}
	}

	/**
	 * Runs the process action in the default test locale.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void invokeCreate(String selection) throws Exception
	{
		invokeCreate(selection, Locale.US);
	}

	/**
	 * Runs the real process action using the specified DOUI session locale.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private void invokeCreate(String selection, Locale locale) throws Exception
	{
		invoke("createMigration", Map.of("name", "handler test", "originServiceInstanceId", origin.getId(), "destServiceInstanceId", destination.getId(),
				"selectedContentIds", selection), locale);
	}

	/**
	 * Runs the real process action with the given DOUI parameters and session locale.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static void invoke(String action, Map<String, ?> parameters, Locale locale) throws Exception
	{
		Object handler = ModuleBridge.load(HANDLER).getConstructor().newInstance();
		Class<?> base = Class.forName("lumis.doui.processaction.ProcessActionHandler");
		set(base, handler, "id", action);
		SessionConfig session = ManagerFactory.getAuthenticationManager().impersonate(UserConfig.USER_SYSTEM_ID);
		session.setLocale(locale);
		set(base, handler, "sessionConfig", session);
		set(base, handler, "parameters", parameters);
		set(base, handler, "processActionNode", DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement("processAction"));
		try
		{
			handler.getClass().getMethod("processAction").invoke(handler);
		}
		catch (InvocationTargetException exception)
		{
			throw (Exception) exception.getCause();
		}
		finally
		{
			ManagerFactory.getAuthenticationManager().endImpersonation(session);
		}
	}

	/**
	 * Shows the workflow name, not its ID, and the instance without workflow in each form language.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void differentWorkflowMessageNamesTheWorkflows() throws Exception
	{
		ServiceInstanceConfig news = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.NEWS_SERVICE_ID);
		ServiceInstanceConfig other = fixture.createServiceInstance(fixture.createChannel(), PortalFixture.NEWS_SERVICE_ID);
		List<WorkflowConfig> workflows = PortalFixture.inTransaction((session, transaction) -> List.copyOf(
				ContentManagerFactory.getWorkflowManager().getAllWorkflows(session, transaction)));
		assertFalse("an installed workflow is required", workflows.isEmpty());
		WorkflowConfig originWorkflow = workflows.get(0);
		setWorkflow(news, originWorkflow.getId());
		setWorkflow(other, null);

		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			boolean english = Locale.US.equals(locale);
			String line = ruleLines("DIFFERENT_WORKFLOW", news, other, locale).get(0);
			assertTrue(line, line.contains(news.getName() + " (") && line.contains(other.getName() + " ("));
			assertTrue(line, line.contains(localizedName(originWorkflow, locale)));
			assertFalse(line, line.contains(originWorkflow.getId()));
			assertTrue(line, line.contains(english ? "has no workflow" : "não tem fluxo de trabalho"));
			assertTrue(line, line.contains(english ? "Align the configuration" : "Alinhe a configuração"));
		}
	}

	/**
	 * Shows the service as not migratable for a files field, naming instance, source and field.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void filesFieldMessageNamesInstanceSourceAndField() throws Exception
	{
		ServiceInstanceConfig blog = fixture.createServiceInstance(fixture.createChannel(), "lumis.service.blog");
		ServiceInstanceConfig other = fixture.createServiceInstance(fixture.createChannel(), "lumis.service.blog");
		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			boolean english = Locale.US.equals(locale);
			List<String> lines = ruleLines("FILES_FIELD", blog, other, locale);
			assertFalse(lines.toString(), lines.isEmpty());
			String line = lines.stream().filter(text -> text.contains("contentImages")).findFirst().orElseThrow(() -> new AssertionError(lines.toString()));
			assertTrue(line, line.contains(blog.getName() + " ("));
			assertFalse(line, line.contains(blog.getId()));
			assertTrue(line, line.contains(english ? "cannot be migrated by this module" : "não pode ser migrado por este módulo"));
			assertTrue(line, line.contains(english ? "file field contentImages" : "campo de arquivos contentImages"));
		}
	}

	/**
	 * Shows the service as not migratable for a table source, naming instance and source.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Test
	public void tableSourceMessageNamesInstanceAndSource() throws Exception
	{
		ServiceInstanceConfig comment = fixture.createServiceInstance(fixture.createChannel(), "lumis.service.comment");
		ServiceInstanceConfig other = fixture.createServiceInstance(fixture.createChannel(), "lumis.service.comment");
		for (Locale locale : List.of(Locale.US, Locale.forLanguageTag("pt-BR")))
		{
			boolean english = Locale.US.equals(locale);
			List<String> lines = ruleLines("TABLE_SOURCE", comment, other, locale);
			String line = lines.stream().filter(text -> text.contains("contentLocale")).findFirst().orElseThrow(() -> new AssertionError(lines.toString()));
			assertTrue(line, line.contains(comment.getName() + " ("));
			assertFalse(line, line.contains(comment.getId()));
			assertTrue(line, line.contains(english ? "cannot be migrated by this module" : "não pode ser migrado por este módulo"));
			assertTrue(line, line.contains(english ? "of type table" : "do tipo tabela"));
		}
	}

	/**
	 * Validates the pair with the module rules and formats the violations of one rule as the handler shows them.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static List<String> ruleLines(String rule, ServiceInstanceConfig from, ServiceInstanceConfig to, Locale locale) throws Exception
	{
		List<?> violations = PortalFixture.inTransaction((session, transaction) -> (List<?>) ModuleBridge.invokeStatic(
				"br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.validation.DependencyRules", "validate",
				session, from.getId(), to.getId(), transaction));
		Class<?> handlerType = ModuleBridge.load(HANDLER);
		java.lang.reflect.Method format = handlerType.getDeclaredMethod("formatViolation",
				ModuleBridge.load("br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation"), Locale.class);
		format.setAccessible(true);
		List<String> lines = new java.util.ArrayList<>();
		for (Object violation : violations)
		{
			if (rule.equals(violation.getClass().getMethod("getRule").invoke(violation)))
			{
				lines.add((String) format.invoke(null, violation, locale));
			}
		}
		return lines;
	}

	private static String localizedName(WorkflowConfig workflow, Locale locale) throws Exception
	{
		return ManagerFactory.getLocalizationManager().localize(workflow.getName(), workflow.getResource(), locale);
	}

	private static void setWorkflow(ServiceInstanceConfig instance, String workflowId) throws Exception
	{
		PortalFixture.inTransaction((session, transaction) ->
		{
			ContentManagerFactory.getWorkflowManager().setServiceInstanceWorkflow(session, instance.getId(), workflowId, transaction);
			return null;
		});
	}

	private static void set(Class<?> type, Object target, String name, Object value) throws Exception
	{
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
