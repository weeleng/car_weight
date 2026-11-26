#!/usr/bin/env python3
"""Provision the ENG-002C schemas on an explicitly marked local Docker lab."""
import argparse
import json
import os
import re
import subprocess
import sys

MARKER = "eng002c02-isolated"
DOMAINS = {"ingest": ("probe_raw", "probe_outbox", "probe_precision"),
           "business": ("probe_inbox", "probe_record", "probe_outbox"),
           "track": ("probe_point",)}
KINDS = ("migrator", "runtime", "audit", "backup", "restore")


def mysql(container, sql):
    result = subprocess.run(
        ["docker", "exec", "-i", container, "sh", "-ec",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot'],
        input=sql, text=True, capture_output=True, check=False,
    )
    if result.returncode:
        # mysql can echo its input on failure, including CREATE USER statements.
        raise ValueError("MySQL rejected bootstrap. Check container and grants using a separate safe query.")
    return result.stdout.strip()


def bootstrap(container, domains, fixtures):
    if os.getenv("ENG002C_ENV") != "isolated" or not re.fullmatch(r"eng002c02-[a-z0-9-]+", container):
        raise ValueError("ENG002C_ENV=isolated and an eng002c02-* container are required")
    host = os.getenv("DOCKER_HOST") or subprocess.check_output(
        ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], text=True,
    ).strip()
    if not host.startswith("unix:///"):
        raise ValueError("A local Unix Docker endpoint is required")
    result = subprocess.check_output(["docker", "inspect", "--type", "container", container], text=True)
    instance, = json.loads(result)
    if instance["Config"]["Labels"].get("org.weeleng.eng002c") != MARKER:
        raise ValueError("Target container is missing the approved isolation label")
    bindings = instance["NetworkSettings"]["Ports"].get("3306/tcp") or []
    if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1" or bindings[0]["HostPort"] == "3306":
        raise ValueError("Only a loopback non-default MySQL port is allowed")
    version, marker, uuid = mysql(container, "SELECT VERSION(), @@report_host, @@server_uuid;").split("\t")
    if not version.startswith("8.4.") or marker != MARKER or not re.fullmatch(r"[0-9a-f-]{36}", uuid):
        raise ValueError("Unexpected server identity")
    commands = []
    for domain in domains:
        schema = "sanitation_" + domain
        if fixtures:
            commands.extend(grant_fixtures(schema, domain))
        else:
            commands.append(f"CREATE DATABASE IF NOT EXISTS `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
            for kind in KINDS:
                account = f"san_{domain}_{kind}"
                password = os.getenv(f"ENG002C_{domain.upper()}_{kind.upper()}_PASSWORD", "")
                if not re.fullmatch(r"[0-9a-f]{32,128}", password):
                    raise ValueError(f"Missing generated hex secret for {account}")
                role = account + "_role"
                commands.extend((
                    f"CREATE ROLE IF NOT EXISTS '{role}';",
                    f"CREATE USER IF NOT EXISTS '{account}'@'%' IDENTIFIED BY '{password}';",
                    f"GRANT '{role}' TO '{account}'@'%';",
                    f"SET DEFAULT ROLE '{role}' TO '{account}'@'%';",
                ))
                privileges = {
                    "migrator": "SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX",
                    "restore": "SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,DROP",
                    "backup": "SELECT,SHOW VIEW",
                    "audit": None, "runtime": None,
                }[kind]
                if privileges:
                    commands.append(f"GRANT {privileges} ON `{schema}`.* TO '{role}';")
    mysql(container, "\n".join(commands))
    print(f"isolated MySQL {version} uuid={uuid} port={bindings[0]['HostPort']} schemas={','.join(domains)}")


def grant_fixtures(schema, domain):
    for table in DOMAINS[domain]:
        yield f"GRANT SELECT,INSERT ON `{schema}`.`{table}` TO 'san_{domain}_runtime_role';"
        yield f"GRANT SELECT ON `{schema}`.`{table}` TO 'san_{domain}_audit_role';"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--domain", choices=DOMAINS, action="append")
    parser.add_argument("--grant-fixtures", action="store_true", help="grant access to existing lab tables only")
    args = parser.parse_args()
    try:
        bootstrap(args.container, tuple(dict.fromkeys(args.domain or DOMAINS)), args.grant_fixtures)
    except (ValueError, OSError, KeyError, AttributeError, subprocess.SubprocessError) as exc:
        # Do not render subprocess arguments or service output.
        print("Bootstrap refused: " + (str(exc) if type(exc) is ValueError else type(exc).__name__), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
