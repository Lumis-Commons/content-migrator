#!/usr/bin/env python3
"""Check the rename against the exceptions declared in the migration map."""

import argparse
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
MAP = ROOT / "docs/internal/migracao-19/MAPA-RENOMEACAO.md"
# Previous package name of the module and its abbreviation in persistence identifiers.
OLD_NAME = re.compile(r"verdana|vrd", re.IGNORECASE)
PERSISTENCE_NAME = re.compile(r"\b(?:lum_cmig_|lum_(?:pk|un)_)[a-z0-9_]+\b", re.IGNORECASE)
SQL_NAME = re.compile(
    r"\b(?:create\s+table|insert\s+into|delete\s+from|from|join|update|constraint)"
    r"\s+[`\"\[]?([a-z_][a-z0-9_]*)",
    re.IGNORECASE,
)
FONT_FAMILY = re.compile(r"\bfont-family\s*:\s*([^;{}<>\r\n]+)", re.IGNORECASE)


def read_exceptions():
    text = MAP.read_text(encoding="utf-8")
    section = text.split("## Mantidos sem renomear\n", 1)[1].split("\n## ", 1)[0]
    paths = re.findall(r"^\| `([^`]+)` \| Exceção por caminho", section, re.MULTILINE)
    if not paths:
        raise ValueError("no path exceptions found in the rename map")
    return paths


def excluded(relative, exceptions):
    if relative == ".git" or relative.startswith(".git/"):
        return True
    for pattern in exceptions:
        if pattern.endswith("/**"):
            prefix = pattern[:-3]
            if relative == prefix or relative.startswith(prefix + "/"):
                return True
        elif relative == pattern:
            return True
    return False


def walk(path, exceptions):
    relative = path.relative_to(ROOT).as_posix()
    if excluded(relative, exceptions):
        return
    yield path
    if path.is_dir():
        for current, directories, files in os.walk(path, followlinks=False):
            directories[:] = sorted(
                name for name in directories
                if not excluded((Path(current) / name).relative_to(ROOT).as_posix(), exceptions)
            )
            for name in directories + sorted(files):
                child = Path(current) / name
                if not excluded(child.relative_to(ROOT).as_posix(), exceptions):
                    yield child


def font_spans(path, text):
    """Only a complete CSS family name may keep the old font spelling."""
    if path.suffix.lower() not in {".css", ".html", ".htm", ".xhtml", ".xml"}:
        return []
    spans = []
    for declaration in FONT_FAMILY.finditer(text):
        value = declaration.group(1)
        offset = declaration.start(1)
        for family in re.finditer(r"[^,]+", value):
            if family.group().strip().strip("\"'").lower() == "verdana":
                spans.append((offset + family.start(), offset + family.end()))
    return spans


def check_file(path, report):
    relative = path.relative_to(ROOT).as_posix()
    text = path.read_bytes().decode("utf-8", errors="replace")
    allowed_fonts = font_spans(path, text)
    for match in OLD_NAME.finditer(text):
        if any(start <= match.start() and match.end() <= end for start, end in allowed_fonts):
            continue
        line = text.count("\n", 0, match.start()) + 1
        report(f"{relative}:{line}: forbidden name {match.group()!r}")

    names = {match.group() for match in PERSISTENCE_NAME.finditer(text)}
    if path.suffix.lower() in {".java", ".sql", ".xml"}:
        names.update(match.group(1) for match in SQL_NAME.finditer(text))
    if path.name == "databasedefinition.xml":
        for element in ET.fromstring(text).iter():
            if element.tag.rsplit("}", 1)[-1] in {"table", "constraint"}:
                name = element.get("name")
                if name:
                    names.add(name)
    for name in sorted(names):
        if len(name) > 30:
            report(f"{relative}: persistence name {name!r} has {len(name)} characters (maximum 30)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("code", "all"), required=True)
    args = parser.parse_args()
    errors = []
    files = 0
    print(f"PROGRESS checking rename scope={args.scope}", flush=True)
    try:
        exceptions = read_exceptions()
        roots = [ROOT] if args.scope == "all" else [
            ROOT / "src", ROOT / "test/java", ROOT / "test/resources", ROOT / "pom.xml",
        ]
        for root in roots:
            if not root.exists():
                errors.append(f"missing scope input: {root.relative_to(ROOT)}")
                continue
            for path in walk(root, exceptions):
                relative = path.relative_to(ROOT).as_posix()
                if OLD_NAME.search(relative):
                    errors.append(f"{relative}: forbidden name in path")
                if path.is_file():
                    files += 1
                    check_file(path, errors.append)
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        errors.append(f"cannot complete rename check: {error}")
    for error in errors[:100]:
        print(f"ERROR {error}", file=sys.stderr)
    if len(errors) > 100:
        print(f"ERROR {len(errors) - 100} additional violations omitted", file=sys.stderr)
    print(f"rename scope={args.scope}: {files} files, {len(errors)} violations", flush=True)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
