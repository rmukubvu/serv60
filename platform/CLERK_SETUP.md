# Clerk and Google sign-in

Serv60 uses **Clerk** as the customer identity provider. Google is enabled in
Clerk (not wired directly into the Flutter app). The mobile app uses
Authorization Code + PKCE against Clerk’s OIDC discovery document and sends
Clerk JWTs to the API.

## 1. Create a Clerk application

1. Create an application at [dashboard.clerk.com](https://dashboard.clerk.com).
2. Under **User & Authentication → Social connections**, enable **Google** and
   complete Google Cloud OAuth (Clerk documents the redirect URI to register).
3. Under **Configure → Native applications** (or OAuth applications), create a
   **Native** / public OAuth client for the Flutter app.
4. Add redirect URIs:
   - `serv60://oauth/callback`
   - `serv60://oauth/logout` (post-logout, if offered)
5. Copy:
   - **Frontend API** URL (e.g. `https://your-app.clerk.accounts.dev`)
   - **OAuth client ID** for the native app

## 2. Flutter dart-defines

```sh
flutter run -d <device> \
  --dart-define=SERV60_DEMO_MODE=true \
  --dart-define=SERV60_API_BASE_URL=http://10.0.0.7:8080 \
  --dart-define=SERV60_CLERK_FRONTEND_API=https://your-app.clerk.accounts.dev \
  --dart-define=SERV60_CLERK_OAUTH_CLIENT_ID=<native-oauth-client-id> \
  --dart-define=SERV60_CLERK_REDIRECT_URL=serv60://oauth/callback \
  --dart-define=SERV60_CLERK_LOGOUT_REDIRECT_URL=serv60://oauth/logout
```

Omit the Clerk defines for local demo mode without sign-in (API with
`SECURITY_ENABLED=false`).

Deep links: see `mobile/DEEP_LINK_SETUP.md` (`serv60` URL scheme).

## 3. API configuration

```sh
SECURITY_ENABLED=true
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://your-app.clerk.accounts.dev
```

Spring Boot loads Clerk’s JWKS from the issuer’s OIDC discovery document.

## 4. Customer identity claims

| Claim | Meaning |
| --- | --- |
| `customer_id` | Preferred Serv60 customer UUID (JWT template / metadata) |
| `sub` | Clerk user id (`user_…`) — mapped to a stable UUID via `UUID.nameUUIDFromBytes("serv60:clerk:" + sub)` when no UUID claim is present |
| `roles` / `cognito:groups` | Optional staff roles (`HUB_OPERATOR`, `ADMIN`) |
| `hub_ids` | Optional hub membership for operators |

Cognito's `custom:customer_id` and `custom:hub_ids` are read only when
`SERV60_SECURITY_TRUST_COGNITO_CUSTOM_ATTRIBUTES=true`. Set it only after removing
every app client's write access to those attributes; otherwise a user could edit
their own attribute and act as another customer or join a hub.

Set `SERV60_SECURITY_AUDIENCES` to the client ids your apps use (comma-separated).
A token must name one of them in `aud`, `client_id` (Cognito access tokens) or
`azp` (Clerk). Check that your mobile tokens carry `azp` before enabling it for
Clerk. Left empty, any client of the issuer is accepted and startup logs a warning.

Optional Clerk JWT template (recommended for production):

```json
{
  "customer_id": "{{user.public_metadata.customer_id}}"
}
```

Provision `public_metadata.customer_id` with a Clerk webhook when the first
customer profile is created, if you want explicit UUIDs instead of the derived
mapping.

## 5. Sign-in UX

The Flutter **Continue with Google** button starts Clerk’s OIDC authorize flow.
With Google enabled in Clerk, customers pick Google on Clerk’s hosted screen
(or account chooser) and return to the app via `serv60://oauth/callback`.
