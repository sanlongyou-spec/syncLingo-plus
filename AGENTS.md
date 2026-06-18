# syncLingo-plus Codex Instructions

## Required Skills

- This repository contains exactly two project-specific custom skills:
  - `/synclingo-coding-standards`
  - `/synclingo-weekly-optimization-docs`
- For every code review, implementation, refactor, bug fix, or verification task, use `/synclingo-coding-standards`.
- For weekly optimization planning, implementation summaries, or validation records, use `/synclingo-weekly-optimization-docs`.

## Non-Negotiable Verification Rule

After modifying code, perform comprehensive verification before reporting completion.

- Do not treat successful compilation, a successful HTTP response, or one happy-path check as sufficient.
- Test the changed behavior, normal and failure paths, permissions/security boundaries, important edge cases, and related regression workflows.
- Run the complete build and test suite for every touched module, plus cross-module verification when contracts or workflows cross module boundaries.
- For frontend changes, verify the real workflow in a browser and check applicable desktop/mobile layouts and loading, empty, error, disabled, and success states.
- Inspect relevant logs, persisted data, generated files, cleanup behavior, and runtime state when needed to prove correctness.
- Do not claim completion while required tests fail. Report anything that could not be tested, why, and the residual risk.

The detailed workflow and module test matrix are in:

- `.Codex/skills/synclingo-coding-standards/SKILL.md`
- `.Codex/skills/synclingo-coding-standards/references/comprehensive-testing.md`

## Baseline Commands

- Backend: `cd si-backend && mvn test`
- Frontend: `cd si-frontend && npm run build`, followed by browser verification for UI changes
- Teams Bot: `cd bot/CallingBotSample && dotnet build CallingBotSample.csproj`, plus available tests and affected workflow verification
- Speaker service: `cd speaker-service && python -m compileall -q .`, plus `python -m pytest -q` when tests exist

## Imported Claude Cowork project instructions
