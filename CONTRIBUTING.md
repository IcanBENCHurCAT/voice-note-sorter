# Contributing

Thanks for looking. This is the open core of a free, local-only voice-note
app, and it doubles as a reference implementation for capability-tiered
on-device AI on Android. Small, focused contributions are the most useful
kind here.

## Licence of contributions

By submitting a contribution you agree it is licensed under the
**Apache License 2.0**, the licence of this repository. There is no CLA;
the licence grant in this section is the whole agreement. Contributors
retain their copyright. Contributions are
used in the free app and may also be used commercially by the maintainer
(for example in client white-label builds), which Apache-2.0 permits.
The open codebase will never be relicensed closed.
Paid/Pro features are developed in a separate closed repository and are
**not** accepted as contributions here; if you have built something that
sounds like a paid feature, open an issue first so we can talk about where
it belongs.

## Ground rules

- Read `AGENTS.md` before writing code. The boundary rules (no paid code,
  no imports from the closed repo, local-only) apply to contributions too.
- One logical change per pull request, build green, tests for anything in
  the capability ladder or sorting pipeline (at minimum the no-AI path).
- AI features go behind the interfaces in the `ai/` package with a working
  fallback. PRs that call ML Kit directly from UI code will be sent back.
- New dependencies need a reason in the PR description. Network-capable
  dependencies need maintainer approval before the PR, not after.

## Good first contributions

- Fallback-path tests (Tier 1 and Tier 2 behaviour)
- Heuristic bucketing rules for new note types
- Accessibility passes on the Compose UI
- Documentation and worked examples for the capability ladder
