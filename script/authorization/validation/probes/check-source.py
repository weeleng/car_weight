"""Inventory existing tenant exceptions and check known RuoYi source boundaries."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
PATTERN = re.compile(
    r"^\s*@TenantIgnore\b|TenantUtils\.executeIgnore\(|"
    r"TenantContextHolder\.setIgnore\(true\)|@InterceptorIgnore\(.*tenantLine"
)


def tracked():
    result = subprocess.check_output(["git", "-C", str(ROOT), "ls-files", "-z"])
    return [ROOT / name for name in result.decode().split("\0") if name]


def facility(paths, name, module=""):
    matches = [p for p in paths if p.name == name and module in str(p) and "/src/main/" in str(p)]
    if len(matches) != 1:
        raise AssertionError(f"expected one {name}: {matches}")
    return matches[0]


def inventory(paths):
    print("site\tscope\tevidence")
    for path in paths:
        if path.suffix == ".java" and "/src/main/" in str(path):
            for line, body in enumerate(path.read_text().splitlines(), 1):
                if PATTERN.search(body):
                    label = "controller" if "controller" in path.parts else "source"
                    print(f"{label}\t{path.relative_to(ROOT)}:{line}\t{PATTERN.search(body).group(0).strip()}")
        if path.suffix not in (".yaml", ".yml") or "src/main/resources" not in str(path):
            continue
        lines = path.read_text().splitlines()
        for line, body in enumerate(lines, 1):
            if re.match(r"^\s*ignore-(?:visit-urls|urls|tables|caches):", body):
                indent = len(body) - len(body.lstrip())
                values = []
                for item in lines[line:]:
                    data = item.split("#", 1)[0].strip()
                    if not data:
                        continue
                    if len(item) - len(item.lstrip()) <= indent:
                        break
                    if data.startswith("- "):
                        values.append(data[2:])
                print(f"configuration\t{path.relative_to(ROOT)}:{line}\t{body.strip()} "
                      f"{', '.join(values) if values else '(empty)'}")


def check_boundaries(paths):
    token = facility(paths, "OAuth2TokenServiceImpl.java")
    token_source = token.read_text()
    getter = token_source.split("getAccessToken(String accessToken)", 1)[1].split(
        "checkAccessToken(String accessToken)", 1
    )[0]
    remover = token_source.split("removeAccessToken(String accessToken)", 1)[1].split(
        "removeAccessToken(Long userId", 1
    )[0]
    lookup_order = [getter.index(marker) for marker in (
        "oauth2AccessTokenRedisDAO.get", "selectByAccessToken", "selectByRefreshToken",
        "convertToAccessToken", "oauth2AccessTokenRedisDAO.set",
    )]
    assert lookup_order == sorted(lookup_order)
    early_return = re.search(r"if \(accessTokenDO == null\)\s*\{\s*return null;", remover)
    assert early_return and early_return.start() < remover.index("deleteByRefreshToken")
    print(f"PASS known-refresh-compatibility-and-revocation-boundary {token.relative_to(ROOT)}")

    controller = facility(paths, "FileController.java", "yudao-module-infra")
    source = controller.read_text()
    download = source.split('@GetMapping("/{configId}/get/**")', 1)[1].split("@GetMapping", 1)[0]
    assert all(marker in download for marker in (
        "@PermitAll", "@TenantIgnore", "getFileStream(configId, path)", "writeAttachment"
    ))
    assert "@PreAuthorize" not in download
    print(f"PASS known-public-file-contract {controller.relative_to(ROOT)}")

    tenant = facility(paths, "TenantDatabaseInterceptor.java")
    source = tenant.read_text()
    assert "TableInfoHelper.getTableInfo(tableName)" in source
    assert re.search(r"if \(tableInfo == null\)\s*\{\s*return true;", source)
    assert "TenantContextHolder.getRequiredTenantId()" in source
    print(f"PASS known-table-metadata-boundary {tenant.relative_to(ROOT)}")


def main():
    paths = tracked()
    if sys.argv[1:] == ["--inventory"]:
        inventory(paths)
    elif not sys.argv[1:]:
        check_boundaries(paths)
    else:
        raise SystemExit("usage: check-source.py [--inventory]")


if __name__ == "__main__":
    main()
