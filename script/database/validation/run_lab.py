#!/usr/bin/env python3
"""Run rollout, migration failure and isolated database integration checks.

Requires Docker (mysql:8.4), JDK 21 and Maven 3.9.12. Use an empty evidence
directory. Maven flags after -- apply to the standalone tool only.
"""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time

TOOLS = Path(__file__).resolve().parents[1]
RUNNER = TOOLS / "migration-runner"
FIXTURES = TOOLS / "validation/fixtures"
DOMAINS = ("ingest", "business", "track")
KINDS = ("migrator", "runtime", "audit", "backup", "restore")


class Lab:
    def __init__(self, folder, maven_flags):
        self.folder = folder
        self.maven = ["mvn", "-B", "-ntp", "-C", "-f", str(RUNNER / "pom.xml"), *maven_flags]
        self.environ = dict(os.environ, ENG002C_ENV="isolated")
        self.environ["MYSQL_ROOT_PASSWORD"] = secrets.token_hex(24)
        for domain in DOMAINS:
            for kind in KINDS:
                self.environ[f"ENG002C_{domain.upper()}_{kind.upper()}_PASSWORD"] = secrets.token_hex(24)
        self.containers = []
        self.count = 0
        self.root = folder / "fixtures"
        shutil.copytree(FIXTURES, self.root)

    def command(self, label, argv, *, env=None, accepted=(0,), stdin=None):
        result = subprocess.run(argv, env=self.environ if env is None else env, text=True,
                                input=stdin, capture_output=True, timeout=180)
        (self.folder / (label + ".log")).write_text(result.stdout + result.stderr)
        self.record(label, result.returncode, accepted)
        return result.stdout.strip()

    def record(self, label, rc, accepted):
        status = "PASS" if rc in accepted else "FAIL"
        with (self.folder / "checks.tsv").open("a") as log:
            log.write(f"{status}\t{label}\t{rc}\n")
        print(f"{status} {label} exit={rc}", flush=True)
        if rc not in accepted:
            raise ValueError(f"unexpected result: {label}")
        self.count += 1

    def mysql(self, container, statement):
        return subprocess.check_output(
            ["docker", "exec", "-i", container, "sh", "-ec",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names'],
            input=statement, text=True, stderr=subprocess.DEVNULL,
        ).strip()

    def require(self, label, value):
        self.record(label, 0 if value else 1, (0,))

    def start(self, suffix, domains):
        container = "eng002c02-" + suffix + "-" + secrets.token_hex(4)
        self.command(f"docker-{suffix}", ["docker", "run", "-d", "--name", container,
                     "--label", "org.weeleng.eng002c=eng002c02-isolated", "-e", "MYSQL_ROOT_PASSWORD",
                     "-p", "127.0.0.1::3306", "mysql:8.4",
                     "--report-host=eng002c02-isolated",
                     "--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION"])
        self.containers.append(container)
        for _ in range(60):
            try:
                uuid = self.mysql(container, "SELECT @@server_uuid")
                port = subprocess.check_output(["docker", "port", container, "3306/tcp"], text=True).strip().rsplit(":", 1)[1]
                break
            except subprocess.SubprocessError:
                time.sleep(1)
        else:
            raise ValueError("MySQL lab did not become ready")
        # Once the image's temporary socket server exits the main server accepts TCP.
        for _ in range(30):
            try:
                subprocess.run(["docker", "exec", container, "sh", "-ec",
                                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -Nse "SELECT 1"'],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                break
            except subprocess.SubprocessError:
                time.sleep(1)
        else:
            raise ValueError("MySQL lab did not become available over TCP")
        argv = self.bootstrap_args(container, domains)
        self.command(f"bootstrap-{suffix}", argv)
        result = dict(self.environ)
        for domain in domains:
            prefix = f"ENG002C_{domain.upper()}_"
            result[prefix + "UUID"] = uuid
            result[prefix + "URL"] = f"jdbc:mysql://127.0.0.1:{port}/sanitation_{domain}"
        return container, result

    @staticmethod
    def bootstrap_args(container, domains):
        return [sys.executable, str(TOOLS / "bootstrap/bootstrap.py"), "--container", container,
                *(part for domain in domains for part in ("--domain", domain))]

    def runner_args(self, action, domain, root):
        return ["java", "-cp", f"{RUNNER / 'target/classes'}:{self.folder / 'lib'}/*",
                "cn.iocoder.sanitation.tools.Runner", action, domain, str(root)]

    def migrate(self, label, action, domain, env, root=None, accepted=(0,)):
        return self.command(label, self.runner_args(action, domain, root or self.root), env=env, accepted=accepted)

    def concurrent(self, env):
        # Hold the migration lock long enough for the second invocation to contend.
        (self.root / "ingest/V0002__extend_probe.sql").write_text(
            "DO SLEEP(2);\nALTER TABLE probe_raw ADD COLUMN synthetic_note INT NULL;\n")
        argv = self.runner_args("migrate", "ingest", self.root)
        outputs = []
        with subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as a, \
                subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as b:
            for n, process in enumerate((a, b), 1):
                out, _ = process.communicate(timeout=60)
                outputs.append(out)
                (self.folder / f"concurrent-{n}.log").write_text(out)
                self.record(f"concurrent-{n}", process.returncode, (0,))
        self.require("only-one-concurrent-application",
                     sum("applied=1" in s for s in outputs) == 1 and sum("applied=0" in s for s in outputs) == 1)

    def drift(self, env):
        path = self.root / "ingest/V0001__probe_tables.sql"
        original = path.read_text()
        path.write_text(original + "\n-- checksum drift fixture\n")
        try:
            self.migrate("checksum-drift", "validate", "ingest", env, accepted=(1,))
            path.unlink()
            self.migrate("missing-file", "validate", "ingest", env, accepted=(1,))
        finally:
            path.write_text(original)
        self.migrate("validate-restored-files", "validate", "ingest", env)

    def interrupted(self, container, env):
        (self.root / "ingest/V0003__interruption.sql").write_text(
            "CREATE TABLE probe_interrupted(id INT PRIMARY KEY) ENGINE=InnoDB;\nDO SLEEP(45);\n")
        argv = self.runner_args("migrate", "ingest", self.root)
        with subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
            for _ in range(60):
                thread = self.mysql(container, "SELECT ID FROM information_schema.PROCESSLIST "
                                    "WHERE USER='san_ingest_migrator' AND INFO LIKE 'DO SLEEP(45)%'")
                if thread.isdigit():
                    self.mysql(container, f"KILL CONNECTION {thread}")
                    break
                if proc.poll() is not None:
                    break
                time.sleep(0.25)
            out, _ = proc.communicate(timeout=55)
            (self.folder / "interrupted.log").write_text(out)
            self.require("migration-connection-interrupted", thread.isdigit())
            self.record("interrupted-migrate", proc.returncode, (1,))
        self.require("interrupted-ddl-residue", self.mysql(container,
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='sanitation_ingest' "
                     "AND TABLE_NAME='probe_interrupted'") == "1")
        self.migrate("interrupted-replay-refused", "migrate", "ingest", env, accepted=(1,))

    def failure_and_restore(self, primary, source_env, satellite, target_env):
        dump_env = dict(self.environ, MYSQL_PWD=self.environ["ENG002C_BUSINESS_BACKUP_PASSWORD"])
        dump = subprocess.check_output(
            ["docker", "exec", "-e", "MYSQL_PWD", primary, "mysqldump", "-h127.0.0.1",
             "-usan_business_backup", "--single-transaction", "--skip-lock-tables",
             "--no-tablespaces", "--skip-add-locks", "--skip-disable-keys",
             "--skip-triggers", "--set-gtid-purged=OFF", "sanitation_business"],
            env=dump_env, text=True, stderr=subprocess.DEVNULL)
        self.require("backup-role-produced-dump", "flyway_schema_history" in dump)
        path = self.root / "business/V0002__restore_drill.sql"
        path.write_text("CREATE TABLE probe_residue(id INT PRIMARY KEY) ENGINE=InnoDB;\n"
                        "DROP TABLE probe_record;\n")
        self.migrate("migrator-insufficient-drop", "migrate", "business", source_env, accepted=(1,))
        self.migrate("failed-history-refused", "validate", "business", source_env, accepted=(1,))
        self.require("failure-ddl-residue", self.mysql(primary, "SELECT COUNT(*) FROM information_schema.TABLES "
                     "WHERE TABLE_SCHEMA='sanitation_business' AND TABLE_NAME='probe_residue'") == "1")
        self.history(primary, "business", "primary-business-failed")
        # Restore to a fresh schema on the second server, preserving the failed source for inspection.
        env = dict(self.environ, MYSQL_PWD=self.environ["ENG002C_BUSINESS_RESTORE_PASSWORD"])
        self.command("restore-business-snapshot", ["docker", "exec", "-i", "-e", "MYSQL_PWD", satellite,
                     "mysql", "-usan_business_restore", "sanitation_business"], env=env, stdin=dump)
        self.require("restored-business-row-evidence", self.mysql(satellite,
                     "SELECT COUNT(*) FROM sanitation_business.probe_record WHERE id=30") == "1")
        path.write_text("CREATE TABLE probe_recovered(id INT PRIMARY KEY) ENGINE=InnoDB;\n")
        self.migrate("reviewed-correction-on-restored-copy", "migrate", "business", target_env)
        self.history(satellite, "business", "satellite-business-restored")

    def history(self, container, domain, label):
        rows = self.mysql(container, f"SELECT installed_rank, version, success FROM sanitation_{domain}."
                          "flyway_schema_history ORDER BY installed_rank")
        (self.folder / f"{label}-history.tsv").write_text(rows + "\n")
        return rows

    def run(self):
        endpoint = os.getenv("DOCKER_HOST") or subprocess.check_output(
            ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], text=True,
        ).strip()
        if not endpoint.startswith("unix:///"):
            raise ValueError("A local Unix Docker endpoint is required")
        self.command("toolchain", ["bash", str(TOOLS.parents[1] / "script/shell/check-build-toolchain.sh")])
        self.command("build-runner", [*self.maven, "-DskipTests", "package",
                     "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies",
                     "-DincludeScope=runtime", "-DoutputDirectory=" + str(self.folder / "lib")])
        lib = self.folder / "lib"
        self.require("pinned-flyway-artifacts", sorted(p.name for p in lib.glob("flyway-*.jar"))
                     == ["flyway-core-11.20.3.jar", "flyway-mysql-11.20.3.jar"])
        primary, original = self.start("primary", DOMAINS)
        satellite, alternate = self.start("satellite", ("business", "track"))
        self.command("bootstrap-repeat", self.bootstrap_args(primary, DOMAINS))
        self.require("separated-lab-roles-and-accounts", self.mysql(primary,
                     "SELECT COUNT(*) FROM mysql.user WHERE LEFT(User,4)='san_'") == "30")
        self.migrate("forbidden-clean", "clean", "ingest", original, accepted=(2,))
        invalid = dict(original, ENG002C_ENV="production")
        self.migrate("production-label-refused", "validate", "ingest", invalid, accepted=(2,))
        for domain in DOMAINS:
            self.migrate(f"empty-{domain}", "validate", domain, original, TOOLS.parents[1] / "db/migration")
            self.migrate(f"first-{domain}", "migrate", domain, original)
            out = self.migrate(f"repeat-{domain}", "migrate", domain, original)
            self.require(f"repeat-{domain}-applied-zero", "applied=0" in out)
        self.migrate("satellite-track-first", "migrate", "track", alternate)
        for name, domains in ((primary, DOMAINS), (satellite, ("track",))):
            self.command(f"grant-fixtures-{len(domains)}", [*self.bootstrap_args(name, domains), "--grant-fixtures"])
        original["ENG002C_ALT_TRACK_URL"] = alternate["ENG002C_TRACK_URL"]
        original["ENG002C_ALT_TRACK_UUID"] = alternate["ENG002C_TRACK_UUID"]
        self.command("spring-jdbc-mybatis-junit", [*self.maven, "test"], env=original)
        self.concurrent(original)
        self.drift(original)
        for domain in DOMAINS:
            self.history(primary, domain, "primary-" + domain)
        self.require("independent-version-progress", "2\t1" in self.history(primary, "ingest", "primary-ingest")
                     and all(row.split("\t")[1] != "0002" for row in
                             self.history(primary, "track", "primary-track").splitlines()))
        self.history(satellite, "track", "satellite-track")
        self.failure_and_restore(primary, original, satellite, alternate)
        self.interrupted(primary, original)
        self.history(primary, "ingest", "primary-ingest-interrupted")

    def close(self):
        for container in self.containers:
            subprocess.run(["docker", "rm", "-fv", container], stdout=subprocess.DEVNULL, check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--evidence-dir", required=True, type=Path)
    ap.add_argument("maven_flags", nargs=argparse.REMAINDER)
    args = ap.parse_args()
    path = args.evidence_dir.resolve()
    path.mkdir(parents=True, exist_ok=True)
    if any(path.iterdir()):
        ap.error("evidence directory must be empty")
    lab = Lab(path, [f for f in args.maven_flags if f != "--"])
    try:
        lab.run()
        print(f"Lab passed {lab.count} checks. JUnit counts are independent.")
        return 0
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
        print(f"Lab failed: {type(exc).__name__}. See local evidence.", file=sys.stderr)
        return 1
    finally:
        lab.close()


if __name__ == "__main__":
    sys.exit(main())
