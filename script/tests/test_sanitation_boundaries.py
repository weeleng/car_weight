"""Synthesized fixtures for the boundary rules, separate from real Maven evidence."""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile


CHECKER = Path(__file__).resolve().parents[1] / "shell/check-sanitation-boundaries.py"
SPEC = importlib.util.spec_from_file_location("sanitation_boundaries", CHECKER)
audit = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = audit
SPEC.loader.exec_module(audit)

REVISION = "2026.10-SNAPSHOT"


def dependency(name, **options):
    return audit.Dependency(audit.GROUP, name, REVISION, **options)


def node(name, *children, group=audit.GROUP):
    return {
        "groupId": group, "artifactId": name, "version": REVISION, "type": "jar",
        "scope": "compile", "optional": "false", "children": list(children),
    }


def write_pom(root, path, name, body=""):
    dest = root / path / "pom.xml"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(f'<project xmlns="{audit.POM_NS}"><artifactId>{name}</artifactId>{body}</project>')
    return dest


def modules(names):
    return "<modules>" + "".join(f"<module>{name}</module>" for name in names) + "</modules>" if names else ""


def declaration(name, version="${revision}"):
    return (f"<dependency><groupId>{audit.GROUP}</groupId><artifactId>{name}</artifactId>"
            f"<version>{version}</version></dependency>")


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.errors = []
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)

    def direct(self, source, *targets, approved=True):
        audit.check_direct(source, list(targets), self.errors, "fixture POM", approved=approved)

    def tree(self, source, *children):
        rows = list(audit.tree_dependencies(node(source, *children), (source,)))
        audit.check_tree(source, rows, REVISION, self.errors)

    def test_minimum_graph_and_resolved_dependencies(self):
        graph = {}
        for source in sorted(audit.CANDIDATE):
            names = audit.MINIMUM.get(source, set())
            self.direct(source, *map(dependency, names))
            self.tree(source, *map(node, names))
            graph[source] = names
        self.assertEqual(audit.cycle_paths(graph), [])
        self.assertEqual(self.errors, [])

    def test_candidate_public_api_and_common_do_not_grant_approval(self):
        self.direct("sanitation-gis-biz", dependency("sanitation-base-api"),
                    dependency("sanitation-common"), approved=False)
        self.assertEqual(self.errors, [])
        self.direct("sanitation-gis-biz", dependency("sanitation-gis-api"),
                    dependency("sanitation-base-api"))
        self.assertIn("UNAPPROVED", "\n".join(self.errors))

    def test_api_cannot_depend_on_biz(self):
        self.direct("sanitation-base-api", dependency("sanitation-base-biz", scope="provided", optional=True))
        self.assertIn("FORBIDDEN", "\n".join(self.errors))

    def test_cross_domain_biz(self):
        self.direct("sanitation-base-biz", dependency("sanitation-base-api"), dependency("sanitation-operation-biz"))
        self.assertIn("FORBIDDEN", "\n".join(self.errors))

    def test_common_cannot_depend_on_domain(self):
        self.direct(audit.COMMON, dependency("sanitation-device-api"))
        self.assertIn("FORBIDDEN", "\n".join(self.errors))

    def test_two_node_cycle(self):
        self.assertEqual(audit.cycle_paths({"A": {"B"}, "B": {"A"}}), [["A", "B", "A"]])

    def test_multi_node_cycle(self):
        self.assertEqual(audit.cycle_paths({"A": {"B"}, "B": {"C"}, "C": {"A"}}), [["A", "B", "C", "A"]])

    def test_gateway_no_sanitation_biz_or_server(self):
        self.direct(audit.GATEWAY, dependency("sanitation-device-biz"), dependency(audit.SERVER))
        self.assertEqual(sum(e.startswith("FORBIDDEN") for e in self.errors), 2)

    def test_bridge_tree_cannot_hide_other_biz(self):
        self.tree("sanitation-operation-biz", node("sanitation-operation-api",
                  node("bridge", node("sanitation-billing-biz"), group="fixture.bridge")))
        self.assertTrue(any("sanitation-operation-api -> bridge -> sanitation-billing-biz" in e for e in self.errors))

    def test_server_existing_assembly(self):
        self.direct(audit.SERVER, *map(dependency, audit.SERVER_DIRECT))
        self.tree(audit.SERVER, *map(node, audit.SERVER_DIRECT))
        self.assertEqual(self.errors, [])

    def test_server_indirect_assembly_regression(self):
        self.tree(audit.SERVER, node("yudao-module-infra", node("sanitation-operation-biz")))
        self.assertTrue(any("yudao-server -> yudao-module-infra -> sanitation-operation-biz" in e for e in self.errors))

    def test_server_jar_cannot_smuggle_sanitation_biz(self):
        path = self.root / "server.jar"
        with ZipFile(path, "w") as z:
            z.writestr("META-INF/MANIFEST.MF",
                       f"Main-Class: {audit.BOOT_LOADER}\r\nStart-Class: {audit.SERVER_BOOT}\r\n\r\n")
            z.writestr("BOOT-INF/classes/" + audit.SERVER_BOOT.replace(".", "/") + ".class",
                       bytes.fromhex("cafebabe0000003d"))
            z.writestr(audit.BOOT_LOADER.replace(".", "/") + ".class", b"")
            z.writestr("BOOT-INF/lib/sanitation-operation-biz-2026.10-SNAPSHOT.jar", b"")
        audit.archive(path, audit.SERVER, audit.SERVER_BOOT, self.errors)
        self.assertTrue(any("forbidden runtime content" in e for e in self.errors))

    def test_illegal_java_import_static_import_and_fully_qualified_name(self):
        path = self.root / "Example.java"
        path.write_text("""
            package cn.iocoder.yudao.module.sanitation.operation.biz;
            import cn.iocoder.yudao.module.sanitation.base.biz.dal.dataobject.OtherDO;
            import static cn.iocoder.yudao.module.sanitation.base.biz.dal.OtherMapper.call;
            class Example {
                cn.iocoder.yudao.module.sanitation.device.biz.service.DeviceServiceImpl other;
                String ignored = "cn.iocoder.yudao.module.sanitation.gis.biz.Test";
            }
        """)
        audit.check_java(path, "sanitation-operation-biz", self.errors)
        self.assertEqual(len(self.errors), 3)

    def test_own_api_import_and_comment(self):
        path = self.root / "Example.java"
        path.write_text("""
            package cn.iocoder.yudao.module.sanitation.operation.biz;
            import cn.iocoder.yudao.module.sanitation.operation.api.OperationApi;
            // cn.iocoder.yudao.module.sanitation.base.biz.BaseImpl
            class Example {}
        """)
        audit.check_java(path, "sanitation-operation-biz", self.errors)
        self.assertEqual(self.errors, [])

    def test_api_cannot_import_persistence(self):
        path = self.root / "Example.java"
        path.write_text("package cn.iocoder.yudao.module.sanitation.base.api; import java.sql.Connection;")
        audit.check_java(path, "sanitation-base-api", self.errors)
        self.assertTrue(any("java.sql.Connection" in e for e in self.errors))

    def test_own_api_dependency_does_not_allow_hidden_implementation(self):
        target = "cn.iocoder.yudao.module.sanitation.operation.api.dal.SomeDO"
        self.assertTrue(audit.internal_reference("sanitation-operation-biz", target))

    def test_profile_and_scope_cannot_bypass(self):
        source = ("<profiles><profile><id>hidden</id><dependencies>"
                  + declaration("sanitation-billing-biz")
                  + "</dependencies></profile></profiles>")
        p = write_pom(self.root, Path("."), audit.SERVER, source)
        audit.pom_profiles(audit.read_pom(p), audit.SERVER, audit.GROUP, self.errors)
        self.direct("sanitation-base-biz", dependency("sanitation-base-api", scope="test", optional=True))
        self.assertTrue(any("PROFILE" in e for e in self.errors))
        self.assertTrue(any("UNAPPROVED" in e for e in self.errors))

    def test_jdeps_path_and_class_major(self):
        missing = audit.jdeps_references(
            "   a.B -> cn.iocoder.yudao.module.sanitation.base.biz.Foo not found\n",
            "sanitation-operation-biz", self.errors,
        )
        self.assertIn("cn.iocoder.yudao.module.sanitation.base.biz.Foo", missing)
        audit.class_version(bytes.fromhex("cafebabe00000041"), "fixture", self.errors)
        self.assertEqual(len(self.errors), 2)


class EntrypointTests(unittest.TestCase):
    def setUp(self):
        tmp = TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        base = Path(tmp.name)
        self.root, self.iot, self.evidence = (base / name for name in ("default", "iot", "evidence"))
        self.args = ["--root", str(self.root), "--iot-root", str(self.iot), "--evidence-dir", str(self.evidence),
                     "--default-repo", str(base), "--iot-repo", str(base)]
        root_props = f"<properties><revision>{REVISION}</revision></properties>"
        write_pom(self.root, Path("."), "yudao", root_props + modules(audit.ROOT_MODULES))
        parents = audit.layout()
        for part in audit.ROOT_MODULES:
            if part != audit.AGGREGATOR:
                write_pom(self.root, Path(part), part)
        for article in audit.SERVER_DIRECT:
            if article.startswith("yudao-spring-"):
                write_pom(self.root, Path("yudao-framework") / article, article)
        fw = (next(a for a in audit.SERVER_DIRECT if a.startswith("yudao-spring-")),
              *(f"framework-{n}" for n in range(15)))
        write_pom(self.root, Path("yudao-framework"), "yudao-framework", modules(fw))
        for name in fw[1:]:
            write_pom(self.root, Path("yudao-framework") / name, name)
        write_pom(self.root, Path(audit.SERVER), audit.SERVER,
                  "<dependencies>" + "".join(map(declaration, sorted(audit.SERVER_DIRECT))) + "</dependencies>")
        for path, (parent, names) in parents.items():
            body = (f"<parent><groupId>{audit.GROUP}</groupId><artifactId>{parent}</artifactId>"
                    "<version>${revision}</version><relativePath>../pom.xml</relativePath></parent>"
                    f"<packaging>{'pom' if names else 'jar'}</packaging>" + modules(names))
            if path.name in audit.MINIMUM:
                body += "<dependencies>" + "".join(map(declaration, audit.MINIMUM[path.name])) + "</dependencies>"
            write_pom(self.root, path, path.name, body)
        shutil.copytree(self.root, self.iot)
        write_pom(self.iot, Path("."), "yudao", root_props + modules((*audit.ROOT_MODULES, audit.IOT)))
        iot_names = ("yudao-module-iot-core", "yudao-module-iot-biz", audit.GATEWAY)
        write_pom(self.iot, Path(audit.IOT), audit.IOT, modules(iot_names))
        for name in iot_names:
            write_pom(self.iot, Path(audit.IOT) / name, name)

        for name in audit.API | audit.BIZ | {audit.COMMON, audit.SERVER, audit.GATEWAY}:
            folder = self.evidence / ("iot" if name == audit.GATEWAY else "default")
            folder.mkdir(parents=True, exist_ok=True)
            edges = audit.SERVER_DIRECT if name == audit.SERVER else audit.MINIMUM.get(name, set())
            (folder / f"{name}-tree.json").write_text(json.dumps(node(name, *map(node, edges))))
            sections = (f"<groupId>{audit.GROUP}</groupId><version>{REVISION}</version>"
                        "<properties><maven.compiler.release>17</maven.compiler.release></properties>"
                        "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                        "<version>3.14.0</version></plugin></plugins></build>")
            if edges:
                sections += "<dependencies>" + "".join(declaration(edge, REVISION) for edge in edges) + "</dependencies>"
            temp = write_pom(folder, Path(name), name, sections)
            temp.rename(folder / f"{name}-effective.xml")

    def invoke(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = audit.main(self.args)
        return rc, out.getvalue(), err.getvalue()

    def test_missing_dependency_tree_exits_two(self):
        (self.evidence / "default/sanitation-common-tree.json").unlink()
        rc, _, err = self.invoke()
        self.assertEqual(rc, 2)
        self.assertIn("sanitation-common-tree.json", err)

    def test_unparseable_pom_exits_two(self):
        (self.root / "pom.xml").write_text("<project>")
        rc, _, err = self.invoke()
        self.assertEqual(rc, 2)
        self.assertIn("EVIDENCE INCOMPLETE", err)

    def test_empty_module_path_exits_two(self):
        path = self.root / "pom.xml"
        path.write_text(path.read_text().replace("<module>yudao-server</module>", "<module/>"))
        rc, _, err = self.invoke()
        self.assertEqual(rc, 2)
        self.assertIn("unresolved module path", err)

    def test_unparseable_tree_exits_two(self):
        (self.evidence / "default/sanitation-common-tree.json").write_text("{")
        rc, _, err = self.invoke()
        self.assertEqual(rc, 2)
        self.assertIn("EVIDENCE INCOMPLETE", err)

    def test_illegal_server_edge_exits_one(self):
        bad = node(audit.SERVER, *map(node, audit.SERVER_DIRECT), node("sanitation-device-biz"))
        (self.evidence / "default/yudao-server-tree.json").write_text(json.dumps(bad))
        rc, _, err = self.invoke()
        self.assertEqual(rc, 1)
        self.assertIn("yudao-server -> sanitation-device-biz", err)


if __name__ == "__main__":
    unittest.main()
