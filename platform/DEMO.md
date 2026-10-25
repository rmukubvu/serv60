# Demo: a car through the hour, live on the floor board

This runs the whole platform on one laptop so a visit can be followed on the phone app while the
hub operations console shows the floor moving in real time. Nothing here is a mock: the app, the
console and the backend share one database, and the only demo-specific part is a **floor
simulation** that advances cars through the stages so a technician does not have to.

## What you will show

1. The customer app: a confirmed booking, **Track your visit**, the car checked in, minutes
   against the 60-minute target, the stage timeline, a coffee voucher, extra work proposed by
   the workshop approved with one tap, "ready for collection".
2. The operations console, **Live floor**: every car on the floor with its stage and minute
   count, the proposal waiting for the customer, and the buttons staff use to check a car in,
   record the next stage, propose extra work and hand the car over.

## One-time setup

- Docker Desktop running.
- Node 20+ for the console (`platform/operations-web`, `npm install` once).
- Flutter stable for the app (`platform/mobile`, `flutter pub get` once) and either a simulator
  or a phone on the same Wi-Fi as the laptop.

## Start the stack (five minutes before the demo)

From the repository root:

```sh
SERV60_DEMO_FLOOR_SIMULATION=true SERV60_EXPRESS_PRICE_MINOR=0 \
  docker compose -f platform/infra/docker-compose.yml up -d --build postgres backend
```

**If a phone will connect over Wi-Fi**, the Mac firewall may block Docker's backend helper
(`com.docker.backend`) from accepting connections from other devices. Run the backend with Java
instead, which the firewall usually already allows, and keep only PostgreSQL in Docker:

```sh
docker compose -f platform/infra/docker-compose.yml up -d postgres
(cd platform/backend && mvn -B -DskipTests package)
DB_URL=jdbc:postgresql://localhost:5432/serv60 SECURITY_ENABLED=false OUTBOX_SQS_ENABLED=false \
  SERV60_DEMO_FLOOR_SIMULATION=true SERV60_EXPRESS_PRICE_MINOR=0 \
  java -jar platform/backend/target/serv60-platform-0.1.0-SNAPSHOT.jar
```

Wait for `curl -s localhost:8080/actuator/health` to say `UP`, then seed the hub, its tow
partners and this morning's floor:

```sh
for f in seed-customer-discovery seed-roadside-partners seed-demo-floor; do
  docker compose -f platform/infra/docker-compose.yml exec -T postgres \
    psql -U serv60 -d serv60 -v ON_ERROR_STOP=1 < platform/infra/$f.sql
done
```

The floor seed creates six cars around the current time: the demo customer's Corolla Cross
about to arrive, a Polo at quality check, a Hilux held at inspection while a R1,770 proposal
waits for the customer, two cars booked for later, and an i20 already handed over. Re-run it any
time to reset the floor.

Start the console:

```sh
cd platform/operations-web
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 NEXT_PUBLIC_SERV60_DEMO_MODE=true npm run dev
```

Open <http://localhost:3000>, enter any email, and click **Live floor**. Put this on the big
screen.

Start the app, pointing it at the laptop. On an iPhone plugged in over USB, a release build runs
without the debugger attached (replace the IP with the laptop's Wi-Fi address from
`ipconfig getifaddr en0`; use `http://10.0.2.2:8080` on an Android emulator):

```sh
cd platform/mobile
flutter run --release -d "RobsoniPhone" \
  --dart-define=SERV60_DEMO_MODE=true --dart-define=SERV60_API_BASE_URL=http://<laptop-ip>:8080
```

The first launch asks for local-network permission; allow it, or the phone cannot reach the
laptop. Phone and laptop must share the Wi-Fi network and port 8080 must be open on the laptop.

With no Clerk settings the app skips sign-in and the backend, running with security off, treats
every call as the demo customer, Alex Demo, who owns the Corolla Cross.

## The script

| Step | On the phone | On the big screen |
|---|---|---|
| 1 | Open **Bookings**. The Corolla Cross visit is there, confirmed. Tap **Track your visit**: "We'll start the clock when you arrive." | **Live floor** shows the Corolla Cross under *Arriving*, the Polo and the Hilux under *On the floor*, the i20 under *Handed over*. |
| 2 | Within ten seconds the screen changes: checked in, 0 of 60 min, a coffee voucher. | The simulation checked the car in (or press **Check in** yourself). The card moves to *On the floor* and its minute count starts. |
| 3 | Watch the stage timeline move: inspection, then service. | Stages advance on their own, about four and a half minutes from check-in to ready at the default pace. |
| 4 | Nothing yet. | On the Corolla Cross card press **Propose extra work**; the form is prefilled with front brake pads at R1,450. Submit. The card shows "1 awaiting customer" and the car stops advancing. |
| 5 | The proposal appears with the items and total. Tap **Approve · settle at handover**. | The badge clears and the car moves on to quality check, wash, ready. |
| 6 | "Your car is ready for collection." | Press **Hand over** on the card. |
| 7 | "Thanks for visiting." | The car is under *Handed over*. |

Optional: the Hilux under *On the floor* already has a proposal waiting. It belongs to another
customer, so it stays put all demo long, which is the point: the floor waits for the customer.

To book live instead of using the seeded visit: **Explore** → Cape Town demo → a hub → **Book
a visit** → pick a slot → **Reserve**. With the express price at zero the reservation is held
without payment; confirm it from the console's **Service floor** calendar, and it appears on the
floor board. To take a real test payment instead, set the Yoco keys described in `PAYMENTS.md`
and leave `SERV60_EXPRESS_PRICE_MINOR` at its default.

## How live is live

The floor board and the app each hold a server-sent event stream open to the backend
(`/v1/hubs/{hubId}/floor/stream` and `/v1/customer/appointments/{id}/progress/stream`). Every
committed change on the floor pushes a fresh snapshot down both streams, so a stage recorded on the
board or an approval tapped on the phone shows on the other screen within a second. Both clients
also poll every 30 seconds as a safety net and reconnect on their own if the stream drops.
Notifications when the app is closed (lock-screen alerts) are not part of this; they need Apple
push credentials and are a separate step.

## Knobs

| Variable | Default | Effect |
|---|---|---|
| `SERV60_DEMO_FLOOR_SIMULATION` | `false` | Turns the simulation on. Off, staff record every stage from the console. |
| `SERV60_DEMO_FLOOR_STAGE_SECONDS` | `60` | Pace. Servicing takes twice this, check-in a third; ready in about 4.5× this many seconds. Use `30` for a two-minute visit. |
| `SERV60_DEMO_FLOOR_AUTO_CHECK_IN` | `true` | Whether cars whose booked time has passed are checked in on their own. |
| `SERV60_EXPRESS_PRICE_MINOR` | `24900` | Zero lets a live booking be reserved without a card. |

The simulation never moves a car past *ready* and never moves one with a proposal waiting, so
handover and approvals stay human. It is a backend flag; the production configuration leaves it
off and the backend logs a warning whenever it is on.

## If something is off

- Floor board empty: the seed ran before midnight and the demo is after it. Re-run
  `seed-demo-floor.sql`.
- App shows no bookings or a "connection refused" error: the phone is not reaching the laptop.
  Check the IP in `SERV60_API_BASE_URL`, that the phone allowed local-network access, and that
  the process listening on 8080 is allowed through the Mac firewall (`lsof -nP -iTCP:8080
  -sTCP:LISTEN` names it). Running the backend with Java, above, avoids the Docker helper.
- The app will not open on the phone: trust the developer under Settings → General → VPN &
  Device Management (the entry appears after you tap the app icon once).
- A car is not moving: it has a proposal waiting (the badge says so), or the simulation is off.
  `docker compose logs backend | grep -i simulation` shows whether it started.
- Console says it cannot load the floor: the backend is still starting, or the demo mode variable
  was not set when `npm run dev` started.
