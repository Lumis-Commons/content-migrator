#!/usr/bin/env python3
"""Check the module's Lumis 19 / Java 21 / Jakarta compatibility contract."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MIGRATED = re.compile(
    r"\bjavax\.(?:servlet|ws\.rs|persistence|inject|annotation|validation|mail|"
    r"xml\.bind|jws|xml\.ws)(?:\.|\b)"
)


def check(root):
    """Return compatibility errors without changing any project files."""
    errors = []
    pom = ET.parse(root / "pom.xml").getroot()
    dependencies = [
        dep for dep in pom.findall("m:dependencies/m:dependency", NS)
        if dep.findtext("m:groupId", namespaces=NS) == "com.lumis"
        and dep.findtext("m:artifactId", namespaces=NS) == "lumisportal"
    ]
    if len(dependencies) != 1 or not re.fullmatch(
        r"19\.0\.0\.[0-9]+-enterprise",
        dependencies[0].findtext("m:version", default="", namespaces=NS)
        if dependencies else "",
    ):
        errors.append("pom.xml: expected one Lumis Portal 19.0.0 dependency")
    for setting in ("source", "target", "release"):
        value = pom.findtext(f"m:properties/m:maven.compiler.{setting}", namespaces=NS)
        if value != "21":
            errors.append(f"pom.xml: maven.compiler.{setting} must be 21")
    compiler = pom.find(
        "m:build/m:plugins/m:plugin[m:artifactId='maven-compiler-plugin']", NS
    )
    if compiler is None:
        errors.append("pom.xml: missing compiler plugin")
    else:
        for setting in ("source", "target", "release"):
            value = compiler.findtext(f"m:configuration/m:{setting}", namespaces=NS)
            # Absent settings inherit the compiler properties checked above.
            if value not in (None, "21", "${maven.compiler." + setting + "}"):
                errors.append(f"pom.xml: compiler {setting} overrides Java 21")
    for directory in ("src", "test/java"):
        for path in sorted((root / directory).rglob("*.java")):
            for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                # javax.annotation.processing is a Java SE package.
                code = line.replace("javax.annotation.processing", "java_se_processing")
                if MIGRATED.search(code):
                    errors.append(f"{path.relative_to(root)}:{number}: migrated javax package")
    readme = (root / "README.md").read_text(encoding="utf-8")
    if "Lumis Portal 19.0.0" not in readme or "Java 21" not in readme:
        errors.append("README.md: missing Lumis Portal 19.0.0 / Java 21 compatibility")
    module = ET.parse(root / "src/META-INF/lumis-module.xml").getroot()
    allowed = {"id", "version", "display-name", "description", "scope"}
    if any(child.tag.split("}")[-1] not in allowed for child in module):
        errors.append("lumis-module.xml: unsupported module element")
    return errors


def main():
    try:
        errors = check(ROOT)
    except (OSError, ET.ParseError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("upgrade-ok: Lumis 19.0.0, Java 21 and Jakarta source/test packages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
