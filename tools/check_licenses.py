#!/usr/bin/env python3
from __future__ import annotations

import fnmatch
import json
import sys
import tomllib
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
PERMISSIVE_LICENSES = {
    "MIT", "BSD-2-Clause", "BSD-3-Clause", "Apache-2.0", "Zlib", "ISC", "FTL", "Unlicense", "Libpng",
}
TEST_OR_BUILD_ONLY_LICENSES = {"EPL-1.0", "EPL-2.0"}
GOOGLE_SERVICE_LICENSES = {"Android-SDK-License"}
VALID_SCOPES = {"app", "test", "build"}


def license_problem(name: str, entry: dict) -> str | None:
    license_id = entry.get("license")
    scope = entry.get("scope")
    if scope not in VALID_SCOPES:
        return f"{name}: unknown scope {scope!r}"
    if license_id in PERMISSIVE_LICENSES:
        return None
    if license_id in TEST_OR_BUILD_ONLY_LICENSES and scope in {"test", "build"}:
        return None
    if license_id in GOOGLE_SERVICE_LICENSES and "*" not in name:
        return None
    return f"{name}: license {license_id!r} is not allowed for scope {scope!r}"


def find_entry(name: str, entries: dict) -> tuple[str, dict] | None:
    for pattern, entry in entries.items():
        if fnmatch.fnmatchcase(name, pattern):
            return pattern, entry
    return None


def main() -> int:
    problems: list[str] = []
    approved = json.loads((REPOSITORY_ROOT / "tools/licenses/dependencies.json").read_text(encoding="utf-8"))
    catalog = tomllib.loads((REPOSITORY_ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))

    for section in ("maven", "plugins", "native", "tools"):
        for name, entry in approved.get(section, {}).items():
            problem = license_problem(name, entry)
            if problem:
                problems.append(problem)

    for alias, library in catalog.get("libraries", {}).items():
        module = library["module"] if isinstance(library, dict) else library.rsplit(":", 1)[0]
        if find_entry(module, approved["maven"]) is None:
            problems.append(f"library '{alias}' ({module}) is not listed in tools/licenses/dependencies.json")

    for alias, plugin in catalog.get("plugins", {}).items():
        plugin_id = plugin["id"] if isinstance(plugin, dict) else plugin.split(":", 1)[0]
        if find_entry(plugin_id, approved["plugins"]) is None:
            problems.append(f"plugin '{alias}' ({plugin_id}) is not listed in tools/licenses/dependencies.json")

    credits_text = (REPOSITORY_ROOT / "CREDITS.md").read_text(encoding="utf-8")
    assets_root = REPOSITORY_ROOT / "assets-src"
    if assets_root.is_dir():
        for asset in sorted(path for path in assets_root.rglob("*") if path.is_file()):
            relative_path = asset.relative_to(REPOSITORY_ROOT).as_posix()
            if asset.name in {"CREDITS.md", "README.md"}:
                continue
            if relative_path not in credits_text:
                problems.append(f"asset {relative_path} is not listed in CREDITS.md")

    if problems:
        print("License check FAILED:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("License check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
