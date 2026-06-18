# Contributing

This is a commercial project. Keep changes small, reviewed, and verified.

## Before Changing Code

- Read the affected module first.
- Follow the repository instructions in `AGENTS.md`.
- Do not commit generated files, secrets, local logs, local model downloads, or deployment records.

## Required Verification

- Backend: `cd si-backend && mvn test`
- Frontend: `cd si-frontend && npm run build`, plus browser workflow verification for UI changes
- Bot: `cd bot/CallingBotSample && dotnet build CallingBotSample.csproj`; run bot tests when affected
- Speaker service: `cd speaker-service && python -m compileall -q .`; run pytest if tests exist

Security, authorization, logging, deployment, and cross-module changes require regression checks across the full affected workflow.
