"""Run a metadata-only probe against libraries from a local Server build."""
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
from zipfile import ZipFile


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: run-tenant-probe.py <local-server.jar> <evidence-dir>")
    source = Path(__file__).with_name("TenantProbe.java")
    output = Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="eng002d-tenant-") as folder, ZipFile(sys.argv[1]) as jar:
        files = [name for name in jar.namelist() if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")]
        if not any("yudao-spring-boot-starter-biz-tenant" in entry for entry in files):
            raise RuntimeError("the Server archive does not contain the tenant starter")
        for entry in files:
            path = Path(folder, Path(entry).name)
            path.write_bytes(jar.read(entry))
        result = []
        for label, args in (("normal", []), ("negative", ["--expect-unscoped-failure"])):
            with (output / f"{label}.log").open("w") as log:
                run = subprocess.run(["java", "-cp", f"{folder}/*", str(source), *args],
                                     stdout=log, stderr=subprocess.STDOUT)
            (output / f"{label}.exit").write_text(f"{run.returncode}\n")
            log = (output / f"{label}.log").read_text()
            expected = (run.returncode == 0 and log.count("PASS ") == 6) if not args else (
                run.returncode == 1 and "AssertionError: unscoped synthetic SQL violates mandatory tenant predicate" in log
            )
            result.append(expected)
            print(f"{label}: exit={run.returncode}, reached expected fixture={expected}")
        return 0 if all(result) else 1


if __name__ == "__main__":
    sys.exit(main())
