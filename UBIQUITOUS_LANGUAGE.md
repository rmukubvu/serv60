# Serv60 Ubiquitous Language

Proposed business vocabulary for the Serv60 DDD model, 11 October 2026; validate with the founder, hub manager, storekeeper and dispatcher before implementation.

## Customers and hub resources

| Term | Definition | Aliases to avoid |
|---|---|---|
| **Customer** | A person or organization receiving or purchasing Serv60 services. | User, login, account |
| **User Identity** | An authenticated identity with explicitly granted customer, staff or partner access. | Customer, account |
| **Hub** | A physical Serv60 location with its own operating capacity and stock. | Tenant, branch when scope is ambiguous |
| **Resource** | A specific capacity unit required to deliver an appointment, such as a bay, qualified person, room, chair or cubicle. | Slot, service |
| **Resource Calendar** | The opening, shift, break and exception rules governing a resource's schedulable time. | Booking list, personal calendar |
| **Service Template** | A versioned definition of service requirements, eligibility, duration and stage offsets. | Appointment, work order |
| **Express Visit Target** | The 60-minute end-to-end target for an eligible automotive visit including inspection, maintenance, QC, wash and handover. | Guaranteed repair time, 60-minute service for every car |

## Visits and appointments

| Term | Definition | Aliases to avoid |
|---|---|---|
| **Visit** | A customer's planned itinerary at one hub containing one or more related appointments. | Appointment, work order |
| **Appointment** | One scheduled activity within a visit, such as automotive maintenance, barber, massage or private workspace use. | Visit, bay slot |
| **Visit Booking** | The accepted selection, terms and lifecycle of a planned visit. | Payment, job |
| **Hold** | An expiring temporary claim on a visit's required resources and policy-specific parts before confirmation. | Confirmed booking, payment authorization |
| **Resource Allocation** | An exclusive interval reserved for a specific resource under a hold or appointment. | Appointment, available slot |
| **Available Slot** | A currently feasible proposed time for the requested selection, subject to transactional reservation. | Guaranteed reservation |
| **Confirmed Booking** | A visit whose required appointment capacity is accepted under the disclosed booking and parts policy. | Paid, ready for express |
| **Ready for Express** | A confirmed eligible automotive appointment with verified fitment and a complete usable kit reserved at its hub. | Confirmed, supplier ordered |
| **Conditional Appointment** | An appointment accepted with an explicit unresolved parts/fitment condition and readiness deadline. | Ready for express, unconditional commitment |
| **Accepted Quote** | The versioned scope, price, currency and conditions explicitly approved by the customer. | Payment, invoice, estimate after approval |
| **Rescheduling** | An approved replacement of appointment timing or selection that preserves the original until the replacement succeeds. | Calendar drag, cancellation |

## Vehicle planning, stock and parts

| Term | Definition | Aliases to avoid |
|---|---|---|
| **Vehicle Identity** | The identification and variant details needed to verify service and recovery requirements. | VIN alone, generic car model |
| **Fitment Verification** | The confirmed compatibility of specified parts and materials with the vehicle and approved service. | VIN decoded, AI prediction |
| **Provisional Service Scope** | The requested automotive activity whose fitment, exact requirements or eligibility still require review. | Approved service plan, ready for express |
| **Approved Service Plan** | A versioned, reviewed automotive scope with verified fitment, requirements and a resolved bill of materials. | Service template, generic service menu |
| **Bill of Materials (BOM)** | The approved part identifiers, quantities, material specifications and permitted substitutions for a service plan. | Shopping list, generic filter |
| **Usable On-Hand Stock** | Physically received stock at a hub that is eligible for use and not quarantined. | Supplier stock, in transit |
| **Stock Reservation** | A claim on usable parts quantities for a particular job that has not yet consumed them. | Stock movement, purchase order |
| **Parts Demand** | A recorded requirement for parts not yet fully covered by a usable stock reservation. | On-hand stock, ready kit |
| **Service Kit** | The verified set of parts/materials prepared for one approved automotive job. | Purchase order, delivery |
| **Stock Movement** | An auditable receipt, consumption, return, transfer or adjustment affecting stock balances. | Reservation |
| **Purchase Order** | An approved request to a supplier for specified items whose outcome and receipts are tracked. | Stock received, kit ready |
| **Parts-Readiness Deadline** | The disclosed latest time by which a conditional appointment's verified kit must be ready or its exception resolved. | Supplier ETA |

## Workshop execution

| Term | Definition | Aliases to avoid |
|---|---|---|
| **Work Order** | The controlled automotive job scope and execution record for a vehicle. | Visit, appointment, payment |
| **Check-In** | The recorded arrival and acceptance of the customer/vehicle for the planned hub work. | Booking confirmation |
| **Actual Occupancy** | A resource's real physical use, independent of its planned allocation end time. | Calendar end time |
| **Scope Change** | Proposed additional or altered work requiring customer approval of timing and charges. | Automatic upsell, already approved repair |
| **Quality Check (QC)** | The required recorded verification that completed work meets the job's release criteria. | Wash, handover |
| **Vehicle Handover** | The recorded release of the completed vehicle to an authorized recipient. | Tow delivery, payment success |
| **Repair Intake** | The hub's received breakdown/repair vehicle awaiting assessment or an approved repair plan. | Express appointment, ready work order |

## Roadside recovery and customer transport

| Term | Definition | Aliases to avoid |
|---|---|---|
| **Roadside Incident** | A customer assistance case coordinating required vehicle recovery and optional personal transport. | Visit, tow job, insurance claim |
| **Tow Job** | The quoted and approved vehicle recovery leg of a roadside incident. | Ride trip, whole incident |
| **Approved Tow Partner** | A towing provider with verified capability, agreed coverage and commercial terms. | Any available driver |
| **Dispatch Offer** | An expiring request for an eligible partner to accept a particular tow job. | Assignment, confirmed truck |
| **Tow Assignment** | The one current accepted provider responsibility for a tow job. | Offer, ETA |
| **Custody Handover** | The recorded transfer of responsibility for the vehicle between identified parties. | Customer ride completion |
| **Ride-Home Trip** | The separately approved passenger transport leg associated with an incident. | Tow, embedded Uber booking |
| **Unresolved Transport** | A required customer transport leg not fulfilled or replaced by an explicitly accepted alternative. | Closed incident after tow |

## Payments and resolution

| Term | Definition | Aliases to avoid |
|---|---|---|
| **Payment Intent** | A durable request to perform a specific approved financial operation through a provider. | Confirmed payment, accepted quote |
| **Payment Authorization** | A verified provider approval to hold or make available an agreed amount under the provider's contract. | Capture, booking confirmation |
| **Payment Capture** | A verified provider result recording collection of an authorized/approved amount. | Authorization, redirect success |
| **Refund** | A separately tracked financial return of a previously collected amount. | Cancelled booking, voided authorization |
| **Unknown Provider Result** | An operation whose external outcome is not yet established after a timeout or ambiguous response. | Failed request, safe to retry create |
| **Reconciliation** | The resolution of local/provider records to a known outcome before repeating or closing an uncertain operation. | Blind retry |

## Relationships

- A **Visit** belongs to one **Customer** and one **Hub**, and contains one or more **Appointments**.
- An **Appointment** requires one or more **Resource Allocations**; a barber requires both a chair and a barber, and massage requires a room and a therapist.
- A **Hold** protects a proposed **Visit Booking** until it is confirmed, released or expired.
- An automotive **Appointment** initially refers to a **Provisional Service Scope** while review is pending, and then to an **Approved Service Plan** after verification; **Ready for Express** requires that approved plan and its complete reserved kit.
- An approved automotive **Appointment** may create a **Work Order**; roadside **Repair Intake** creates a work order only after assessment/approval.
- A **Service Kit** matches an **Approved Service Plan**; **Stock Reservations** protect its quantities until consumption or release.
- A **Roadside Incident** may contain a **Tow Job** and a **Ride-Home Trip**, whose outcomes remain independent.
- A **Tow Job** has at most one active **Tow Assignment**; a **Dispatch Offer** does not establish an assignment.
- A **Payment Intent** references the relevant **Accepted Quote** and operation, without becoming the owner of service/recovery scope.

## Example dialogue (illustrative)

> **Developer:** “Once the customer's payment succeeds, is the visit ready for express?”
>
> **Hub manager:** “Payment alone does not establish that; the booking must still have valid capacity, verified fitment and its complete kit.”
>
> **Developer:** “So a confirmed booking may still be a conditional appointment?”
>
> **Storekeeper:** “Yes, if that condition was accepted; supplier delivery estimates are not usable stock, and the parts-readiness deadline still applies.”
>
> **Developer:** “If a tow delivers the car, may we close the roadside incident?”
>
> **Dispatcher:** “Only when every required leg is fulfilled or an alternative is explicitly accepted; the ride-home trip may still be unresolved.”

## Flagged ambiguities

- **Booking** is often used for a whole visit, one appointment, or a payment; use **Visit Booking** for accepted itinerary terms and **Appointment** for a scheduled activity.
- **Confirmed** and **ready** are distinct; capacity acceptance under a conditional policy does not imply **Ready for Express**.
- **Slot** may refer to a bay interval or a whole feasible bundle; show **Available Slot** as a proposal and persist **Resource Allocations** for every required capacity unit.
- **Calendar** refers to operating rules and allocations here, rather than a customer phone calendar or an external calendar provider.
- **Service** may mean a business activity or a deployed backend process; use the named activity in business discussion and describe deployment separately.
- **Parts in store** means **Usable On-Hand Stock** at the correct hub, rather than supplier availability or a placed **Purchase Order**.
- **Safe ride home** describes coordinated safeguards, not guaranteed driver availability or safety; required transport remains open until fulfilled or an alternative is accepted.
- **Provider** can mean a barber/therapist, tow partner, ride platform or payment company; name the role/context explicitly.
- **Account** can mean a customer, user identity, organization or provider billing account; use the precise term.
