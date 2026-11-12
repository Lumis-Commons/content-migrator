#!/usr/bin/env python3
"""Check the mechanically verifiable PAT001 rules without parsing comment prose.

Usage: check_style.py [--rules mechanical|all] [--max-params N] [--exclude GLOB] [PATH ...]
Exclusions match repository-relative paths (or a path component). Defaults to
src and test/java; XML receives whitespace/width checks only. No files change.

--max-params N rejects any method, constructor or record header with more than N
parameters (generic commas do not count). The only exception is the line
'// style:many-params <reason>' right above the declaration. Without the option
files under src use the limit of 5 and other files (test/java) have no limit.
"""

import argparse
import bisect
import fnmatch
from pathlib import Path
import re
import sys
import unittest


SRC_MAX_PARAMS = 5
LITERALS = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
TOKENS = re.compile(r'[A-Za-z_$][\w$]*|&&|\|\||->|[^\s]')


def mask_java(text):
    """Keep offsets/newlines and one literal token, but hide literal contents."""
    def replace(match):
        value = match.group()
        masked = ''.join('\n' if c == '\n' else ' ' for c in value)
        return masked if value.startswith('/') else 'L' + masked[1:]
    return LITERALS.sub(replace, text)


def check(text, suffix='.java', rules='all', max_params=None):
    code = mask_java(text) if suffix == '.java' else re.sub(
        r'<!--.*?-->|"[^"\n]*"|\'[^\'\n]*\'',
        lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), text, flags=re.S)
    lines = code.splitlines()
    raw_lines = text.splitlines()
    issues = []
    comment_starts = {m.start() for m in LITERALS.finditer(text) if m.group().startswith('/')} if suffix == '.java' else set()

    def report(line, rule, detail):
        issues.append((line, rule, detail))

    line_offset = 0
    for number, (raw, line) in enumerate(zip(raw_lines, lines), 1):
        # Whitespace is checked on the source, except inside multiline literals.
        first_code = len(raw) - len(raw.lstrip())
        visible_indent = first_code < len(line) and (not line[first_code].isspace() or line_offset + first_code in comment_starts)
        if visible_indent and re.match(r'\t* +\S', raw):
            report(number, 'indent', 'indent with TABs only (TAB = 4)')
        if raw.rstrip(' \t') != raw and (line.strip() or not raw.strip()):
            report(number, 'trailing', 'remove trailing whitespace')
        if len(line.rstrip().replace('\t', ' ' * 4)) > 170:
            report(number, 'width', 'code exceeds 170 columns')
        line_offset += len(raw) + 1
    if suffix != '.java':
        return issues

    starts = [0] + [m.end() for m in re.finditer('\n', code)]
    tokens = [(m.group(), m.start()) for m in TOKENS.finditer(code)]
    values = [value for value, _ in tokens]

    def line_of(index):
        return bisect.bisect_right(starts, tokens[index][1])

    def indent(index):
        return re.match(r'\s*', lines[line_of(index) - 1]).group()

    pairs = {}
    stack = []
    for i, value in enumerate(values):
        if value in ('(', '[', '{'):
            stack.append(i)
        elif value in (')', ']', '}') and stack:
            opening = stack.pop()
            if values[opening] == {')': '(', ']': '[', '}': '{'}[value]:
                pairs[opening] = i
                pairs[i] = opening

    control_bodies = {}
    do_while = set()
    # Find the end of a braced do body so its trailing while is not a loop body.
    for i, value in enumerate(values[:-1]):
        if value == 'do' and values[i + 1] == '{' and i + 1 in pairs:
            after = pairs[i + 1] + 1
            if after < len(values) and values[after] == 'while':
                do_while.add(after)
    for i, value in enumerate(values):
        if value in ('if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try') and i + 1 < len(values) and values[i + 1] == '(':
            close = pairs.get(i + 1)
            if close is None:
                continue
            if value in ('if', 'for', 'while') and rules == 'all':
                if line_of(i) != line_of(close):
                    report(line_of(i), 'condition-line', 'keep the complete control condition on one line')
                depth = 0
                operators = 0
                clauses = []
                for token in values[i + 2:close]:
                    if token in ('(', '[', '{'):
                        depth += 1
                    elif token in (')', ']', '}'):
                        depth -= 1
                    elif depth == 0 and token in ('&&', '||'):
                        operators += 1
                    elif depth == 0 and token == ';':
                        clauses.append(operators)
                        operators = 0
                if value == 'for' and len(clauses) == 2:
                    operators = clauses[1]
                if operators > 1:
                    report(line_of(i), 'condition-terms', 'extract a named boolean: at most one top-level && or ||')
            body = close + 1
            if i not in do_while and body < len(values):
                control_bodies[body] = i
                if value in ('if', 'for', 'while') and values[body] != '{':
                    report(line_of(i), 'braces', 'control body requires braces')
        elif value in ('else', 'do') and i + 1 < len(values):
            body = i + 1
            if value == 'else' and values[body] == 'if':
                continue
            control_bodies[body] = i
            if values[body] != '{':
                report(line_of(i), 'braces', 'control body requires braces')

    kinds = {}
    owners = {}
    enclosing = []
    methods = []
    type_names = {}

    def declaration_prefix(start, opening):
        """Tokens before the parameter list, without annotations and their arguments."""
        prefix_code = []
        k = start
        while k < opening:
            if values[k] == '@':
                k += 1
                while k < opening and (re.fullmatch(r'[\w$]+', values[k]) or values[k] == '.'):
                    k += 1
                if k < opening and values[k] == '(' and k in pairs:
                    k = pairs[k] + 1
            else:
                prefix_code.append(values[k])
                k += 1
        return prefix_code

    def parameter_count(opening, close):
        """Top-level parameters between the parentheses; commas inside <...>, (), [] and {} do not split."""
        depth = 0
        count = 0
        pending = False
        for token in values[opening + 1:close]:
            if token in ('(', '[', '{', '<'):
                depth += 1
            elif token in (')', ']', '}', '>'):
                depth -= 1
            elif token == ',' and depth == 0:
                count += pending
                pending = False
                continue
            pending = True
        return count + pending

    def check_params(first, opening, close):
        if max_params is None:
            return
        count = parameter_count(opening, close)
        marker = raw_lines[first - 2].strip() if first > 1 else ''
        if count > max_params and not re.fullmatch(r'// style:many-params\s+\S.*', marker):
            report(first, 'many-params', f'{count} parameters exceed the limit of {max_params}; group them in a DTO')
    for i, value in enumerate(values):
        if value == '{':
            previous = values[i - 1] if i else ''
            # Array/annotation values, including nested initializers.
            array = previous in ('=', ']', ',', 'default') or (previous == '{' and enclosing and kinds[enclosing[-1]] == 'array')
            boundary = i - 1
            while boundary >= 0 and values[boundary] not in (';', '{', '}'):
                boundary -= 1
            header = values[boundary + 1:i]
            annotation = '(' in header and '@' in header and previous != ')'
            kind = 'array' if array or annotation else 'block'
            owner = control_bodies.get(i, boundary + 1)
            declared_type = any(t in ('class', 'interface', 'enum', 'record') and (j == 0 or header[j - 1] != '.') for j, t in enumerate(header))
            is_type = (declared_type and 'new' not in header) or (previous == ')' and 'new' in header)
            # A method/constructor body belongs directly to a type; invocation
            # bodies, lambdas and annotation arguments must not count as methods.
            parent_type = bool(enclosing and kinds[enclosing[-1]] == 'type')
            closing_paren = next((j for j in range(i - 1, boundary, -1) if values[j] == ')'), None)
            if is_type:
                kind = 'type'
                for j, t in enumerate(header[:-1]):
                    if t in ('class', 'interface', 'enum', 'record') and (j == 0 or header[j - 1] != '.'):
                        type_names[i] = header[j + 1]
                        if t == 'record' and '(' in header[j + 2:]:
                            record_open = boundary + 1 + header.index('(', j + 2)
                            if record_open in pairs:
                                check_params(line_of(boundary + 1), record_open, pairs[record_open])
                        break
            elif kind != 'array' and parent_type and closing_paren is not None and i not in control_bodies and '->' not in header and 'new' not in header:
                opening_paren = pairs.get(closing_paren)
                prefix = values[boundary + 1:opening_paren] if opening_paren is not None else []
                # Ignore named annotation arguments while distinguishing field
                # initializers such as factory() from method declarations.
                prefix_code = declaration_prefix(boundary + 1, opening_paren) if opening_paren is not None else []
                if prefix and '=' not in prefix_code:
                    kind = 'method'
                    methods.append((i, owner))
                    # A lone name that is not the type name is an enum constant with a body.
                    if len(prefix_code) > 1 or (prefix_code and prefix_code[-1] == type_names.get(enclosing[-1])):
                        check_params(line_of(boundary + 1), opening_paren, closing_paren)
            if kind != 'array':
                line = lines[line_of(i) - 1].strip()
                if line != '{':
                    report(line_of(i), 'allman', "opening brace must be on its own line")
                if owner < i and indent(i) != indent(owner):
                    report(line_of(i), 'alignment', 'brace must align with its declaration')
            kinds[i] = kind
            owners[i] = owner
            enclosing.append(i)
        elif value == ';' and max_params is not None and enclosing and kinds[enclosing[-1]] == 'type':
            # Abstract and interface methods have no body; enum constants and fields are not declarations.
            boundary = i - 1
            while boundary >= 0 and values[boundary] not in (';', '{', '}'):
                boundary -= 1
            closing_paren = next((j for j in range(i - 1, boundary, -1) if values[j] == ')'), None)
            opening_paren = pairs.get(closing_paren) if closing_paren is not None else None
            if opening_paren is not None:
                prefix_code = declaration_prefix(boundary + 1, opening_paren)
                if len(prefix_code) > 1 and '=' not in prefix_code and re.fullmatch(r'[A-Za-z_$][\w$]*', prefix_code[-1]):
                    check_params(line_of(boundary + 1), opening_paren, closing_paren)
        elif value == '}' and i in pairs:
            opening = pairs[i]
            kind = kinds.get(opening)
            if kind != 'array':
                # A closing brace may carry the end of the enclosing call, e.g. '});' after a lambda argument.
                if not re.fullmatch(r'\}[)\];,]*', lines[line_of(i) - 1].strip()):
                    report(line_of(i), 'allman', 'closing brace must be on its own line')
                if indent(i) != indent(opening):
                    report(line_of(i), 'alignment', 'closing brace must align with opening brace')
            if enclosing and enclosing[-1] == opening:
                enclosing.pop()

    if rules == 'all':
        for opening, owner in methods:
            close = pairs.get(opening)
            if close is None:
                continue
            first = line_of(owner)
            marker = raw_lines[first - 2].strip() if first > 1 else ''
            exempt = re.fullmatch(r'// style:long-method\s+\S.*', marker)
            if line_of(close) - first + 1 > 80 and not exempt:
                report(first, 'method-length', 'method exceeds 80 lines; extract private steps')
            depth = 0
            for j in range(opening + 1, close):
                if values[j] == '{' and kinds.get(j) != 'array':
                    depth += 1
                    if depth > 4:
                        report(line_of(j), 'nesting', 'method body exceeds four nested blocks')
                        break
                elif values[j] == '}' and kinds.get(pairs.get(j)) != 'array':
                    depth -= 1
    return sorted(set(issues))


class StyleTests(unittest.TestCase):
    def test_each_rule(self):
        cases = [
            ('indent', '\tx();\n', '    x();\n'),
            ('trailing', '\tx();\n', '\tx(); \n'),
            ('width', 'x' * 170, 'x' * 171),
            ('allman', 'class A\n{\n}\n', 'class A { }\n'),
            ('alignment', 'class A\n{\n}\n', 'class A\n\t{\n\t}\n'),
            ('braces', 'if (a)\n{\n}\n', 'if (a) x();\n'),
            ('condition-line', 'if (a && b)\n{\n}\n', 'if (a\n\t\t&& b)\n{\n}\n'),
            ('condition-terms', 'if ((a && b) || c)\n{\n}\n', 'if (a && b || c)\n{\n}\n'),
            ('method-length', 'class A\n{\n\tvoid run()\n\t{\n\t}\n}\n',
             'class A\n{\n\tvoid run()\n\t{\n' + '\t\tx();\n' * 79 + '\t}\n}\n'),
            ('nesting', 'class A\n{\n\tvoid run()\n\t{\n' + '\t\t{\n' * 4 + '\t\t}\n' * 4 + '\t}\n}\n',
             'class A\n{\n\tvoid run()\n\t{\n' + '\t\t{\n' * 5 + '\t\t}\n' * 5 + '\t}\n}\n'),
        ]
        for rule, good, bad in cases:
            with self.subTest(rule=rule):
                self.assertNotIn(rule, [x[1] for x in check(good)])
                self.assertIn(rule, [x[1] for x in check(bad)])

    def test_controls(self):
        for control in ['else', 'do', 'while (a)', 'for (;;)', 'for (Object x : xs)']:
            self.assertIn('braces', [x[1] for x in check(control + ' x();')])
            self.assertNotIn('braces', [x[1] for x in check(control + '\n{\n}\n')])
        self.assertFalse(check('do\n{\n}\nwhile (a);\n'))
        # a lambda argument closes with '});'; a closing brace followed by code is still wrong
        self.assertNotIn('allman', [x[1] for x in check('class A\n{\n\tvoid run()\n\t{\n\t\tx(() ->\n\t\t{\n\t\t});\n\t}\n}\n')])
        self.assertIn('allman', [x[1] for x in check('class A\n{\n\tvoid run()\n\t{\n\t\t{\n\t\t} x();\n\t}\n}\n')])
        self.assertFalse(check('if (a)\n{\n}\nelse if (b)\n{\n}\n'))
        self.assertFalse(check('for (boolean a = x && y; a; a = x || y)\n{\n}\n'))

    def test_exceptions_and_masking(self):
        self.assertNotIn('width', [x[1] for x in check('\t' * 42 + 'xx')])
        self.assertIn('width', [x[1] for x in check('\t' * 42 + 'xxx')])
        self.assertIn('indent', [x[1] for x in check('    // explanation\n')])
        self.assertFalse(check('class A\n{\n\tint[] a = { 1, 2 };\n\tint[][] b = { { 1 } };\n}\n'))
        self.assertFalse(check('@Anno(value = { 1, 2 })\nclass A\n{\n}\n'))
        self.assertFalse(check('@interface A\n{\n\tint[] value() default { 1 };\n}\n'))
        self.assertFalse(check('// if (a) { ' + 'x' * 180 + '\n/* {\n    } */\nString s = "if (a) { }";\n'))
        self.assertFalse(check('String s = """\n    { if (a) ' + 'x' * 180 + '\n    """;\n'))
        long_method = 'class A\n{\n\t// style:long-method linear DAO mapping\n\tvoid run()\n\t{\n' + '\t\tx();\n' * 81 + '\t}\n}\n'
        self.assertNotIn('method-length', [x[1] for x in check(long_method)])
        annotated = long_method.replace('// style:long-method linear DAO mapping', '@Anno(value = 1)')
        self.assertIn('method-length', [x[1] for x in check(annotated)])
        self.assertFalse(check('if (a\n\t\t&& b && c)\n{\n}\n', rules='mechanical'))

    def test_max_params(self):
        def rules(source, limit=5):
            return [x[1] for x in check(source, '.java', 'mechanical', limit)]
        body = 'class A\n{\n\t%s\n\t{\n\t}\n}\n'
        five = 'void run(int a, int b, int c, int d, int e)'
        six = 'void run(int a, int b, int c, int d, int e, int f)'
        # accept: at the limit, generics and annotation arguments do not add commas, and no option means no check
        self.assertNotIn('many-params', rules(body % five))
        self.assertNotIn('many-params', rules(body % 'void run(Map<String, List<Integer>> a, Map<K, V> b, int c, int d, int e)'))
        self.assertNotIn('many-params', rules(body % 'void run(@Anno(a = 1, b = 2) int a, int b)'))
        self.assertNotIn('many-params', rules(body % 'void run()'))
        self.assertFalse([x for x in check(body % six, '.java', 'mechanical') if x[1] == 'many-params'])
        # reject: methods, constructors, generics-heavy lists, interface methods and record headers
        self.assertIn('many-params', rules(body % six))
        self.assertIn('many-params', rules(body % 'A(int a, int b, int c, int d, int e, int f)'))
        self.assertIn('many-params', rules(body % 'void run(Map<String, Integer> a, int b, int c, int d, int e, int f)'))
        self.assertIn('many-params', rules('interface A\n{\n\tvoid run(int a, int b, int c, int d, int e, int f);\n}\n'))
        self.assertIn('many-params', rules('record A(int a, int b, int c, int d, int e, int f)\n{\n}\n'))
        self.assertNotIn('many-params', rules('enum A\n{\n\tX(1, 2, 3, 4, 5, 6);\n\tA(int a)\n\t{\n\t}\n}\n'))
        self.assertNotIn('many-params', rules('class A\n{\n\tint x = f(1, 2, 3, 4, 5, 6);\n}\n'))
        self.assertEqual([(3, 'many-params')], [x[:2] for x in check(body % six, '.java', 'mechanical', 5)])
        # exception: the marker with a reason on the previous line; without a reason or detached it fails
        marked = body % ('// style:many-params mirrors the legacy API\n\t' + six)
        self.assertNotIn('many-params', rules(marked))
        self.assertIn('many-params', rules(marked.replace(' mirrors the legacy API', '')))
        self.assertIn('many-params', rules(marked.replace('API\n', 'API\n\n')))
        # the exception covers only the declaration right below it
        self.assertIn('many-params', rules(marked.replace('\n}\n', '\n\t' + six + '\n\t{\n\t}\n}\n')))

    def test_xml(self):
        self.assertFalse(check('<root>\n\t<item/>\n</root>\n', '.xml'))
        self.assertEqual({'indent', 'trailing'}, {x[1] for x in check('<root>\n    <item/> \n</root>', '.xml')})

    def test_exclude(self):
        self.assertTrue(excluded(Path('src/generated/A.java'), ['src/generated/**']))
        self.assertTrue(excluded(Path('src/generated/A.java'), ['generated']))
        self.assertFalse(excluded(Path('src/A.java'), ['generated']))

    def test_default_max_params(self):
        self.assertEqual(SRC_MAX_PARAMS, default_max_params(Path('src/br/com/A.java')))
        self.assertIsNone(default_max_params(Path('test/java/br/com/ATest.java')))
        self.assertIsNone(default_max_params(Path('test/src/A.java')))


def relative_path(path):
    try:
        return path.resolve().relative_to(Path.cwd()).as_posix()
    except ValueError:
        return path.as_posix()


def default_max_params(path):
    """Production code under src keeps the PAT001 limit; tests have none."""
    return SRC_MAX_PARAMS if relative_path(path).split('/')[0] == 'src' else None


def excluded(path, patterns):
    relative = relative_path(path)
    return any(fnmatch.fnmatch(path.as_posix(), pattern) or fnmatch.fnmatch(relative, pattern) or pattern in path.parts for pattern in patterns)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('paths', nargs='*', type=Path)
    parser.add_argument('--rules', choices=['mechanical', 'all'], default='all')
    parser.add_argument('--max-params', type=int, metavar='N', help=f'fail methods and constructors with more than N parameters (default: {SRC_MAX_PARAMS} in src, none elsewhere)')
    parser.add_argument('--exclude', action='append', default=[], metavar='GLOB')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        return 0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(StyleTests)).wasSuccessful() else 1
    paths = args.paths or [Path('src'), Path('test/java')]
    files = set()
    for path in paths:
        if not path.exists():
            parser.error(f'path does not exist: {path}')
        candidates = [path] if path.is_file() else path.rglob('*')
        files.update(p for p in candidates if p.is_file() and p.suffix in ('.java', '.xml') and not excluded(p, args.exclude))
    count = 0
    for path in sorted(files):
        max_params = default_max_params(path) if args.max_params is None else args.max_params
        for line, rule, detail in check(path.read_text(encoding='utf-8'), path.suffix, args.rules, max_params):
            print(f'{path}:{line}: {rule}: {detail}')
            count += 1
    print(f'Checked {len(files)} files ({args.rules}); {count} violation(s).')
    return int(count > 0)


if __name__ == '__main__':
    sys.exit(main())
