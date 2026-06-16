# Permission Management Implementation Record - 2026-06-17

## Completed

- Anonymous share WebSockets now require a short-lived one-time `share-ws-ticket` minted from a valid `share_token`; `/ws/share` and `/ws/share-audio` no longer trust client-supplied `sessionId` during handshake.
- Credential revocation now closes active ASR WebSockets: self password change, logout-all, admin role change, disable, and password reset all increment `token_version` and close the user's realtime connections.
- Audit writes now use `audit_outbox` plus a scheduled dispatcher into final append-only `audit_log`; critical audit outbox write failure is fail-closed.
- `/api/internal/ops/**` is protected by dedicated `X-Internal-Ops-Secret` plus optional IP allowlist and is catalogued as a service/internal operations entry point.
- Frontend security operations page `#/security-operations` is implemented for meeting members, support grants, and audit logs.
- Flyway dependencies and permission table migrations are present but default disabled with `FLYWAY_ENABLED=false`; production still requires baseline, backup, and rehearsal before enabling.
- MFA remains explicitly excluded from P6 and the current permission delivery.

## Automated Verification

- `si-backend`: `mvn -q test` passed.
- `si-frontend`: `npm.cmd exec tsc -- --noEmit` passed.
- `si-frontend`: `npm.cmd run build` passed.
- `bot`: `dotnet test CallingBotSample.Tests\CallingBotSample.Tests.csproj` passed.

## Manual / Environment Verification Remaining

- Use real admin/operator accounts to verify `#/security-operations`: meeting member assign/revoke, support grant request/approve/revoke, and audit log display.
- Use a real share link to verify `/ws/share` text stream and `/ws/share-audio` language streams connect, refresh, and reconnect by minting new tickets.
- In a full backend plus database environment, verify account security password change/logout-all immediately invalidates old API requests and active ASR WebSockets.
- In a production maintenance window, baseline Flyway, rehearse empty/stored database migrations, back up, and only then consider `FLYWAY_ENABLED=true`.
