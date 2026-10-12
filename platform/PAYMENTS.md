# Payment boundary

The booking domain creates a visit hold; the payment domain records a provider-neutral `payment_order` against that hold. The first implementation is deliberately a durable ledger boundary: it validates the customer, hold ownership, amount, currency, provider and idempotency key before a gateway adapter is introduced.

Supported provider identifiers are `YOCO`, `PEACH` and `PAYFAST`. Amounts are integer minor units in ZAR. Hosted checkout creation, signed webhook verification and reconciliation live behind a provider port and populate `provider_reference` and payment status without changing booking contracts.

The Yoco webhook endpoint is `POST /v1/payments/webhooks/yoco`. It verifies the raw request body with the `webhook-id`, `webhook-timestamp` and `webhook-signature` headers, rejects timestamps older than three minutes, stores each provider event once, and applies only a newer payment state. Configure `SERV60_YOCO_WEBHOOK_SECRET` from the Yoco webhook registration; never accept a success redirect as payment proof.

The mobile client creates a payment order with an `Idempotency-Key`, opens the provider checkout returned by the adapter, and refreshes the order after the `serv60://payment-return` deep link. A payment order is not proof of payment; the signed webhook is authoritative. The mobile journey only invokes confirmation after it observes `PAID`; production must also enable the server-side paid-booking guard before accepting paid reservations.
