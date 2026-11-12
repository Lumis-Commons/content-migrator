#!/usr/bin/env python3
"""Check the PAT002 package contract, reflective references and stale bytecode.

This is a lexical checker, not a Java compiler. Comments and string literals are
excluded from dependency analysis; reflection is checked only for literal method
names and the documented ModuleBridge target forms.
"""
import argparse
import re
import tempfile
from pathlib import Path

BASE = 'br.com.lumis.commons.contentmigrator.service'
ORIGINAL_EDGES = {
    **dict.fromkeys(('dataprovider', 'processaction', 'rest'), 'api query dto exception'),
    'api': 'execution undo validation security lock config dao dto exception',
    'execution': 'copy undo dependency security lock config dao dto exception',
    'copy': 'dependency validation lock config dao dto exception',
    'undo': 'copy dependency validation lock dao dto exception',
    'dependency': 'dao dto exception',
    'query': 'validation security lock dao dto exception',
    'validation': 'dto exception', 'lock': 'dao', 'dao': 'dto exception',
    'exception': 'dto', 'dto': '', 'config': '', 'security': '',
}
# The hierarchical service adds the hierarchy package (tree, selection and browsing).
HIERARCHICAL_EDGES = {
    **ORIGINAL_EDGES,
    'dataprovider': 'api query config dto exception',
    'rest': 'api query hierarchy security config dto exception',
    'api': ORIGINAL_EDGES['api'] + ' hierarchy',
    'copy': ORIGINAL_EDGES['copy'] + ' hierarchy',
    'hierarchy': 'config dao dto exception',
    'security': 'exception',
}
TEST_SUFFIXES = ('', '.migration', '.admin', '.contract', '.spike', '.support')
# Each service root is checked on its own: package map, dependency direction and test packages.
SERVICES = {
    BASE + '.contentmigration': (ORIGINAL_EDGES, TEST_SUFFIXES),
    BASE + '.hierarchicalcontentmigration': (HIERARCHICAL_EDGES, TEST_SUFFIXES + ('.hierarchy',)),
}
ROOT = BASE + '.contentmigration'
# Tokenize strings before comments, so URLs and comment markers inside strings survive.
LEX = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/|'
                 r'[A-Za-z_$][\w$]*|[^\s]', re.S)
IDENT = re.compile(r'^[A-Za-z_$][\w$]*$')


def tokens(text):
    return [m.group() for m in LEX.finditer(text)
            if not m.group().startswith(('//', '/*'))]


def literal(token):
    if token.startswith('"') and token.endswith('"'):
        return token[1:-1]
    return None


def qualified(parts):
    return ''.join(parts)


def package_of(ts):
    try:
        start = ts.index('package') + 1
        return qualified(ts[start:ts.index(';', start)])
    except ValueError:
        return ''


def declarations(ts, package):
    """Index top-level/nested types and their explicitly public static methods."""
    types, tops = {}, set()
    stack, depth, pending, boundary = [], 0, None, 0
    for i, token in enumerate(ts):
        if token in ('class', 'interface', 'enum', 'record') and i + 1 < len(ts):
            # Ignore Foo.class and accept type declarations only.
            if (i == 0 or ts[i - 1] != '.') and IDENT.fullmatch(ts[i + 1]):
                parent = stack[-1][0] + '$' if stack else package + '.'
                pending = (parent + ts[i + 1], token)
                types[pending[0]] = set()
                if not stack:
                    tops.add(pending[0])
        if (token == '(' and stack and depth == stack[-1][1]
                and i > 0 and IDENT.fullmatch(ts[i - 1])):
            modifiers = ts[boundary:i - 1]
            if ('=' not in modifiers and 'new' not in modifiers and 'static' in modifiers
                    and ('public' in modifiers or stack[-1][2] == 'interface')):
                types[stack[-1][0]].add(ts[i - 1])
        if token == '{':
            depth += 1
            if pending:
                stack.append((pending[0], depth, pending[1]))
                pending = None
            boundary = i + 1
        elif token == '}':
            if stack and depth == stack[-1][1]:
                stack.pop()
            depth -= 1
            boundary = i + 1
        elif token == ';':
            boundary = i + 1
    return types, tops


def constants(ts):
    found = {}
    for i, token in enumerate(ts):
        if token != 'String' or i + 3 >= len(ts) or ts[i + 2] != '=':
            continue
        # Both legal modifier orders, with any visibility.
        start = i - 1
        while start >= 0 and ts[start] not in (';', '{', '}'):
            start -= 1
        if {'static', 'final'} <= set(ts[start + 1:i]):
            value = literal(ts[i + 3])
            if value is not None and ts[i + 4:i + 5] == [';']:
                found[ts[i + 1]] = value
    return found


def arguments(ts, start):
    args, current, depth = [], [], 0
    for token in ts[start:]:
        if token == ')' and depth == 0:
            return args + [current]
        if token == ',' and depth == 0:
            args.append(current)
            current = []
            continue
        if token in ('(', '[', '{'):
            depth += 1
        elif token in (')', ']', '}'):
            depth -= 1
        current.append(token)
    return []


def cycle_errors(graph):
    errors, active, done = [], [], set()

    def visit(node):
        if node in active:
            errors.append('cycle: ' + ' -> '.join(active[active.index(node):] + [node]))
            return
        if node in done:
            return
        active.append(node)
        for dependency in sorted(graph.get(node, ())):
            visit(dependency)
        active.pop()
        done.add(node)

    for node in sorted(graph):
        visit(node)
    return errors


def owned_by_other(name, root):
    """True when a package or class belongs to another service checked on its own."""
    return any(name == other or name.startswith(other + '.') for other in SERVICES if other != root)


def check(repo, portal, strict=False, announce=True, root=ROOT):
    errors, warnings = [], []
    edges, suffixes = SERVICES[root]
    allowed_production = {root + '.' + node for node in edges}
    allowed_production.update(root + '.dependency.' + node for node in
                              ('media', 'document', 'categorization', 'widget'))
    allowed_tests = {root + '.test' + suffix for suffix in suffixes}
    class_literal = re.compile(re.escape(root) + r'\.(?:[\w$]+\.)*[A-Z][\w]*(?:\$[A-Z][\w]*)*$')
    production, tests, all_types, tops = [], [], {}, set()
    for folder, destination in (('src', production), ('test/java', tests)):
        for path in sorted((repo / folder).rglob('*.java')):
            ts = tokens(path.read_text(encoding='utf-8'))
            package = package_of(ts)
            types, top_types = declarations(ts, package)
            all_types.update(types)
            if folder == 'src':
                tops.update(top_types)
            if owned_by_other(package, root):
                continue
            destination.append((path, ts, package, top_types))
            allowed = allowed_production if folder == 'src' else allowed_tests
            if package not in allowed:
                errors.append(f'{path}: forbidden package {package!r}')
            if (strict and folder == 'test/java' and package == root + '.test'
                    and any(t.endswith('Test') for t in top_types)):
                errors.append(f'{path}: strict-tests forbids *Test directly in {package}')

    graph = {node: set() for node in edges}
    reference = re.compile(re.escape(root) + r'\.([A-Za-z_$][\w$]*)')
    for path, ts, package, _ in production:
        # Removing literals and joining tokens also handles multiline qualified names.
        code = ''.join(' ' if token.startswith(('"', "'")) else token for token in ts)
        if not package.startswith(root + '.'):
            continue
        node = package[len(root) + 1:].split('.')[0]
        for target in reference.findall(code):
            if node == target:
                continue
            graph.setdefault(node, set()).add(target)
            if target not in edges.get(node, '').split():
                errors.append(f'{path}: forbidden direction {node} -> {target}')
    errors.extend(cycle_errors(graph))

    def check_fqn(value, path):
        if value.startswith(root + '.'):
            if value.endswith('.'):
                errors.append(f'{path}: incomplete FQN {value}')
            elif class_literal.fullmatch(value) and value not in all_types:
                errors.append(f'{path}: missing class {value}')

    for path in sorted((repo / 'src').rglob('*.xml')):
        text = re.sub(r'<!--.*?-->', '', path.read_text(encoding='utf-8'), flags=re.S)
        for value in re.findall(re.escape(root) + r'\.[\w.$]*', text):
            check_fqn(value, path)
    bridge = {}
    for path, ts, _, _ in tests:
        if path.name == 'ModuleBridge.java':
            bridge = constants(ts)
    for path, ts, _, _ in tests:
        local = constants(ts)
        for token in ts:
            value = literal(token)
            if value is not None:
                check_fqn(value, path)
        for i in range(len(ts) - 3):
            if (ts[i:i + 2] != ['ModuleBridge', '.'] or ts[i + 3] != '('
                    or ts[i + 2] not in ('api', 'dao', 'invokeStatic')):
                continue
            operation = ts[i + 2]
            args = arguments(ts, i + 4)
            target = bridge.get(operation.upper())
            if operation == 'invokeStatic':
                if len(args) < 2:
                    continue
                expression, args = args[0], args[1:]
                target = None
                if len(expression) == 1:
                    target = literal(expression[0]) or local.get(expression[0])
                elif expression[:2] == ['ModuleBridge', '.'] and len(expression) == 3:
                    target = bridge.get(expression[2])
            if target is None:
                warnings.append(f'{path}: unresolved {operation} target; skipped')
                continue
            if not args or len(args[0]) != 1 or literal(args[0][0]) is None:
                continue
            method = literal(args[0][0])
            if target not in all_types or method not in all_types[target]:
                errors.append(f'{path}: missing public static method {target}.{method}')

    directories = [repo / 'target/classes/br', repo / 'src/br']
    installed = portal / 'lumisdata/data/installedmodules/lib/br.com.lumis.commons.contentmigrator.jar'
    if portal.exists():
        directories.append(installed / 'br')
        if not installed.is_dir():
            errors.append(f'missing installed module directory: {installed}')
    for directory in directories:
        if announce:
            print(f'PROGRESS reading classes: {directory} (exists={directory.is_dir()})', flush=True)
        for path in sorted(directory.rglob('*.class')):
            name = '.'.join(path.relative_to(directory.parent).with_suffix('').parts)
            top = name.split('$', 1)[0]
            if top not in tops and not owned_by_other(top, root):
                errors.append(f'{path}: orphan class {name}')
    return errors, warnings


def self_test():
    with tempfile.TemporaryDirectory(prefix='structure-selftest-') as temp:
        repo = Path(temp)
        portal = repo / 'portal_install'

        def write(relative, text):
            path = repo / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
            return path

        def java(package, name, body='', test=False):
            return write(('test/java/' if test else 'src/') + package.replace('.', '/') + '/' + name + '.java',
                         f'package {package}; public class {name} {{ {body} }}')

        def expect(label, fragment=None, strict=False):
            errors, _ = check(repo, portal, strict, announce=False)
            assert (any(fragment in e for e in errors) if fragment else not errors), (label, errors)
            print(f'PROGRESS self-test: {label}', flush=True)

        api = java(ROOT + '.api', 'Facade', 'public static void okay() {} public void instance() {}')
        dto = java(ROOT + '.dto', 'Row')
        java(ROOT + '.test.support', 'ModuleBridge',
             f'public static final String API = "{ROOT}.api.Facade"; '
             f'public static final String DAO = "{ROOT}.api.Facade";', True)
        test = java(ROOT + '.test', 'ExampleTest', 'void run() { ModuleBridge.api("okay"); }', True)
        expect('valid baseline')
        bad = java(ROOT + '.old', 'Bad')
        expect('forbidden package', 'forbidden package')
        bad.unlink()
        original = dto.read_text()
        dto.write_text(original.replace('public class', f'import {ROOT}.api.Facade; public class'))
        expect('forbidden direction', 'forbidden direction dto -> api')
        api_text = api.read_text()
        api.write_text(api_text.replace('public class', f'import {ROOT}.dto.Row; public class'))
        expect('dependency cycle', 'cycle:')
        api.write_text(api_text)
        dto.write_text(original)
        dto.write_text(original.replace('public class', f'/* import {ROOT}.api.Facade; */ public class'))
        expect('comments ignored')
        dto.write_text(original.replace('public class', f'/** {ROOT}.api.Facade */ public class'))
        expect('Javadoc ignored')
        dto.write_text(original.replace(' {  }', f' {{ {ROOT}.api.Facade value; }}'))
        expect('FQN dependency', 'forbidden direction')
        dto.write_text(original)
        xml = write('src/definition.xml', f'<class>{ROOT}.api.Missing</class>')
        expect('missing XML FQN', 'missing class')
        xml.write_text(f'<service id="{ROOT}.service"/>')
        expect('service id is not a class')
        test_text = test.read_text()
        test.write_text(test_text.replace('"okay"', f'"{ROOT}.api.Missing"'))
        expect('missing test FQN', 'missing class')
        test.write_text(test_text.replace('"okay"', f'"{ROOT}.api."'))
        expect('incomplete FQN', 'incomplete FQN')
        test.write_text(test_text.replace('"okay"', '"missing"'))
        expect('missing reflective method', 'missing public static method')
        test.write_text(test_text.replace('"okay"', '"instance"'))
        expect('instance method rejected', 'missing public static method')
        api.write_text(api_text.replace('public static void okay()',
                                       'public static Object VALUE = inherited(); public static void okay()'))
        test.write_text(test_text.replace('"okay"', '"inherited"'))
        expect('initializer call is not a method declaration', 'missing public static method')
        api.write_text(api_text)
        test.write_text(test_text.replace('ModuleBridge.api("okay")', 'ModuleBridge.dao("missing")'))
        expect('dao method', 'missing public static method')
        for expression, declaration in (
                (f'"{ROOT}.api.Facade"', ''),
                ('TARGET', f'static final String TARGET = "{ROOT}.api.Facade";'),
                ('ModuleBridge.API', '')):
            test.write_text(test_text.replace('void run()', declaration + ' void run()')
                            .replace('ModuleBridge.api("okay")', f'ModuleBridge.invokeStatic({expression}, "missing")'))
            expect('invokeStatic target ' + expression, 'missing public static method')
        test.write_text(test_text.replace('ModuleBridge.api("okay")', 'ModuleBridge.invokeStatic(dynamic(), "missing")'))
        expect('dynamic target skipped')
        assert check(repo, portal, announce=False)[1]
        test.write_text(test_text)
        expect('strict-tests', 'strict-tests', strict=True)
        hierarchical = BASE + '.hierarchicalcontentmigration'
        other = java(hierarchical + '.hierarchy', 'Tree', f'{hierarchical}.api.Facade value;')
        expect('the other service is left to its own check')
        errors, _ = check(repo, portal, announce=False, root=hierarchical)
        assert errors == [f'{other}: forbidden direction hierarchy -> api'], errors
        other.write_text(other.read_text().replace('.api.Facade', '.dto.Row'))
        assert not check(repo, portal, announce=False, root=hierarchical)[0]
        print('PROGRESS self-test: second service root', flush=True)
        other.unlink()
        misplaced = java(ROOT + '.hierarchy', 'Tree')
        expect('package of the other service map', 'forbidden package')
        misplaced.unlink()
        stale = write('target/classes/br/com/lumis/commons/contentmigrator/Removed.class', '')
        expect('target orphan', 'orphan class')
        stale.unlink()
        stale = write('src/br/com/lumis/commons/contentmigrator/Removed.class', '')
        expect('local orphan', 'orphan class')
        stale.unlink()
        nested = write('src/' + (ROOT + '.api.Facade$Inner').replace('.', '/') + '.class', '')
        expect('local nested bytecode belongs to top-level source')
        nested.unlink()
        nested = write('target/classes/' + (ROOT + '.api.Facade$Inner').replace('.', '/') + '.class', '')
        expect('nested bytecode belongs to top-level source')
        nested.unlink()
        portal.mkdir()
        expect('missing installed module', 'missing installed module directory')
        installed = portal / 'lumisdata/data/installedmodules/lib/br.com.lumis.commons.contentmigrator.jar'
        installed.mkdir(parents=True)
        stale = write(str(installed.relative_to(repo)) + '/br/com/lumis/commons/contentmigrator/Removed.class', '')
        expect('installed orphan', 'orphan class')
        stale.unlink()
        expect('clean installed module')
        # Multiple top-level declarations need not have the same filename.
        api.write_text(api_text + ' class Helper {}')
        write('target/classes/' + (ROOT + '.api.Helper').replace('.', '/') + '.class', '')
        expect('additional top-level type')
    print('PASS structure self-test', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--strict-tests', action='store_true')
    parser.add_argument('--repo-root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--portal-install', type=Path)
    parser.add_argument('--root', action='append', choices=[r[len(BASE) + 1:] for r in SERVICES],
                        help='service to check, below ' + BASE + '; repeatable (default: all)')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    repo = args.repo_root.resolve()
    portal = args.portal_install or repo.parents[3]
    roots = [BASE + '.' + name for name in args.root] if args.root else list(SERVICES)
    failed = []
    for root in roots:
        print(f'PROGRESS checking {root}', flush=True)
        errors, warnings = check(repo, portal, args.strict_tests, root=root)
        for warning in warnings:
            print('WARNING ' + warning)
        for error in errors:
            print('ERROR ' + error)
        print(f'{"FAIL" if errors else "PASS"} structure {root}: {len(errors)} error(s), {len(warnings)} warning(s)')
        if errors:
            failed.append(root)
    print(f'{"FAIL" if failed else "PASS"} structure: {len(roots) - len(failed)}/{len(roots)} service(s) passed')
    return int(bool(failed))


if __name__ == '__main__':
    raise SystemExit(main())
