# Security Policy

## Supported Version

Only the current production branch is supported for security fixes.

## Reporting

Report suspected vulnerabilities to the project owner privately. Do not open public GitHub issues containing secrets, exploit details, database dumps, JWTs, API keys, or customer data.

## Secret Handling

- Never commit `backend.env`, `.env`, bot `appsettings.json`, deployment records, database dumps, service keys, JWT secrets, cloud API keys, or Teams/Azure credentials.
- Rotate any secret that has appeared in chat logs, screenshots, terminal output, or Git history.
- Production must set strong values for `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_API_SECRET`, `TEAMS_BOT_API_SECRET`, `SERVICE_SIGNATURE_DOWNSTREAM_KEY`, and `SERVICE_SIGNATURE_UPSTREAM_KEY`.
- Keep Azure Bot, Google, Cartesia, OpenAI/OpenRouter, and database credentials in the server secret store or locked-down env files only.

## Production Requirements

- Expose only `80`, `443`, and locked-down `22` publicly.
- Keep backend, MySQL, speaker service, and Bot ports bound to localhost or private network only.
- Use HTTPS for browser and Azure Bot callbacks.
- Keep `/bot-api/**` behind Java user authorization; direct C# Bot business endpoints must require service signatures in production.
- Enable audit logging, log rotation, database backups, and restore drills before going live.
