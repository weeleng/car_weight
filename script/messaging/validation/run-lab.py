"""ENG-002E isolated acceptance. All service configuration is generated per run."""
import argparse
from collections import defaultdict
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
from urllib.parse import urlparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
MYSQL = "mysql@sha256:8c233bad33d520a7aae1c1e619c3a9775f4a1ba2d463d692fada04ec7f40be00"
RABBIT = "rabbitmq@sha256:d15c432b200a107c9602b9aafe6499b5ac7a56d870d367b53a7922f6f04e14df"
SUITES = {"A": "TransactionTest", "B": "PublishTest", "C": "ConsumerTest", "D": "RecoveryTest"}


class Lab:
    def __init__(self, evidence):
        self.evidence = evidence
        self.evidence.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.env = os.environ.copy()
        context = self.env.pop("DOCKER_CONTEXT", None)
        host = self.env.pop("DOCKER_HOST", None)
        if context or not host:
            host = subprocess.check_output(
                ["docker", "context", "inspect", *([context] if context else []),
                 "--format", "{{.Endpoints.docker.Host}}"], text=True, env=self.env).strip()
        if urlparse(host).scheme != "unix":
            raise RuntimeError("local Docker Unix socket required")
        self.base = ["docker", "--host", host]
        self.env["ENG002E_DOCKER_HOST"] = host
        self.run_id = "eng002e-" + secrets.token_hex(8)
        self.env["ENG002E_MARKER"] = self.run_id
        self.containers = {}
        self.ports = set()

    def docker(self, *args, **kwargs):
        return subprocess.run([*self.base, *args], text=True, capture_output=True,
                              env=self.env, **kwargs)

    def port(self):
        while True:
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
                if port not in self.ports and port not in (3306, 5672, 15672):
                    self.ports.add(port)
                    return str(port)

    def start(self, role, image, args):
        name = f"{self.run_id}-{role}"
        self.containers[role] = name
        result = self.docker("run", "-d", "--name", name, "--hostname", name,
                             "--label", f"org.weeleng.eng002e={self.run_id}", *args, image,
                             *(["--report-host=" + self.run_id] if role == "db" else []))
        if result.returncode:
            raise RuntimeError(f"{role} container startup failed: {result.stderr}")
        return name

    def wait(self, command):
        for _ in range(90):
            response = self.docker(*command)
            if response.returncode == 0:
                return response.stdout.strip()
            time.sleep(1)
        raise RuntimeError("isolated service did not become ready")

    def checked(self, *args, stdin=None):
        result = self.docker(*args, input=stdin)
        if result.returncode:
            raise RuntimeError(f"isolated command failed: {args[0]}")
        return result.stdout.strip()

    def provision(self):
        self.env.update(MYSQL_ROOT_PASSWORD=secrets.token_hex(24),
                        RABBITMQ_DEFAULT_USER="probe", RABBITMQ_DEFAULT_PASS=secrets.token_hex(24),
                        RABBITMQ_DEFAULT_VHOST="probe")
        db_port, mq_port, admin_port = self.port(), self.port(), self.port()
        db = self.start("db", MYSQL, ["-p", f"127.0.0.1:{db_port}:3306", "-e", "MYSQL_ROOT_PASSWORD"])
        mq = self.start("mq", RABBIT, ["-p", f"127.0.0.1:{mq_port}:5672", "-p", f"127.0.0.1:{admin_port}:15672",
                                      "-e", "RABBITMQ_DEFAULT_USER", "-e", "RABBITMQ_DEFAULT_PASS",
                                      "-e", "RABBITMQ_DEFAULT_VHOST"])
        # Probe the final network listener, not the image's bootstrap server.
        sql = ("exec", "-i", db, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -uroot -NB')
        identity = self.wait((*sql[:-1], sql[-1] + ' -e "SELECT VERSION(),@@server_uuid,@@report_host"'))
        version, uuid, marker = identity.split("\t")
        if not version.startswith("8.4.") or marker != self.run_id:
            raise RuntimeError("unexpected database identity")
        for schema in ("ingest", "business", "track"):
            password = secrets.token_hex(24)
            self.env[f"ENG002E_{schema.upper()}_PASSWORD"] = password
            ddl = (f"CREATE DATABASE probe_{schema}; "
                   f"CREATE USER '{schema}'@'%' IDENTIFIED BY '{password}'; "
                   f"GRANT SELECT,INSERT,UPDATE,DELETE ON probe_{schema}.* TO '{schema}'@'%'; "
                   f"USE probe_{schema};\n" + (ROOT / "fixtures" / schema / "schema.sql").read_text())
            self.checked(*sql, stdin=ddl)
        self.wait(("exec", mq, "rabbitmq-diagnostics", "-q", "check_port_connectivity"))
        self.checked("exec", mq, "rabbitmqctl", "-q", "set_cluster_name", self.run_id)
        self.checked("exec", mq, "rabbitmq-plugins", "enable", "rabbitmq_management")
        rabbit_version = self.checked("exec", mq, "rabbitmq-diagnostics", "-q", "server_version")
        node = self.checked("exec", mq, "rabbitmqctl", "-q", "eval", "node().").strip("'")
        vhosts = self.checked("exec", mq, "rabbitmqctl", "-q", "list_vhosts", "name").splitlines()
        if node != f"rabbit@{mq}" or "probe" not in vhosts or rabbit_version != "4.2.9":
            raise RuntimeError("unexpected RabbitMQ node, version or vhost")
        self.env.update(ENG002E_DB_PORT=db_port, ENG002E_DB_UUID=uuid, ENG002E_MQ_PORT=mq_port,
                        ENG002E_MGMT_PORT=admin_port, ENG002E_MQ_NAME=mq, ENG002E_NODE=node)
        (self.evidence / "environment.json").write_text(json.dumps(
            {"mysql": version, "mysql_image": MYSQL, "rabbitmq": rabbit_version, "rabbit_image": RABBIT,
             "node": node, "vhost": "probe", "marker": self.run_id}, indent=2))

    def owns(self, name):
        result = self.docker("inspect", "--format", '{{index .Config.Labels "org.weeleng.eng002e"}}', name)
        return result.returncode == 0 and result.stdout.strip() == self.run_id

    def clean(self):
        errors = []
        for role, name in reversed(self.containers.items()):
            if not self.owns(name):
                continue
            logs = self.docker("logs", "--tail", "200", name)
            body = logs.stdout + logs.stderr
            for key, value in self.env.items():
                if value and ("PASSWORD" in key or key == "RABBITMQ_DEFAULT_PASS"):
                    body = body.replace(value, "[redacted]")
            (self.evidence / f"{role}-container.log").write_text(body)
            if self.docker("rm", "-fv", name).returncode:
                errors.append(role)
        if errors:
            raise RuntimeError(f"container cleanup failed: {errors}")

    def maven(self, suite, options):
        location = self.evidence / suite
        location.mkdir()
        goals = ["org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree"] if suite == "dependencies" else [
            "verify", f"-Dtest={SUITES[suite]}", f"-Dsurefire.reportsDirectory={location}"]
        args = ["mvn", "-B", "-ntp", "-f", str(ROOT / "pom.xml"), *options, *goals]
        env = dict(self.env, ENG002E_EVIDENCE=str(location))
        with (location / "maven.log").open("w") as log:
            status = subprocess.run(args, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
        totals = defaultdict(int)
        for file in location.glob("TEST-*.xml"):
            report = ET.parse(file)
            for key in ("tests", "failures", "errors", "skipped"):
                totals[key] += int(report.getroot().attrib[key])
            # Surefire captures VM properties; keep the evidence limited to test results and output.
            for item in report.getroot().findall("properties"):
                report.getroot().remove(item)
            report.write(file, encoding="utf-8", xml_declaration=True)
        (location / "result.json").write_text(json.dumps(
            {"command": args, "exit_code": status, **totals}, indent=2))
        return status == 0 and (suite == "dependencies" or totals["tests"] > 0 and not totals["skipped"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--group", choices=SUITES, action="append")
    parser.add_argument("maven", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    lab = Lab(options.evidence_dir.resolve())
    flags = options.maven[1:] if options.maven[:1] == ["--"] else options.maven
    command = ["bash", str(ROOT.parents[2] / "script/shell/check-build-toolchain.sh")]
    with (lab.evidence / "toolchain.log").open("w") as log:
        status = subprocess.run(command, env=lab.env, stdout=log, stderr=subprocess.STDOUT).returncode
    (lab.evidence / "toolchain.exit").write_text(f"{status}\n")
    if status:
        return status
    try:
        lab.provision()
        results = [lab.maven(group, flags) for group in [*(options.group or SUITES), "dependencies"]]
        return 0 if all(results) else 1
    finally:
        lab.clean()


if __name__ == "__main__":
    raise SystemExit(main())
