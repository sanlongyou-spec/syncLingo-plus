---
name: synclingo-coding-standards
description: Mandatory syncLingo-plus coding standards and comprehensive testing workflow for Codex. Use this skill proactively — invoke it automatically before reviewing, writing, editing, fixing, refactoring, or verifying any code in this repository, including Java backend, React/TypeScript frontend, Teams Bot, speaker service, configuration, integrations, and tests. Do not wait for the user to ask.
---

# syncLingo Coding Standards

Apply the project's coding rules before Codex makes or reviews code changes in `syncLingo-plus`.

## Required Workflow

1. Read the repository's latest `docs/coding-standards.md` before code work.
2. If that file is unavailable, read [references/coding-standards.md](references/coding-standards.md) as the fallback copy.
3. Keep edits scoped to the requested area and preserve the existing architecture unless the task explicitly requires a structural fix.
4. When replacing an old implementation path, delete the superseded code, files, constants, and entry points that are no longer used so they cannot interfere with testing or future changes.
5. Before finalizing, run a compliance pass against the checklist below and complete the mandatory comprehensive verification workflow. Merely compiling successfully or receiving a successful API response is not sufficient.

## Mandatory Comprehensive Verification

Read [references/comprehensive-testing.md](references/comprehensive-testing.md) before finalizing any code change.

- Test the changed behavior itself, not only whether an endpoint responds or a module compiles.
- Cover the normal path, failure path, permission/security path, important boundaries, and regression risks relevant to the change.
- Run the complete test suite and build for every touched module. Run cross-module verification when contracts, shared behavior, deployment, or user workflows cross module boundaries.
- For frontend behavior changes, verify the affected workflow in a browser at representative desktop and mobile widths, including loading, empty, error, disabled, and success states where applicable.
- Inspect relevant logs, API responses, persisted data, generated files, or runtime state when those are needed to prove correctness.
- Do not claim completion while required tests fail. Fix failures caused by the change. Clearly identify pre-existing failures with evidence, and do not treat them as acceptable when the changed surface depends on them.
- If environmental limitations prevent a required test, state exactly what was not run, why, and the residual risk.

## Backend Checklist

- Keep the layer order `controller -> facade -> service -> integration -> mapper`.
- Do not let controllers call services or mappers directly when a facade is required by the architecture.
- Put external API calls only in `integration`.
- Prefer official SDKs or official sample implementations for external services. If no suitable official SDK exists for the current runtime, use the official protocol/documentation and isolate that code in `integration`.
- Keep DTO, VO, and Entity separate. Do not expose persistence entities directly from controllers.
- Return the unified `Result` structure from APIs.
- Use Lombok consistently where the project standard expects it.
- Add start/end logs for changed business methods, include key parameters, and log external-call latency.
- Use `BizException` plus the global exception flow for business errors.
- Do not use `System.out.println`.
- Replace repeated or important magic values with named constants.
- Keep naming specific and descriptive.
- Remove obsolete code paths when migrating behavior to a new implementation; do not leave old endpoints, helpers, constants, or fallback branches that can still be invoked accidentally.

## Frontend Checklist

- Keep HTTP calls inside `src/api`.
- Do not call `axios` directly from views or components.
- Keep page-level views and reusable components separated.
- Use `const` and `let`, never `var`.
- Preserve the existing React/TypeScript project structure unless the task requires a deliberate refactor.
- Treat user-facing UI quality as part of the implementation, not as a follow-up polish task.
- Keep frontend changes visually coherent: clear hierarchy, balanced spacing, consistent component styling, and deliberate hover/focus/disabled states.
- Verify responsive layouts so text, controls, panels, and badges do not overlap, overflow, or look like rough placeholders on common desktop and mobile widths.
- Remove superseded UI flows and browser-side helpers when behavior moves to the backend or another runtime; old unused capture, polling, or transport code should not remain as dead code.

## Review Pass

Before saying a task is complete:

1. Check changed controllers for direct service/mapper injection.
2. Check changed API responses for direct Entity exposure.
3. Check changed service code for external HTTP/protocol logic that belongs in `integration`.
4. Check changed frontend views/components for direct `axios` usage.
5. Check changed frontend UI for visual quality, responsive behavior, readable hierarchy, and polished interaction states.
6. Check changed code for missing logs, `System.out.println`, unclear names, and avoidable magic values.
7. Check that superseded implementation paths were removed, including unused files, constants, imports, endpoints, and transport methods.
8. Complete the comprehensive verification matrix in `references/comprehensive-testing.md`; an interface smoke test alone never satisfies this requirement.
9. Run full builds and test suites for every touched module, plus cross-module and browser/runtime verification where applicable.
10. In the final summary, report the commands and behavior scenarios verified, failures found, and any remaining exception or untested risk.

## Conflict Handling

- If the user's request conflicts with the repository standard, state the conflict briefly before deviating.
- If official SDK usage conflicts with current provider support, prefer the official SDK where possible; otherwise follow official docs/examples and keep the custom protocol code isolated.
