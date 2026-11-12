#!/usr/bin/env python3
"""Checker determinístico dos links de código de documentos .md e .html.

Uso: check_doc_links.py ARQUIVO...
     check_doc_links.py --self-test

Saída: 'arquivo:linha: motivo'. Links http, mailto e âncoras internas são ignorados.
Link 'Classe.método, linhas a-b' com âncora #La-Lb: o arquivo existe, a-b do texto é igual
à âncora, Classe é o nome do arquivo e a declaração do método (ou seu Javadoc) começa nas
6 primeiras linhas da faixa. Link com texto de arquivo ('arquivo.xml, linhas 4-137') só
confere o arquivo e a faixa. Faixa que cobre mais de 60% de um arquivo Java falha. Link
de um documento fora de docs/internal para dentro dele falha, porque esse diretório não vai
no pacote do módulo. Em HTML, a sequência '](' no texto visível falha: é link Markdown
colado no HTML.
"""
import argparse
import os
import re
import sys
import tempfile
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MD_LINK = re.compile(r"(!?)\[([^\]\n]*)\]\(\s*(<[^>]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\s*\)")
LINE_ANCHOR = re.compile(r"L(\d+)(?:-L(\d+))?")
TEXT_RANGE = re.compile(r"^(.+?)(?:,\s*(linhas?)\s+(\d+)(?:\s*[-–]\s*(\d+))?)?$")
CLASS_METHOD = re.compile(r"^(\w+)\.(\w+)$")
FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})(.*)$")
METHOD_WINDOW = 6
MAX_JAVA_SHARE = 0.6
INTERNAL_DIR = "docs/internal"

MODIFIERS = r"(?:(?:public|protected|private|static|final|abstract|synchronized|default|native|strictfp)\s+)*"
RETURN_TYPE = r"(?:<[^>]*>\s+)?[\w.$]+(?:<.*>)?(?:\[\])*\s+"
NOT_STATEMENT = r"(?!(?:return|new|throw|else|case|yield)\b)(?:@\w+(?:\([^)]*\))?\s+)*"


def count_lines(path):
    with open(path, "rb") as f:
        data = f.read()
    if not data:
        return 0
    n = data.count(b"\n")
    return n if data.endswith(b"\n") else n + 1


def declaration_pattern(method, owner):
    """Declaração de método; o construtor dispensa o tipo de retorno."""
    prefix = NOT_STATEMENT + MODIFIERS
    if method != owner:
        prefix += RETURN_TYPE
    return re.compile(r"^" + prefix + re.escape(method) + r"\s*\(")


def method_starts_in_window(lines, start, end, method, owner):
    declaration = declaration_pattern(method, owner)
    for n in range(start, min(end, start + METHOD_WINDOW - 1) + 1):
        text = lines[n - 1].strip()
        if declaration.match(text):
            return True
        if text.startswith("/*"):
            k = n
            while k <= len(lines) and "*/" not in lines[k - 1]:
                k += 1
            k += 1
            while k <= len(lines):
                after = lines[k - 1].strip()
                if declaration.match(after):
                    return True
                if after and not after.startswith("@"):
                    break
                k += 1
    return False


def check_link(document, root, text, href):
    """Retorna o motivo da falha de um link, ou None."""
    target = href.strip().strip("<>")
    url = urlsplit(target)
    if url.scheme or url.netloc or not url.path:
        return None
    path = os.path.realpath(os.path.join(os.path.dirname(document), unquote(url.path)))
    real_root = os.path.realpath(root)
    internal = os.path.join(real_root, INTERNAL_DIR)
    if (path + os.sep).startswith(internal + os.sep) and not (os.path.realpath(document) + os.sep).startswith(internal + os.sep):
        return "link para %s, que não vai no pacote do módulo: %s" % (INTERNAL_DIR, target)
    if not os.path.isfile(path):
        return "link inexistente: %s" % target
    anchor = None
    if url.fragment:
        anchor = LINE_ANCHOR.fullmatch(url.fragment)
        if not anchor:
            if path.endswith((".md", ".html")):
                return None
            return "âncora inválida: %s" % target
    text = re.sub(r"\s+", " ", text.replace("`", "")).strip()
    parsed = TEXT_RANGE.match(text)
    has_lines = bool(parsed and parsed[2])
    if not anchor:
        return "texto cita linhas, mas o link não tem âncora #La-Lb: %s" % target if has_lines else None
    total = count_lines(path)
    start = int(anchor[1])
    end = int(anchor[2]) if anchor[2] else start
    if start < 1 or end < start or end > total:
        return "faixa L%d-L%d fora do arquivo (%d linhas): %s" % (start, end, total, target)
    is_java = path.endswith(".java")
    if is_java and (end - start + 1) > MAX_JAVA_SHARE * total:
        return "faixa L%d-L%d cobre %d%% do arquivo (máximo 60%%): %s" % (start, end, 100 * (end - start + 1) // total, target)
    if not parsed:
        return None
    if has_lines:
        text_start = int(parsed[3])
        text_end = int(parsed[4]) if parsed[4] else text_start
        if (text_start, text_end) != (start, end):
            return "texto 'linhas %d-%d' diferente da âncora L%d-L%d: %s" % (text_start, text_end, start, end, target)
    name = parsed[1]
    base = os.path.basename(path)
    if name in (base, os.path.splitext(base)[0]):
        return None
    pair = CLASS_METHOD.match(name)
    if is_java and pair:
        stem = base[:-len(".java")]
        if pair[1] != stem:
            return "classe %s diferente do arquivo %s: %s" % (pair[1], base, target)
        with open(path, encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
        if not method_starts_in_window(lines, start, end, pair[2], stem):
            return "declaração de %s não começa nas %d primeiras linhas da faixa L%d-L%d" % (
                pair[2], METHOD_WINDOW, start, end)
        return None
    if has_lines:
        return "texto '%s' não corresponde ao arquivo %s" % (name, base)
    return None


def markdown_links(text):
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        m = FENCE.match(line)
        if fence:
            if m and m[1][0] == fence[0] and len(m[1]) >= len(fence) and not m[2].strip():
                fence = None
            continue
        if m:
            fence = m[1]
            continue
        for link in MD_LINK.finditer(line):
            yield number, link[2], link[3]


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.markdown_in_text = []
        self._skip = 0
        self._link = None

    def _close_link(self):
        if self._link:
            line, href, parts = self._link
            self.links.append((line, "".join(parts), href))
            self._link = None

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._skip += 1
        elif tag == "a":
            self._close_link()
            href = dict(attrs).get("href")
            if href:
                self._link = (self.getpos()[0], href, [])

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self._skip:
            self._skip -= 1
        elif tag == "a":
            self._close_link()

    def handle_data(self, data):
        if self._skip:
            return
        if "](" in data:
            self.markdown_in_text.append(self.getpos()[0] + data[:data.index("](")].count("\n"))
        if self._link:
            self._link[2].append(data)

    def close(self):
        super().close()
        self._close_link()


def html_links(text):
    parser = HtmlLinks()
    parser.feed(text)
    parser.close()
    return parser.links, parser.markdown_in_text


def check_text(text, kind, document, root=MODULE_ROOT):
    """Retorna [(linha, motivo)] ordenado por linha."""
    errors = []
    if kind == "html":
        links, markdown_lines = html_links(text)
        errors.extend((n, "sequência '](' no texto visível: link Markdown não renderiza no HTML") for n in markdown_lines)
    else:
        links = list(markdown_links(text))
    for line, link_text, href in links:
        reason = check_link(document, root, link_text, href)
        if reason:
            errors.append((line, reason))
    return sorted(errors)


def check_file(path, root=MODULE_ROOT):
    if not os.path.isfile(path):
        return [(0, "arquivo inexistente")]
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    kind = "html" if path.lower().endswith((".html", ".htm")) else "md"
    return check_text(text, kind, os.path.abspath(path), root)


def build_java_fixture():
    """Fonte de teste com métodos em linhas conhecidas."""
    lines = ["package demo;", "", "public class Foo {", ""]
    marks = {}

    def method(name, javadoc=False):
        first = len(lines) + 1
        if javadoc:
            lines.extend(["    /**", "     * Documentação.", "     */", "    @Override"])
        lines.append("    public void %s(int x) {" % name)
        lines.extend(["        work();"] * 8)
        lines.append("    }")
        marks[name] = (first, len(lines))
        lines.append("")

    lines.extend(["    // preenchimento"] * 30)
    method("alpha")
    lines.extend(["    // preenchimento"] * 30)
    method("beta", javadoc=True)
    lines.extend(["    // preenchimento"] * 30)
    method("gamma")
    lines.append("}")
    return "\n".join(lines) + "\n", marks, len(lines)


def self_test():
    failures = []
    java, marks, total = build_java_fixture()
    with tempfile.TemporaryDirectory() as root:
        for directory in ("src", "docs/internal", "docs/requisitos"):
            os.makedirs(os.path.join(root, directory))
        with open(os.path.join(root, "src/Foo.java"), "w") as f:
            f.write(java)
        with open(os.path.join(root, "src/data.xml"), "w") as f:
            f.write("<a>\n<b/>\n</a>\n")
        with open(os.path.join(root, "docs/internal/notas.md"), "w") as f:
            f.write("# notas\n")
        document = os.path.join(root, "docs/requisitos/doc.md")
        internal_document = os.path.join(root, "docs/internal/outra.md")

        def link(name, first, last, file="Foo.java", text=None):
            return "[%s](../../src/%s#L%d-L%d)" % (text or "%s, linhas %d-%d" % (name, first, last), file, first, last)

        def md(name, text, ok, needle=None, doc=document):
            errors = check_text(text, "md", doc, root)
            if bool(errors) == ok or (needle and not any(needle in e[1] for e in errors)):
                failures.append("md %s: erros=%s" % (name, errors))

        def html(name, text, ok, needle=None):
            errors = check_text(text, "html", document.replace(".md", ".html"), root)
            if bool(errors) == ok or (needle and not any(needle in e[1] for e in errors)):
                failures.append("html %s: erros=%s" % (name, errors))

        a1, a2 = marks["alpha"]
        b1, b2 = marks["beta"]
        md("metodo", link("Foo.alpha", a1, a2), True)
        md("metodo-javadoc", link("Foo.beta", b1, b2), True)
        md("metodo-com-folga", link("Foo.alpha", a1 - 2, a2), True)
        md("arquivo-java", link("Foo.java", a1, a2), True)
        md("nome-da-classe", link("Foo", a1, a2), True)
        md("arquivo-xml-inteiro", "[data.xml, linhas 1-3](../../src/data.xml#L1-L3)", True)
        md("linha-unica", "[Foo.alpha, linha %d](../../src/Foo.java#L%d)" % (a1, a1), True)
        md("sem-ancora", "[Foo](../../src/Foo.java) e [dados](../../src/data.xml)", True)
        md("ignorados", "[a](http://x.org/a.java#L9999) [b](https://x.org) [c](mailto:a@b.c) [d](#secao)", True)
        md("bloco-de-codigo", "```md\n[x](../../src/Nada.java#L1-L2)\n```", True)
        md("doc-interno-para-irmao", "[notas](notas.md)", True, doc=internal_document)
        md("faixa-fora", "[Foo.alpha, linhas 1-%d](../../src/Foo.java#L1-L%d)" % (total + 50, total + 50), False, "fora do arquivo")
        md("faixa-invertida", "[Foo.alpha, linhas 9-3](../../src/Foo.java#L9-L3)", False, "fora do arquivo")
        md("metodo-fora-da-faixa", link("Foo.beta", a1, a2), False, "não começa nas 6 primeiras linhas")
        md("metodo-depois-da-janela", link("Foo.alpha", a1 - 7, a2), False, "não começa nas 6 primeiras linhas")
        md("texto-diferente-da-ancora", "[Foo.alpha, linhas %d-%d](../../src/Foo.java#L%d-L%d)" % (a1, a2 + 1, a1, a2), False, "diferente da âncora")
        md("classe-diferente", link("Bar.alpha", a1, a2), False, "diferente do arquivo")
        md("arquivo-diferente", link("Outro.java", a1, a2), False, "diferente do arquivo")
        md("texto-diferente-do-arquivo-xml", "[outro.xml, linhas 1-3](../../src/data.xml#L1-L3)", False, "não corresponde ao arquivo")
        md("faixa-grande-demais", link("Foo.java", 1, int(total * 0.7)), False, "máximo 60%")
        md("link-docs-internal", "[notas](../internal/notas.md)", False, "docs/internal")
        md("link-docs-internal-diretorio-acima", "[notas](../../docs/internal/notas.md)", False, "docs/internal")
        md("link-inexistente", "[x](../../src/Nada.java#L1-L2)", False, "inexistente")
        md("ancora-invalida", "[Foo.alpha](../../src/Foo.java#L1-3)", False, "âncora inválida")
        md("texto-sem-ancora", "[Foo.alpha, linhas 1-3](../../src/Foo.java)", False, "não tem âncora")
        md("linha-reportada", "texto\n\n[x](nada.md)", False, "inexistente")
        if check_text("texto\n\n[x](nada.md)", "md", document, root)[0][0] != 3:
            failures.append("md linha-reportada: número de linha incorreto")

        html("metodo", '<p><a href="../../src/Foo.java#L%d-L%d">Foo.alpha, linhas %d-%d</a></p>' % (a1, a2, a1, a2), True)
        html("texto-em-varias-linhas", '<a href="../../src/Foo.java#L%d-L%d">\n  <code>Foo.alpha</code>,\n  linhas %d-%d\n</a>' % (a1, a2, a1, a2), True)
        html("ignorados", '<a href="https://x.org">x</a> <a href="#topo">topo</a> <a href="mailto:a@b.c">m</a> <a name="x">y</a>', True)
        html("script-ignorado", "<script>var x = a[0](1);</script>", True)
        html("metodo-fora-da-faixa", '<a href="../../src/Foo.java#L%d-L%d">Foo.beta, linhas %d-%d</a>' % (a1, a2, a1, a2), False, "não começa nas 6 primeiras linhas")
        html("faixa-fora", '<a href="../../src/Foo.java#L1-L%d">Foo.java, linhas 1-%d</a>' % (total + 9, total + 9), False, "fora do arquivo")
        html("texto-diferente-da-ancora", '<a href="../../src/Foo.java#L%d-L%d">Foo.alpha, linhas 1-2</a>' % (a1, a2), False, "diferente da âncora")
        html("link-docs-internal", '<a href="../internal/notas.md">notas</a>', False, "docs/internal")
        html("link-markdown-no-html", "<p>\nVeja [Foo.alpha, linhas 1-2](../../src/Foo.java#L1-L2).</p>", False, "link Markdown")
        errors = check_text("<p>\nVeja [Foo](x.md).</p>", "html", document, root)
        if not errors or errors[0][0] != 2:
            failures.append("html linha-markdown: erros=%s" % errors)

        with open(document, "w") as f:
            f.write("[ok](../../src/Foo.java)\n[ruim](nada.md)\n")
        if check_file(document, root) != [(2, "link inexistente: nada.md")]:
            failures.append("check_file: %s" % check_file(document, root))
    if failures:
        for failure in failures:
            print("FALHA", failure, file=sys.stderr)
        return 1
    print("self-test OK")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.files:
        ap.error("informe ao menos um arquivo")
    rc = 0
    for path in a.files:
        for line, reason in check_file(path):
            print("%s:%d: %s" % (path, line, reason), file=sys.stderr)
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
