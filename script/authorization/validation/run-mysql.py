"""Run ENG-002D tests against a disposable local MySQL container."""
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
from urllib.parse import urlparse


def docker(*args, host=None, **options):
    command = ["docker"]
    if host:
        command.extend(("--host", host))
        child_env = dict(options.pop("env", os.environ))
        child_env.pop("DOCKER_CONTEXT", None)
        child_env.pop("DOCKER_HOST", None)
        options["env"] = child_env
    return subprocess.run([*command, *args], text=True, capture_output=True, **options)


def main():
    host_override = None if os.environ.get("DOCKER_CONTEXT") else os.environ.get("DOCKER_HOST")
    endpoint = host_override or docker(
        "context", "inspect", "--format", "{{.Endpoints.docker.Host}}", check=True
    ).stdout.strip()
    if urlparse(endpoint).scheme != "unix":
        raise RuntimeError("a local Docker Unix socket is required")
    name = f"eng002d-probe-{secrets.token_hex(6)}"
    password = secrets.token_hex(24)
    container_env = dict(os.environ, MYSQL_ROOT_PASSWORD=secrets.token_hex(24),
                         MYSQL_PASSWORD=password, MYSQL_DATABASE="eng002d_probe",
                         MYSQL_USER="eng002d_probe")
    started = docker(
        "run", "-d", "--name", name, "--label", "org.weeleng.eng002d=isolated",
        "-p", "127.0.0.1::3306", "-e", "MYSQL_DATABASE", "-e", "MYSQL_ROOT_PASSWORD",
        "-e", "MYSQL_USER", "-e", "MYSQL_PASSWORD", "mysql:8.4",
        "--report-host=eng002d-probe-isolated", env=container_env, host=endpoint,
    )
    if started.returncode:
        raise RuntimeError("isolated MySQL container creation failed")
    try:
        binding = docker("port", name, "3306/tcp", host=endpoint, check=True).stdout.strip()
        match = re.fullmatch(r"127\.0\.0\.1:([0-9]{4,5})", binding)
        if not match or match.group(1) == "3306":
            raise RuntimeError("unexpected container port mapping")
        for _ in range(60):
            ready = docker("exec", name, "sh", "-c",
                           'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h127.0.0.1 '
                           '-u "$MYSQL_USER" "$MYSQL_DATABASE" -Nse "SELECT VERSION(), @@server_uuid"', host=endpoint)
            if not ready.returncode:
                break
            time.sleep(1)
        else:
            raise RuntimeError("isolated MySQL startup timed out")
        version, uuid = ready.stdout.strip().split("\t")
        if not version.startswith("8.4.") or not re.fullmatch(r"[a-f0-9-]{36}", uuid):
            raise RuntimeError("unexpected isolated MySQL version or UUID")
        print(f"Isolated MySQL {version}", flush=True)
        env = dict(os.environ, ENG002D_MYSQL_PORT=match.group(1), ENG002D_MYSQL_UUID=uuid,
                   ENG002D_MYSQL_PASSWORD=password)
        pom = Path(__file__).with_name("pom.xml")
        return subprocess.call(["mvn", "-B", "-ntp", "-f", str(pom), "clean", "verify",
                                "-Djunit.excludedGroups=unused", *sys.argv[1:]], env=env)
    finally:
        if docker("rm", "-fv", name, host=endpoint).returncode:
            raise RuntimeError("isolated MySQL cleanup failed")


if __name__ == "__main__":
    sys.exit(main())
