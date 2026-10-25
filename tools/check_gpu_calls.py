#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent
ENGINE_SOURCES = REPOSITORY_ROOT / "engine" / "src"
GL_BACKEND = ENGINE_SOURCES / "main" / "cpp" / "gpu" / "gles"
FORBIDDEN = re.compile(r"\b(?:gl|egl)[A-Z][A-Za-z0-9]*\s*\(|#\s*include\s*<(?:GLES[23]?|EGL)/")
LINE_COMMENT = re.compile(r"//.*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)


def code_lines(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    text = BLOCK_COMMENT.sub(lambda match: "\n" * match.group(0).count("\n"), text)
    return [LINE_COMMENT.sub("", line) for line in text.splitlines()]


def main() -> int:
    problems = []
    for path in sorted(ENGINE_SOURCES.rglob("*")):
        if path.suffix not in {".cpp", ".h", ".cc", ".hpp"} or GL_BACKEND in path.parents:
            continue
        for number, line in enumerate(code_lines(path), start=1):
            if FORBIDDEN.search(line):
                problems.append(f"{path.relative_to(REPOSITORY_ROOT)}:{number}: {line.strip()}")
    for problem in problems:
        print(problem)
    if problems:
        print(f"{len(problems)} GL/EGL use(s) outside engine/src/main/cpp/gpu/gles/")
        return 1
    print("No GL/EGL use outside the GL backend.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
