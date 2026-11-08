# Native local fixture sign-in

This opt-in entry lets the four native apps use the installed synthetic `local-ops-v1` dataset through ordinary authenticated APIs. It does not create identities, repair roles or memberships, bypass API authorization, run external payments, or reset edited fixture records.

Set `sequo.auth.local-mobile-access.enabled=true` only in the private local workspace configuration. Its default is false. Startup also requires the independently validated [development admin settings](DEVELOPMENT_ADMIN_ACCESS.md), `sequo.admin.local-fixtures.enabled=true`, the isolated fixture database/issuer/audience, notification workers explicitly off, runtime payment simulator off, and no configured Yas/Moov credentials. A production, staging, mixed, remote-bind, ordinary-database or ordinary-JWT configuration cannot enable this path, including when the development-default bypass is set.

The request must reach the directly bound loopback socket, contain no forwarding headers, and use a literal loopback Host. The exact Android emulator Host `10.0.2.2` is additionally accepted only with no Origin and a verified loopback socket peer. Native requests may omit Origin; a browser Origin must exactly match configured local origins and cannot use the emulator exception. No DNS alias or nonlocal peer is accepted.

## Client contract

Native fixture controls appear only when the compiled environment is `LOCAL_FIXTURE`, the API target is local, and the server confirms a capability. Production and staging clients must not request or display these controls. A client build flag alone cannot enable the server feature.

`GET /api/auth/development-access?appSource=SEQUO_RIDER` returns:

```json
{"enabled":true,"mode":"LOCAL_FIXTURE","accounts":[{"key":"senam","label":"[TEST] Senam Nayo — indépendant disponible"},{"key":"komlan","label":"[TEST] Komlan Bessa — salarié disponible"}]}
```

Supported fixed families and account keys:

| Family | Keys | Ordinary authority |
| --- | --- | --- |
| `SEQUO_APP` | `ama`, `koffi` | Own customer records |
| `SEQUO_VENDOR` | `yawa`, `mawuli` | Existing owner role and corresponding merchant membership |
| `SEQUO_RIDER` | `senam`, `komlan` | Existing courier role and corresponding courier record |
| `SEQUO_HUB` | `dede`, `esso` | Existing relay role and corresponding active site assignment |

The dataset must have a completed, valid installed manifest. Only entries whose persisted identity, active status, role and scope still match that manifest are returned. Changed or disabled accounts are omitted; the route never restores them. Disabled, untrusted, unknown-family or unavailable requests return `enabled:false`, `mode:null`, and an empty account list. Ordinary CORS rejection may occur before the controller.

`POST /api/auth/development-session` accepts only the fixed family/key selection and normal device metadata:

```json
{"appSource":"SEQUO_RIDER","accountKey":"senam","deviceId":"native-device-uuid","deviceLabel":"Optional local device label"}
```

Serialize `appSource`, `accountKey` and `deviceId` explicitly. Do not define these required request fields as omittable serialization defaults. There is no client-selectable user ID, role, merchant scope or provider. The response is flat `accessToken`, `refreshToken`, `expiresIn`, `user`; `user` is the ordinary current-user DTO including merchant scopes. Vendor deliberately retains existing persisted `SEQUO_HUB` device app-source compatibility; merchant authorization comes from its actual role/membership, not that legacy label.

Both routes use `Cache-Control: no-store`. POST returns 404 if unavailable/ineligible, 400 for invalid device metadata, 409 for a normal existing-device/account conflict, and 429 with `Retry-After` when the existing local-entry limiter is reached. Sign out before using another account on the same device. Use ordinary device-bound refresh rotation, current-user reads, session revocation and logout afterwards. Never log tokens or put them in URLs, fixture guides or screenshots.

## Verification

Offline tests: `DevelopmentMobileAccessPolicyTest` covers disabled defaults, production/staging/mixed/unsafe configuration refusal, direct peer/Host/Origin/forwarding boundaries and the native-only Android alias. `DevelopmentMobileSessionHttpTest` covers all eight fixed accounts, normal role scope, device binding, refresh replay, logout, missing/altered manifest identities, changed memberships, disabled accounts, forbidden arbitrary selection and rate limiting. The unchanged `DevelopmentAdminSessionHttpTest` also passes. These are isolated local tests; provider execution and real physical deliveries are outside this entry.
