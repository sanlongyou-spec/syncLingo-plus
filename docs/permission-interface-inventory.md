# Permission Interface Inventory

This inventory is the P0 authorization baseline. It is generated and enforced from code before release:

- Spring MVC HTTP handlers are discovered from Swagger/OpenAPI mappings. Each handler must resolve an
  `@AuthorizationSpec`; `AuthorizationOpenApiCustomizer` publishes `x-identity`, `x-permission`,
  `x-resource-scope`, and `x-expected-status-codes`.
- Swagger cannot discover WebSocket registrations, the Java Bot proxy's exact operation allowlist, C# Bot
  routes, scheduled/async tasks, or framework-provided HTTP endpoints such as Actuator. Those entry points are
  discovered by the release test and matched against
  `si-backend/src/main/resources/authorization-non-http-entrypoints.json`. Exposed Actuator endpoints and
  configured Swagger paths are derived from `application.yml`; wildcard Actuator exposure is rejected.
- `PermissionEntryPointCoverageTest` writes the merged machine-readable report to
  `si-backend/target/permission-entrypoints-report.json`.
- `scripts/verify-permission-entrypoints.sh`, the Docker build, and `scripts/deploy-and-test.sh` fail when a
  discovered entry point is unclassified or a catalog entry is stale.

Raw `CompletableFuture` work is an internal implementation chain rather than an externally invokable entry point.
Authorization must be completed before enqueueing and actor/resource identifiers must be passed explicitly.
For scheduled/async task entries, status `200` in the normalized report means successful task completion; these
tasks do not emit an HTTP response.

## HTTP Entry Points

| Entry point | Identity | Current P0.5 rule | Resource resolution | Failure contract | Automated coverage |
|---|---|---|---|---|---|
| `POST /api/auth/login` | Anonymous | Public | Account by username | Authentication failure uses existing auth error; after soft throttle requires captcha `428` | `AuthServiceTest`, `CaptchaChallengeServiceTest`, `LoginThrottleServiceTest` |
| `POST /api/auth/refresh` | Anonymous | HttpOnly refresh cookie + same-origin check + IP limit | `auth_session.refresh_token_hash` family | `401` invalid/expired/reused refresh; `403` origin mismatch; `429` limited; reused old refresh revokes family | `AuthSessionServiceTest`, `AuthControllerSecurityTest` |
| `POST /api/auth/logout` | Anonymous | HttpOnly refresh cookie | `auth_session` family | Clears cookie and revokes family when present | `AuthControllerSecurityTest` |
| `GET /api/auth/captcha` | Anonymous | Public | Account by username + remote IP | Issues one-time, 5-minute captcha challenge for throttled login flow | `CaptchaChallengeServiceTest` |
| `POST /api/auth/register` | Anonymous | Explicitly closed | None | `403` | `AuthControllerSecurityTest` |
| `/api/interpretation/public/**` | Anonymous | Token share resolve/info/results remain public; legacy user active lookup returns `410`; latency is field-limited and rate limited; share WS tickets are minted from a valid share token | Share token or public session; legacy target user id is not resolved | `410` legacy active lookup; `400` invalid latency; `429` limited; invalid share WS ticket closes the socket | `PublicInterpretationEndpointSecurityTest`, `AnonymousRequestRateLimiterTest`, `ShareTokenServiceTest`, `ShareWsTicketServiceTest` |
| `/api/interpretation/start`, `/stop`, `/status/**`, `/history/**`, `/results`, `/records/**`, `/session-speakers/**` | User | OWN only | Actor user id; `interpretation_session.user_id` | `401` no actor; `404` missing/other resource | `ResourceOwnershipPolicyTest`, `AuthContextTest`, `UserIdBoundaryControllerTest` |
| `/api/interpretation/sessions`, title/delete | User | SELF/OWN only | Actor user id, then session owner | `401` no actor; `404` other session | `UserIdBoundaryControllerTest`, `ResourceOwnershipPolicyTest` |
| `/api/summary/**`, `/api/meeting-materials/**` | User | OWN only | Parent session owner | `404` missing/other session | `ResourceOwnershipPolicyTest` |
| `/api/meetings/**` | User | OWN only during P0.5 | Explicit actor; meeting owner; file path `meetingId` must match file parent; speaker summary/action item resolve through parent meeting/session | `404` missing/other resource | `ResourceOwnershipPolicyTest`, `MeetingServiceSecurityTest` |
| `/api/pre-meeting/**` | User | SELF for usage/cross-meeting; OWN when meeting/session is supplied | Actor user id; meeting/session parent | `401` no actor; `404` other meeting/session | `UserIdBoundaryControllerTest`, `ResourceOwnershipPolicyTest` |
| `/api/translate` | User | SELF only | Actor user id | `401` no actor | `UserIdBoundaryControllerTest` |
| `/api/terminology/**`, `/api/asr-hotwords/**`, `/api/language-preferences/**`, `/api/audio-records/**`, `/api/cost/monthly-summary` | User | SELF only | Actor user id; service query also includes user id for child ids; missing owned terminology/hotword mutation is rejected | `401` no actor; `404` other child id | `AuthContextTest`, `OwnedMutationSecurityTest` plus existing service tests |
| `/api/user/preference/**` | User | SELF from actor only | Actor user id | `401` no actor | `UserPreferenceControllerSecurityTest` |
| `/api/system-users/**` | User | Authenticated; role restriction deferred to P1 | Directory record | P1: `403` without directory permission | P1 coverage required |
| `/api/teams-bot/**` | Service | Existing Bot secret plus current JWT filter behavior; dedicated service filter deferred to P4 | Bot-matched user | `401`/service denial | P4 coverage required |
| `/api/admin/**` | Admin secret | Header `X-Admin-Secret` only; log download query secret removed | Admin operation | `401` invalid/missing secret | `SecurityConfigValidatorTest`; CLI download script |
| `/api/internal/ops/**` | Service | Dedicated `X-Internal-Ops-Secret` plus optional IP allowlist; excluded from browser JWT flow | Internal operations only | `401` missing/invalid ops secret; `403` denied source IP | `InternalOpsAuthFilterTest` |
| `/bot-api/**` | User allowlist | JWT + non-empty user allowlist + exact method/path allowlist; safe headers only; fixed target | Actor user id | `401` no JWT; `403` user/operation denied | `JwtAuthFilterTest`, `BotApiProxyControllerTest` |
| `/api/health`, `/actuator/health` | Anonymous | Public health only | None | Normal health response | Deployment health check |
| Swagger/OpenAPI paths | Anonymous in dev | Disabled in production | None | `404` in production | Production configuration validation |

## WebSocket Entry Points

| Entry point | Identity | Rule | Failure contract | Automated coverage |
|---|---|---|---|---|
| `/ws/asr` | User | One-time handshake ticket binds user id; `start` binds one owned session; audio/stop/translate require the same active binding; one controller connection per session | Handshake reject or policy close | `JwtHandshakeInterceptorTest`, `AsrWebSocketHandlerSecurityTest`, `WsTicketServiceTest` |
| `/ws/share`, `/ws/share-audio` | Anonymous share listener | One-time 60s share WS ticket minted from a valid share token; no client-supplied session id is trusted after handshake | Policy close on missing/invalid/expired ticket | `ShareWsTicketServiceTest`, `ShareAudioLoadTest` |

## Non-MVC Entry Points

| Entry point | Identity | Rule |
|---|---|---|
| Java to C# automatic summary notification | Service integration | Direct fixed Bot target; not routed through `/bot-api/**` |
| C# Bot production source | Service deployment | Only `bot/CallingBotSample`; `scripts/verify-bot-production-source.sh` rejects external sample deployment references |
| Async summary/embedding/hotword jobs | Captured actor or already-authorized resource id | Authorization happens before enqueue; actor/user id is passed explicitly where required |

## Known P0.5 Residuals

- Temporary in-memory pre-meeting upload `fileId` values do not yet persist an owner relation. They remain opaque compatibility handles; persistent meeting files are protected through their parent meeting.
- Runtime DDL is still enabled until the production database has been baselined and the startup initializers can be disabled in a controlled release window.
