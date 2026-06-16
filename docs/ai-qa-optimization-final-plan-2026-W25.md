# 2026-W25 AI Q&A Optimization Final Plan

## Scope

This document records the final implementation plan for Track B: AI Q&A optimization.
The implementation is not a minimum version. It targets the complete production-ready
path: evaluation baseline, retrieval quality, grounded answers, multi-turn context,
streaming performance, and data-safe embedding evolution.

MFA is explicitly out of scope for this track.

## Non-Negotiable Data Safety Rules

- Do not truncate `interpretation_embedding`.
- Do not clear and rebuild production vectors as the optimization path.
- Do not overwrite embeddings from an older retrieval profile when a new profile is introduced.
- Business deletion cleanup is allowed only when the source meeting, session, file, or item is deleted.
- New embedding profiles must coexist with legacy/default profile data and support rollback.
- Production migration must be additive first, rehearsed on a copied database, and backed up before enabling.

## Final Implementation Items

| Area | Final Requirement | Implementation Notes |
|---|---|---|
| Evaluation baseline | Build a reusable Q&A evaluation set and runner | Store question, expected answer points, expected sources, meeting/speaker/time constraints, difficulty, and metrics output |
| Query rewrite | Use recent chat history to rewrite follow-up questions | Teams Bot streaming and normal Q&A paths must pass history where available |
| Query expansion | Generate complementary retrieval queries | Keep fail-open behavior and enforce timeout/cost limits |
| Decomposition | Split multi-hop/comparison questions | Preserve original question and cap sub-queries |
| Hybrid retrieval | Combine dense, sparse/BM25, and RRF | Existing in-candidate hybrid remains enabled; add safeguards and metrics |
| Rerank | LLM rerank recalled chunks | Keep topK configurable, fail open, and preserve source diversity |
| Source grounding | Answers must cite meeting, speaker, time, and file/chunk source | Source block and answer text must remain consistent |
| Multi-turn | Carry useful history into rewrite and final answer | Bound history window and include prior cited sources when available |
| Streaming latency | Improve first-token perception and total latency | Emit early retrieval/generation events where clients support it; budget expansion/rerank |
| Embedding versioning | Add model/dim/profile/hash/status metadata | New profile data must coexist with legacy/default vectors |
| Re-embedding | Incremental, profile-aware rebuild | Batch by meeting/file/user/time range; no destructive full rebuild |
| Observability | Log retrieval profile, candidate count, hybrid/rerank status, and timings | Logs must not include full sensitive transcript text |

## Implementation Order

1. Add the data-safe embedding profile foundation.
2. Add evaluation data model and runner.
3. Wire profile-aware retrieval and rebuild paths.
4. Strengthen Teams Bot streaming Q&A with history, grounded prompt requirements, and source alignment.
5. Add latency safeguards, early stream events, and retrieval metrics.
6. Add and run automated tests.
7. Hand off local manual validation checklist.

## Implemented Evaluation Endpoints

`POST /api/admin/qa-evaluation/score` accepts prepared evaluation cases plus generated
answers and source snippets, then returns answer coverage, source coverage, and pass/fail
summary. It is protected by the existing `X-Admin-Secret` admin path and does not mutate
production business data.

`POST /api/admin/qa-evaluation/run` accepts prepared evaluation cases, runs each question
through the production Teams Bot Q&A path, captures generated answers and sources, and then
scores the generated batch with the same deterministic coverage logic. Cases may include
their own bounded chat history so multi-turn follow-up questions can be evaluated without
polluting other cases.

Example request template: `docs/examples/qa-evaluation-run-request.example.json`.
Local runner script: `scripts/run-qa-evaluation.ps1`.

## Implemented RAG Tuning Defaults

Query expansion and rerank are enabled by default and remain fail-open. Helper calls use
a short timeout, total expanded queries are capped, and each expanded query has a recall
cap so production latency and cost can be bounded while preserving rollback through env vars.

## Automated Test Matrix

| Layer | Required Tests |
|---|---|
| Unit | Query expansion failure fallback, rerank failure fallback, hybrid fusion, BM25/CJK tokens, source formatting, profile normalization, content hash |
| Service | Current profile writes metadata, legacy/default vectors remain readable, new profile can coexist with old profile, business delete removes all profiles for the deleted source |
| Retrieval | Dense-only, hybrid, rerank topK, source diversity, no-result fallback |
| Multi-turn | Follow-up question is rewritten with history and still keeps fail-open behavior |
| Streaming | RAG answer streams, final source block is appended, no-data path completes cleanly |
| Evaluation | Baseline and optimized profile reports are generated and comparable |
| Regression | `mvn test`, frontend build if touched, `git diff --check` |

## Manual Validation After Automation

- Fill `docs/examples/qa-evaluation-run-request.example.json` with real server user identity and
  expected answer/source keywords, then call `POST /api/admin/qa-evaluation/run` with
  `X-Admin-Secret`.
- Or run `scripts/run-qa-evaluation.ps1` to call the endpoint and save timestamped JSON and
  Markdown reports under `outputs/qa-evaluation`.
- Ask real cross-meeting questions against existing server data.
- Ask 3 to 5 follow-up questions with pronouns and omitted subjects.
- Confirm old meetings remain searchable after the new profile is enabled.
- Upload a new meeting/file and confirm incremental embedding and retrieval.
- Check that each answer source maps to the meeting, speaker, time, or file shown.
- Confirm no answer invents content when the data is missing.
- Judge perceived first-token latency and total answer time.

## Acceptance Criteria

- Existing server data is preserved.
- Old/default and new embedding profiles can coexist.
- The optimized profile can be disabled without deleting data.
- Q&A quality has baseline and post-optimization reports.
- Answers include traceable sources.
- Multi-turn streaming Q&A works with bounded history.
- Automated tests pass before manual validation starts.
