package br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.processaction;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.api.MigrationApi;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation.InstanceReference;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.dto.RuleViolation.WorkflowReference;
import br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.exception.MigrationApiException;
import lumis.doui.processaction.GenericProcessActionHandler;
import lumis.portal.PortalException;
import lumis.portal.localization.GenericStringResource;
import lumis.portal.manager.ManagerFactory;
import lumis.portal.privacyterm.IPrivacyTermManagerSPI;
import lumis.portal.serviceinstance.ServiceInstanceDependencyConfig;

/** DOUI adapter for the migration API; validation and lifecycle rules remain in the API. */
public class MigrationProcessActionHandler extends GenericProcessActionHandler
{
	private static final Map<String, String> ERROR_STRINGS = Map.ofEntries(
			Map.entry(MigrationApiException.ERROR_REQUIRED, "STR_MIGRATION_REQUIRED"),
			Map.entry(MigrationApiException.ERROR_INSTANCE_NOT_FOUND, "STR_MIGRATION_INSTANCE_NOT_FOUND"),
			Map.entry(MigrationApiException.ERROR_SAME_INSTANCE, "STR_MIGRATION_SAME_INSTANCE"),
			Map.entry(MigrationApiException.ERROR_DOUI_SERVICE, "STR_MIGRATION_DOUI_SERVICE"),
			Map.entry(MigrationApiException.ERROR_REPOSITORY_SERVICE, "STR_MIGRATION_REPOSITORY_SERVICE"),
			Map.entry(MigrationApiException.ERROR_DIFFERENT_SERVICES, "STR_MIGRATION_DIFFERENT_SERVICES"),
			Map.entry(MigrationApiException.ERROR_CONTENTMIGRATION_CONFLICT, "STR_MIGRATION_CONTENTMIGRATION_CONFLICT"),
			Map.entry(MigrationApiException.ERROR_DUPLICATE_PAIR, "STR_MIGRATION_DUPLICATE_PAIR"),
			Map.entry(MigrationApiException.ERROR_UNSUPPORTED_SERVICE, "STR_MIGRATION_UNSUPPORTED_SERVICE"),
			Map.entry(MigrationApiException.ERROR_RULES_VIOLATED, "STR_MIGRATION_RULES_VIOLATED"),
			Map.entry(MigrationApiException.ERROR_INVALID_UPDATE_MODE, "STR_MIGRATION_INVALID_UPDATE_MODE"),
			Map.entry(MigrationApiException.ERROR_INVALID_WIDGET_MAPPING, "STR_MIGRATION_INVALID_WIDGET_MAPPING"),
			Map.entry(MigrationApiException.ERROR_NOT_HIERARCHICAL, "STR_MIGRATION_NOT_HIERARCHICAL"),
			Map.entry(MigrationApiException.ERROR_NO_SELECTION, "STR_MIGRATION_NO_SELECTION"),
			Map.entry(MigrationApiException.ERROR_INVALID_SELECTION, "STR_MIGRATION_INVALID_SELECTION"),
			Map.entry(MigrationApiException.ERROR_NOT_FOUND, "STR_MIGRATION_NOT_FOUND"),
			Map.entry(MigrationApiException.ERROR_FORBIDDEN, "STR_MIGRATION_FORBIDDEN"),
			Map.entry("SELECTION_TOO_LARGE", "STR_MIGRATION_SELECTION_TOO_LARGE"),
			Map.entry(MigrationApiException.ERROR_LOCKED, "STR_MIGRATION_LOCKED"));

	/**
	 * Localization bundle for the hierarchical migration form.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final GenericStringResource STRINGS = new GenericStringResource(
			"br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/strings/strings");

	/**
	 * Display labels keyed by each migrated repository dependency type.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final Map<String, String> REPOSITORY_STRINGS = Map.of(
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY, "STR_MIGRATION_DEPENDENCY_MEDIA",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY, "STR_MIGRATION_DEPENDENCY_DOCUMENT",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, "STR_MIGRATION_DEPENDENCY_WIDGET",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_TAG_REPOSITORY, "STR_MIGRATION_DEPENDENCY_TAG",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY, "STR_MIGRATION_DEPENDENCY_CATEGORIZATION",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_EMAIL_TEMPLATE_REPOSITORY, "STR_MIGRATION_DEPENDENCY_EMAIL",
			IPrivacyTermManagerSPI.DEPENDENCY_TYPE_PRIVACYTERM_REPOSITORY, "STR_MIGRATION_DEPENDENCY_PRIVACYTERM");

	/**
	 * Effect of linking another repository, keyed by each migrated repository dependency type.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final Map<String, String> EFFECT_STRINGS = Map.of(
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_MEDIA_REPOSITORY, "STR_MIGRATION_EFFECT_MEDIA",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_DOCUMENT_REPOSITORY, "STR_MIGRATION_EFFECT_DOCUMENT",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_WIDGET_REPOSITORY, "STR_MIGRATION_EFFECT_WIDGET",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_TAG_REPOSITORY, "STR_MIGRATION_EFFECT_TAG",
			ServiceInstanceDependencyConfig.DEPENDENCY_TYPE_CATEGORIZATION_REPOSITORY, "STR_MIGRATION_EFFECT_CATEGORIZATION");

	/**
	 * Form and button of each process action, used to tell the user where a required field is missing.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final Map<String, ActionForm> ACTION_FORMS = Map.of(
			"createMigration", new ActionForm("STR_ADD_MIGRATION", "STR_CREATE"),
			"updateSelection", new ActionForm("STR_EDIT_SELECTION", "STR_SAVE_SELECTION"),
			"startIncremental", new ActionForm("STR_MIGRATION_FORM_LIST", "STR_START_INCREMENTAL"),
			"startUpdate", new ActionForm("STR_MIGRATION_FORM_LIST", "STR_START_UPDATE"),
			"stopMigration", new ActionForm("STR_MIGRATION_FORM_LIST", "STR_STOP"),
			"deleteMigration", new ActionForm("STR_MIGRATION_FORM_LIST", "STR_DELETE"),
			"undoMigration", new ActionForm("STR_MIGRATION_FORM_LIST", "STR_UNDO"));

	/**
	 * Label and instruction of each request parameter that an action can find missing, keyed by parameter name.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final Map<String, RequiredField> REQUIRED_FIELDS = Map.of(
			"name", new RequiredField("STR_NAME", "STR_MIGRATION_REQUIRED_HINT_NAME"),
			"originServiceInstanceId", new RequiredField("STR_ORIGIN", "STR_MIGRATION_REQUIRED_HINT_ORIGIN"),
			"destServiceInstanceId", new RequiredField("STR_DESTINATION", "STR_MIGRATION_REQUIRED_HINT_DESTINATION"),
			"selectedContentIds", new RequiredField("STR_CONTENTS_TO_MIGRATE", "STR_MIGRATION_REQUIRED_HINT_CONTENTS"),
			"migrationId", new RequiredField("STR_MIGRATION_SELECTED", "STR_MIGRATION_REQUIRED_HINT_MIGRATION"));

	/**
	 * Dispatches the form action and localizes API validation failures.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	@Override
	public void processAction() throws PortalException
	{
		try
		{
			String migrationId = (String) getParameter("migrationId");
			switch (getId())
			{
				case "createMigration" :
					Map<String, Object> create = new LinkedHashMap<>();
					create.put("name", getParameter("name", String.class));
					create.put("originServiceInstanceId", getParameter("originServiceInstanceId", String.class));
					create.put("destServiceInstanceId", getParameter("destServiceInstanceId", String.class));
					create.put("selectedContentIds", getParameter("selectedContentIds", String.class));
					create.put("author", sessionConfig.getUserId());
					MigrationApi.create(create);
					break;
				case "updateSelection" :
					Map<String, Object> selection = new LinkedHashMap<>();
					selection.put("migrationId", migrationId);
					selection.put("selectedContentIds", getParameter("selectedContentIds", String.class));
					MigrationApi.updateSelection(selection);
					break;
				case "startIncremental" :
					MigrationApi.start(startParams(migrationId, "ADD_ONLY"));
					break;
				case "startUpdate" :
					MigrationApi.start(startParams(migrationId, "UPDATE"));
					break;
				case "stopMigration" :
					MigrationApi.stop(migrationId);
					break;
				case "deleteMigration" :
					MigrationApi.delete(migrationId);
					break;
				case "undoMigration" :
					Map<String, Object> undo = new LinkedHashMap<>();
					undo.put("migrationId", migrationId);
					undo.put("userId", sessionConfig.getUserId());
					MigrationApi.undo(undo);
					break;
				default :
					throw new IllegalArgumentException("Unknown migration action " + getId());
			}
		}
		catch (MigrationApiException exception)
		{
			String key = ERROR_STRINGS.getOrDefault(exception.getCode(), "STR_MIGRATION_ERROR");
			if (MigrationApiException.ERROR_RULES_VIOLATED.equals(exception.getCode()) && !exception.getViolations().isEmpty())
			{
				throw localizedViolations(key, exception);
			}
			if (MigrationApiException.ERROR_REQUIRED.equals(exception.getCode()) && namesKnownFields(exception))
			{
				throw localizedRequiredFields(exception);
			}
			throw new PortalException(key, STRINGS, exception);
		}
		addDefaultResponse();
	}

	/**
	 * Builds one localized popup line per violation in validation order.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private PortalException localizedViolations(String headerKey, MigrationApiException exception) throws PortalException
	{
		Locale locale = sessionConfig.getLocale();
		StringBuilder message = new StringBuilder(localize(headerKey, locale));
		for (RuleViolation violation : exception.getViolations())
		{
			message.append("\n• ").append(formatViolation(violation, locale));
		}
		return new LocalizedMessageException(message.toString(), exception);
	}

	/**
	 * Tells whether the missing parameters and the running action are known, so that a precise message can be built;
	 * otherwise the generic required-fields message is shown.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private boolean namesKnownFields(MigrationApiException exception) throws PortalException
	{
		return ACTION_FORMS.containsKey(getId()) && !exception.getFields().isEmpty() && REQUIRED_FIELDS.keySet().containsAll(exception.getFields());
	}

	/**
	 * Builds a popup message that names the form, lists each missing field with what to do about it, and says which
	 * button to press again. The texts come from the service strings in the session locale, so no transaction or system
	 * session is needed to show them.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private PortalException localizedRequiredFields(MigrationApiException exception) throws PortalException
	{
		Locale locale = sessionConfig.getLocale();
		ActionForm actionForm = ACTION_FORMS.get(getId());
		String button = localize(actionForm.buttonKey(), locale);
		StringBuilder message = new StringBuilder(format("STR_MIGRATION_REQUIRED_HEADER", locale, button, localize(actionForm.formKey(), locale)));
		for (String name : exception.getFields())
		{
			RequiredField field = REQUIRED_FIELDS.get(name);
			message.append("\n• ").append(format("STR_MIGRATION_REQUIRED_FIELD", locale, localize(field.labelKey(), locale), localize(field.hintKey(), locale)));
		}
		message.append('\n').append(format("STR_MIGRATION_REQUIRED_RETRY", locale, button));
		return new LocalizedMessageException(message.toString(), exception);
	}

	/**
	 * Chooses a localized rule template and supplies its display arguments.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String formatViolation(RuleViolation violation, Locale locale) throws PortalException
	{
		String key = "STR_MIGRATION_RULE_" + violation.getRule();
		return switch (violation.getRule())
		{
			case RuleViolation.RULE_REPOSITORY_REQUIRED -> formatAll(locale, repositoryArguments(violation, locale),
					key, key + "_PATHS", "STR_MIGRATION_STEP_CHOOSE");
			case RuleViolation.RULE_REPOSITORY_NOT_SHARED -> formatAll(locale, repositoryArguments(violation, locale),
					key, violation.getDestinationRepository() == null ? key + "_NONE" : key + "_CURRENT", "STR_MIGRATION_STEP_LINK");
			case RuleViolation.RULE_DIFFERENT_WORKFLOW -> formatAll(locale, new Object[] {display(violation.getOrigin()), display(violation.getDestination()),
					workflowSentence(violation.getOriginWorkflow(), locale), workflowSentence(violation.getDestinationWorkflow(), locale)},
					key, key + "_ALIGN");
			case RuleViolation.RULE_FILES_FIELD ->
			{
				String subject = violation.getSubject();
				int separator = subject.indexOf('.');
				yield format(key, locale, display(violation.getOrigin()), popupText(separator < 0 ? subject : subject.substring(0, separator)),
						popupText(separator < 0 ? subject : subject.substring(separator + 1)));
			}
			case RuleViolation.RULE_TABLE_SOURCE -> format(key, locale, display(violation.getOrigin()), popupText(violation.getSubject()));
			default -> localize("STR_MIGRATION_RULE_UNKNOWN", locale);
		};
	}

	/**
	 * Tells which workflow an instance uses, by its localized name, or that it has none.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String workflowSentence(WorkflowReference workflow, Locale locale) throws PortalException
	{
		if (workflow == null)
		{
			return localize("STR_MIGRATION_WORKFLOW_NONE", locale);
		}
		String name = workflow.name() == null || workflow.name().isBlank() ? workflow.id()
				: ManagerFactory.getLocalizationManager().localize(workflow.name(), workflow.resource(), locale);
		return format("STR_MIGRATION_WORKFLOW_USES", locale, popupText(name));
	}

	/**
	 * Builds the arguments shared by the repository messages: destination, type label, origin repository, and
	 * either the effect of linking another repository or the current destination repository.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static Object[] repositoryArguments(RuleViolation violation, Locale locale) throws PortalException
	{
		String type = violation.getDependencyType();
		boolean shared = RuleViolation.RULE_REPOSITORY_NOT_SHARED.equals(violation.getRule());
		String detail;
		if (shared)
		{
			detail = violation.getDestinationRepository() == null ? "" : display(violation.getDestinationRepository());
		}
		else
		{
			detail = localize(EFFECT_STRINGS.getOrDefault(type, "STR_MIGRATION_EFFECT_REPOSITORY"), locale);
		}
		return new Object[] {display(violation.getDestination()), localize(REPOSITORY_STRINGS.getOrDefault(type, "STR_MIGRATION_DEPENDENCY_REPOSITORY"), locale),
				display(violation.getOriginRepository()), detail};
	}

	/**
	 * Formats the given templates with the same arguments and joins them into one sentence group.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String formatAll(Locale locale, Object[] arguments, String... keys) throws PortalException
	{
		StringBuilder text = new StringBuilder();
		for (String key : keys)
		{
			text.append(text.length() == 0 ? "" : " ").append(format(key, locale, arguments));
		}
		return text.toString();
	}

	/**
	 * Resolves a string in the form session locale.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String localize(String key, Locale locale) throws PortalException
	{
		return ManagerFactory.getLocalizationManager().localize(key, STRINGS, locale);
	}

	/**
	 * Interpolates literal arguments after resolving the localized template.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String format(String key, Locale locale, Object... arguments) throws PortalException
	{
		return new MessageFormat(localize(key, locale), locale).format(arguments);
	}

	/**
	 * Formats the instance name with its channel path when available.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String display(InstanceReference instance)
	{
		if (instance.name() == null || instance.name().isBlank())
		{
			return popupText(instance.id());
		}
		if (instance.channelPath() == null || instance.channelPath().isBlank())
		{
			return popupText(instance.name());
		}
		return popupText(instance.name() + " (" + instance.channelPath() + ")");
	}

	/**
	 * Keeps display data on one line and prevents markup in the DOUI popup script.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static String popupText(String text)
	{
		return text.replace('<', '＜').replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ");
	}

	private Map<String, Object> startParams(String migrationId, String updateMode) throws PortalException
	{
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("migrationId", migrationId);
		params.put("updateMode", updateMode);
		params.put("userId", sessionConfig.getUserId());
		return params;
	}

	/**
	 * Form and button where the user triggers a process action.
	 *
	 * @param formKey
	 *            string id of the form name.
	 * @param buttonKey
	 *            string id of the button label.
	 * @since %LUM_NEXT_VERSION%
	 */
	private record ActionForm(String formKey, String buttonKey)
	{
	}

	/**
	 * Display texts of a request parameter that a process action requires.
	 *
	 * @param labelKey
	 *            string id of the field label the user sees.
	 * @param hintKey
	 *            string id of the instruction on how to fill the field.
	 * @since %LUM_NEXT_VERSION%
	 */
	private record RequiredField(String labelKey, String hintKey)
	{
	}

	/**
	 * Carries a message already localized and formatted for the form session.
	 *
	 * @since %LUM_NEXT_VERSION%
	 */
	private static final class LocalizedMessageException extends PortalException
	{
		/**
		 * Serialization identifier for the localized exception.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private static final long serialVersionUID = 1L;

		/**
		 * Retains the API error as the cause of the localized message.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		private LocalizedMessageException(String message, MigrationApiException cause)
		{
			super(message, cause);
		}

		/**
		 * Returns the formatted session message directly to the DOUI.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		@Override
		public String getLocalizedMessage()
		{
			return getMessage();
		}

		/**
		 * Returns the same message already rendered in the session locale.
		 *
		 * @since %LUM_NEXT_VERSION%
		 */
		@Override
		public String getLocalizedMessage(Locale locale)
		{
			return getMessage();
		}
	}
}
