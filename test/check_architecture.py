#!/usr/bin/env python3
"""Checker determinístico de documentos de arquitetura e README.

O perfil review aceita um diretório e verifica todos os .md dele. O perfil htmldesign exige,
em 'Fontes pesquisadas', ao menos 6 fontes: itens de lista (ou subseções H3) com URL https,
'Acesso:' com data (AAAA-MM-DD ou DD/MM/AAAA) e 'Trecho:' com citação curta entre aspas.

Uso: check_architecture.py [--profile architecture|readme|guide|evidence|requirements|review|htmldesign|dtodesign] ARQUIVO_OU_DIRETORIO...
     check_architecture.py --self-test
"""
import argparse
import os
import re
import sys
import tempfile
from datetime import datetime
from urllib.parse import unquote, urlsplit

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Lumis_PortalJava checkout holding portal_install/lumisdata/shared/source/<module>; LUMIS_WORKSPACE overrides it.
DEFAULT_WORKSPACE = os.path.normpath(os.path.join(MODULE_ROOT, *[os.pardir] * 5))

PROFILES = {
    "architecture": [
        "Modelo de dados", "Algoritmo de migração", "Mapeamento e reuso",
        "Modo atualização", "Falhas e bloqueios", "Riscos",
        "Propostas de contrato", "Evidências",
    ],
    "readme": [
        "Instalação", "Uso da administração", "Modelo de dados", "Testes",
        "Decisões de arquitetura", "Limitações e evolução",
    ],
    "guide": [
        "Organização de pacotes", "Indentação e chaves", "Largura e quebras de linha",
        "Condições e variáveis explicativas", "Ordem dos membros", "Nomes",
        "Comentários e Javadoc", "SQL e XML", "Ferramentas",
    ],
    "evidence": [
        "ExecutorManager e threads", "Clock e maxRunTime", "Transações",
        "addData, updateData e versões", "HtmlDataType e widgets",
        "Arquivos, mídias e documentos", "Categorias, tags e workflow",
        "Exclusão de conteúdo", "Upgrade de tabelas do módulo",
    ],
    "requirements": [
        "Visão geral da ferramenta", "Pontos-chave da execução",
        "addData, updateData, workflow e versões", "Campos HTML e widgets embutidos",
        "Mídia, documento, categoria e arquivos", "Clock, transação, volume e maxRunTime",
        "Reversão do que foi migrado", "Incremental e alterações na origem",
        "Riscos e recomendações", "Decisões e informações necessárias na adoção",
    ],
    "review": [
        "Escopo revisado", "Achados corrigidos", "Achados adiados", "Verificações executadas",
    ],
    "htmldesign": [
        "Fontes pesquisadas", "Tokens", "Componentes", "Ordem das seções", "Abertura",
    ],
    "dtodesign": [
        "Inventário", "Design dos DTOs", "Destino de cada método", "Riscos", "Ordem de implementação",
    ],
}
MODERN_PROFILES = ("guide", "evidence", "requirements", "review", "htmldesign", "dtodesign")
SOURCES_SECTION = "Fontes pesquisadas"
MIN_SOURCES = 6
MAX_QUOTE_WORDS = 40

FORBIDDEN = re.compile(r"\b(TODO|TBD)\b")
HEADING = re.compile(r"^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$")
REF = re.compile(r"(?<![\w/.:-])((?:[\w.-]+/)*[\w.-]+\.[A-Za-z0-9]+):(\d+)(?:-(\d+))?(?![\w])")
LOCAL_REF = re.compile(r"(?<![\w/.:-])((?:/|[A-Za-z]:/)?(?:[\w.-]+/)*[\w.-]+\.[A-Za-z0-9]+):(\d+)(?:-(\d+))?(?![\w])")
H2 = re.compile(r"^\s{0,3}##\s+(.*?)\s*#*\s*$")
LINK = re.compile(r"!?\[[^\]]*\]\(\s*(<[^>]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\s*\)")
LINK_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(<[^>]+>|\S+)")
LINE_ANCHOR = re.compile(r"L(\d+)(?:-L(\d+))?")
LIST_ITEM = re.compile(r"^\s{0,3}(?:[-*+]|\d+[.)])\s")
H3 = re.compile(r"^\s{0,3}###\s")
URL = re.compile(r"\b[hH][tT][tT][pP][sS]?://[^\s<>)\]]+")
ACCESS = re.compile(r"\bAcesso:\s*(\d{4}-\d{2}-\d{2}|\d{2}/\d{2}/\d{4})")
QUOTE = re.compile(r"\bTrecho:\s*(?:[“\"«](.+?)[”\"»])", re.S)


def prose_lines(lines):
    """Retira blocos de código cercados ou indentados dos perfis novos."""
    fence = None
    for line in lines:
        m = re.match(r"^\s{0,3}(`{3,}|~{3,})(.*)$", line)
        if fence:
            if m and m[1][0] == fence[0] and len(m[1]) >= len(fence) and not m[2].strip():
                fence = None
            yield ""
        elif m:
            fence = m[1]
            yield ""
        elif line.startswith(("    ", "\t")):
            yield ""
        else:
            yield line


def h2_section(prose, title):
    """Linhas do H2 'title' até o próximo H2, ou None se não existir."""
    body = None
    for line in prose:
        m = H2.match(line)
        if m:
            if body is not None:
                break
            if m.group(1).strip().lower() == title.lower():
                body = []
        elif body is not None:
            body.append(line)
    return body


def source_blocks(body):
    """Cada fonte é uma subseção H3; sem H3, cada item de lista de primeiro nível."""
    starter = H3 if any(H3.match(l) for l in body) else LIST_ITEM
    blocks = []
    for line in body:
        if starter.match(line):
            blocks.append([line])
        elif blocks:
            blocks[-1].append(line)
    return ["\n".join(b).replace("**", "").replace("__", "") for b in blocks]


def check_sources(prose):
    body = h2_section(prose, SOURCES_SECTION)
    if body is None:
        return []
    errors = []
    blocks = source_blocks(body)
    if len(blocks) < MIN_SOURCES:
        errors.append("fontes pesquisadas: %d fontes (mínimo %d)" % (len(blocks), MIN_SOURCES))
    for n, block in enumerate(blocks, 1):
        label = "fonte %d" % n
        url = URL.search(block)
        if not url:
            errors.append("%s: sem URL" % label)
        elif not url.group(0).lower().startswith("https://"):
            errors.append("%s: URL não https: %s" % (label, url.group(0)))
        access = ACCESS.search(block)
        if not access:
            errors.append("%s: 'Acesso:' com data ausente" % label)
        else:
            raw = access.group(1)
            try:
                datetime.strptime(raw, "%Y-%m-%d" if "-" in raw else "%d/%m/%Y")
            except ValueError:
                errors.append("%s: data de acesso inválida: %s" % (label, raw))
        quote = QUOTE.search(block)
        if not quote:
            errors.append("%s: 'Trecho:' com citação entre aspas ausente" % label)
        elif not quote.group(1).strip():
            errors.append("%s: citação vazia" % label)
        elif len(quote.group(1).split()) > MAX_QUOTE_WORDS:
            errors.append("%s: citação longa (máximo %d palavras)" % (label, MAX_QUOTE_WORDS))
    return errors


def in_module(path):
    return os.path.commonpath((os.path.realpath(path), os.path.realpath(MODULE_ROOT))) == os.path.realpath(MODULE_ROOT)


def check_links(lines, profile, document):
    errors = []
    for i, line in enumerate(lines, 1):
        targets = [m[1] for m in LINK.finditer(line)]
        definition = LINK_DEF.match(line)
        if definition:
            targets.append(definition[1])
        for target in targets:
            target = target.strip("<>")
            url = urlsplit(target)
            if url.scheme or url.netloc:
                continue
            path = os.path.realpath(os.path.join(os.path.dirname(document), unquote(url.path))) if url.path else document
            if profile == "requirements" and not in_module(path):
                errors.append("linha %d: link fora do módulo: %s" % (i, target))
            if not os.path.isfile(path):
                errors.append("linha %d: link inexistente: %s" % (i, target))
            elif "#" in target:
                anchor = LINE_ANCHOR.fullmatch(url.fragment)
                if not anchor:
                    errors.append("linha %d: âncora inválida: %s" % (i, target))
                else:
                    start = int(anchor[1])
                    end = int(anchor[2]) if anchor[2] else start
                    if start < 1 or end < start or end > count_lines(path):
                        errors.append("linha %d: linha fora do arquivo em %s" % (i, target))
    return errors


def check_requirements_words(lines):
    total = 0
    section = None
    section_words = 0
    errors = []
    for line in lines + ["## "]:
        # Um H2 inicia outro orçamento, incluindo subseções H3-H6.
        if re.match(r"^\s{0,3}##\s", line):
            if section is not None and section_words > 700:
                errors.append("seção %s: %d palavras (máximo 700)" % (section, section_words))
            section = line.strip().lstrip("#").strip()
            section_words = 0
        if LINK_DEF.match(line):
            continue
        line = LINK.sub(lambda m: m[0].split("](", 1)[0].lstrip("!["), line)
        line = re.sub(r"(?:[A-Za-z][A-Za-z0-9+.-]*://|mailto:|www\.)[^\s<>]+", "", line)
        words = len(re.findall(r"\b\w+(?:[-’']\w+)*\b", line))
        total += words
        section_words += words
    if total > 4500:
        errors.append("documento: %d palavras (máximo 4500)" % total)
    return errors


def workspace():
    return os.environ.get("LUMIS_WORKSPACE", DEFAULT_WORKSPACE)


def count_lines(path):
    with open(path, "rb") as f:
        data = f.read()
    if not data:
        return 0
    n = data.count(b"\n")
    return n if data.endswith(b"\n") else n + 1


def resolve(rel, ws):
    for base in (ws, MODULE_ROOT):
        p = os.path.join(base, rel)
        if os.path.isfile(p):
            return p
    return None


def check_text(text, profile, ws, document=None):
    errors = []
    lines = text.splitlines()
    modern = profile in MODERN_PROFILES
    prose = list(prose_lines(lines)) if modern else lines
    heading_pattern = H2 if modern else HEADING
    headings = [m.group(1).strip().lower() for m in (heading_pattern.match(l) for l in prose) if m]
    for sec in PROFILES[profile]:
        if sec.lower() not in headings:
            errors.append("seção obrigatória ausente: %s" % sec)
    in_fence = False
    for i, line in enumerate(lines, 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        m = FORBIDDEN.search(line)
        if m:
            errors.append("linha %d: marcador proibido %s" % (i, m.group(1)))
        for r in (LOCAL_REF if modern else REF).finditer(line):
            rel, a, b = r.group(1), int(r.group(2)), r.group(3)
            end = int(b) if b else a
            p = resolve(rel, ws)
            if p is None:
                errors.append("linha %d: referência inexistente %s" % (i, r.group(0)))
            elif profile == "requirements" and not in_module(p):
                errors.append("linha %d: referência fora do módulo: %s" % (i, r.group(0)))
            elif a < 1 or end < a or end > count_lines(p):
                errors.append("linha %d: linha fora do arquivo em %s (arquivo tem %d linhas)"
                              % (i, r.group(0), count_lines(p)))
    if modern:
        errors.extend(check_links(prose, profile, os.path.abspath(document or os.path.join(MODULE_ROOT, "document.md"))))
    if profile == "requirements":
        errors.extend(check_requirements_words(prose))
    if profile == "htmldesign":
        errors.extend(check_sources(prose))
    return errors


def check_file(path, profile, ws):
    with open(path, encoding="utf-8", errors="replace") as f:
        return check_text(f.read(), profile, ws, path)


def check_path(path, profile, ws):
    """Arquivo, ou diretório com todos os .md dele (recursivo, em ordem)."""
    if not os.path.isdir(path):
        return check_file(path, profile, ws)
    errors = []
    found = False
    for base, dirs, names in os.walk(path):
        dirs.sort()
        for name in sorted(names):
            if name.endswith(".md"):
                found = True
                full = os.path.join(base, name)
                errors.extend("%s: %s" % (os.path.relpath(full, path), e) for e in check_file(full, profile, ws))
    if not found:
        errors.append("nenhum arquivo .md no diretório")
    return errors


def self_test():
    failures = []

    def expect(name, text, profile, ws, want_ok, needle=None, document=None):
        errs = check_text(text, profile, ws, document)
        ok = not errs
        if ok != want_ok or (needle and not any(needle in e for e in errs)):
            failures.append("%s: erros=%s" % (name, errs))

    def source(n, url="https://example.org/%d", access="2026-10-17", quote="“trecho curto”"):
        return "- %s\n  Acesso: %s\n  Trecho: %s" % (url % n if "%d" in url else url, access, quote)

    def sources(count=6, **kw):
        return "\n".join(source(n, **kw) for n in range(1, count + 1)) + "\n"

    def doc(profile, extra="", skip=None, body=None):
        def section(s):
            text = body if s == SOURCES_SECTION and body is not None else (sources() if s == SOURCES_SECTION and profile == "htmldesign" else "texto\n")
            return "## %s\n%s" % (s, text)
        return "\n".join(section(s) for s in PROFILES[profile] if s != skip) + extra

    with tempfile.TemporaryDirectory() as ws:
        with open(os.path.join(ws, "a.txt"), "w") as f:
            f.write("um\ndois\ntres\n")
        for prof in PROFILES:
            ref = "test/check_architecture.py:1" if prof == "requirements" else "a.txt:3"
            expect("valido-" + prof, doc(prof, "ver %s\n" % ref), prof, ws, True)
            expect("secao-ausente-" + prof, doc(prof, skip=PROFILES[prof][0]), prof, ws, False, "ausente")
            expect("todo-" + prof, doc(prof, "TODO fazer\n"), prof, ws, False, "TODO")
            expect("tbd-" + prof, doc(prof, "valor TBD\n"), prof, ws, False, "TBD")
            expect("ref-inexistente-" + prof, doc(prof, "ver nada.txt:1\n"), prof, ws, False, "inexistente")
            expect("ref-linha-alem-" + prof, doc(prof, "ver %s\n" % ref.replace(":1", ":999999").replace(":3", ":4")), prof, ws, False, "fora do arquivo")
        expect("ref-intervalo", doc("readme", "ver a.txt:1-3\n"), "readme", ws, True)
        expect("ref-intervalo-alem", doc("readme", "ver a.txt:2-9\n"), "readme", ws, False, "fora do arquivo")
        document = os.path.join(ws, "guide.md")
        for prof in ("guide", "evidence"):
            for target in ("a.txt", "a.txt#L1", "./a.txt#L1-L3", "a.txt#L3-L3"):
                expect("link-valido-" + target, doc(prof, "[fonte](%s)" % target), prof, ws, True, document=document)
            for target, needle in (("nada.txt#L1", "inexistente"), ("a.txt#L4", "fora do arquivo"),
                                   ("a.txt#L0", "fora do arquivo"), ("a.txt#L3-L1", "fora do arquivo"),
                                   ("a.txt#L1-L4", "fora do arquivo"), ("a.txt#L1-3", "âncora inválida")):
                expect("link-invalido-" + target, doc(prof, "[fonte](%s)" % target), prof, ws, False, needle, document)
            expect("exige-h2-" + prof, doc(prof).replace("## ", "### ", 1), prof, ws, False, "ausente")
            expect("h2-no-codigo-" + prof, "```md\n" + doc(prof) + "\n```", prof, ws, False, "ausente")
            expect("link-referencia-" + prof, doc(prof, "[fonte][src]\n[src]: a.txt#L4"), prof, ws, False, "fora do arquivo", document)
        expect("requirements-ref-plataforma", doc("requirements", "a.txt:1"), "requirements", ws, False, "fora do módulo")
        expect("requirements-ref-absoluta", doc("requirements", os.path.join(ws, "a.txt") + ":1"), "requirements", ws, False, "fora do módulo")
        expect("requirements-link-plataforma", doc("requirements", "[fonte](a.txt#L1)"), "requirements", ws, False, "fora do módulo", document)
        expect("requirements-link-modulo", doc("requirements", "[fonte](test/check_architecture.py#L1-L3)"), "requirements", ws, True)
        expect("requirements-limite-secao", doc("requirements", "palavra " * 701), "requirements", ws, False, "máximo 700")
        heading_words = len(PROFILES["requirements"][-1].split())
        expect("requirements-700", doc("requirements", "palavra " * (699 - heading_words)), "requirements", ws, True)
        expect("requirements-701", doc("requirements", "palavra " * (700 - heading_words)), "requirements", ws, False, "máximo 700")
        expect("requirements-limite-total", doc("requirements").replace("texto", "palavra " * 450), "requirements", ws, False, "máximo 4500")
        base = doc("requirements").replace("texto", "palavra " * 400)
        padding = 4500 - (len(base.split()) - len(PROFILES["requirements"]))
        expect("requirements-4500", "palavra " * padding + "\n" + base, "requirements", ws, True)
        expect("requirements-4501", "palavra " * (padding + 1) + "\n" + base, "requirements", ws, False, "máximo 4500")
        expect("requirements-codigo-urls", doc("requirements", "\n~~~java\n" + "palavra " * 5000 + "\n~~~\n" +
               "https://example.org/a " * 5000), "requirements", ws, True)
        expect("requirements-codigo-indentado", doc("requirements", "\n    " + "palavra " * 5000), "requirements", ws, True)
        expect("requirements-subsecao", doc("requirements", "\n### Detalhes\n" + "palavra " * 701), "requirements", ws, False, "máximo 700")
        # Perfil review: diretório com todos os .md.
        reports = os.path.join(ws, "review")
        os.makedirs(os.path.join(reports, "sub"))

        def write_report(name, text):
            with open(os.path.join(reports, name), "w", encoding="utf-8") as f:
                f.write(text)

        def expect_path(name, path, profile, want_ok, needle=None):
            errs = check_path(path, profile, ws)
            if (not errs) != want_ok or (needle and not any(needle in e for e in errs)):
                failures.append("%s: erros=%s" % (name, errs))

        write_report("a.md", doc("review", "ver a.txt:2\n"))
        write_report("sub/b.md", doc("review"))
        with open(os.path.join(reports, "notas.txt"), "w") as f:
            f.write("TODO ignorado: não é .md\n")
        expect_path("review-diretorio-valido", reports, "review", True)
        expect_path("review-arquivo-valido", os.path.join(reports, "a.md"), "review", True)
        write_report("sub/c.md", doc("review", skip="Achados adiados"))
        expect_path("review-diretorio-secao-ausente", reports, "review", False, "sub/c.md: seção obrigatória ausente: Achados adiados")
        write_report("sub/c.md", doc("review", "TODO revisar\n"))
        expect_path("review-diretorio-todo", reports, "review", False, "sub/c.md: linha")
        write_report("sub/c.md", doc("review", "ver nada.txt:1\n"))
        expect_path("review-diretorio-referencia", reports, "review", False, "inexistente")
        os.remove(os.path.join(reports, "sub", "c.md"))
        expect_path("review-diretorio-valido-de-novo", reports, "review", True)
        os.makedirs(os.path.join(ws, "vazio"))
        expect_path("review-diretorio-vazio", os.path.join(ws, "vazio"), "review", False, "nenhum arquivo .md")

        # Perfil htmldesign: fontes pesquisadas.
        expect("html-seis-fontes", doc("htmldesign"), "htmldesign", ws, True)
        expect("html-mais-fontes", doc("htmldesign", body=sources(9)), "htmldesign", ws, True)
        expect("html-cinco-fontes", doc("htmldesign", body=sources(5)), "htmldesign", ws, False, "mínimo 6")
        expect("html-sem-fontes", doc("htmldesign", body="texto\n"), "htmldesign", ws, False, "0 fontes")
        expect("html-url-http", doc("htmldesign", body=sources(5) + source(6, url="http://example.org/6") + "\n"),
               "htmldesign", ws, False, "URL não https")
        expect("html-sem-url", doc("htmldesign", body=sources(5) + source(6, url="sem link") + "\n"),
               "htmldesign", ws, False, "sem URL")
        expect("html-sem-acesso", doc("htmldesign", body=sources(5) + "- https://example.org/6\n  Trecho: “curto”\n"),
               "htmldesign", ws, False, "'Acesso:'")
        expect("html-data-invalida", doc("htmldesign", body=sources(5) + source(6, access="2026-13-40") + "\n"),
               "htmldesign", ws, False, "data de acesso inválida")
        expect("html-data-brasileira", doc("htmldesign", body=sources(5) + source(6, access="17/10/2026") + "\n"),
               "htmldesign", ws, True)
        expect("html-sem-trecho", doc("htmldesign", body=sources(5) + "- https://example.org/6\n  Acesso: 2026-10-17\n"),
               "htmldesign", ws, False, "'Trecho:'")
        expect("html-trecho-sem-aspas", doc("htmldesign", body=sources(5) + source(6, quote="sem aspas") + "\n"),
               "htmldesign", ws, False, "'Trecho:'")
        expect("html-trecho-longo", doc("htmldesign", body=sources(5) + source(6, quote="“%s”" % ("palavra " * 41)) + "\n"),
               "htmldesign", ws, False, "citação longa")
        expect("html-trecho-aspas-retas", doc("htmldesign", body=sources(5) + source(6, quote='"curto"') + "\n"),
               "htmldesign", ws, True)
        expect("html-negrito", doc("htmldesign", body="\n".join(
            "- **Fonte %d**: https://example.org/%d\n  **Acesso:** 2026-10-17\n  **Trecho:** “curto”" % (n, n)
            for n in range(1, 7)) + "\n"), "htmldesign", ws, True)
        expect("html-subsecoes-h3", doc("htmldesign", body="\n".join(
            "### Fonte %d\n- https://example.org/%d\n- Acesso: 2026-10-17\n- Trecho: “curto”\n" % (n, n)
            for n in range(1, 7))), "htmldesign", ws, True)
        expect("html-subsecoes-h3-incompleta", doc("htmldesign", body="\n".join(
            "### Fonte %d\n- https://example.org/%d\n- Acesso: 2026-10-17\n" % (n, n) for n in range(1, 7))),
            "htmldesign", ws, False, "'Trecho:'")
        expect("html-fontes-fora-da-secao", doc("htmldesign", body="texto\n", extra="\n" + sources(8)),
               "htmldesign", ws, False, "0 fontes")
        expect("html-fontes-em-bloco-de-codigo", doc("htmldesign", body="```\n" + sources(6) + "```\n"),
               "htmldesign", ws, False, "0 fontes")
    if failures:
        for f in failures:
            print("FALHA", f, file=sys.stderr)
        return 1
    print("self-test OK")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--profile", choices=sorted(PROFILES), default="architecture")
    ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.files:
        ap.error("informe ao menos um arquivo")
    rc = 0
    for f in a.files:
        for e in check_path(f, a.profile, workspace()):
            print("%s: %s" % (f, e), file=sys.stderr)
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
