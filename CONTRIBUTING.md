# Contributing to Nanquim

Thank you for helping; please read these rules before writing any code.

## Workflow
1. Open an issue describing the change, or pick an existing one.
2. Implement it; when the expected behavior is unclear, pick the simplest one and explain it in the pull request.
3. Add tests (JVM, C++ host tests, golden images where applicable).
4. Run `./gradlew test :engine:hostTest :app:lintProdDebug` and `python3 tools/check_licenses.py` before opening a pull request.

## Code style
- Kotlin: official style, `ktlint` (`.editorconfig`).
- C++17: `clang-format` (Google, 100 columns) and `clang-tidy` (modernize, bugprone).
- Descriptive names, no unclear abbreviations. Every public engine function documents its thread affinity.
- User-visible text only in string resources, in English and Brazilian Portuguese.

## Licensing
By contributing you agree that your contribution is licensed under the MIT License (`LICENSE`). The Nanquim name and logo are not covered by the MIT License (`NOTICE.md`).

## Conduct
Participation is governed by `CODE_OF_CONDUCT.md`.
