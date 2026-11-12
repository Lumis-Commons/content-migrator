#!/usr/bin/env bash
# Offline static validation; stop at the first failing checker.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
shopt -s globstar nullglob

run_check() {
	local checker=$1
	shift
	printf 'PROGRESS %s\n' "$checker"
	local status=0
	python3 "test/$checker.py" "$@" || status=$?
	if (( status != 0 )); then
		printf 'ERROR %s failed (status %d)\n' "$checker" "$status" >&2
		exit "$status"
	fi
}

documents=(README.md docs/**/*.md docs/**/*.html)
html=(docs/**/*.html)

run_check check_architecture docs/architecture
run_check check_architecture --profile readme README.md
run_check check_architecture --profile guide docs/guia-de-codigo.md
run_check check_architecture --profile requirements docs/requisitos/requisitos-migracao-conteudo.md
run_check check_architecture --profile evidence docs/internal/evidencias-plataforma-lumis.md
run_check check_architecture --profile htmldesign docs/internal/design-visao-geral-html.md
run_check check_architecture --profile dtodesign docs/internal/design-dtos-migracao.md
run_check check_architecture --profile review docs/internal/review
run_check check_code_refs "${html[@]}"
run_check check_doc_links "${documents[@]}"
run_check check_html "${html[@]}"

classes=(target/classes/**/*.class)
if (( ${#classes[@]} == 0 )); then
	printf 'PROGRESS compiling missing classes offline\n'
	mvn -o -B compile
fi
run_check check_structure
run_check check_style
run_check check_tone "${documents[@]}"
run_check check_bytecode check
printf 'PROGRESS all eight static checkers passed\n'
