#!/bin/bash
#
# Runs the JUnit tests of the br.com.lumis.commons.contentmigrator module in a JVM of its own, over portal_install.
#
#     test/run-tests.sh HarnessSmokeTest [OtherTest ...]   # simple or fully qualified class names
#     test/run-tests.sh --all                               # every *Test class except UiScenarioTest
# UiScenarioTest requires -Dcontentmigrator.uiScenario=create|remove; use ui-smoke.sh.
#
# Steps (each one prints "[run-tests] <step>"):
#   1. takes an exclusive lock, so two portal test JVMs never run at the same time;
#   2. stops Tomcat if it is running (a second portal JVM over the same database leaves the
#      running portal caches stale);
#   3. removes old compiled output, compiles the module, and deploys it with ai_tools
#      update-module.sh (or runs the same UpdateModule.java worker directly when unavailable);
#   4. compiles the tests with Maven (test-compile);
#   5. runs JUnitCore with portal_install/www/WEB-INF/classes, WEB-INF/lib/*, test-classes and
#      junit -- never the module target/classes, which the portal loads through its deploy;
#   6. on exit, success or failure, brings Tomcat back to the state it had before.
#
# Environment:
#   LUMIS_AI_TOOLS          ai_tools folder (default: ai_lumis_dev/ai_tools in the environment version
#                           folder, eight levels above the module)
#   RUN_TESTS_JAVA_OPTS     extra JVM options, e.g. -Dcontentmigrator.keepTestData=true
#
set -euo pipefail

MODULE_DIR=$(cd -- "$(dirname -- "$0")/.." && pwd)
INSTALL_DIR=$(cd -- "$MODULE_DIR/../../../.." && pwd)
MODULE_NAME=$(basename -- "$MODULE_DIR")
INSTALLED_MODULE_DIR="$INSTALL_DIR/lumisdata/data/installedmodules/lib/$MODULE_NAME.jar"
LUMIS_AI_TOOLS=${LUMIS_AI_TOOLS:-$(cd -- "$MODULE_DIR/../../../../../../../.." && pwd)/ai_lumis_dev/ai_tools}
LOCK_FILE=${RUN_TESTS_LOCK_FILE:-${TMPDIR:-/tmp}/lumis-portal-run-tests.lock}
INSTALLED_BACKUP_DIR=
BACKUP_STAGING_DIR=
INSTALLED_MODULE_WAS_PRESENT=false

step() { echo "[run-tests] $*"; }
fail() { echo "[run-tests] ERROR: $*" >&2; exit 1; }

restore_installed_module()
{
	[ -n "$INSTALLED_BACKUP_DIR" ] || return 0
	rm -rf -- "$INSTALLED_MODULE_DIR" || return 1
	if [ "$INSTALLED_MODULE_WAS_PRESENT" = true ]; then
		cp -a "$INSTALLED_BACKUP_DIR/module" "$INSTALLED_MODULE_DIR" || return 1
	fi
	rm -rf -- "$INSTALLED_BACKUP_DIR" || return 1
	INSTALLED_BACKUP_DIR=
}

if [ "$#" -eq 0 ]; then
	echo "Uso: $0 <ClasseDeTeste>... | --all" >&2
	exit 2
fi
[ -d "$INSTALL_DIR/www/WEB-INF/classes" ] || fail "portal_install not found at $INSTALL_DIR"
[ -x "$LUMIS_AI_TOOLS/tomcat.sh" ] || fail "ai_tools not found at $LUMIS_AI_TOOLS (set LUMIS_AI_TOOLS)"

# Same Java and Maven as the ai_tools (their .env), unless already set in the environment
read_env() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$LUMIS_AI_TOOLS/.env" 2>/dev/null | tail -n 1; }
JAVA_HOME=${JAVA_HOME:-$(read_env JAVA_HOME)}
MAVEN_HOME=${MAVEN_HOME:-$(read_env MAVEN_HOME)}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
if [ -n "$MAVEN_HOME" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then MVN=$MAVEN_HOME/bin/mvn; else MVN=mvn; fi
export JAVA_HOME

step "lock: $LOCK_FILE"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
	step "lock: another run-tests is running, waiting"
	flock 9
fi
step "lock: acquired"

# Refuse a different Elasticsearch before changing the Tomcat state.
step "PROGRESS elasticsearch: checking the 19.0.0 cluster"
bash "$MODULE_DIR/test/ensure-es.sh" || fail "Elasticsearch preflight failed"

source "$MODULE_DIR/test/tomcat-cycle.sh"
tomcat_cycle_init || fail "could not determine Tomcat state"
restore_tomcat()
{
	local status=$?
	trap - EXIT INT TERM
	if [ -n "$BACKUP_STAGING_DIR" ]; then
		rm -rf -- "$BACKUP_STAGING_DIR" || status=1
	fi
	if [ -n "$INSTALLED_BACKUP_DIR" ]; then
		step "deploy: restoring installed module after an incomplete deploy"
		restore_installed_module || { echo "[run-tests] ERROR: could not restore installed module" >&2; status=1; }
	fi
	tomcat_cycle_restore || { echo "[run-tests] ERROR: tomcat restoration failed" >&2; status=1; }
	step "exit $status"
	exit $status
}
trap restore_tomcat EXIT
trap 'exit 130' INT TERM

tomcat_cycle_stop || fail "could not safely stop Tomcat"

RUNTIME_CP="$INSTALL_DIR/lib/shared/*:$INSTALL_DIR/lib/*:$INSTALL_DIR/www/WEB-INF/classes:$INSTALL_DIR/www/WEB-INF/lib/*"

# Incremental compilation and deployment can leave classes from removed/renamed sources.
# Delete only compiled classes: resources (themes, definitions) and bundled dependencies must stay,
# because the deploy does not restore them. The next compile and deploy recreate this module's classes.
for output in "$MODULE_DIR/target/classes/br" "$MODULE_DIR/src/br"; do
	step "deploy: remove old compiled classes from $output"
	if [ -d "$output" ]; then
		find "$output" -name '*.class' -delete || fail "could not remove old compiled classes"
	fi
done
# The registered source root is src: rebuild it before deploy, including ui-smoke.sh runs.
# local-development disables incremental cleanup and preserves source resources.
step "deploy: compile $MODULE_NAME"
"$MVN" -B -q -f "$MODULE_DIR/pom.xml" -Plocal-development compile \
	|| fail "module compile failed"

BACKUP_STAGING_DIR=$(mktemp -d "${TMPDIR:-/tmp}/lumis-run-tests-module.XXXXXX") \
	|| fail "could not prepare installed module backup"
if [ -d "$INSTALLED_MODULE_DIR" ]; then
	cp -a "$INSTALLED_MODULE_DIR" "$BACKUP_STAGING_DIR/module" \
		|| { rm -rf -- "$BACKUP_STAGING_DIR"; BACKUP_STAGING_DIR=; fail "could not back up installed module"; }
	INSTALLED_MODULE_WAS_PRESENT=true
fi
INSTALLED_BACKUP_DIR=$BACKUP_STAGING_DIR
BACKUP_STAGING_DIR=

if [ -d "$INSTALLED_MODULE_DIR/br" ]; then
	step "deploy: remove old installed classes from $INSTALLED_MODULE_DIR/br"
	find "$INSTALLED_MODULE_DIR/br" -name '*.class' -delete || fail "could not remove old installed classes"
fi

if [ -f "$INSTALL_DIR/../development/ai_tools/UpdateModule.java" ]; then
	step "deploy: update-module $MODULE_NAME"
	"$LUMIS_AI_TOOLS/update-module.sh" "$MODULE_NAME" || fail "update-module failed (logs in $LUMIS_AI_TOOLS/logs)"
else
	# UpdateRunner looks for its UpdateModule.java worker under <workspace>/development/ai_tools,
	# which this ai_tools clone does not populate; run the same worker here
	step "deploy: UpdateModule $MODULE_NAME"
	(cd "$INSTALL_DIR" && "$JAVA" --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED \
		-Xmx768m -cp "$RUNTIME_CP" "$LUMIS_AI_TOOLS/UpdateModule.java" -d:"$INSTALL_DIR/lumisdata" "$MODULE_NAME") \
		> "$MODULE_DIR/target/update-module.log" 2>&1 \
		|| { tail -n 40 "$MODULE_DIR/target/update-module.log" >&2; fail "module deploy failed (log: target/update-module.log)"; }
	grep -E "^\s*modulo |Atualizacao" "$MODULE_DIR/target/update-module.log" || true
fi

if [ -n "$INSTALLED_BACKUP_DIR" ]; then
	rm -rf -- "$INSTALLED_BACKUP_DIR" || fail "could not remove installed module backup"
	INSTALLED_BACKUP_DIR=
fi

step "test-compile"
"$MVN" -B -q -f "$MODULE_DIR/pom.xml" -Dmaven.compiler.useIncrementalCompilation=false test-compile \
	|| fail "test-compile failed"

JUNIT_CP_FILE=$MODULE_DIR/target/junit.classpath
"$MVN" -B -q -f "$MODULE_DIR/pom.xml" dependency:build-classpath -DincludeGroupIds=junit,org.hamcrest \
	-Dmdep.outputFile="$JUNIT_CP_FILE" || fail "could not resolve the junit classpath"

TEST_CLASSES=$MODULE_DIR/target/test-classes
CLASSES=()
if [ "$1" = "--all" ]; then
	while IFS= read -r file; do
		CLASSES+=("$(echo "${file#"$TEST_CLASSES"/}" | sed 's/\.class$//; s#/#.#g')")
	done < <(find "$TEST_CLASSES" -name '*Test.class' ! -name '*$*' ! -name 'UiScenarioTest.class' | sort)
	[ "${#CLASSES[@]}" -gt 0 ] || fail "no *Test class in $TEST_CLASSES"
else
	for name in "$@"; do
		if [[ "$name" == *.* ]]; then
			[ -f "$TEST_CLASSES/${name//.//}.class" ] || fail "test class not found: $name"
			CLASSES+=("$name")
			continue
		fi
		mapfile -t found < <(find "$TEST_CLASSES" -name "$name.class")
		[ "${#found[@]}" -eq 1 ] || fail "expected one class named $name in $TEST_CLASSES, found ${#found[@]}"
		CLASSES+=("$(echo "${found[0]#"$TEST_CLASSES"/}" | sed 's/\.class$//; s#/#.#g')")
	done
fi

CLASSPATH_TEST="$TEST_CLASSES:$RUNTIME_CP:$(cat "$JUNIT_CP_FILE")"

# The working directory is the module: PortalConfiguration.properties points lumisDataPath
# relative to it
step "junit: ${CLASSES[*]}"
cd "$MODULE_DIR"
set +e
# shellcheck disable=SC2086
"$JAVA" --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -Xmx1g \
	${RUN_TESTS_JAVA_OPTS:-} -cp "$CLASSPATH_TEST" org.junit.runner.JUnitCore "${CLASSES[@]}"
JUNIT_STATUS=$?
set -e
step "junit: finished with status $JUNIT_STATUS"
exit $JUNIT_STATUS
