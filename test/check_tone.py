#!/usr/bin/env python3
"""Checker de tom formal para documentos .md e .html em português do Brasil.

Regra de tom: a documentação do módulo descreve o comportamento do código com
neutralidade técnica. Não atribui culpa a terceiros, não qualifica o trabalho de alguém e
não usa termos ásperos ou de obviedade (TERMS abaixo). Blocos de código, trechos em crase e tags
HTML não contam. --allow TERMO libera uma citação literal de nome de classe ou de string
de código que contenha um desses termos.

Uso: check_tone.py [--allow TERMO]... ARQUIVO...
     check_tone.py --self-test
Saída: 'arquivo:linha: termo inadequado "X"'.
"""
import argparse
import re
import sys
import tempfile
import unicodedata
import os
from html.parser import HTMLParser

# Padrões sem acento: o texto é comparado sem acentos e sem diferença de caixa.
TERMS = [
    r"errad[oa]s?",
    r"ruim",
    r"ruins",
    r"falha\s+d[oa]s?\s+(?:cliente|usuario|equipe|fornecedor|desenvolvedor)(?:e?s|a|as)?",
    r"culpa(?:d[oa]s?)?",
    r"absurd[oa]s?",
    r"obvi[oa]s?",
    r"obviamente",
    r"simplesmente",
    r"claramente",
    r"dever(?:ia|iam)\s+ter",
    r"inaceitaveis|inaceitavel",
    r"amador(?:es|a|as)?",
    r"gambiarras?",
    r"pessim[oa]s?",
    r"incompeten(?:cia|te|tes)",
    r"negligen(?:cia|te|tes)",
    r"bagunca",
]
TERM_PATTERN = re.compile(r"(?<![\w])(?:%s)(?![\w])" % "|".join(TERMS))
FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})(.*)$")
SKIPPED_TAGS = ("script", "style", "pre", "code")


def fold(text):
    """Minúsculas sem acento, preservando o comprimento para mapear posições."""
    return "".join(unicodedata.normalize("NFD", c)[0] for c in text.lower())


def allowed_spans(folded, allow):
    spans = []
    for term in allow:
        needle = fold(term)
        start = folded.find(needle)
        while needle and start >= 0:
            spans.append((start, start + len(needle)))
            start = folded.find(needle, start + 1)
    return spans


def find_terms(line, allow):
    folded = fold(line)
    spans = allowed_spans(folded, allow)
    return [line[m.start():m.end()] for m in TERM_PATTERN.finditer(folded)
            if not any(a <= m.start() and m.end() <= b for a, b in spans)]


def markdown_prose(text):
    """Linhas (número, texto) sem blocos cercados, crases e tags HTML."""
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        m = FENCE.match(line)
        if fence:
            if m and m[1][0] == fence[0] and len(m[1]) >= len(fence) and not m[2].strip():
                fence = None
        elif m:
            fence = m[1]
        else:
            line = re.sub(r"`+[^`\n]*`+", lambda c: " " * len(c[0]), line)
            yield number, re.sub(r"<[^>\n]*>", lambda t: " " * len(t[0]), line)


class HtmlProse(HTMLParser):
    def __init__(self):
        super().__init__()
        self.chunks = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in SKIPPED_TAGS:
            self._skip += 1

    def handle_endtag(self, tag):
        if tag in SKIPPED_TAGS and self._skip:
            self._skip -= 1

    def handle_data(self, data):
        if not self._skip:
            self.chunks.append((self.getpos()[0], data))


def html_prose(text):
    parser = HtmlProse()
    parser.feed(text)
    parser.close()
    for first, data in parser.chunks:
        for offset, line in enumerate(data.split("\n")):
            yield first + offset, line


def check_text(text, kind, allow=()):
    """Retorna [(linha, termo)] na ordem do documento."""
    prose = html_prose(text) if kind == "html" else markdown_prose(text)
    return [(number, term) for number, line in prose for term in find_terms(line, allow)]


def check_file(path, allow=()):
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    return check_text(text, "html" if path.lower().endswith((".html", ".htm")) else "md", allow)


def self_test():
    failures = []

    def expect(name, text, kind, want, allow=()):
        got = [t.lower() for _, t in check_text(text, kind, allow)]
        if got != want:
            failures.append("%s: obtido=%s esperado=%s" % (name, got, want))

    expect("limpo", "O método grava o estado e devolve o resultado.\n", "md", [])
    for term in ("errado", "ruim", "falha do cliente", "falha da equipe", "falha dos fornecedores", "culpa", "absurdo",
                 "óbvio", "simplesmente", "claramente", "deveria ter", "inaceitável", "amador", "gambiarra"):
        expect("termo-" + term, "O trecho é %s para o leitor.\n" % term, "md", [term.lower()])
    expect("acento-e-caixa", "É ÓBVIO que sim. Isto é Inaceitável.\n", "md", ["óbvio", "inaceitável"])
    expect("flexao", "Os valores errados e a culpada.\n", "md", ["errados", "culpada"])
    expect("limite-de-palavra", "Culpabilidade e simplesmenteX não são termos.\n", "md", [])
    expect("falha-sem-terceiro", "A falha de gravação é registrada.\n", "md", [])
    expect("bloco-cercado", "texto\n```java\nint errado = 0; // gambiarra\n```\ntexto\n", "md", [])
    expect("bloco-til", "~~~\nruim\n~~~\n", "md", [])
    expect("codigo-em-linha", "O campo `errado` e a classe `Gambiarra` ficam.\n", "md", [])
    expect("tag-html-no-md", '<span class="errado">texto</span>\n', "md", [])
    expect("termo-fora-do-bloco", "```\nruim\n```\nIsso é ruim.\n", "md", ["ruim"])
    expect("linha-do-termo", "um\ndois\nsimplesmente\n", "md", ["simplesmente"])
    if check_text("um\n\nÓbvio\n", "md") != [(3, "Óbvio")]:
        failures.append("linha-do-termo: %s" % check_text("um\n\nÓbvio\n", "md"))
    expect("allow-literal", "A constante \"errado\" é citada.\n", "md", [], allow=["errado"])
    expect("allow-nao-libera-outros", "A constante \"errado\" e o texto ruim.\n", "md", ["ruim"], allow=["errado"])
    expect("allow-frase", "Use Classe Errada aqui.\n", "md", [], allow=["Classe Errada"])
    expect("allow-caixa", "A constante ERRADO.\n", "md", [], allow=["errado"])
    expect("html-termo", "<p>Isso é <b>claramente</b> ruim.</p>", "html", ["claramente", "ruim"])
    expect("html-atributo-e-tag", '<p class="errado" title="ruim"><gambiarra>texto</gambiarra></p>', "html", [])
    expect("html-codigo", "<pre>errado</pre><code>ruim</code><script>var culpa;</script><style>.ruim{}</style>", "html", [])
    expect("html-comentario", "<!-- gambiarra --><p>texto</p>", "html", [])
    expect("html-allow", "<p>A string errado aparece.</p>", "html", [], allow=["errado"])
    if check_text("<p>a</p>\n<p>b\nruim</p>", "html") != [(3, "ruim")]:
        failures.append("html-linha: %s" % check_text("<p>a</p>\n<p>b\nruim</p>", "html"))
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "doc.md")
        with open(path, "w") as f:
            f.write("ok\nsimplesmente assim\n")
        if check_file(path) != [(2, "simplesmente")] or check_file(path, ["simplesmente"]):
            failures.append("check_file: %s" % check_file(path))
    if failures:
        for failure in failures:
            print("FALHA", failure, file=sys.stderr)
        return 1
    print("self-test OK")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--allow", action="append", default=[], metavar="TERMO",
                    help="libera citação literal de nome de classe ou string de código")
    ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.files:
        ap.error("informe ao menos um arquivo")
    rc = 0
    for path in a.files:
        for line, term in check_file(path, a.allow):
            print('%s:%d: termo inadequado "%s"' % (path, line, term), file=sys.stderr)
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
