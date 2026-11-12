#!/usr/bin/env python3
"""Exercise the local Maven output, non-destructive clean and ordinary build."""

import hashlib
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent


def progress(message):
    print('PROGRESS ' + message, flush=True)


def run(command):
    progress('running ' + ' '.join(command))
    try:
        subprocess.run(command, cwd=ROOT, check=True, timeout=420)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f'{command[0]} exceeded 420s') from error


def snapshot(directory, include_classes=True):
    return {path.relative_to(directory): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in directory.rglob('*') if path.is_file()
            and (include_classes or path.suffix != '.class')}


def require_classes(sources, source_root, output):
    missing = [str(path.relative_to(ROOT)) for path in sources
               if not (output / path.relative_to(source_root).with_suffix('.class')).is_file()]
    if not sources or missing:
        raise RuntimeError(f'{output}: missing compiled sources: {missing[:10]}')
    progress(f'{output.relative_to(ROOT)} contains classes for all {len(sources)} Java sources')


def main():
    source_root = ROOT / 'src'
    sources = sorted(source_root.rglob('*.java'))
    originals = snapshot(source_root, include_classes=False)
    maven = ['mvn', '-o', '-B', '-f', 'pom.xml']
    initial = snapshot(source_root)
    run(maven + ['-Plocal-development', 'clean'])
    if initial != snapshot(source_root):
        raise RuntimeError('initial local clean removed or changed files in src')
    tracked = subprocess.check_output(['git', 'ls-files', '--', '*.class'], cwd=ROOT, text=True, timeout=30)
    if tracked.strip():
        raise RuntimeError('git tracks class files: ' + tracked[:500])
    # clean deliberately preserves local bytecode. Remove generated files here
    # so existing output cannot make the compilation proof pass without javac.
    generated = sorted(source_root.rglob('*.class'))
    progress(f'removing {len(generated)} generated src class files before the compilation proof')
    for path in generated:
        path.unlink()
    run(maven + ['-Plocal-development', 'compile'])
    require_classes(sources, source_root, source_root)
    if originals != snapshot(source_root, include_classes=False):
        raise RuntimeError('local compilation changed source files or resources')

    # Tests must never be written into the module's deployable source root.
    run(maven + ['-Plocal-development', 'test-compile'])
    tests_root = ROOT / 'test/java'
    tests = sorted(tests_root.rglob('*.java'))
    require_classes(tests, tests_root, ROOT / 'target/test-classes')
    if any((source_root / path.relative_to(tests_root).with_suffix('.class')).exists() for path in tests):
        raise RuntimeError('local test-compile wrote test classes into src')
    if originals != snapshot(source_root, include_classes=False):
        raise RuntimeError('local test compilation changed source files or resources')

    before_clean = snapshot(source_root)
    class_count = sum(path.suffix == '.class' for path in before_clean)
    run(maven + ['-Plocal-development', 'clean'])
    if before_clean != snapshot(source_root):
        raise RuntimeError('local clean removed or changed files in src')
    progress(f'clean preserved all {len(before_clean)} src files, including {class_count} class files (SHA-256)')

    # clean has removed target, so these classes must come from a fresh default build.
    run(maven + ['compile'])
    require_classes(sources, source_root, ROOT / 'target/classes')
    if before_clean != snapshot(source_root):
        raise RuntimeError('default compilation changed src')
    tracked = subprocess.check_output(['git', 'ls-files', '--', '*.class'], cwd=ROOT, text=True, timeout=30)
    if tracked.strip():
        raise RuntimeError('git tracks class files: ' + tracked[:500])
    classes = [str(Path('src') / path) for path in before_clean if path.suffix == '.class']
    ignored = subprocess.check_output(['git', 'check-ignore', '--stdin'], cwd=ROOT,
                                      input='\n'.join(classes) + '\n', text=True, timeout=30)
    if set(ignored.splitlines()) != set(classes):
        raise RuntimeError('some src class files are not ignored by git')
    progress(f'git ignores all {class_count} src class files; no class files tracked')
    progress('local build, test output, clean and default build passed')


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print('ERROR local build: ' + str(error), file=sys.stderr, flush=True)
        sys.exit(1)
