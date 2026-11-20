# Authentication

Accounts, access tokens and refresh tokens. Code: `swipread.api.auth`, `swipread.api.security`.

## Endpoints

| Endpoint | Does |
| --- | --- |
| `POST /v1/auth/register` | Creates a `READER` account, returns an access token, sets the refresh cookie |
| `POST /v1/auth/login` | Checks the password, returns an access token, sets the refresh cookie (a new session) |
| `POST /v1/auth/refresh` | Rotates the refresh token and returns a new access token |
| `POST /v1/auth/logout` | Revokes the session of the device and clears the cookie |
| `POST /v1/auth/email/verify` | Confirms the email address with the token of the link sent by mail (public) |
| `POST /v1/me/verification-email` | Sends a new confirmation link (in `UserController`) |
| `PUT /v1/auth/password` | Checks the current password, replaces it, revokes the reader's other sessions (needs the access token; the cookie keeps its own session) |

Request and response details: [API reference](../api.md#auth).

## Two tokens

| | Access token | Refresh token |
| --- | --- | --- |
| Form | HS256 JWT (`sub`, `roles`, `iat`, `exp`, `iss`) | 32 random bytes, base64url |
| Lifetime | 15 min (`swipread.jwt.ttl`) | 30 days, restarted at each renewal (`swipread.auth.refresh-ttl`) |
| Carried in | `Authorization: Bearer` header | `swipread_refresh` cookie: HttpOnly, `SameSite=Strict`, `Path=/v1/auth`, `Secure` outside `dev`/`test` |
| Stored server-side | No (stateless) | SHA-256 hash in `app.refresh_tokens` |
| Revocable | No: it expires; deleted accounts are refused by an extra validator | Yes: logout, reuse detection, account deletion |

`JwtService` issues access tokens; `SecurityConfig` validates them with Spring Security's Resource Server, plus a validator that refuses tokens of deleted accounts (a primary-key lookup per request).

## Refresh tokens

`RefreshTokenService` manages them in `app.refresh_tokens` (migration V7):

| Column | Meaning |
| --- | --- |
| `id` | UUID |
| `user_id` | Owner, `ON DELETE CASCADE` |
| `family_id` | One login on one device; every rotation stays in the family |
| `token_hash` | SHA-256 of the token, unique |
| `created_at`, `expires_at` | `expires_at` = creation + 30 days |
| `revoked_at` | Set when the token is rotated or its family revoked |
| `replaced_by` | The token that replaced it at rotation |

### Rotation and reuse detection

`rotate(token)` locks the row (`SELECT … FOR UPDATE`) and decides:

```mermaid
flowchart TD
    A["POST /v1/auth/refresh<br/>with cookie"] --> B{"hash found<br/>and not expired?"}
    B -- no --> R["401, cookie cleared"]
    B -- yes --> C{"revoked?"}
    C -- no --> D["revoke it, insert a new token<br/>in the same family (30 days)"]
    D --> OK1["200 access token<br/>+ new cookie"]
    C -- yes --> E{"replaced less than 10 s ago<br/>and family still active?"}
    E -- yes --> OK2["200 access token<br/>cookie unchanged (grace)"]
    E -- no --> F{"was it replaced?"}
    F -- yes --> G["reuse: revoke the whole family<br/>(warning in the logs)"] --> R
    F -- "no (logged out)" --> R
```

- **Grace period (10 s, `swipread.auth.refresh-grace`).** Two tabs can renew at the same moment with the same cookie. The second request arrives with a token that was just replaced; instead of treating it as theft, it gets an access token and the browser keeps the newer cookie.
- **Reuse.** A replaced token presented after the grace period means someone else holds an older copy: the family is revoked and every holder must log in again.
- **Logout** revokes the whole family of the presented token.
- **Purge.** A nightly job (03:30 UTC) deletes tokens expired for more than a day.
- **Role refresh.** The access token issued at renewal reads the role from the database, so a promotion or demotion applies within 15 minutes.

### Cookie and CSRF

The refresh endpoints authenticate with a cookie, so they could be targeted by cross-site requests. They are protected by `SameSite=Strict` (the browser does not send the cookie from another site), by CORS (credentials allowed for the configured origins only), and by the fact that their response (an access token) cannot be read cross-origin. Every other endpoint authenticates with the `Authorization` header, which a browser never adds by itself, so CSRF tokens are not needed.

## Accounts

- **Sign-up** normalises the email (trimmed, lowercase), hashes the password with BCrypt, generates a `u_` ULID and defaults the locale to `fr-FR`. A duplicate email answers 409, including the race where two sign-ups hit the unique constraint.
- **Email verification** (`EmailVerificationService`): sign-up sends a link `<web>/verifier-email?token=…` (`AccountMails.verifyEmail`, through the [mail outbox](mail.md)), valid 24 hours. The account works at once; `app.users.email_verified_at` is set when the link is used, and `GET /v1/me` returns `emailVerified`. A new link revokes the older ones; at most one per minute and five per 24 hours (429). Using a link is public, since it may be opened on another device.
- **Mail links** (`UserTokens`, table `app.user_tokens`, V12): 32 random bytes in base64url, stored as SHA-256 with their purpose (`verify_email`, later `reset_password`), creation, expiry and use. `consume` uses a token in one `UPDATE … RETURNING` (unknown, used, replaced or expired: nothing), so a link cannot be used twice even by two concurrent requests. Tokens expired for more than a day are purged every night at 03:35 UTC.
- **Login** answers 401 `"Invalid email or password"` for any mismatch, and runs a BCrypt check against a dummy hash when the email is unknown, so timing does not reveal accounts.
- **Password change** (`AuthService.changePassword`): the current password must match and the new one must differ (400 with `errors[].code` `WrongPassword` or `SamePassword`, built by `ApiException.fieldRejected`). In the same transaction, `RefreshTokenService.revokeOtherSessions` revokes every refresh token of the reader except the family of the cookie sent, if it is the reader's, and a notice is queued (`AccountMails.passwordChanged`). It sits under `/v1/auth` because the cookie is scoped to that path, but unlike the other auth endpoints it needs the access token.
- **Roles**: `READER` (default) or `ADMIN` (column `app.users.role`, migration V3). There is no endpoint to change a role; see [Configuration](../configuration.md#production-checklist).

## Tests

`AuthIntegrationTest` (sign-up, login, 401/409/400 as `ProblemDetail`, English validation messages with codes), `RefreshTokenIntegrationTest` (cookie attributes, 15-minute token, rotation, grace period, reuse revoking the family, missing/unknown/expired tokens, logout, account deletion, CORS with credentials, hashed storage), `EmailVerificationIntegrationTest` (link mailed at sign-up and read back from Mailpit, used once, replaced by a new one, one per minute and five per day, expired, already verified, password change notice), `PasswordChangeIntegrationTest` (other sessions signed out and this one kept, no cookie or another reader's cookie, wrong current password and same password as field errors, sign-up rules, access token required), `RoleIntegrationTest`, `CorsIntegrationTest`.
