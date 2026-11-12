#!/usr/bin/env bash
# Java only. Stage selected files, add braces, run Eclipse, then copy back.
# ECLIPSE_HOME may name another compatible Eclipse installation.
set -euo pipefail
exec python3 - "$0" "$@" <<'PY'
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(sys.argv[1]).resolve().parent.parent
PLUGIN = 'org.openrewrite.maven:rewrite-maven-plugin:6.12.0:run'
RECIPE = 'org.openrewrite.recipe:rewrite-static-analysis:2.7.0'
HEADLESS = {
    'org.eclipse.jdt.core', 'org.eclipse.jdt.core.compiler.batch', 'org.eclipse.jdt.core.formatterapp',
    'org.eclipse.core.runtime', 'org.eclipse.core.resources', 'org.eclipse.core.jobs', 'org.eclipse.core.commands',
    'org.eclipse.core.filesystem', 'org.eclipse.core.contenttype', 'org.eclipse.core.expressions',
    'org.eclipse.equinox.common', 'org.eclipse.equinox.preferences', 'org.eclipse.equinox.registry', 'org.eclipse.equinox.app',
    'org.eclipse.equinox.simpleconfigurator', 'org.eclipse.osgi', 'org.eclipse.osgi.services',
    'org.osgi.service.prefs', 'org.eclipse.text', 'com.sun.jna', 'com.sun.jna.platform',
}


def progress(message):
    print('PROGRESS ' + message, flush=True)


def run(command, timeout, cwd=None):
    progress('running ' + command[0])
    try:
        subprocess.run(command, cwd=cwd, timeout=timeout, check=True)
    except subprocess.TimeoutExpired as error:
        print(f'ERROR {command[0]} exceeded {timeout}s', file=sys.stderr, flush=True)
        raise RuntimeError('formatter stage timed out') from error


def eclipse_bundles(home):
    """Support both unpacked installations and Eclipse Installer's p2 pool."""
    bundles = {}
    info = home / 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
    for line in info.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        name, _, location, *_ = line.split(',')
        parsed = urllib.parse.urlparse(location)
        path = Path(urllib.parse.unquote(parsed.path))
        if not path.is_absolute():
            path = home / path
        bundles[name] = path.resolve()
    return bundles, info


def format_files(files, temp):
    home = Path(os.environ.get('ECLIPSE_HOME', '~/programas/eclipse/jee-2026-06/eclipse')).expanduser().resolve()
    bundles, info = eclipse_bundles(home)
    stage = temp / 'project'
    source = stage / 'src/main/java'
    source.mkdir(parents=True)
    # Separate packages/duplicate basenames remain distinct in staging.
    copies = []
    for index, original in enumerate(files):
        target = source / str(index) / original.name
        target.parent.mkdir()
        shutil.copyfile(original, target)
        copies.append(target)
    (stage / 'pom.xml').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>local.style</groupId>
<artifactId>format</artifactId><version>1</version>
<properties><maven.compiler.release>21</maven.compiler.release>
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
</project>''')
    try:
        # Offline execution is deliberate: formatting must not depend on a
        # network repository. Missing cached artifacts select the JDT fallback.
        run(['mvn', '-B', '-o', '-f', str(stage / 'pom.xml'), PLUGIN,
             '-Drewrite.activeRecipes=org.openrewrite.staticanalysis.NeedBraces',
             '-Drewrite.recipeArtifactCoordinates=' + RECIPE], 120, stage)
        progress('NeedBraces: OpenRewrite completed')
    except (subprocess.CalledProcessError, FileNotFoundError, RuntimeError):
        progress('OpenRewrite unavailable; inserting braces with Eclipse JDT AST')
        for original, staged in zip(files, copies):
            shutil.copyfile(original, staged)
        classpath = os.pathsep.join(str(p) for name, p in bundles.items() if name in HEADLESS)
        classes = temp / 'helper-classes'
        classes.mkdir()
        helper = ROOT / 'test/fixtures/format/NeedBraces.java'
        run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), str(helper)], 60)
        run(['java', '-cp', str(classes) + os.pathsep + classpath, 'NeedBraces', *map(str, copies)], 120)

    # JavaCodeFormatter consumes Java properties, while the checked-in profile
    # remains an importable Eclipse XML profile.
    profile = ET.parse(ROOT / 'config/eclipse-formatter.xml')
    properties = temp / 'formatter.properties'
    properties.write_text('\n'.join(s.attrib['id'] + '=' + s.attrib['value'] for s in profile.findall('.//setting')) + '\n')
    configuration = temp / 'configuration'
    configuration.mkdir()
    (configuration / 'config.ini').write_text(
        'osgi.framework=' + bundles['org.eclipse.osgi'].as_uri() + '\n'
        'osgi.bundles=reference:' + bundles['org.eclipse.equinox.simpleconfigurator'].as_uri() + '@1:start\n'
        'osgi.bundles.defaultStartLevel=4\n'
        'org.eclipse.equinox.simpleconfigurator.configUrl=' + info.as_uri() + '\n')
    launcher = next((home / 'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    progress('Eclipse JavaCodeFormatter: applying PAT001 profile')
    try:
        run(['java', '-Xmx512m', '-jar', str(launcher), '-nosplash',
             '-install', str(home), '-configuration', str(configuration),
             '-data', str(temp / 'workspace'), '-application', 'org.eclipse.jdt.core.JavaCodeFormatter',
             '-config', str(properties), '-verbose', *map(str, copies)], 180)
    except (subprocess.CalledProcessError, RuntimeError):
        for log in list(configuration.rglob('*.log')) + list((temp / 'workspace').rglob('*.log')):
            print('ERROR Eclipse log ' + log.name + ':\n' + log.read_text(errors='replace')[-12000:], file=sys.stderr, flush=True)
        raise
    run(['python3', str(ROOT / 'test/check_style.py'), '--rules', 'mechanical', *map(str, copies)], 60)
    for original, formatted in zip(files, copies):
        shutil.copyfile(formatted, original)
    progress(f'formatted {len(files)} file(s)')


def main():
    parser = argparse.ArgumentParser(description='Add mandatory braces, then apply the PAT001 Eclipse Java profile.')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('paths', type=Path, nargs='*', help='Java files/directories; default: src test/java')
    args = parser.parse_args(sys.argv[2:])
    with tempfile.TemporaryDirectory(prefix='pat001-format-') as directory:
        temp = Path(directory)
        if args.self_test:
            fixture = temp / 'FormatFixture.java'
            shutil.copyfile(ROOT / 'test/fixtures/format/FormatFixture.java', fixture)
            format_files([fixture], temp / 'first')
            result = fixture.read_text()
            if 'Math.max(count, 1)' not in result:
                raise RuntimeError('Eclipse did not join a wrapped invocation that fits')
            run(['javac', '-d', str(temp / 'fixture-classes'), str(fixture)], 60)
            format_files([fixture], temp / 'second')
            if result != fixture.read_text():
                raise RuntimeError('formatter is not idempotent')
            progress('formatter self-test passed (braces, Allman, joining, compilation, idempotence)')
            return
        files = set()
        for path in args.paths or [ROOT / 'src', ROOT / 'test/java']:
            if not path.exists():
                parser.error(f'path does not exist: {path}')
            files.update([path.resolve()] if path.is_file() and path.suffix == '.java' else (p.resolve() for p in path.rglob('*.java')))
        if not files:
            parser.error('no Java files selected')
        format_files(sorted(files), temp)


try:
    main()
except (OSError, RuntimeError, subprocess.CalledProcessError, KeyError, StopIteration) as error:
    print(f'ERROR formatter: {error}', file=sys.stderr, flush=True)
    sys.exit(1)
PY
