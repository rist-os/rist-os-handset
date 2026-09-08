# Contributing

RistOS is pre-release and experimental. It targets the Google Pixel 10a only; other devices are out of scope.

## Pull requests

- `app/`: PRs are welcome.
- `aosp/` and `image/`: PRs are accepted only with a report of the change tested on a real Pixel 10a
  (what was flashed, what was checked, what happened).

Before opening a PR:

1. `./gradlew testDebugUnitTest` passes.
2. For changes to scripts or tools, the relevant `tools/check_*.py --selftest` passes.

Keep PRs small and describe what changed and why.

## Bugs and questions

- Bugs: open an issue with the bug form.
- Questions: open an issue with the "Other" form.

There is no support channel and no chat. Read `SAFETY.md` before filing, and report vulnerabilities or
emergency-calling problems privately as described in `SECURITY.md`, never in a public issue.

## License

There is no CLA. Contributions are licensed under Apache-2.0, like the rest of the project.
