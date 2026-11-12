#!/usr/bin/env python3
"""Check Java 21 class headers or compare javap output against a git ref.

The working tree is compiled in place (mvn clean test-compile). The base is built
in a temporary detached git worktree; both production and test classes count.
Exit status: 0 equal, 1 first bytecode difference, 2 build/tool/git error.
"""

import argparse
import difflib
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import unittest


def progress(message):
    print('PROGRESS ' + message, flush=True)


def check_headers(root):
    directories = [root / 'target/classes', root / 'src']
    paths = sorted(path for directory in directories for path in directory.rglob('*.class'))
    if not paths:
        print('ERROR no class files in target/classes or src; run mvn -o compile', file=sys.stderr, flush=True)
        return 1
    errors = 0
    for path in paths:
        with path.open('rb') as stream:
            header = stream.read(8)
        if len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe':
            print(f'ERROR invalid class header: {path}', file=sys.stderr, flush=True)
            errors += 1
        elif struct.unpack('>H', header[6:8])[0] != 65:
            print(f'ERROR expected Java 21 (major 65): {path}', file=sys.stderr, flush=True)
            errors += 1
    progress(f'checked {len(paths)} Java 21 class header(s): {errors} error(s)')
    return int(bool(errors))


def run(command, cwd, timeout=30, capture=True):
    try:
        result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE if capture else None,
                                stderr=subprocess.PIPE if capture else None, timeout=timeout, check=True)
        return result.stdout or ''
    except subprocess.TimeoutExpired as error:
        print(f'ERROR {command[0]} exceeded {timeout}s in {cwd}', file=sys.stderr, flush=True)
        raise RuntimeError('command timed out') from error
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f'{command[0]} failed in {cwd}: {error.stderr or error.stdout or error.returncode}') from error


def normalize(output):
    lines = []
    for line in output.splitlines():
        if line.startswith('Compiled from '):
            continue
        # Constant-pool indices in instructions are incidental; the resolved
        # operand after // is significant (including literal strings like #42).
        instruction, separator, comment = line.partition('//')
        instruction = re.sub(r'#\d+', '#', instruction)
        comment = re.sub(r'\b(InvokeDynamic|Dynamic) #\d+:', r'\1 #:', comment)
        lines.append((instruction + separator + comment).rstrip())
    return '\n'.join(lines) + '\n'


def compile_tree(root):
    progress(f'Maven clean test-compile: {root}')
    run(['mvn', '-B', '-f', str(root / 'pom.xml'), 'clean', 'test-compile'], root, timeout=420, capture=False)


def classes(root):
    result = {}
    for output in ['classes', 'test-classes']:
        directory = root / 'target' / output
        for path in sorted(directory.rglob('*.class')):
            key = output + '/' + path.relative_to(directory).as_posix()
            progress('javap ' + key)
            # Shared PID namespaces can collide in hsperfdata and emit JVM
            # warnings on stdout. Disable perf counters so only javap output
            # participates in the comparison.
            result[key] = normalize(run(['javap', '-J-XX:-UsePerfData', '-c', '-p', str(path)], root))
    if not result:
        raise RuntimeError(f'no class files in {root}/target/{{classes,test-classes}}')
    return result


def first_difference(base, current):
    for name in sorted(base.keys() | current.keys()):
        if name not in base:
            return f'{name}: class added'
        if name not in current:
            return f'{name}: class removed'
        if base[name] != current[name]:
            diff = difflib.unified_diff(base[name].splitlines(), current[name].splitlines(),
                                        fromfile='base/' + name, tofile='current/' + name, lineterm='')
            return '\n'.join(list(diff)[:30])
    return None


def compare(root, base):
    root = root.resolve()
    top = Path(run(['git', 'rev-parse', '--show-toplevel'], root).strip())
    module = root.relative_to(top)
    commit = run(['git', 'rev-parse', '--verify', '--end-of-options', base + '^{commit}'], root).strip()
    with tempfile.TemporaryDirectory(prefix='pat001-bytecode-') as directory:
        worktree = Path(directory) / 'base'
        progress('creating detached worktree for ' + commit)
        run(['git', 'worktree', 'add', '--detach', str(worktree), commit], top)
        try:
            base_root = worktree / module
            compile_tree(base_root)
            baseline = classes(base_root)
            compile_tree(root)
            difference = first_difference(baseline, classes(root))
            if difference:
                print('ERROR bytecode differs:\n' + difference, flush=True)
                return 1
            progress(f'bytecode equal: {len(baseline)} class(es)')
            return 0
        finally:
            progress('removing temporary base worktree')
            run(['git', 'worktree', 'remove', '--force', str(worktree)], top)


FIXTURE_POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>local.style</groupId>
<artifactId>bytecode-self-test</artifactId><version>1</version>
<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
<build><plugins>
<plugin><artifactId>maven-clean-plugin</artifactId><version>3.3.2</version></plugin>
<plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
<plugin><artifactId>maven-compiler-plugin</artifactId><version>3.12.1</version>
<configuration><release>21</release></configuration></plugin>
</plugins></build></project>'''


class BytecodeTests(unittest.TestCase):
    def test_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(1, check_headers(root))
            output = root / 'target/classes/br/com/lumis/commons/contentmigrator/Example.class'
            output.parent.mkdir(parents=True)
            output.write_bytes(struct.pack('>IHH', 0xcafebabe, 0, 65))
            self.assertEqual(0, check_headers(root))
            output.write_bytes(struct.pack('>IHH', 0xcafebabe, 0, 55))
            self.assertEqual(1, check_headers(root))
            output.write_bytes(b'bad')
            self.assertEqual(1, check_headers(root))
            output.unlink()
            local = root / 'src/br/com/lumis/commons/contentmigrator/Example.class'
            local.parent.mkdir(parents=True)
            local.write_bytes(struct.pack('>IHH', 0xcafebabe, 0, 65))
            self.assertEqual(0, check_headers(root))
            output.write_bytes(struct.pack('>IHH', 0xcafebabe, 0, 55))
            self.assertEqual(1, check_headers(root))
            output.write_bytes(struct.pack('>IHH', 0xcafebabe, 0, 65))
            local.write_bytes(struct.pack('>IHH', 0xcafebabe, 0, 55))
            self.assertEqual(1, check_headers(root))

    def test_normalization(self):
        self.assertEqual(normalize('Compiled from "A.java"\n  0: ldc #1 // String foo\n'),
                         normalize('Compiled from "B.java"\n  0: ldc #92 // String foo\n'))
        self.assertNotEqual(normalize('  0: ldc #1 // String #123\n'), normalize('  0: ldc #2 // String #456\n'))
        self.assertIsNotNone(first_difference({'A': 'x'}, {}))
        self.assertIsNotNone(first_difference({}, {'A': 'x'}))

    def test_compare_git_and_maven(self):
        # Exercise the actual compare path, not a substitute javac-only path.
        with tempfile.TemporaryDirectory(prefix='pat001-bytecode-selftest-') as directory:
            root = Path(directory)
            source = root / 'src/main/java/Example.java'
            source.parent.mkdir(parents=True)
            source.write_text('public class Example { public int value() { return 1; } }\n')
            test = root / 'src/test/java/ExampleTest.java'
            test.parent.mkdir(parents=True)
            test.write_text('class ExampleTest { int read() { return new Example().value(); } }\n')
            (root / 'pom.xml').write_text(FIXTURE_POM)
            run(['git', 'init', '-q'], root)
            run(['git', 'add', 'pom.xml', 'src'], root)
            run(['git', '-c', 'user.name=Bytecode self-test', '-c', 'user.email=selftest@localhost',
                 '-c', 'commit.gpgsign=false', 'commit', '-qm', 'baseline'], root)
            source.write_text('public class Example\n{\n\tpublic int value()\n\t{\n\t\treturn 1;\n\t}\n}\n')
            self.assertEqual(0, compare(root, 'HEAD'))
            source.write_text(source.read_text().replace('return 1;', 'return 2;'))
            self.assertEqual(1, compare(root, 'HEAD'))
            # A test-only semantic change must also fail comparison.
            source.write_text(source.read_text().replace('return 2;', 'return 1;'))
            test.write_text(test.read_text().replace('.value();', '.value() + 1;'))
            self.assertEqual(1, compare(root, 'HEAD'))
            self.assertEqual(1, len(run(['git', 'worktree', 'list', '--porcelain'], root).split('worktree ')) - 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    commands = parser.add_subparsers(dest='command')
    commands.add_parser('check', help='require compiled production classes with Java 21 major 65')
    comparison = commands.add_parser('compare')
    comparison.add_argument('--base', required=True, help='git commit, branch or tag')
    args = parser.parse_args()
    if args.self_test:
        # Bound offline fixture builds to the installed Maven cache. Normal
        # comparison keeps the repository's own Maven configuration untouched.
        previous = os.environ.get('MAVEN_ARGS')
        os.environ['MAVEN_ARGS'] = (previous or '') + ' --offline'
        try:
            result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BytecodeTests))
            return 0 if result.wasSuccessful() else 1
        finally:
            if previous is None:
                os.environ.pop('MAVEN_ARGS', None)
            else:
                os.environ['MAVEN_ARGS'] = previous
    if args.command not in ('check', 'compare'):
        parser.error('choose check, compare --base REF or --self-test')
    try:
        root = Path(__file__).resolve().parent.parent
        return check_headers(root) if args.command == 'check' else compare(root, args.base)
    except (OSError, RuntimeError, ValueError) as error:
        print('ERROR bytecode comparison: ' + str(error), file=sys.stderr, flush=True)
        return 2


if __name__ == '__main__':
    sys.exit(main())
