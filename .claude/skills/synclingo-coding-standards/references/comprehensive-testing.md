# Comprehensive Testing Workflow

Use this workflow after every code modification. A successful compile, a successful HTTP status, or one happy-path manual check is not comprehensive verification.

## 1. Determine the Affected Surface

Before testing, identify:

- Changed modules and directly dependent modules.
- Changed API contracts, database behavior, files, queues, WebSockets, external integrations, permissions, and user workflows.
- Existing tests that should prove the behavior and missing tests that must be added.

## 2. Required Verification Layers

Run every applicable layer:

1. **Focused tests**: add or update tests for the changed behavior and run them first.
2. **Module regression**: run the complete build and test suite for every touched module.
3. **Cross-module verification**: verify callers and consumers when APIs, DTOs, events, WebSockets, files, database schemas, or deployment contracts change.
4. **Behavior verification**: exercise the real workflow, not only a direct endpoint smoke test.
5. **Negative and boundary verification**: cover invalid input, missing data, unauthorized access, wrong ownership, duplicate/retry behavior, maximum sizes, empty values, and failure recovery where relevant.
6. **Operational verification**: inspect logs, persisted records, generated artifacts, cleanup behavior, resource usage, and external-call failures where relevant.
7. **Frontend verification**: use a browser for changed UI workflows and verify relevant desktop/mobile layouts plus loading, empty, error, disabled, and success states.

## 3. syncLingo Default Module Matrix

Run these commands whenever the corresponding module is touched:

| Module | Required baseline verification |
|---|---|
| `si-backend` | `mvn test`; verify changed API/service behavior, authorization boundaries, persistence, logs, and error handling |
| `si-frontend` | `npm run build`; use a browser to verify changed workflows and responsive states |
| `bot/CallingBotSample` | `dotnet build CallingBotSample.csproj`; run available tests and verify affected Bot/Graph/Teams workflow with safe test targets |
| `speaker-service` | `python -m compileall -q .`; run `python -m pytest -q` when a suite exists; verify affected service endpoint/model workflow |
| Deployment/configuration | Verify configuration validation, startup behavior, health checks, rollback compatibility, and that secrets/defaults are safe |

If more than one module is touched, run every relevant row and verify the end-to-end workflow across them.

## 4. Completion Gate

Do not report the task complete unless:

- Required focused tests exist and pass.
- Full touched-module builds and test suites pass.
- Applicable cross-module, browser, security, persistence, and operational checks pass.
- No failure introduced by the change remains.
- Any untestable item is explicitly reported with reason and residual risk.

In the final response, list the commands run, behavior scenarios verified, and any remaining failures or untested risks.
