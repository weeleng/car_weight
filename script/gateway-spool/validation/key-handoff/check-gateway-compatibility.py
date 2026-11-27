#!/usr/bin/env python3
"""Run the formal v0 startup validator against the two synthetic Group A snapshots."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    args = parser.parse_args()
    output = args.evidence_dir.resolve()
    reference = output / "A"
    run = output / "gateway-compatibility"
    run.mkdir()
    with args.gateway.open("rb") as file:
        (run / "jar.sha256").write_text(hashlib.file_digest(file, "sha256").hexdigest() + "\n")
    with zipfile.ZipFile(args.gateway) as jar:
        for item in jar.namelist():
            if item.startswith(("BOOT-INF/lib/", "BOOT-INF/classes/")):
                jar.extract(item, run)
    cp = f"{run}/BOOT-INF/classes:{run}/BOOT-INF/lib/*"
    source = Path(__file__).resolve().parent / "fixtures/GatewaySchemaWitness.java"
    with (run / "check.log").open("w") as log:
        for command in (
            ["javac", "--release", "17", "-proc:none", "-cp", cp, "-d", str(run), str(source)],
            ["java", "-cp", f"{run}:{cp}", "GatewaySchemaWitness", str(reference / "v0-for-legacy-guard.db"),
             str(reference / "candidate-for-legacy-guard.db")],
        ):
            log.write(repr(command) + "\n")
            log.flush()
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
            if completed.returncode:
                return completed.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
