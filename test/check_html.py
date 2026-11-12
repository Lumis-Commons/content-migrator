#!/usr/bin/env python3
"""Checker determinístico de estrutura, acessibilidade e trechos de código de documentos .html.

Uso: check_html.py [--root DIR] ARQUIVO...
     check_html.py --self-test

Saída: 'arquivo:linha: motivo'. Usa só a biblioteca padrão.

Estrutura: <html lang="pt-BR">, <title> não vazio, um único h1, hierarquia h1-h6 sem salto,
ids únicos, todo href '#x' resolve para um id, meta viewport, nav com id toc, main, imagens com
alt e links com texto. Recursos externos: src, href de <link>, @import e url() não podem usar
http(s). Um <script> executável só é aceito com o atributo data-optional, que declara que a
leitura não depende dele; script com src falha.

CSS exigido: @media print, @media (prefers-color-scheme: dark), @media (max-width ...) e
'tab-size: 4' numa regra de pre, porque o código usa TAB.

Contraste WCAG: o atributo data-contrast-pairs em <html> é obrigatório e lista pares
'token-texto/token-fundo' separados por vírgula, para texto, links, mark, código e callouts.
Cada token é uma propriedade '--token' definida em ':root'; a razão mínima é 4.5 no tema claro
(':root') e no escuro (':root' dentro de @media (prefers-color-scheme: dark), sobre o claro).

Trechos: <pre data-source='caminho#La-Lb'> tem caminho relativo à raiz do módulo; o texto
decodificado do pre (sem tags internas como mark e span) deve ser igual às linhas a-b do
fonte, ignorando espaço no fim da linha. O primeiro desvio é apontado.
"""
import argparse
import os
import re
import sys
import tempfile
from html.parser import HTMLParser

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(MODULE_ROOT, "test", "fixtures", "html")

MIN_CONTRAST = 4.5
EXTERNAL = re.compile(r"^\s*(?:https?:)?//", re.I)
SOURCE_REF = re.compile(r"^(.+)#L(\d+)(?:-L(\d+))?$")
JS_TYPES = ("", "text/javascript", "application/javascript", "module")
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}
PAIR_CATEGORIES = (
    ("texto", r"text|texto|corpo|body"),
    ("link", r"link"),
    ("mark", r"mark|destaque"),
    ("código", r"code|codigo|código"),
    ("callout", r"callout|aviso|nota"),
)
NAMED_COLORS = {"white": "#ffffff", "black": "#000000"}


class Page(HTMLParser):
    """Coleta os fatos do documento, cada um com a linha de origem."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.html_attrs = None
        self.html_line = 1
        self.title = None
        self.headings = []
        self.ids = {}
        self.duplicate_ids = []
        self.anchors = []
        self.externals = []
        self.images_without_alt = []
        self.empty_links = []
        self.scripts = []
        self.styles = []
        self.pres = []
        self.viewport = False
        self.main = False
        self.toc = False
        self._stack = []
        self._text = None
        self._link = None
        self._pre = None
        self._style = None
        self._script = None
        self._title = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        line = self.getpos()[0]
        if tag == "html":
            self.html_attrs, self.html_line = a, line
        elif tag == "title":
            self._title = [line, []]
        elif tag == "main":
            self.main = True
        elif tag == "nav" and a.get("id") == "toc":
            self.toc = True
        elif tag == "meta" and (a.get("name") or "").lower() == "viewport":
            self.viewport = True
        elif re.fullmatch(r"h[1-6]", tag):
            self.headings.append((int(tag[1]), line))
        elif tag == "img" and a.get("alt") is None:
            self.images_without_alt.append(line)
        elif tag == "style":
            self._style = [line, []]
        elif tag == "script":
            self._script = [line, a, []]
        elif tag == "pre":
            self._pre = [line, a.get("data-source"), []]
        if "id" in a and a["id"] is not None:
            if a["id"] in self.ids:
                self.duplicate_ids.append((line, a["id"]))
            else:
                self.ids[a["id"]] = line
        if tag == "a":
            href = a.get("href")
            if href is not None and href.startswith("#"):
                self.anchors.append((line, href[1:]))
            self._link = [line, [], bool((a.get("aria-label") or "").strip())]
        if "src" in a and a["src"] and EXTERNAL.match(a["src"]):
            self.externals.append((line, "src externo '%s'" % a["src"]))
        if tag == "link" and a.get("href") and EXTERNAL.match(a["href"]):
            self.externals.append((line, "href de <link> externo '%s'" % a["href"]))

    def handle_endtag(self, tag):
        if tag == "title" and self._title:
            self.title = "".join(self._title[1]).strip()
            self._title = None
        elif tag == "style" and self._style:
            self.styles.append((self._style[0], "".join(self._style[1])))
            self._style = None
        elif tag == "script" and self._script:
            self.scripts.append((self._script[0], self._script[1], "".join(self._script[2])))
            self._script = None
        elif tag == "pre" and self._pre:
            self.pres.append((self._pre[0], self._pre[1], "".join(self._pre[2])))
            self._pre = None
        elif tag == "a" and self._link:
            text = "".join(self._link[1]).strip()
            if not text and not self._link[2]:
                self.empty_links.append(self._link[0])
            self._link = None

    def handle_data(self, data):
        for target in (self._title, self._style, self._script):
            if target is not None:
                target[-1].append(data)
        if self._pre is not None:
            self._pre[2].append(data)
        if self._link is not None:
            self._link[1].append(data)


# ---------------------------------------------------------------------------- CSS


def split_blocks(css):
    """Divide o CSS em (preludio, corpo) de nível superior, sem comentários."""
    css = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
    blocks, depth, start, head = [], 0, 0, ""
    for i, ch in enumerate(css):
        if ch == "{":
            if depth == 0:
                head, start = css[head_start(css, i):i].strip(), i + 1
            depth += 1
        elif ch == "}" and depth:
            depth -= 1
            if depth == 0:
                blocks.append((head, css[start:i]))
    return blocks


def head_start(css, brace):
    """Início do preludio: depois do último ';' ou '}' anterior à chave."""
    return max(css.rfind(";", 0, brace), css.rfind("}", 0, brace)) + 1


def css_rules(css, media=None, out=None):
    """Lista de (media, seletor, corpo); media é o preludio do @media que contém a regra."""
    out = [] if out is None else out
    for head, body in split_blocks(css):
        if head.startswith("@media"):
            css_rules(body, re.sub(r"\s+", " ", head[len("@media"):].strip()).lower(), out)
        elif head.startswith("@"):
            continue
        else:
            out.append((media, re.sub(r"\s+", " ", head), body))
    return out


def declarations(body):
    found = {}
    for m in re.finditer(r"([-\w]+)\s*:\s*([^;]+)", body):
        found[m.group(1)] = m.group(2).strip()
    return found


def parse_color(value, tokens, seen=()):
    value = value.strip().lower()
    m = re.fullmatch(r"var\(\s*(--[-\w]+)\s*(?:,[^)]*)?\)", value)
    if m:
        name = m.group(1)
        if name in seen or name not in tokens:
            raise ValueError("token %s não definido" % name)
        return parse_color(tokens[name], tokens, seen + (name,))
    value = NAMED_COLORS.get(value, value)
    m = re.fullmatch(r"#([0-9a-f]{3}|[0-9a-f]{6})", value)
    if m:
        h = m.group(1)
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    m = re.fullmatch(r"rgb\(\s*(\d+)\s*[, ]\s*(\d+)\s*[, ]\s*(\d+)\s*\)", value)
    if m:
        return tuple(int(g) for g in m.groups())
    raise ValueError("cor '%s' não suportada (use #rgb, #rrggbb ou rgb())" % value)


def luminance(rgb):
    def channel(c):
        c /= 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def theme_tokens(rules):
    """Tokens '--x' do tema claro e do escuro (o escuro sobrepõe o claro)."""
    light, dark = {}, {}
    for media, selector, body in rules:
        if not re.search(r"(^|,)\s*:root\s*(,|$)", selector):
            continue
        tokens = {k: v for k, v in declarations(body).items() if k.startswith("--")}
        if media is None:
            light.update(tokens)
        elif "prefers-color-scheme" in media and "dark" in media:
            dark.update(tokens)
    return light, {**light, **dark}


# ------------------------------------------------------------------------ checks


def check_contrast(page, rules, errors):
    attrs = page.html_attrs or {}
    line = page.html_line
    raw = attrs.get("data-contrast-pairs")
    if raw is None or not raw.strip():
        errors.append((line, "atributo data-contrast-pairs ausente ou vazio em <html>"))
        return
    light, dark = theme_tokens(rules)
    if not any("prefers-color-scheme" in (m or "") and "dark" in m for m, _, _ in rules):
        return
    pairs = [p.strip() for p in raw.split(",") if p.strip()]
    for category, pattern in PAIR_CATEGORIES:
        if not any(re.search(pattern, p.split("/")[0], re.I) for p in pairs):
            errors.append((line, "data-contrast-pairs sem par de contraste para %s" % category))
    for pair in pairs:
        if pair.count("/") != 1:
            errors.append((line, "par de contraste '%s' fora do formato 'texto/fundo'" % pair))
            continue
        fg, bg = (("--" + t.strip().lstrip("-")) for t in pair.split("/"))
        for theme, tokens in (("claro", light), ("escuro", dark)):
            try:
                ratio = contrast(parse_color(f"var({fg})", tokens), parse_color(f"var({bg})", tokens))
            except ValueError as e:
                errors.append((line, "par '%s' no tema %s: %s" % (pair, theme, e)))
                continue
            if ratio < MIN_CONTRAST:
                errors.append((line, "contraste %.2f < %.1f no par '%s' do tema %s" % (ratio, MIN_CONTRAST, pair, theme)))


def check_css(page, rules, css, errors):
    medias = [m for m, _, _ in rules if m]
    first = page.styles[0][0] if page.styles else 1
    if not page.styles:
        errors.append((1, "documento sem <style>: o CSS deve ser embutido"))
    if not any(m.strip() == "print" or m.startswith("print") for m in medias):
        errors.append((first, "falta @media print"))
    if not any("prefers-color-scheme" in m and "dark" in m for m in medias):
        errors.append((first, "falta @media (prefers-color-scheme: dark)"))
    if not any(re.search(r"\(\s*max-width", m) for m in medias):
        errors.append((first, "falta @media (max-width: ...)"))
    tab_ok = False
    for _, selector, body in rules:
        if any(re.fullmatch(r"pre(?:[.:#\[][^\s,]*)?", s.strip()) for s in selector.split(",")):
            if re.fullmatch(r"4", declarations(body).get("tab-size", "")):
                tab_ok = True
    if not tab_ok:
        errors.append((first, "regra de pre sem 'tab-size: 4'"))
    for m in re.finditer(r"@import\b[^;]*;?|url\(\s*['\"]?([^)'\"]*)", css, re.I):
        text = m.group(0)
        if text.lower().startswith("@import"):
            if re.search(r"https?:", text, re.I) or EXTERNAL.search(text.split("import", 1)[1].replace("url(", "").strip(" '\"(")):
                errors.append((first, "@import externo '%s'" % text.strip()))
        elif m.group(1) is not None and EXTERNAL.match(m.group(1)):
            errors.append((first, "url() externo '%s'" % m.group(1)))


def check_structure(page, errors):
    attrs = page.html_attrs
    if attrs is None:
        errors.append((1, "falta o elemento <html>"))
        attrs = {}
    elif (attrs.get("lang") or "").lower() != "pt-br":
        errors.append((page.html_line, "html sem lang=\"pt-BR\""))
    if not page.title:
        errors.append((1, "falta <title> não vazio"))
    if not page.viewport:
        errors.append((1, "falta meta viewport"))
    if not page.main:
        errors.append((1, "falta <main>"))
    if not page.toc:
        errors.append((1, "falta <nav id=\"toc\">"))
    h1 = [h for h in page.headings if h[0] == 1]
    if len(h1) != 1:
        errors.append((h1[1][1] if len(h1) > 1 else 1, "esperado exatamente um h1, encontrados %d" % len(h1)))
    previous = 0
    for level, line in page.headings:
        if previous == 0 and level != 1:
            errors.append((line, "o primeiro título deve ser h1, não h%d" % level))
        elif level > previous + 1:
            errors.append((line, "salto de título de h%d para h%d" % (previous, level)))
        previous = level
    for line, ident in page.duplicate_ids:
        errors.append((line, "id duplicado '%s'" % ident))
    for line, ident in page.anchors:
        if not ident:
            errors.append((line, "âncora '#' vazia"))
        elif ident not in page.ids:
            errors.append((line, "âncora '#%s' sem id correspondente" % ident))
    for line, reason in page.externals:
        errors.append((line, "recurso externo: " + reason))
    for line in page.images_without_alt:
        errors.append((line, "imagem sem atributo alt"))
    for line in page.empty_links:
        errors.append((line, "link sem texto acessível"))
    for line, attrs, body in page.scripts:
        kind = (attrs.get("type") or "").strip().lower()
        if attrs.get("src"):
            errors.append((line, "script com src: a leitura não pode depender de arquivo externo"))
        elif kind in JS_TYPES and "data-optional" not in attrs and body.strip():
            errors.append((line, "script executável sem data-optional: a leitura deve funcionar sem script"))


def source_lines(root, path):
    full = os.path.normpath(os.path.join(root, path))
    if os.path.commonpath([os.path.abspath(root), os.path.abspath(full)]) != os.path.abspath(root):
        raise ValueError("caminho fora da raiz do módulo '%s'" % path)
    if not os.path.isfile(full):
        raise ValueError("arquivo '%s' não existe" % path)
    with open(full, encoding="utf-8") as f:
        return f.read().split("\n")


def check_snippet(root, line, source, text):
    """Motivo do primeiro desvio entre o pre e o fonte, ou None."""
    m = SOURCE_REF.match(source.strip())
    if not m:
        return "data-source '%s' fora do formato 'caminho#La-Lb'" % source
    path, first = m.group(1), int(m.group(2))
    last = int(m.group(3) or first)
    if first < 1 or last < first:
        return "faixa inválida L%d-L%d em data-source" % (first, last)
    try:
        lines = source_lines(root, path)
    except ValueError as e:
        return str(e)
    if lines and lines[-1] == "":
        lines.pop()
    if last > len(lines):
        return "faixa L%d-L%d passa do fim de %s (%d linhas)" % (first, last, path, len(lines))
    expected = [s.rstrip() for s in lines[first - 1:last]]
    if text.startswith("\r\n"):
        text = text[2:]
    elif text.startswith("\n"):
        text = text[1:]
    actual = [s.rstrip() for s in text.replace("\r\n", "\n").rstrip("\n").split("\n")]
    for i in range(max(len(expected), len(actual))):
        want = expected[i] if i < len(expected) else None
        got = actual[i] if i < len(actual) else None
        if want != got:
            where = "linha %d de %s" % (first + i, path)
            if got is None:
                return "trecho termina antes do fonte em %s: falta %r" % (where, want)
            if want is None:
                return "trecho tem linha a mais depois de L%d de %s: %r" % (last, path, got)
            return "trecho diverge do fonte em %s: esperado %r, encontrado %r" % (where, want, got)
    return None


def check_text(text, root=MODULE_ROOT):
    page = Page()
    page.feed(text)
    page.close()
    errors = []
    check_structure(page, errors)
    css = "\n".join(body for _, body in page.styles)
    rules = css_rules(css)
    check_css(page, rules, css, errors)
    check_contrast(page, rules, errors)
    for line, source, body in page.pres:
        if source is None:
            continue
        reason = check_snippet(root, line, source, body)
        if reason:
            errors.append((line, reason))
    return sorted(errors, key=lambda e: e[0])


def check_file(path, root=MODULE_ROOT):
    with open(path, encoding="utf-8") as f:
        return check_text(f.read(), root)


# ---------------------------------------------------------------------- self-test


def self_test():
    failures = []
    with open(os.path.join(FIXTURES, "valid.html"), encoding="utf-8") as f:
        valid = f.read()

    def case(name, text, needle=None):
        errors = check_text(text)
        if needle is None:
            if errors:
                failures.append("%s: esperado válido, erros=%s" % (name, errors))
        elif not any(needle in reason for _, reason in errors):
            failures.append("%s: esperado erro com %r, erros=%s" % (name, needle, errors))

    def mutate(old, new, count=1):
        assert old in valid, old
        return valid.replace(old, new, count)

    case("fixture válida", valid)
    case("lang", mutate('lang="pt-BR"', 'lang="en"'), "lang")
    case("título ausente", mutate("<title>Documento de teste</title>", "<title></title>"), "title")
    case("salto de título", mutate("<h3>Trecho</h3>", "<h4>Trecho</h4>"), "salto de título")
    case("dois h1", mutate("<h3>Trecho</h3>", "<h1>Outro</h1>"), "exatamente um h1")
    case("id duplicado", mutate('<h2 id="codigo">', '<h2 id="intro">'), "id duplicado")
    case("âncora quebrada", mutate('href="#codigo"', 'href="#nada"'), "sem id correspondente")
    case("sem viewport", mutate('<meta name="viewport" content="width=device-width, initial-scale=1">', ""), "viewport")
    case("sem toc", mutate('<nav id="toc">', "<nav>"), "nav id=\"toc\"")
    case("sem main", mutate("<main>", "<div>").replace("</main>", "</div>"), "<main>")
    case("sem print", mutate("@media print", "@media screen"), "@media print")
    case("sem escuro", mutate("prefers-color-scheme: dark", "prefers-color-scheme: light"), "prefers-color-scheme")
    case("sem max-width", mutate("(max-width: 700px)", "(min-width: 700px)"), "max-width")
    case("sem tab-size", mutate("tab-size: 4;", "tab-size: 8;"), "tab-size")
    case("src externo", mutate("<h1>", '<img src="https://x.com/a.png" alt="a"><h1>'), "recurso externo")
    case("link css externo", mutate("<style>", '<link rel="stylesheet" href="http://x.com/a.css"><style>'), "href de <link>")
    case("@import externo", mutate("<style>", '<style>\n@import url("https://x.com/a.css");'), "@import externo")
    case("url() externo", mutate("body {", "body { background: url(https://x.com/a.png);"), "url() externo")
    case("imagem sem alt", mutate("<h1>", '<img src="a.png"><h1>'), "sem atributo alt")
    case("script executável", mutate("</body>", "<script>x = 1;</script></body>"), "data-optional")
    case("script opcional", mutate("</body>", "<script data-optional>x = 1;</script></body>"))
    case("script com src", mutate("</body>", '<script src="a.js"></script></body>'), "script com src")
    case("contraste baixo", mutate("--link: #0b4f9c;", "--link: #9bbcdc;"), "contraste")
    case("contraste baixo no escuro", mutate("--bg: #121417;", "--bg: #8a8a8a;"), "tema escuro")
    case("contraste ausente", mutate(' data-contrast-pairs="text/bg, link/bg, mark-text/mark-bg, code-text/code-bg, callout-text/callout-bg"', ""), "data-contrast-pairs")
    case("contraste sem callout", mutate(", callout-text/callout-bg", ""), "callout")
    case("token indefinido", mutate("link/bg", "link/fundo-x"), "não definido")
    case("trecho divergente", mutate("a + b;", "a - b;"), "esperado '\\t\\t\\treturn a + b;'")
    case("trecho curto", mutate("L3-L5", "L3-L6"), "termina antes")
    case("trecho além do fim", mutate("L3-L5", "L3-L90"), "passa do fim")
    case("trecho sem arquivo", mutate("snippet.txt", "nao-existe.txt"), "não existe")
    case("trecho fora da raiz", mutate("test/fixtures/html/snippet.txt", "../../../etc/passwd"), "fora da raiz")

    with open(os.path.join(FIXTURES, "invalid.html"), encoding="utf-8") as f:
        invalid = check_text(f.read())
    for needle in ("lang", "title", "salto de título", "id duplicado", "sem id correspondente", "recurso externo", "@import externo",
                   "data-contrast-pairs", "diverge", "data-optional", "exatamente um h1", "viewport"):
        if not any(needle in reason for _, reason in invalid):
            failures.append("invalid.html: falta erro com %r" % needle)

    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "doc.html")
        with open(path, "w", encoding="utf-8") as f:
            f.write(mutate("<h3>Trecho</h3>", "<h4>Trecho</h4>"))
        if not check_file(path):
            failures.append("check_file não encontrou erro no arquivo temporário")

    for failure in failures:
        print("self-test: " + failure, file=sys.stderr)
    if failures:
        return 1
    print("self-test OK")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=MODULE_ROOT, help="raiz dos caminhos de data-source")
    ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.files:
        ap.error("informe ao menos um arquivo")
    rc = 0
    for path in a.files:
        for line, reason in check_file(path, a.root):
            print("%s:%d: %s" % (path, line, reason), file=sys.stderr)
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
