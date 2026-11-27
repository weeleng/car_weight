#!/usr/bin/env python3
"""Independent candidate handoff test runner. RabbitMQ only binds to local loopback."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import xml.etree.ElementTree as ET

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[3]
GROUPS = {"A": "SchemaTest", "B": "StateTest", "C": "ProofTest,BrokerTest", "D": "RecoveryTest"}


def run(*args, **kwargs):
    return subprocess.run(args, text=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs).stdout


def write(path, obj):
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def tests(path, group, flags, env):
    output = path / group
    output.mkdir()
    command = ["mvn", "-B", "-ntp", *flags, "-f", str(LAB / "pom.xml"), "test",
               f"-Dtest={GROUPS[group]}", f"-Dsurefire.reportsDirectory={output}"]
    with (output / "maven.log").open("w") as log:
        result = subprocess.run(command, env={**env, "LAB_OUT": str(output)}, stdout=log, stderr=subprocess.STDOUT)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for item in output.glob("TEST-*.xml"):
        parsed = ET.parse(item).getroot()
        for key in counts:
            counts[key] += int(parsed.get(key, 0))
    write(output / "result.json", dict(command=command, exit_code=result.returncode, **counts))
    print(group, result.returncode, counts, flush=True)
    return bool(result.returncode or counts["tests"] == 0 or counts["skipped"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--group", action="append", choices=GROUPS)
    parser.add_argument("maven", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    path = args.evidence_dir.resolve()
    path.mkdir(parents=True, exist_ok=False, mode=0o700)
    flags = args.maven[1:] if args.maven[:1] == ["--"] else args.maven
    groups = args.group or list(GROUPS)
    water = os.statvfs(path)
    fingerprints = [f for f in sorted(LAB.rglob("*")) if f.is_file() and "target" not in f.relative_to(LAB).parts
                    and "__pycache__" not in f.parts]
    meta = dict(source=run("git", "-C", str(ROOT), "rev-parse", "HEAD").strip(),
                java=run("java", "-version").strip(), maven=run("mvn", "-v").strip(),
                machine=os.uname().machine, cpu=os.cpu_count(), free_bytes=water.f_frsize * water.f_bavail,
                file_digests={str(f.relative_to(LAB)): hashlib.sha256(f.read_bytes()).hexdigest() for f in fingerprints})
    env = dict(os.environ)
    rabbit = "key-handoff-" + secrets.token_hex(6)
    password = secrets.token_hex(24)
    image = "rabbitmq:4.2"
    started = False
    try:
        if "C" in groups:
            run("docker", "run", "-d", "--name", rabbit, "--label", "org.weeleng.key-handoff=lab",
                "-p", "127.0.0.1::5672", "-e", "RABBITMQ_DEFAULT_USER", "-e", "RABBITMQ_DEFAULT_PASS",
                "-e", "RABBITMQ_DEFAULT_VHOST", image,
                env={**env, "RABBITMQ_DEFAULT_USER": "fixture", "RABBITMQ_DEFAULT_PASS": password,
                     "RABBITMQ_DEFAULT_VHOST": "fixture"})
            started = True
            for _ in range(60):
                try:
                    run("docker", "exec", "--user", "rabbitmq", rabbit, "sh", "-c",
                        "test -s /var/lib/rabbitmq/.erlang.cookie && rabbitmq-diagnostics -q check_port_connectivity")
                    break
                except subprocess.CalledProcessError:
                    if run("docker", "inspect", "-f", "{{.State.Running}}", rabbit).strip() != "true":
                        raise RuntimeError("RabbitMQ exited during startup; see rabbit.log")
                    time.sleep(1)
            else:
                raise RuntimeError("RabbitMQ readiness timeout")
            meta.update(rabbit=run("docker", "exec", "--user", "rabbitmq", rabbit, "rabbitmq-diagnostics", "-q", "server_version").strip(),
                        rabbit_image=run("docker", "image", "inspect", image, "--format", "{{json .RepoDigests}}").strip())
            env.update(HANDOFF_RABBIT=rabbit, HANDOFF_USER="fixture", HANDOFF_PASSWORD=password)
        write(path / "environment.json", meta)
        return int(any([tests(path, group, flags, env) for group in groups]))
    finally:
        if started:
            try:
                logs = run("docker", "logs", "--tail", "180", rabbit)
                (path / "rabbit.log").write_text(logs.replace(password, "[redacted]"))
            finally:
                run("docker", "rm", "-fv", rabbit)


if __name__ == "__main__":
    os.umask(0o077)
    raise SystemExit(main())
