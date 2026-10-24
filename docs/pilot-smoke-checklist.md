# Pilot smoke checklist

Run against Compose with migrations V13–V18 applied and illustrative discovery seed loaded.

## Hub visit

1. Sign in (or local demo with Cognito unset).
2. Open **Account**, save name/phone and a default vehicle.
3. **Explore** → use location or Cape Town demo → open hub **Details** → **Book a visit**.
4. Confirm contact is prefilled; choose complimentary option + slot → **Reserve**.
5. On **Reservation held**, pay with Yoco (or note pay-later when price is zero).
6. **Bookings**: change within 48h window; cancel a hold or confirmed visit with notice.
7. Confirm cancelled capacity is released on the ops calendar.

## Roadside

1. **Roadside** tab → map shows nearby seeded tow trucks.
2. Pin location → optionally enable **ride home** → **Request tow quotes**.
3. Accept a quote → status shows SEARCHING with "Our dispatcher is confirming the truck".
4. If ride home was requested, the ride leg shows REQUESTED with a local partner; the dispatcher arranges it (no ride provider is integrated, so there is no tracking link or PIN).
5. Ops **Dispatch board** → **Confirm truck dispatched** with an ETA → the app shows EN_ROUTE with the truck and ETA.
6. Record **Truck arrived**, **Towing**, **Completed** → the incident leaves the board.
7. A second request: cancel it from the app before dispatch; a third: confirm dispatch, then check the app refuses to cancel and the board can.

## Live floor

Run with `SERV60_DEMO_FLOOR_SIMULATION=true` and `seed-demo-floor.sql` applied (see `platform/DEMO.md`).

1. Ops **Live floor** → the seeded cars are grouped *Arriving*, *On the floor* and *Handed over*; the Hilux shows "1 awaiting customer" and does not move.
2. **Bookings** → the Corolla Cross visit → **Track your visit** → "We'll start the clock when you arrive."
3. Within a tick the car is checked in on both screens: coffee voucher on the phone, minute count on the board.
4. Board: **Propose extra work** on the Corolla Cross → the phone shows the items and total; the car stops advancing.
5. Phone: **Approve · settle at handover** → the board badge clears and the car continues to *Ready for collection*.
6. Board: **Hand over** → the phone shows the thank-you copy; the card moves to *Handed over*.
7. Turn the simulation off and repeat with the stage buttons: out-of-order stages are refused with a message.

## Ops self-sufficiency

1. Ops shell: save staff Cognito token when security is enabled.
2. Switch hub when `NEXT_PUBLIC_SERV60_HUB_IDS` lists more than one.
3. Calendar: previous/next day; filter Held/Confirmed; confirm holds; cancel confirmed.
4. Create appointment including **Massage**; blank customer ID creates a walk-in UUID.
5. **Hub settings**: set complimentary capacity to 0 and confirm it disappears from customer discovery; restore capacity.

## Configuration gates

- `SERV60_EXPRESS_PRICE_MINOR` > 0 for customer payment.
- `OPENROUTER_API_KEY` optional (assistant); blank keeps manual booking.
- Roadside quotes need tow partners: load `platform/infra/seed-roadside-partners.sql` for local demos only. Migrations ship none.
