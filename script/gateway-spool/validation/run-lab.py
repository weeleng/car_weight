#!/usr/bin/env python3
"""Run isolated Maven JUnit groups with per-run broker resources and evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

LAB = Path(__file__).resolve().parent
ROOT = LAB.parents[2]


def command(args, **kwargs):
    return subprocess.run(args, text=True, check=True, **kwargs)


def docker(*args):
    return command(["docker", *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()


def evidence(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def wait_for(operation):
    for _ in range(60):
        try:
            return operation()
        except subprocess.CalledProcessError:
            time.sleep(1)
    raise RuntimeError("fixture service readiness timed out")


def prepare_probe(args, directory):
    libs = directory / "libs"
    classes = directory / "classes"
    libs.mkdir()
    classes.mkdir()
    with zipfile.ZipFile(args.gateway) as jar:
        for entry in jar.namelist():
            if entry.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")):
                jar.extract(entry, libs)
        original = jar.read("BOOT-INF/classes/application.yaml").decode()
    lines = [line for line in original.splitlines() if line.strip() != "type: redis # 消息总线的类型"]
    if len(lines) != len(original.splitlines()) - 1:
        raise ValueError("unexpected Gateway message-bus default; review fixture extraction")
    (directory / "no-type.yaml").write_text("\n".join(lines) + "\n")
    cp = f"{libs}/BOOT-INF/classes:{libs}/BOOT-INF/lib/*"
    command(["javac", "--release", "17", "-proc:none", "-cp", cp, "-d", str(classes),
             str(LAB / "fixtures/GatewayProbe.java"), str(LAB / "fixtures/LegacyShapes.java"),
             str(LAB / "fixtures/SpoolRabbitFixture.java")])
    command(["java", "-cp", f"{classes}:{cp}", "labprobe.LegacyShapes",
             str(directory / "s2.db"), str(directory / "s1.db")])
    with args.gateway.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run_maven(args, group, env):
    out = args.evidence_dir / group
    out.mkdir()
    flags = ["mvn", "-B", "-ntp", *args.maven, "-f", str(LAB / "pom.xml"), "test",
             f"-Dtest={dict(A='GatewayTest', B='HistoryTest', C='BrokerTest', D='SpoolTest')[group]}",
             f"-Dsurefire.reportsDirectory={out}"]
    with (out / "maven.log").open("w") as log:
        result = subprocess.run(flags, env={**env, "LAB_OUT": str(out)}, stdout=log, stderr=subprocess.STDOUT)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for report in out.glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    exit_code = result.returncode or (1 if not totals["tests"] else 0)
    evidence(out / "result.json", dict(command=flags, exit_code=exit_code, maven_exit=result.returncode, **totals))
    print(group, totals, "exit", exit_code, flush=True)
    return exit_code


def dependencies(args):
    directory = args.evidence_dir
    flags = ["mvn", "-B", "-ntp", *args.maven, "-f", str(LAB / "pom.xml"),
             "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom",
             "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree",
             f"-Doutput={directory / 'effective-pom.xml'}", "-DoutputType=json",
             f"-DoutputFile={directory / 'dependency-tree.json'}"]
    with (directory / "dependencies.log").open("w") as log:
        result = subprocess.run(flags, stdout=log, stderr=subprocess.STDOUT)
    evidence(directory / "dependencies-result.json", dict(command=flags, exit_code=result.returncode))
    return result.returncode


def run_groups(args, groups, env):
    results = [run_maven(args, group, env) for group in groups]
    return int(any([*results, dependencies(args)]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--gateway", type=Path, required=True, help="existing Gateway exec JAR from the source under test")
    parser.add_argument("--legacy-gateway", type=Path,
                        help="run production remediation matrix against --gateway and compare to this 02A original JAR")
    parser.add_argument("--group", choices=list("ABCD"), action="append")
    parser.add_argument("maven", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    args.maven = args.maven[1:] if args.maven[:1] == ["--"] else args.maven
    args.gateway = args.gateway.resolve(strict=True)
    args.evidence_dir = args.evidence_dir.resolve()
    args.evidence_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
    env = dict(os.environ)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    env["LAB_GATEWAY"] = str(args.gateway)
    env["LAB_HOME"] = str(LAB)
    env["LAB_RUN"] = str(args.evidence_dir)
    env["LAB_NETWORK"] = "eng002f-" + secrets.token_hex(6)
    env["LAB_GATEWAY_NETWORK"] = env["LAB_NETWORK"] + "-internal"
    env["LAB_RABBIT"] = env["LAB_NETWORK"] + "-rabbit"
    env["LAB_REDIS"] = env["LAB_NETWORK"] + "-redis"
    env["LAB_MQ_USER"] = "fixture"
    env["LAB_MQ_PASSWORD"] = secrets.token_hex(24)
    os.environ.update(RABBITMQ_DEFAULT_USER=env["LAB_MQ_USER"],
                      RABBITMQ_DEFAULT_PASS=env["LAB_MQ_PASSWORD"], RABBITMQ_DEFAULT_VHOST="fixture")
    images = {"rabbit": "rabbitmq:4.2", "redis": "redis:7.4", "gateway": "eclipse-temurin:21-jre"}
    if args.legacy_gateway and args.group:
        parser.error("--legacy-gateway and --group are mutually exclusive")
    if args.legacy_gateway:
        args.legacy_gateway = args.legacy_gateway.resolve(strict=True)
    groups = [] if args.legacy_gateway else (args.group or list("ABCD"))
    command(["bash", str(ROOT / "script/shell/check-build-toolchain.sh")])
    metadata = dict(source=command(["git", "-C", str(ROOT), "rev-parse", "HEAD"],
                                   stdout=subprocess.PIPE).stdout.strip(),
                    gateway=str(args.gateway), sha256=prepare_probe(args, args.evidence_dir),
                    java=command(["java", "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip(),
                    maven=command(["mvn", "-v"], stdout=subprocess.PIPE).stdout.strip())
    if not args.legacy_gateway and not any(g in "AC" for g in groups):
        evidence(args.evidence_dir / "environment.json", metadata)
        return run_groups(args, groups, env)

    network = env["LAB_NETWORK"]
    docker("network", "create", network)
    gateway_network = env["LAB_GATEWAY_NETWORK"]
    gateway_network_created = False
    try:
        label = f"org.weeleng.eng002f02a={network}"
        docker("run", "-d", "--name", env["LAB_RABBIT"], "--network", network, "--network-alias", "rabbit",
               "--label", label, "-p", "127.0.0.1::5672", "-e", "RABBITMQ_DEFAULT_USER",
               "-e", "RABBITMQ_DEFAULT_PASS", "-e", "RABBITMQ_DEFAULT_VHOST", images["rabbit"])
        docker("run", "-d", "--name", env["LAB_REDIS"], "--network", network, "--network-alias", "redis",
               "--label", label, images["redis"], "redis-server", "--appendonly", "yes", "--appendfsync", "always",
               "--save", "")
        docker("network", "create", "--internal", gateway_network)
        gateway_network_created = True
        docker("network", "connect", "--alias", "rabbit", gateway_network, env["LAB_RABBIT"])
        docker("network", "connect", "--alias", "redis", gateway_network, env["LAB_REDIS"])
        metadata["gateway_network"] = dict(name=gateway_network, internal=True)
        if args.legacy_gateway:
            with args.legacy_gateway.open("rb") as source:
                metadata["legacy_gateway"] = str(args.legacy_gateway)
                metadata["legacy_sha256"] = hashlib.file_digest(source, "sha256").hexdigest()
        metadata.update(
            rabbit=wait_for(lambda: docker("exec", env["LAB_RABBIT"], "rabbitmq-diagnostics", "-q", "server_version")),
            redis=wait_for(lambda: docker("exec", env["LAB_REDIS"], "redis-server", "--version")),
            images={k: docker("image", "inspect", v, "--format", "{{json .RepoDigests}}") for k, v in images.items()},
            redis_persistence=docker("exec", env["LAB_REDIS"], "redis-cli", "CONFIG", "GET", "save", "appendonly", "appendfsync"))
        evidence(args.evidence_dir / "environment.json", metadata)
        if args.legacy_gateway:
            output = args.evidence_dir / "remediation"
            output.mkdir()
            fixture_command = ["python3", str(LAB / "fixtures/remediation.py")]
            fixture_env = {**env, "LAB_OUT": str(output),
                           "LAB_LEGACY_GATEWAY": str(args.legacy_gateway)}
            with (output / "runner.log").open("w") as log:
                result = subprocess.run(fixture_command, env=fixture_env, stdout=log, stderr=subprocess.STDOUT)
            evidence(output / "runner-result.json", dict(command=fixture_command, exit_code=result.returncode))
            return result.returncode
        return run_groups(args, groups, env)
    finally:
        for kind in ("RABBIT", "REDIS"):
            name = env[f"LAB_{kind}"]
            try:
                logs = docker("logs", "--tail", "150", name).replace(env["LAB_MQ_PASSWORD"], "[redacted]")
                (args.evidence_dir / f"{kind.lower()}-container.log").write_text(logs + "\n")
            except subprocess.CalledProcessError:
                pass
        labels = docker("ps", "-aq", "--filter", f"label=org.weeleng.eng002f02a={network}").splitlines()
        if labels:
            docker("rm", "-fv", *labels)
        if gateway_network_created:
            docker("network", "rm", gateway_network)
        docker("network", "rm", network)


if __name__ == "__main__":
    os.umask(0o077)
    raise SystemExit(main())
