# Cognito and Google sign-in

Serv60 uses an Amazon Cognito User Pool as the API token issuer. Google is a
federated identity provider: customers sign in with Google through Cognito
Managed Login, and the applications receive Cognito JWTs rather than Google
tokens.

## Google provider setup

1. Create a Web OAuth client in Google Cloud for the Cognito user-pool domain.
2. Register `https://<cognito-domain>/oauth2/idpresponse` as an authorized
   redirect URI.
3. Add Google under the user pool's **Social and external providers**.
4. Configure scopes `openid email profile` and map the email and profile
   attributes.
5. Enable Google on the Cognito app client and configure callback URLs for the
   web and mobile applications.

The Flutter app should use Authorization Code + PKCE through the system
browser. The operations web app should use the Cognito managed-login redirect
and a secure application session. Neither client stores a Google client secret.

## Serv60 authorization claims

The API accepts the following claims after Cognito has issued the JWT:

| Claim | Meaning |
| --- | --- |
| `custom:customer_id` | Serv60 customer identifier for customer-owned resources |
| `custom:hub_ids` | Comma-separated or list of hub IDs available to the principal |
| `cognito:groups` | `HUB_OPERATOR` or `ADMIN` for staff operations |

Google authentication establishes identity only. A customer record and staff
group membership are provisioned by Serv60; an email address is not used as a
stable identifier or as an operator permission.

## API configuration

Expose the API only with security enabled and an issuer URI:

```sh
SECURITY_ENABLED=true
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>
```

Local compose and tests explicitly use security-disabled mode. Production
deployment must set the issuer URI and use a custom Cognito domain where the
redirect URLs are controlled by Serv60.
