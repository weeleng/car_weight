#!/usr/bin/env python3
"""Audit the approved ENG-002B skeleton using effective POMs, trees and built artifacts.

Build both source trees with JDK 21, then generate unfiltered Maven tree JSON and
effective XML named <artifact>-tree.json and <artifact>-effective.xml under
EVIDENCE/default or EVIDENCE/iot. See the implementation report for commands.
Exit 0: completed, 1: rule violation, 2: unreadable or incomplete evidence.
"""

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile


GROUP = "cn.iocoder.boot"
PREFIX = "sanitation-"
PACKAGE = "cn.iocoder.yudao.module.sanitation"
SERVER = "yudao-server"
GATEWAY = "yudao-module-iot-gateway"
IOT = "yudao-module-iot"
DOMAINS = ("base", "gis", "device", "operation", "billing", "event")
COMMON = PREFIX + "common"
AGGREGATOR = PREFIX + "modules"
API = {PREFIX + domain + "-api" for domain in DOMAINS}
BIZ = {PREFIX + domain + "-biz" for domain in (*DOMAINS, "analysis", "integration")}
MINIMUM = {PREFIX + domain + "-biz": {PREFIX + domain + "-api"} for domain in DOMAINS}
EXTRA = {
    "base": (),
    "gis": ("base",),
    "device": ("base",),
    "operation": ("base", "gis", "device"),
    "billing": ("operation", "base"),
    "event": (),
    "analysis": DOMAINS,
    "integration": DOMAINS,
}
CANDIDATE = {
    COMMON: set(),
    **{api: {COMMON} for api in API},
    **{
        PREFIX + domain + "-biz": ({COMMON, PREFIX + "event-api"}
                                   | {PREFIX + name + "-api" for name in domains}
                                   | MINIMUM.get(PREFIX + domain + "-biz", set()))
        for domain, domains in EXTRA.items()
    },
}
ROOT_MODULES = (
    "yudao-dependencies", "yudao-framework", SERVER, "yudao-module-system",
    "yudao-module-infra", AGGREGATOR,
)
SERVER_DIRECT = {
    "yudao-module-system", "yudao-module-infra", "yudao-spring-boot-starter-protection",
}
SERVER_BOOT = "cn.iocoder.yudao.server.YudaoServerApplication"
GATEWAY_BOOT = "cn.iocoder.yudao.module.iot.gateway.IotGatewayServerApplication"
BOOT_LOADER = "org.springframework.boot.loader.launch.JarLauncher"
POM_NS = "http://maven.apache.org/POM/4.0.0"


@dataclass(frozen=True)
class Dependency:
    group: str
    artifact: str
    version: str = ""
    scope: str = "compile"
    kind: str = "jar"
    classifier: str = ""
    optional: bool = False

    def label(self):
        return f"{self.group}:{self.artifact}:{self.version} [{self.scope}, {self.kind}]"


class EvidenceError(ValueError):
    pass


def read_pom(path):
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise EvidenceError(f"{path}: {exc}") from exc
    if root.tag != f"{{{POM_NS}}}project":
        raise EvidenceError(f"{path}: expected one Maven project")
    for node in root.iter():
        node.tag = node.tag.removeprefix(f"{{{POM_NS}}}")
    return root


def deps(node, group):
    result = []
    for d in node.findall("dependencies/dependency"):
        g = d.findtext("groupId", "").strip()
        if g in ("${project.groupId}", "${pom.groupId}"):
            g = group
        a = d.findtext("artifactId", "").strip()
        if not g or not a or "${" in g or "${" in a:
            raise EvidenceError(f"cannot resolve dependency coordinate {g}:{a}")
        optional = d.findtext("optional", "false").strip().lower()
        scope = d.findtext("scope", "compile").strip()
        kind = d.findtext("type", "jar").strip()
        classifier = d.findtext("classifier", "").strip()
        if optional not in ("true", "false") or any("${" in field for field in (scope, kind, classifier)):
            raise EvidenceError(f"cannot resolve dependency qualifiers {g}:{a}")
        result.append(Dependency(g, a, d.findtext("version", "").strip(), scope, kind,
                                 classifier, optional == "true"))
    return result


def layout():
    base = Path(AGGREGATOR)
    children = (COMMON, *(PREFIX + d for d in DOMAINS[:5]), PREFIX + "analysis-biz",
                PREFIX + "event", PREFIX + "integration-biz")
    result = {base: ("yudao", children)}
    for name in children:
        parent = base / name
        pair = (name + "-api", name + "-biz") if name.removeprefix(PREFIX) in DOMAINS else ()
        result[parent] = (AGGREGATOR, pair)
        for item in pair:
            result[parent / item] = (name, ())
    return result


def modules(pom):
    paths = tuple((node.text or "").strip() for node in pom.findall("modules/module"))
    if any(not path or "${" in path for path in paths):
        raise EvidenceError(f"{pom.findtext('artifactId') or pom.findtext('id')}: unresolved module path")
    return paths


def cycle_paths(graph):
    state, stack, cycles = {}, [], []

    def visit(node):
        state[node] = 1
        stack.append(node)
        for target in sorted(graph[node]):
            if state.get(target) == 1:
                cycles.append(stack[stack.index(target):] + [target])
            elif target in graph and target not in state:
                visit(target)
        stack.pop()
        state[node] = 2

    for node in sorted(graph):
        if node not in state:
            visit(node)
    return cycles


def forbidden_target(consumer, d, allowed):
    if consumer == GATEWAY and d.artifact == SERVER:
        return True
    if d.artifact.startswith(PREFIX):
        return d.group != GROUP or d.artifact not in allowed
    return consumer in CANDIDATE


def check_direct(consumer, declared, errors, context, approved=True):
    candidate = CANDIDATE.get(consumer, set())
    minimum = MINIMUM.get(consumer, set())
    allowed = minimum if approved else candidate
    for d in declared:
        if forbidden_target(consumer, d, candidate):
            errors.append(f"FORBIDDEN {context}: {consumer} -> {d.label()}")
        elif d.artifact.startswith(PREFIX) and (
                d.artifact not in allowed or d.optional or d.scope != "compile"
                or d.kind != "jar" or d.classifier):
            errors.append(f"UNAPPROVED {context}: {consumer} -> {d.label()}, optional={d.optional}")
    if approved and consumer in CANDIDATE:
        if len(declared) != len(minimum) or {d.artifact for d in declared} != minimum:
            errors.append(f"MINIMUM {context}: {consumer} requires exactly {sorted(minimum)}")
    if consumer == SERVER and {d.artifact for d in declared if d.group == GROUP} != SERVER_DIRECT:
        errors.append(f"ASSEMBLY {context}: {SERVER} project dependencies changed")


def pom_profiles(pom, name, group, errors, strict=False):
    for profile in pom.findall("profiles/profile"):
        profile_id = profile.findtext("id", "<unknown>")
        declared = deps(profile, group)
        if strict or any(d.artifact.startswith(PREFIX) for d in declared):
            errors.append(f"PROFILE {name}: {profile_id} introduces an unapproved profile or ring edge")
        if name == GATEWAY and any(d.artifact == SERVER for d in declared):
            errors.append(f"PROFILE {name}: {profile_id} introduces a Server edge")
        if any("sanitation" in item for item in modules(profile)):
            errors.append(f"PROFILE {name}: {profile_id} changes sanitation Reactor membership")


def reactor(root, expected, errors):
    seen = {}

    def visit(relative, parents):
        path = (root / relative / "pom.xml").resolve()
        if not path.is_relative_to(root.resolve()) or path in parents:
            raise EvidenceError(f"{path}: invalid or cyclic module path")
        pom = read_pom(path)
        artifact = pom.findtext("artifactId")
        if not artifact:
            raise EvidenceError(f"{path}: missing artifactId")
        if artifact in seen:
            errors.append(f"DUPLICATE artifactId {artifact}: {path} and {seen[artifact]}")
            return
        seen[artifact] = path
        for child in modules(pom):
            visit(relative / child, parents | {path})

    visit(Path("."), set())
    if len(seen) != expected:
        errors.append(f"REACTOR {root}: expected {expected} projects, observed {len(seen)}")
    return seen


def audit_poms(root, iot, errors):
    default_xml = read_pom(root / "pom.xml")
    iot_xml = read_pom(iot / "pom.xml")
    if modules(default_xml) != ROOT_MODULES or set(modules(iot_xml)) != {*ROOT_MODULES, IOT}:
        errors.append("REACTOR default or isolated IoT module list differs from approved membership")
    revision = default_xml.findtext("properties/revision")
    if not revision or iot_xml.findtext("properties/revision") != revision:
        raise EvidenceError("missing or inconsistent root revision")
    for name, tree in (("default root", default_xml), ("isolated root", iot_xml)):
        pom_profiles(tree, name, GROUP, errors)
    default_paths, iot_paths = reactor(root, 44, errors), reactor(iot, 48, errors)
    declared = {}
    graph = {}
    for relative, (parent, children) in layout().items():
        original = root / relative / "pom.xml"
        copy = iot / relative / "pom.xml"
        if original.read_bytes() != copy.read_bytes():
            errors.append(f"SOURCE {relative}: default and isolated POM differ")
        pom = read_pom(original)
        expected_parent = (GROUP, parent, "${revision}", "../pom.xml")
        if (
            tuple(pom.findtext("parent/" + tag) for tag in ("groupId", "artifactId", "version", "relativePath"))
            != expected_parent or pom.findtext("artifactId") != relative.name
            or pom.findtext("packaging") != ("pom" if children else "jar") or modules(pom) != children
            or any(pom.find(tag) is not None for tag in ("groupId", "version", "properties", "dependencyManagement", "build"))
            or read_pom(original.parent / "../pom.xml").findtext("artifactId") != parent
        ):
            errors.append(f"POM {relative}: parent, inheritance, coordinates or children differ")
    expected_paths = {root / relative / "pom.xml" for relative in layout()}
    if set((root / AGGREGATOR).rglob("pom.xml")) != expected_paths:
        errors.append("POM missing or unexpected POM under sanitation-modules")
    combined = {**iot_paths, **default_paths}
    for name, path in sorted(combined.items()):
        pom = read_pom(path)
        group = pom.findtext("groupId") or pom.findtext("parent/groupId") or GROUP
        declared[name] = deps(pom, group)
        graph[name] = {d.artifact for d in declared[name] if d.group == GROUP}
        check_direct(name, declared[name], errors, str(path))
        pom_profiles(pom, name, group, errors, strict=name.startswith(PREFIX))
        if name.startswith(PREFIX) and any(d.version != "${revision}" for d in declared[name]):
            errors.append(f"POM {path}: dependencies must inherit revision")
    for cycle in cycle_paths(graph):
        errors.append("CYCLE " + " -> ".join(cycle))
    print(f"POM: {len(default_paths)} default / {len(iot_paths)} isolated assembled projects; graph checked")
    return revision, declared, default_paths, iot_paths


def parse_tree(path, consumer, revision):
    tree = json.loads(path.read_text())
    if not isinstance(tree, dict) or (
            tree.get("groupId"), tree.get("artifactId"), tree.get("version"), tree.get("type")
    ) != (GROUP, consumer, revision, "jar"):
        raise EvidenceError(f"{path}: unexpected Maven dependency tree root")
    return tree


def dependency_identity(d):
    return (d.group, d.artifact, d.scope, d.kind, d.classifier, d.optional)


def tree_dependencies(node, ancestors):
    children = node.get("children", [])
    if not isinstance(children, list):
        raise EvidenceError(f"{ancestors}: invalid dependency children")
    for child in children:
        if not isinstance(child, dict) or not all(
            isinstance(child.get(k), str) and child[k] for k in ("groupId", "artifactId", "version", "type", "scope")
        ):
            raise EvidenceError(f"{ancestors}: invalid dependency coordinate")
        classifier = child.get("classifier", "")
        optional = str(child.get("optional", "false")).lower()
        if not isinstance(classifier, str) or optional not in ("true", "false"):
            raise EvidenceError(f"{ancestors}: invalid dependency qualifiers")
        dep = Dependency(child["groupId"], child["artifactId"], child["version"], child["scope"],
                         child["type"], classifier, optional == "true")
        chain = (*ancestors, dep.artifact)
        yield chain, dep
        yield from tree_dependencies(child, chain)


def check_tree(consumer, rows, revision, errors):
    allowed = MINIMUM.get(consumer, set())
    direct_ring = set()
    for chain, dep in rows:
        ring = dep.artifact.startswith(PREFIX)
        if forbidden_target(consumer, dep, allowed) or (ring and (
                dep.version != revision or dep.optional or dep.scope != "compile"
                or dep.kind != "jar" or dep.classifier)):
            errors.append(f"TREE {' -> '.join(chain)}: {dep.label()}")
        if ring and len(chain) == 2:
            direct_ring.add(dep.artifact)
    if direct_ring != allowed:
        errors.append(f"TREE {consumer}: expected direct sanitation edges {sorted(allowed)}, got {sorted(direct_ring)}")


def effective_dependencies(path, consumer, revision, errors):
    pom = read_pom(path)
    plugin = next((node for node in pom.findall("build/plugins/plugin")
                   if node.findtext("artifactId") == "maven-compiler-plugin"), None)
    if (
        (pom.findtext("groupId"), pom.findtext("artifactId"), pom.findtext("version"),
         pom.findtext("packaging", "jar"), pom.findtext("properties/maven.compiler.release"))
        != (GROUP, consumer, revision, "jar", "17")
        or plugin is None or plugin.findtext("version") != "3.14.0"
    ):
        errors.append(f"EFFECTIVE {path}: coordinates, compiler or Java release differ")
    declared = deps(pom, GROUP)
    check_direct(consumer, declared, errors, str(path))
    if consumer in CANDIDATE and any(d.version != revision for d in declared):
        errors.append(f"EFFECTIVE {path}: direct dependency revision differs")
    return declared


def audit_dependency_evidence(evidence, revision, declared, errors):
    parsed = {}
    for name in sorted(API | BIZ | {COMMON, SERVER, GATEWAY}):
        parent = evidence / ("iot" if name == GATEWAY else "default")
        effective = effective_dependencies(parent / f"{name}-effective.xml", name, revision, errors)
        tree = parse_tree(parent / f"{name}-tree.json", name, revision)
        rows = list(tree_dependencies(tree, (name,)))
        check_tree(name, rows, revision, errors)
        # Maven mediation can change an inherited version. Preserve identity and scope in both views.
        actual = {dependency_identity(d) for chain, d in rows if len(chain) == 2}
        if {dependency_identity(d) for d in effective} != actual:
            errors.append(f"TREE {name}: resolved direct identities differ from effective POM")
        if {dependency_identity(d) for d in declared[name]} != {dependency_identity(d) for d in effective}:
            errors.append(f"EFFECTIVE {name}: inherited or active-profile direct dependencies differ from approved POM")
        parsed[name] = rows
        print(f"TREE {name}: {len(effective)} direct declarations, {len(rows)} resolved nodes")
    return parsed


LEXICAL = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')
QUALIFIED = re.compile(r"\b(?:[A-Za-z_$][\w$]*\s*\.\s*)+[A-Za-z_$][\w$]*(?:\s*\.\s*\*)?")
PERSISTENCE = ("java.sql.", "javax.sql.", "jakarta.persistence.", "javax.persistence.",
               "org.mybatis.", "org.apache.ibatis.", "com.baomidou.", "org.springframework.jdbc.",
               "org.hibernate.", "com.mysql.", "org.postgresql.", "org.sqlite.")
INTERNAL_PACKAGES = {"biz", "controller", "dal", "mapper", "dataobject", "serviceimpl"}
IMPLEMENTATION_SUFFIXES = ("Mapper", "DO", "ServiceImpl", "ApiImpl", "Controller")


def package_prefix(name):
    if name == COMMON:
        return PACKAGE + ".common"
    domain, layer = name.removeprefix(PREFIX).rsplit("-", 1)
    return f"{PACKAGE}.{domain}.{layer}"


def internal_reference(consumer, target):
    if consumer in API and target.startswith(PERSISTENCE):
        return True
    if target.startswith(PACKAGE + "."):
        own = package_prefix(consumer) if consumer in CANDIDATE else ""
        if own and (target == own or target.startswith(own + ".")):
            return False
        for api in MINIMUM.get(consumer, set()):
            prefix = package_prefix(api)
            if target == prefix or target.startswith(prefix + "."):
                parts = target[len(prefix):].split(".")
                return bool(set(parts) & INTERNAL_PACKAGES
                            or any(part.endswith(IMPLEMENTATION_SUFFIXES) for part in parts))
        return True
    if consumer in CANDIDATE and target.startswith("cn.iocoder.yudao.module."):
        # No upstream business adapter or public type has been approved for this skeleton.
        return True
    return consumer == GATEWAY and target.startswith("cn.iocoder.yudao.server.")


def check_java(path, consumer, errors):
    source = LEXICAL.sub(lambda m: re.sub(r"[^\n]", " ", m[0]), path.read_text())
    package = re.search(r"\bpackage\s+([\w.]+)\s*;", source)
    if consumer in CANDIDATE:
        expected = package_prefix(consumer)
        if not package or not (package[1] == expected or package[1].startswith(expected + ".")):
            errors.append(f"PACKAGE {path}: expected {expected}")
        elif consumer in API and set(package[1][len(expected):].split(".")) & INTERNAL_PACKAGES:
            errors.append(f"PACKAGE {path}: internal package in API")
    for match in QUALIFIED.finditer(source):
        target = re.sub(r"\s", "", match[0])
        if internal_reference(consumer, target):
            line = source.count("\n", 0, match.start()) + 1
            errors.append(f"JAVA {path}:{line}: {consumer} -> {target}")
    if consumer in API:
        for declared in re.finditer(r"\b(?:class|interface|record|enum)\s+(\w+)", source):
            if declared[1].endswith(IMPLEMENTATION_SUFFIXES):
                errors.append(f"JAVA {path}: implementation type {declared[1]} in API")


def class_version(data, label, errors):
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        raise EvidenceError(f"{label}: invalid class header")
    major = struct.unpack_from(">H", data, 6)[0]
    if major != 61:
        errors.append(f"CLASS {label}: expected major 61, found {major}")


def archive(path, consumer, entry, errors):
    with ZipFile(path) as jar:
        names = jar.namelist()
        manifest = jar.read("META-INF/MANIFEST.MF").decode().replace("\r\n", "\n").replace("\n ", "")
        headers = dict(line.split(": ", 1) for line in manifest.split("\n\n")[0].splitlines()
                       if ": " in line)
        if entry:
            starter = "BOOT-INF/classes/" + entry.replace(".", "/") + ".class"
            loader = BOOT_LOADER.replace(".", "/") + ".class"
            if (headers.get("Main-Class"), headers.get("Start-Class")) != (BOOT_LOADER, entry) or (
                    starter not in names or loader not in names
                    or not any(n.startswith("BOOT-INF/lib/") and n.endswith(".jar") for n in names)):
                errors.append(f"ARCHIVE {path}: missing independent Spring Boot entry or runtime libraries")
        elif "Main-Class" in headers or any(n.startswith("BOOT-INF/") for n in names):
            errors.append(f"ARCHIVE {path}: expected a regular library JAR")
        if consumer in (SERVER, GATEWAY):
            for name in names:
                if (name.startswith("BOOT-INF/lib/" + PREFIX)
                        or name.startswith("BOOT-INF/classes/" + PACKAGE.replace(".", "/") + "/")
                        or (consumer == GATEWAY and name.startswith("BOOT-INF/lib/yudao-server-"))):
                    errors.append(f"ARCHIVE {path}: forbidden runtime content {name}")
        classes = [name for name in names if name.endswith(".class") and (
            name.startswith("BOOT-INF/classes/") and not name.startswith("BOOT-INF/classes/META-INF/")
            if entry else not name.startswith("META-INF/")
        )]
        for name in classes:
            class_version(jar.read(name)[:8], f"{path}!{name}", errors)
            if consumer in CANDIDATE:
                prefix = package_prefix(consumer).replace(".", "/") + "/"
                if not name.startswith(prefix):
                    errors.append(f"CLASS {path}!{name}: outside the approved module package")
                elif consumer in API:
                    parts = name[len(prefix):-6].split("/")
                    if set(parts) & INTERNAL_PACKAGES or parts[-1].split("$")[0].endswith(IMPLEMENTATION_SUFFIXES):
                        errors.append(f"CLASS {path}!{name}: implementation type in API")
        return len(classes)


def jdeps_references(text, consumer, errors):
    unresolved = set()
    for line in text.splitlines():
        match = re.match(r"\s+(\S+)\s+->\s+(\S+)\s+(.+)", line)
        if not match:
            continue
        source, target, location = match.groups()
        if internal_reference(consumer, target):
            errors.append(f"BYTECODE {consumer}: {source} -> {target} ({location})")
        if location.strip() == "not found":
            unresolved.add(target)
    return unresolved


def dependency_file(repo, dep):
    extension = {"test-jar": "jar", "bundle": "jar", "maven-plugin": "jar"}.get(dep.kind, dep.kind)
    classifier = dep.classifier or ("tests" if dep.kind == "test-jar" else "")
    suffix = "-" + classifier if classifier else ""
    return (repo.joinpath(*dep.group.split("."), dep.artifact, dep.version)
            / f"{dep.artifact}-{dep.version}{suffix}.{extension}")


def project_classes(classes):
    return (path for path in classes.rglob("*.class")
            if "META-INF" not in path.relative_to(classes).parts)


def audit_jdeps(classes, consumer, rows, repo, executable, errors):
    if not any(project_classes(classes)):
        print(f"BYTECODE {consumer}: N/A (no generated project classes)")
        return
    files = {dependency_file(repo, dep) for _, dep in rows if dep.kind != "pom"}
    missing = sorted(path for path in files if not path.is_file())
    if missing:
        raise EvidenceError(f"BYTECODE {consumer}: missing resolved classpath file {missing[0]}")
    result = subprocess.run(
        [str(executable), "-verbose:class", "-filter:none", "--multi-release", "17",
         "--ignore-missing-deps", "--class-path", os.pathsep.join(map(str, sorted(files))), str(classes)],
        capture_output=True, text=True, timeout=90, check=False,
    )
    if result.returncode or any(key in result.stderr for key in ("not found", "Warning:")):
        raise EvidenceError(f"jdeps {consumer} ({result.returncode}): {result.stderr.strip() or result.stdout.strip()}")
    unresolved = jdeps_references(result.stdout, consumer, errors)
    if unresolved:
        raise EvidenceError(f"BYTECODE {consumer}: unresolved referenced types: {', '.join(sorted(unresolved))}")
    print(f"BYTECODE {consumer}: jdeps class dependencies checked")


def audit_artifacts(args, build_paths, rows, revision, errors):
    for kind, projects in zip(("default", "iot"), build_paths, strict=True):
        total = 0
        for path in projects.values():
            for cls in project_classes(path.parent / "target/classes"):
                with cls.open("rb") as stream:
                    class_version(stream.read(8), cls, errors)
                total += 1
        if not total:
            raise EvidenceError(f"{kind}: no built upstream project classes")
        print(f"CLASS {kind}: {total} built project class headers checked")

    default_paths, iot_paths = build_paths
    for name in sorted(API | BIZ | {COMMON, SERVER, GATEWAY}):
        iot = name == GATEWAY
        paths = iot_paths if iot else default_paths
        path = paths[name].parent
        for source_set in ("main", "test"):
            for source in (path / "src" / source_set / "java").rglob("*.java"):
                check_java(source, name, errors)
        target = path / "target"
        if name == GATEWAY:
            plain_count = archive(target / f"{name}.jar", name, None, errors)
            jar, entry = target / f"{name}-exec.jar", GATEWAY_BOOT
        elif name == SERVER:
            jar, entry = target / f"{name}.jar", SERVER_BOOT
        else:
            jar, entry = target / f"{name}-{revision}.jar", None
        count = archive(jar, name, entry, errors)
        print(f"JAR {jar}: {count} project classes")
        classes = target / "classes"
        if count != sum(1 for _ in project_classes(classes)) or (iot and plain_count != count):
            raise EvidenceError(f"CLASS {name}: class counts differ between build output and archive")
        audit_jdeps(classes, name, rows[name], args.iot_repo if iot else args.default_repo,
                    args.jdeps, errors)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="built source tree for default Reactor")
    parser.add_argument("--mode", choices=("default",), default="default", help="approved scheme A")
    parser.add_argument("--iot-root", type=Path, required=True, help="built isolated source tree with IoT enabled")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--default-repo", type=Path, required=True, help="Maven local repository used for default build")
    parser.add_argument("--iot-repo", type=Path, required=True, help="Maven local repository used for isolated IoT build")
    parser.add_argument("--jdeps", type=Path, default=Path("jdeps"), help="JDK 21 jdeps executable")
    args = parser.parse_args(argv)
    errors = []
    try:
        revision, declared, default_paths, iot_paths = audit_poms(args.root.resolve(), args.iot_root.resolve(), errors)
        if errors:
            for error in errors:
                print(error, file=sys.stderr)
            return 1
        rows = audit_dependency_evidence(args.evidence_dir, revision, declared, errors)
        if not errors:
            audit_artifacts(args, (default_paths, iot_paths), rows, revision, errors)
    except (OSError, ValueError, KeyError, BadZipFile, subprocess.SubprocessError) as exc:
        for error in errors:
            print(error, file=sys.stderr)
        print(f"EVIDENCE INCOMPLETE: {exc}", file=sys.stderr)
        return 2
    print("Coverage: lexical references and statically linked bytecode; reflection, runtime wiring,"
          " wildcard symbol binding and generated code without class evidence require separate review.")
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("ENG-002B boundary audit passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
