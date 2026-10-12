# Payment boundary

The booking domain creates a visit hold; the payment domain records a provider-neutral `payment_order` against that hold. The first implementation is deliberately a durable ledger boundary: it validates the customer, hold ownership, amount, currency, provider and idempotency key before a gateway adapter is introduced.

Supported provider identifiers are `YOCO`, `PEACH` and `PAYFAST`. Amounts are integer minor units in ZAR. Gateway checkout creation, signed webhook verification, capture/refund and reconciliation belong behind a provider port and will populate `provider_reference` and the payment status without changing booking contracts.

The mobile client should create a payment order with an `Idempotency-Key`, then use the provider-specific adapter response when that adapter is enabled. A payment order is not proof of payment; booking confirmation should require a successful, reconciled payment state when paid booking enforcement is enabled.
