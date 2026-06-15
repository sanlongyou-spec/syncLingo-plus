---
name: synclingo-weekly-optimization-docs
description: Maintain the syncLingo-plus weekly optimization record and matching weekly validation record in the project's canonical long-lived documents. Use this skill proactively — invoke it automatically whenever Claude Code plans, implements, summarizes, or verifies weekly optimization work in syncLingo-plus, especially when adding new optimization items, updating completion status, or writing the corresponding test plan after a weekly change. Do not wait for the user to ask.
---

# syncLingo Weekly Optimization Docs

Use one long-lived optimization document and one long-lived validation document for weekly work in `syncLingo-plus`.

## Required Workflow

1. Read [references/weekly-document-workflow.md](references/weekly-document-workflow.md) before changing weekly optimization or validation documentation.
2. Read the latest repository copies of:
   - `docs/optimization-implementation-plan.md`
   - `docs/optimization-validation-test-plan.md`
3. Add new weekly optimization content to `docs/optimization-implementation-plan.md`.
4. After the optimization work is completed or materially updated, add the matching weekly validation content to `docs/optimization-validation-test-plan.md`.
5. Use the same week label and title in both documents so the optimization entry and validation entry can be matched quickly.
6. Preserve historical entries. Append new weekly sections; do not split each week into a new standalone document unless the user explicitly requests that.

## Documentation Rules

- Treat the two canonical documents as the source of truth for weekly optimization history and weekly validation history.
- Keep phase-specific documents supplementary. If a side document exists, summarize the week back into the canonical documents.
- Record optimization goals, implementation items, completion status, affected modules, acceptance criteria, and residual issues.
- Write validation plans with log analysis first. Use manual judgment only for behavior that logs, API responses, or database records cannot prove.
- Keep optimization and validation language concrete enough that a future reader can see what changed, how to verify it, and what remains open.
- When code standards matter, preserve links or references to `docs/coding-standards.md` rather than duplicating the full standard.

## Completion Check

Before finishing weekly documentation work:

1. Confirm the optimization week exists in `docs/optimization-implementation-plan.md`.
2. Confirm the matching validation week exists in `docs/optimization-validation-test-plan.md`.
3. Confirm both entries use the same week label, same scope, and compatible acceptance criteria.
4. Confirm log-based validation comes before manual validation.
5. Mention any work intentionally deferred, including the reason.
