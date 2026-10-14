# Ops staff login (Auth0-ready)

The operations UI uses a branded login experience inspired by
[Auth0’s custom Universal Login layout](https://auth0.com/blog/building-beautiful-login-pages-with-auth0/):
product story on the left, framed sign-in card on the right.

## Local / demo

Open `/login`, enter a staff email, and continue. The session is stored in
`localStorage` (`serv60.ops.session`). Ops routes redirect here when unsigned.

## Wire Auth0 Universal Login

1. Create an Auth0 Application (Regular Web App) for Serv60 Ops.
2. Set Allowed Callback URLs to `http://localhost:3000/auth/callback` (and prod).
3. Customize Universal Login with your brand colors (`#ef7145` / `#111718`) or the
   Liquid template from the Auth0 blog (swap “My Product” for Serv60 Ops).
4. Set env:

```sh
NEXT_PUBLIC_AUTH0_DOMAIN=your-tenant.auth0.com
NEXT_PUBLIC_AUTH0_CLIENT_ID=...
NEXT_PUBLIC_AUTH0_LOGIN_URL=/auth/login
```

When domain + client id are present, the login card shows **Continue with Auth0**.
Mount Auth0’s Next.js SDK routes (`/auth/login`, `/auth/callback`, `/auth/logout`)
in a follow-up when the tenant is ready — the UI CTA is already wired to
`NEXT_PUBLIC_AUTH0_LOGIN_URL`.

Customer mobile auth remains **Clerk**; ops staff login is this Auth0-oriented path.
