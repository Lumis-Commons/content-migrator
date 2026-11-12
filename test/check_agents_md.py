#!/usr/bin/env python3
"""Confere limites, rotas e ausência de detalhes de serviço nos AGENTS globais.

Análise lexical offline: vocabulário vem dos contratos literais em src/.
Use --root para verificar uma cópia temporária sem alterar o módulo.
"""

import argparse
from pathlib import Path
import re
import sys


LIMITS = {'AGENTS.md': 45, 'src/AGENTS.md': 95, 'test/AGENTS.md': 80}
SERVICE_LIMIT = 7
PURPOSE_LIMIT = 120
GUIDE = 'docs/GUIA-AGENTS-MD.md'


def vocabulary(root):
    """Deriva nomes persistidos, chaves específicas e caminhos REST do produto."""
    terms = set()
    for path in sorted((root / 'src').rglob('*')):
        if not path.is_file() or path.suffix not in ('.java', '.xml'):
            continue
        text = path.read_text(encoding='utf-8')
        terms.update(re.findall(r'\blum_cmig_\w+', text))
        if path.suffix != '.java':
            continue
        for annotation, body in re.findall(
                r'@(?:[\w]+\.)*(Table|Column)\s*\(([^)]*)\)', text, re.S):
            for name in re.findall(r'\bname\s*=\s*"([^"]+)"', body):
                if annotation == 'Table' or len(name) >= 8:
                    terms.add(name)
        # Chaves curtas também são prosa: exigimos aspas ou crases no global.
        terms.update(re.findall(
            r'\.\s*(?:put|get|getString|getBoolean|getInt|optString)\s*\(\s*'
            r'"([A-Za-z_][A-Za-z0-9_]*)"', text))
        terms.update(re.findall(r'@Path\s*\(\s*"([^"]+)"\s*\)', text))
    return sorted(terms)


def check(root):
    errors = []

    def read(relative):
        try:
            return (root / relative).read_text(encoding='utf-8')
        except (OSError, UnicodeError) as exc:
            errors.append(f'{relative}: não foi possível ler: {exc}')
            return ''

    globals_ = {name: read(name) for name in LIMITS}
    guide = read(GUIDE)
    expected = {
        '`AGENTS.md` da raiz': f'{LIMITS["AGENTS.md"]} linhas',
        '`src/AGENTS.md`': f'{LIMITS["src/AGENTS.md"]} linhas',
        '`test/AGENTS.md`': f'{LIMITS["test/AGENTS.md"]} linhas',
        'AGENTS.md de serviço': f'{SERVICE_LIMIT} linhas',
        'Propósito na tabela de serviços de `src/AGENTS.md`': f'{PURPOSE_LIMIT} caracteres',
    }
    rows = {}
    for line in guide.splitlines():
        cells = [cell.strip() for cell in line.strip().strip('|').split('|')]
        if len(cells) == 2:
            rows[cells[0]] = cells[1]
    for label, limit in expected.items():
        if rows.get(label) != limit:
            errors.append(f'{GUIDE}: limite de {label} deve ser {limit}')

    terms = vocabulary(root)
    for name, text in globals_.items():
        count = len(text.splitlines())
        if count > LIMITS[name]:
            errors.append(f'{name}: {count} linhas; máximo {LIMITS[name]}')
        for route in (GUIDE, 'docs/DECISOES-TECNICAS.md',
                      'docs/OPERAR-MODULO-NO-PORTAL.md'):
            if f'`{route}`' not in text:
                errors.append(f'{name}: falta rota para {route}')
            if not (root / route).is_file():
                errors.append(f'{name}: destino inexistente: {route}')
        for term in terms:
            pattern = (r'["\x27`]' + re.escape(term) + r'["\x27`]'
                       if len(term) < 8 and not term.startswith('/') else
                       r'(?<![\w/])' + re.escape(term) + r'(?!\w)')
            if re.search(pattern, text):
                errors.append(f'{name}: vocabulário de serviço: {term}')
    for route in ('src/AGENTS.md', 'test/AGENTS.md'):
        if f'`{route}`' not in globals_['AGENTS.md']:
            errors.append(f'AGENTS.md: falta rota para {route}')

    source = globals_['src/AGENTS.md']
    service_docs = set()
    service_definitions = list((root / 'src').glob('**/service/*/servicedefinition.xml'))
    if not service_definitions:
        errors.append('src/: nenhum serviço encontrado')
    for definition in service_definitions:
        service_docs.add(definition.with_name('AGENTS.md'))
    service_docs.update((root / 'src').glob('**/service/**/AGENTS.md'))
    for path in sorted(service_docs):
        relative = path.relative_to(root).as_posix()
        text = read(relative)
        count = len(text.splitlines())
        if not text.strip() or count > SERVICE_LIMIT:
            errors.append(f'{relative}: {count} linhas; esperado 1 a {SERVICE_LIMIT}')
        if f'`{relative}`' not in source:
            errors.append(f'src/AGENTS.md: falta rota para {relative}')
        for route in (relative, 'AGENTS.md', 'src/AGENTS.md'):
            if f'`{route}`' in text:
                errors.append(f'{relative}: auto-rota ou rota de volta ao pai: {route}')
    for name in ('src/AGENTS.md', 'test/AGENTS.md'):
        for route in (name, 'AGENTS.md'):
            if f'`{route}`' in globals_[name]:
                errors.append(f'{name}: auto-rota ou rota de volta ao pai: {route}')

    routed = set()
    for line in source.splitlines():
        if not line.startswith('|'):
            continue
        cells = [cell.strip() for cell in line.strip('|').split('|')]
        if len(cells) != 3 or not re.fullmatch(r'`[\w-]+`', cells[0]):
            continue
        purpose = cells[1]
        route = cells[2].strip('`')
        routed.add(route)
        if not purpose or len(purpose) > PURPOSE_LIMIT:
            errors.append(f'src/AGENTS.md: propósito de {cells[0]} excede {PURPOSE_LIMIT} caracteres ou está vazio')
        if not (root / route).is_file() or route not in {
                path.relative_to(root).as_posix() for path in service_docs}:
            errors.append(f'src/AGENTS.md: rota de serviço inválida: {route}')
    for path in service_docs:
        relative = path.relative_to(root).as_posix()
        if relative not in routed:
            errors.append(f'src/AGENTS.md: serviço ausente da tabela: {relative}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = check(args.root.resolve())
    for error in errors:
        print(f'ERROR {error}', file=sys.stderr)
    if errors:
        return 1
    print('PROGRESS AGENTS.md: limites, rotas, propósitos e vocabulário conferidos')
    return 0


if __name__ == '__main__':
    sys.exit(main())
