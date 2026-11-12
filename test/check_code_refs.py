#!/usr/bin/env python3
"""Checker determinístico das referências de código de documentos .html (PAT002).

Uso: check_code_refs.py [--allow-links] [--root DIR] [--workspace DIR] ARQUIVO...
     check_code_refs.py --self-test

Saída: 'arquivo:linha: motivo'. Usa só a biblioteca padrão.

Referência: <code class="ref"> traz o nome completo pacote.Classe.método (classe aninhada:
pacote.Externa.Interna.método). O arquivo Java da classe existe em src ou test/java do módulo, ou no
fonte da plataforma (*/src/java e */src/test/java sob a raiz Lumis_PortalJava, que é o módulo
mais ../../../../..; LUMIS_WORKSPACE ou --workspace só a sobrescreve), e o método está declarado
nessa classe. Sem pacote, sem classe ou sem método a referência falha.

Externo: <code class="ref" data-origin="externo"> exige o nome completo (pacote de código de fora
do módulo e da plataforma, que não está nesta árvore) e dispensa a resolução no fonte. Arquivo:
class="ref-file" exige que o caminho exista (relativo ao módulo, a src do módulo ou à raiz da
plataforma), exceto com data-origin="externo".

Linha: a referência por método é a preferida. data-line="N" só vale em método com mais de 60 linhas,
com N dentro do método, e o texto visível 'linha N' deve aparecer ao lado da referência.

Links: <a href> para código (src, test, arquivos de código ou módulos da plataforma) falha; âncoras
internas (#x) e links http(s) seguem permitidos. Todo <code> de texto com a forma Classe.método ou
Classe#método sem class="ref" falha.

--allow-links é o modo de conversão parcial: desliga a checagem de <a href> para código e a de
<code> simples sem class="ref"; os code.ref presentes continuam resolvidos.
"""
import argparse
import os
import re
import sys
import tempfile
from html.parser import HTMLParser

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXTERNAL_ORIGIN = "externo"
LONG_METHOD = 60
ENCODING = "iso-8859-1"

LITERALS = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
TOKENS = re.compile(r"[A-Za-z_$][\w$]*|[^\s\w$]|\d+", re.S)
IDENT = re.compile(r"[A-Za-z_$][\w$]*$")
NOT_TYPE = {"return", "new", "throw", "else", "case", "assert", "yield", "do", "instanceof", "package", "import"}
NOT_NAME = {"if", "for", "while", "switch", "catch", "synchronized", "return", "super", "this", "try", "new"}
CODE_SUFFIXES = (".java", ".jsp", ".xml", ".js", ".py", ".sh", ".gradle", ".properties", ".sql", ".cmd")
SIMPLE_REF = re.compile(
    r"^(?:[a-z_][\w]*\.)*[A-Z][\w$]*(?:\.[A-Z][\w$]*)*[.#][a-z_$][\w$]*(?:\(.*\))?$")


class Page(HTMLParser):
    """Coleta <code> e <a href> com a linha de origem, o texto vizinho e o contexto de pre."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.codes = []
        self.links = []
        self._pre = 0
        self._open = None
        self._last = None
        self._after = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        line = self.getpos()[0]
        if tag == "pre":
            self._pre += 1
        elif tag == "a" and attrs.get("href") is not None:
            self.links.append((line, attrs["href"]))
        elif tag == "code" and not self._pre:
            self._open = {"line": line, "attrs": attrs, "text": "", "before": self._last or "", "after": ""}
            self._last = None
        elif self._open is None:
            self._last = None

    def handle_endtag(self, tag):
        if tag == "pre" and self._pre:
            self._pre -= 1
        elif tag == "code" and self._open is not None:
            self.codes.append(self._open)
            self._open = None
            self._last = None
            self._after = self.codes[-1]
        elif self._open is None:
            self._last = None

    def handle_data(self, data):
        if self._pre:
            return
        if self._open is not None:
            self._open["text"] += data
            return
        if self._after is not None:
            self._after["after"] = data
            self._after = None
        self._last = data

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)


def read_source(path):
    with open(path, encoding=ENCODING, errors="replace") as f:
        return f.read()


def mask(text):
    """Esconde comentários e literais mantendo deslocamentos e quebras de linha."""
    def replace(match):
        return "".join("\n" if c == "\n" else " " for c in match.group())
    return LITERALS.sub(replace, text)


def declared_members(text, class_path):
    """Retorna {nome: [(linha_inicial, linha_final)]} dos membros diretos da classe, ou None se a classe não existe."""
    code = mask(text)
    starts = [0] + [m.end() for m in re.finditer("\n", code)]
    tokens = [(m.group(), m.start()) for m in TOKENS.finditer(code)]
    values = [t for t, _ in tokens]

    def line_of(index):
        low, high = 0, len(starts)
        while low < high - 1:
            mid = (low + high) // 2
            if starts[mid] <= tokens[index][1]:
                low = mid
            else:
                high = mid
        return low + 1

    depth = []
    level = 0
    for value in values:
        if value == "}":
            level -= 1
        depth.append(level)
        if value == "{":
            level += 1
    close = {}
    stack = []
    for i, value in enumerate(values):
        if value in ("(", "{"):
            stack.append(i)
        elif value in (")", "}") and stack:
            close[stack.pop()] = i

    # Localiza o corpo da classe (ou da classe aninhada) pelo caminho Externa.Interna.
    body_open, body_depth = None, 0
    for name in class_path:
        found = None
        for i in range(len(values) - 2):
            if values[i] in ("class", "interface", "enum", "record") and values[i + 1] == name and depth[i] == body_depth \
                    and (body_open is None or i > body_open):
                found = i
                break
        if found is None:
            return None
        j = found + 2
        while j < len(values) and values[j] != "{":
            j += 1
        if j >= len(values) or j not in close:
            return None
        body_open, body_depth = j, depth[j] + 1
    members = {}
    end = close[body_open]
    class_name = class_path[-1]
    for i in range(body_open + 1, end):
        name = values[i]
        if depth[i] != body_depth or not IDENT.match(name) or name in NOT_NAME or i + 1 >= len(values) or values[i + 1] != "(":
            continue
        previous = values[i - 1]
        declaration = (IDENT.match(previous) and previous not in NOT_TYPE) or previous in (">", "]")
        constructor = name == class_name and previous in (";", "{", "}", ")")
        if not declaration and not constructor:
            continue
        j = close.get(i + 1)
        if j is None:
            continue
        j += 1
        while j < end and values[j] not in ("{", ";"):
            j += 1
        if constructor and not declaration and (j >= end or values[j] != "{"):
            continue
        stop = line_of(i)
        if j < end and values[j] == "{" and j in close:
            stop = line_of(close[j])
        members.setdefault(name, []).append((line_of(i), stop))
    return members


class Workspace:
    def __init__(self, module_root, platform_root):
        self.module_root = module_root
        self.platform_root = platform_root
        self._files = {}
        self._members = {}

    def java_roots(self):
        roots = [os.path.join(self.module_root, "src"), os.path.join(self.module_root, "test", "java")]
        if os.path.isdir(self.platform_root):
            for name in sorted(os.listdir(self.platform_root)):
                for sub in (("src", "java"), ("src", "test", "java")):
                    roots.append(os.path.join(self.platform_root, name, *sub))
        return roots

    def find_class(self, package, top_class):
        key = (package, top_class)
        if key not in self._files:
            relative = os.path.join(*package.split("."), top_class + ".java")
            self._files[key] = next(
                (os.path.join(root, relative) for root in self.java_roots() if os.path.isfile(os.path.join(root, relative))), None)
        return self._files[key]

    def members(self, path, class_path):
        key = (path, tuple(class_path))
        if key not in self._members:
            self._members[key] = declared_members(read_source(path), class_path)
        return self._members[key]

    def platform_modules(self):
        if not os.path.isdir(self.platform_root):
            return set()
        return {n for n in os.listdir(self.platform_root) if os.path.isdir(os.path.join(self.platform_root, n, "src"))}

    def file_exists(self, text):
        relative = text.strip().strip("/")
        if not relative:
            return False
        candidates = [os.path.join(self.module_root, relative), os.path.join(self.module_root, "src", relative),
                      os.path.join(self.platform_root, relative)]
        if os.path.isdir(self.platform_root):
            for name in os.listdir(self.platform_root):
                candidates.append(os.path.join(self.platform_root, name, "src", "java", relative))
                candidates.append(os.path.join(self.platform_root, name, "src", "www", relative))
        return any(os.path.isfile(c) for c in candidates)


def parse_reference(text):
    """Retorna (pacote, [classes], método) ou (None, None, motivo)."""
    name = re.sub(r"\(\s*\)$", "", text.strip())
    parts = name.split(".")
    if not name or not all(re.fullmatch(r"[A-Za-z_$][\w$]*", p) for p in parts):
        return None, None, "referência '%s' não tem a forma pacote.Classe.método" % text.strip()
    first = next((i for i, p in enumerate(parts) if p[0].isupper()), None)
    if first is None:
        return None, None, "referência '%s' sem classe (esperado pacote.Classe.método)" % text.strip()
    if first == 0:
        return None, None, "referência '%s' sem pacote (esperado pacote.Classe.método)" % text.strip()
    if len(parts) - first < 2:
        return None, None, "referência '%s' sem método (esperado pacote.Classe.método)" % text.strip()
    return ".".join(parts[:first]), parts[first:-1], parts[-1]


def line_text_near(code, number):
    """True se 'linha N' aparece ao lado da referência (logo depois ou logo antes)."""
    pattern = r"linha\s+%d\b" % number
    return bool(re.match(r"[\s(,;:\u2014\u2013-]*" + pattern, code["after"])
                or re.search(pattern + r"[\s),;:\u2014\u2013-]*$", code["before"]))


def check_reference(code, workspace, report):
    attrs = code["attrs"]
    line = code["line"]
    package, classes, method = parse_reference(code["text"])
    if package is None:
        report(line, method)
        return
    external = attrs.get("data-origin") == EXTERNAL_ORIGIN
    declared = None
    if not external:
        path = workspace.find_class(package, classes[0])
        if path is None:
            report(line, "classe '%s.%s' não encontrada no fonte do módulo nem da plataforma" % (package, classes[0]))
            return
        members = workspace.members(path, classes)
        if members is None:
            report(line, "classe '%s' não declarada em %s" % (".".join(classes), os.path.basename(path)))
            return
        declared = members.get(method)
        if not declared:
            report(line, "método '%s' não declarado na classe %s.%s" % (method, package, ".".join(classes)))
            return
    data_line = attrs.get("data-line")
    if data_line is None:
        return
    if not re.fullmatch(r"\d+", data_line.strip()):
        report(line, "data-line '%s' não é um número" % data_line)
        return
    number = int(data_line)
    if declared is not None:
        containing = [span for span in declared if span[0] <= number <= span[1]]
        if not containing:
            report(line, "linha %d fora do método %s (%s)" % (
                number, method, ", ".join("%d-%d" % span for span in declared)))
            return
        if not any(span[1] - span[0] + 1 > LONG_METHOD for span in containing):
            report(line, "método %s tem %d linhas ou menos; use só a referência por método, sem linha" % (method, LONG_METHOD))
            return
    if not line_text_near(code, number):
        report(line, "falta o texto visível 'linha %d' ao lado da referência" % number)


def check_page(page, workspace, allow_links, report):
    for code in page.codes:
        classes = (code["attrs"].get("class") or "").split()
        text = code["text"].strip()
        if "ref-file" in classes:
            if code["attrs"].get("data-origin") != EXTERNAL_ORIGIN and not workspace.file_exists(text):
                report(code["line"], "arquivo '%s' não existe" % text)
        elif "ref" in classes:
            check_reference(code, workspace, report)
        elif not allow_links and SIMPLE_REF.match(text):
            report(code["line"], "'%s' tem a forma Classe.método sem class=\"ref\"" % text)
    if allow_links:
        return
    modules = workspace.platform_modules()
    for line, href in page.links:
        target = href.strip()
        if target.startswith("#") or re.match(r"^(?:https?:)?//", target, re.I) or re.match(r"^(?:https?|mailto|tel):", target, re.I):
            continue
        path = re.split(r"[?#]", target)[0].replace("\\", "/")
        segments = [s for s in path.split("/") if s not in ("", ".", "..")]
        if target.lower().startswith("file:") or "src" in segments or "test" in segments or path.lower().endswith(CODE_SUFFIXES) \
                or (segments and segments[0] in modules):
            report(line, "hiperlink para código '%s'; use <code class=\"ref\"> com pacote.Classe.método" % href)


def check_file(path, workspace, allow_links):
    problems = []
    page = Page()
    with open(path, encoding="utf-8") as f:
        page.feed(f.read())
    page.close()
    check_page(page, workspace, allow_links, lambda line, reason: problems.append((line, reason)))
    return sorted(set(problems))


def platform_root(module_root, override):
    if override:
        return os.path.abspath(override)
    env = os.environ.get("LUMIS_WORKSPACE")
    if env:
        return os.path.abspath(env)
    return os.path.abspath(os.path.join(module_root, "..", "..", "..", "..", ".."))


# --- autoteste -------------------------------------------------------------------------------

SAMPLE_JAVA = """package br.com.example;

public class Sample
{
	public interface Contract
	{
		void abstractOne(int a, int b);
	}

	public static class Inner
	{
		public void innerWork()
		{
			helper(1, 2);
		}
	}

	public Sample(int a)
	{
		this.a = a;
	}

	public void shortOne()
	{
		call(1);
	}

	public java.util.Map<String, Integer> generic(int a)
	{
		return null;
	}

%s
}
"""

PLATFORM_JAVA = """package lumis.sample;

public class Platform
{
	public void run()
	{
		other();
	}
}
"""

HTML = """<!DOCTYPE html>
<html lang="pt-BR"><head><title>t</title></head><body>
<p>%s</p>
</body></html>
"""


def self_test():
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        module = os.path.join(tmp, "platform", "mod")
        platform = os.path.join(tmp, "platform")
        os.makedirs(os.path.join(module, "src", "br", "com", "example"))
        os.makedirs(os.path.join(module, "docs"))
        os.makedirs(os.path.join(platform, "core", "src", "java", "lumis", "sample"))
        long_method = "\tpublic void longOne()\n\t{\n" + "\t\tcall(1);\n" * 70 + "\t}\n"
        with open(os.path.join(module, "src", "br", "com", "example", "Sample.java"), "w", encoding=ENCODING) as f:
            f.write(SAMPLE_JAVA % long_method)
        with open(os.path.join(platform, "core", "src", "java", "lumis", "sample", "Platform.java"), "w", encoding=ENCODING) as f:
            f.write(PLATFORM_JAVA)
        with open(os.path.join(module, "README.md"), "w") as f:
            f.write("x")
        workspace = Workspace(module, platform)
        sample = open(os.path.join(module, "src", "br", "com", "example", "Sample.java"), encoding=ENCODING).read().splitlines()
        long_start = next(i for i, l in enumerate(sample, 1) if "longOne" in l)
        short_line = next(i for i, l in enumerate(sample, 1) if "shortOne" in l) + 2

        def ref(text, attrs="", after=""):
            return '<code class="ref"%s>%s</code>%s' % (attrs, text, after)

        def run(body, allow_links=False):
            path = os.path.join(tmp, "t.html")
            with open(path, "w", encoding="utf-8") as f:
                f.write(HTML % body)
            return [reason for _, reason in check_file(path, workspace, allow_links)]

        def expect(label, body, expected, allow_links=False):
            reasons = run(body, allow_links)
            hit = [r for r in reasons if expected and expected in r]
            if expected is None and reasons or expected is not None and not hit:
                failures.append("%s: esperado %r, obtido %r" % (label, expected, reasons))

        in_long = long_start + 5
        expect("método do módulo", ref("br.com.example.Sample.shortOne"), None)
        expect("método com ()", ref("br.com.example.Sample.shortOne()"), None)
        expect("construtor", ref("br.com.example.Sample.Sample"), None)
        expect("genérico", ref("br.com.example.Sample.generic"), None)
        expect("classe aninhada", ref("br.com.example.Sample.Inner.innerWork"), None)
        expect("método abstrato", ref("br.com.example.Sample.Contract.abstractOne"), None)
        expect("plataforma", ref("lumis.sample.Platform.run"), None)
        expect("classe ausente", ref("br.com.example.Missing.run"), "não encontrada")
        expect("pacote ausente da árvore", ref("br.com.nada.Sample.shortOne"), "não encontrada")
        expect("método ausente", ref("br.com.example.Sample.nothing"), "não declarado")
        expect("chamada não é declaração", ref("br.com.example.Sample.call"), "não declarado")
        expect("método de outra classe", ref("br.com.example.Sample.Inner.shortOne"), "não declarado")
        expect("classe aninhada ausente", ref("br.com.example.Sample.Nope.x"), "não declarada")
        expect("sem pacote", ref("Sample.shortOne"), "sem pacote")
        expect("sem método", ref("br.com.example.Sample"), "sem método")
        expect("sem classe", ref("br.com.example"), "sem classe")
        expect("linha válida no método longo",
               ref("br.com.example.Sample.longOne", ' data-line="%d"' % in_long, " (linha %d)" % in_long), None)
        expect("linha escrita antes", "linha %d: %s" % (in_long, ref("br.com.example.Sample.longOne", ' data-line="%d"' % in_long)), None)
        expect("linha fora do método", ref("br.com.example.Sample.longOne", ' data-line="1"', " (linha 1)"), "fora do método")
        expect("método curto com linha", ref("br.com.example.Sample.shortOne", ' data-line="%d"' % short_line, " (linha %d)" % short_line),
               "use só a referência por método")
        expect("linha sem texto visível", ref("br.com.example.Sample.longOne", ' data-line="%d"' % in_long), "falta o texto visível")
        expect("linha com número diferente",
               ref("br.com.example.Sample.longOne", ' data-line="%d"' % in_long, " (linha %d)" % (in_long + 1)), "falta o texto visível")
        expect("externo completo", ref("br.com.exemplo.Thing.run", ' data-origin="externo"'), None)
        expect("externo sem pacote", ref("Thing.run", ' data-origin="externo"'), "sem pacote")
        expect("arquivo existente", '<code class="ref-file">src/br/com/example/Sample.java</code>', None)
        expect("arquivo ausente", '<code class="ref-file">src/br/com/example/Nope.java</code>', "não existe")
        expect("link para src", '<a href="src/br/com/example/Sample.java">fonte</a>', "hiperlink para código")
        expect("link para test", '<a href="../test/java/A.java#L3">teste</a>', "hiperlink para código")
        expect("link para a plataforma", '<a href="../../core/src/java/lumis/sample/Platform.java">p</a>', "hiperlink para código")
        expect("âncora interna", '<a href="#abertura">a</a>', None)
        expect("link http", '<a href="https://example.com/src/x.java">a</a>', None)
        expect("código simples", "<code>Sample.shortOne</code>", "sem class=\"ref\"")
        expect("código simples com #", "<code>Sample#shortOne</code>", "sem class=\"ref\"")
        expect("código com pacote sem ref", "<code>br.com.example.Sample.shortOne()</code>", "sem class=\"ref\"")
        expect("código comum", "<code>runId</code> <code>lum_Document</code> <code>br.com.lumis.commons.contentmigrator</code>", None)
        expect("código em pre", "<pre><code>Sample.shortOne</code></pre>", None)
        # modo parcial: links e code simples liberados, code.ref continua resolvido
        expect("parcial libera link", '<a href="src/A.java">fonte</a>', None, allow_links=True)
        expect("parcial libera código simples", "<code>Sample.shortOne</code>", None, allow_links=True)
        expect("parcial resolve ref ruim", ref("br.com.example.Missing.run"), "não encontrada", allow_links=True)
        expect("parcial resolve ref sem pacote", ref("Sample.shortOne"), "sem pacote", allow_links=True)
        expect("parcial aceita ref boa", ref("br.com.example.Sample.shortOne"), None, allow_links=True)
        # linha reportada
        path = os.path.join(tmp, "t.html")
        with open(path, "w", encoding="utf-8") as f:
            f.write(HTML % ref("br.com.example.Missing.run"))
        if [line for line, _ in check_file(path, workspace, False)] != [3]:
            failures.append("a linha do <code> deveria ser 3")
    if failures:
        for failure in failures:
            print("self-test: " + failure, file=sys.stderr)
        return 1
    print("self-test OK")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="*")
    parser.add_argument("--allow-links", action="store_true", help="conversão parcial: não checa <a href> para código nem <code> simples")
    parser.add_argument("--root", default=MODULE_ROOT, help="raiz do módulo (padrão: a deste script)")
    parser.add_argument("--workspace", help="raiz da plataforma; sobrescreve LUMIS_WORKSPACE")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.files:
        parser.error("informe ao menos um arquivo .html")
    root = os.path.abspath(args.root)
    workspace = Workspace(root, platform_root(root, args.workspace))
    count = 0
    for path in args.files:
        if not os.path.isfile(path):
            parser.error("arquivo não existe: %s" % path)
        for line, reason in check_file(path, workspace, args.allow_links):
            print("%s:%d: %s" % (path, line, reason), file=sys.stderr)
            count += 1
    return 1 if count else 0


if __name__ == "__main__":
    sys.exit(main())
