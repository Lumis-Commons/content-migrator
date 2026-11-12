#!/usr/bin/env bash
# Capturas de um HTML em Chrome headless: PNG claro e escuro em 1280x900, 768x1024, 390x844 e
# 360x800, PDF de impressão e sonda de overflow horizontal.
#
# Uso: render_html.sh [--out DIR] ARQUIVO.html   (padrão: target/render)
#      render_html.sh --self-test
#
# A sonda não altera o original: gera um HTML temporário que embute o documento num iframe com
# a largura testada (o Chrome headless não abre janelas menores que 500px) e grava scrollWidth e
# innerWidth do iframe em document.title, lido com --dump-dom. Falha com 'arquivo:1: motivo' se
# scrollWidth > innerWidth. CHROME troca o executável (padrão: google-chrome).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHROME="${CHROME:-google-chrome}"
SIZES=(1280x900 768x1024 390x844 360x800)
TIMEOUT=120
WORK=""

progress() { echo "[render] $*"; }

cleanup() { [[ -n "$WORK" ]] && rm -rf "$WORK"; return 0; }
trap cleanup EXIT

require_chrome() {
	if ! command -v "$CHROME" >/dev/null 2>&1; then
		echo "render_html.sh: Chrome não encontrado ('$CHROME'). Instale o google-chrome ou defina CHROME." >&2
		exit 2
	fi
}

chrome() {
	timeout "$TIMEOUT" "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
		--user-data-dir="$WORK/profile" --no-first-run "$@" 2>/dev/null
}

# probe ARQUIVO LARGURA -> imprime 'scrollWidth innerWidth'
probe() {
	local html="$1" width="$2" wrapper="$WORK/probe-$2.html" escaped title
	escaped="$(sed -e 's/&/\&amp;/g' -e 's/"/\&quot;/g' "$html")"
	printf '%s' '<!DOCTYPE html><html><head><title>probe</title></head><body style="margin:0">' \
		"<iframe id=\"f\" style=\"width:${width}px;height:900px;border:0\" srcdoc=\"" "$escaped" '"></iframe>' \
		'<script>var f=document.getElementById("f");f.addEventListener("load",function(){var d=f.contentDocument.documentElement;' \
		'document.title="sw="+d.scrollWidth+" iw="+f.contentWindow.innerWidth;});</script></body></html>' >"$wrapper"
	title="$(chrome --window-size=1280,900 --virtual-time-budget=3000 --dump-dom "file://$wrapper" | grep -o '<title>[^<]*' | head -n 1)"
	if [[ ! "$title" =~ sw=([0-9]+)\ iw=([0-9]+) ]]; then
		echo "render_html.sh: sonda de overflow sem resposta em ${width}px (título: '${title}')" >&2
		return 1
	fi
	echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]}"
}

render() {
	local html="$1" out="$2" name size w h sw iw status=0
	[[ -f "$html" ]] || { echo "render_html.sh: arquivo '$html' não existe" >&2; return 2; }
	name="$(basename "${html%.*}")"
	html="$(cd "$(dirname "$html")" && pwd)/$(basename "$html")"
	mkdir -p "$out"
	: >"$out/$name-overflow.txt"
	for size in "${SIZES[@]}"; do
		w="${size%x*}"; h="${size#*x}"
		progress "PNG claro $size"
		chrome --window-size="$w,$h" --screenshot="$out/$name-$size.png" "file://$html" >/dev/null
		progress "PNG escuro $size"
		chrome --window-size="$w,$h" --force-dark-mode --enable-features=WebContentsForceDark \
			--screenshot="$out/$name-dark-$size.png" "file://$html" >/dev/null
		progress "sonda de overflow ${w}px"
		read -r sw iw < <(probe "$html" "$w")
		echo "${w} scrollWidth=${sw} innerWidth=${iw}" >>"$out/$name-overflow.txt"
		if ((sw > iw)); then
			echo "$html:1: overflow horizontal em ${w}px (scrollWidth ${sw} > innerWidth ${iw})" >&2
			status=1
		fi
	done
	progress "PDF"
	chrome --no-pdf-header-footer --print-to-pdf="$out/$name.pdf" "file://$html" >/dev/null
	progress "saída em $out"
	return "$status"
}

self_test() {
	local fixtures="$ROOT/test/fixtures/html" out failures=0 size f
	out="$WORK/self"
	progress "valid.html: renderização esperada sem overflow"
	render "$fixtures/valid.html" "$out" || { echo "self-test: valid.html acusou overflow" >&2; failures=1; }
	for size in "${SIZES[@]}"; do
		for f in "$out/valid-$size.png" "$out/valid-dark-$size.png"; do
			if [[ "$(head -c 8 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" != 89504e470d0a1a0a ]]; then
				echo "self-test: $f não é PNG" >&2; failures=1
			fi
		done
	done
	[[ "$(head -c 4 "$out/valid.pdf" 2>/dev/null)" == "%PDF" ]] || { echo "self-test: valid.pdf não é PDF" >&2; failures=1; }
	[[ "$(wc -l <"$out/valid-overflow.txt")" -eq ${#SIZES[@]} ]] || { echo "self-test: sonda incompleta" >&2; failures=1; }
	if [[ "$(sha256sum <"$out/valid-1280x900.png")" == "$(sha256sum <"$out/valid-dark-1280x900.png")" ]]; then
		echo "self-test: PNG escuro igual ao claro" >&2; failures=1
	fi
	progress "wide.html: overflow esperado"
	if render "$fixtures/wide.html" "$WORK/wide" 2>"$WORK/wide.err"; then
		echo "self-test: wide.html deveria acusar overflow" >&2; failures=1
	elif ! grep -q "overflow horizontal em 360px" "$WORK/wide.err"; then
		echo "self-test: overflow de wide.html não apontado em 360px" >&2; failures=1
	fi
	if ((failures)); then return 1; fi
	echo "self-test OK"
}

main() {
	local out="$ROOT/target/render" file=""
	WORK="$(mktemp -d)"
	while (($#)); do
		case "$1" in
			--self-test) require_chrome; self_test; return ;;
			--out) out="$2"; shift ;;
			-h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; return 0 ;;
			*) file="$1" ;;
		esac
		shift
	done
	[[ -n "$file" ]] || { echo "uso: render_html.sh [--out DIR] ARQUIVO.html | --self-test" >&2; return 2; }
	require_chrome
	render "$file" "$out"
}

main "$@"
