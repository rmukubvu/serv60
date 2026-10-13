import { HubOverview } from './hub-overview';
import { AppointmentDialog } from './appointment-dialog';
import { CalendarView } from './calendar-view';
import { PartsReadinessView } from './parts-readiness';
import { RoadsideDispatch } from './roadside-dispatch';

export default function OperationsPage() {
  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <span className="eyebrow">CAR CARE. TIME WELL SPENT.</span>
          <h1>Today at the hub.</h1>
        </div>
        <div className="header-actions">
          <span className="status-pill"><span className="status-dot" aria-hidden="true" /> Daily overview</span>
          <AppointmentDialog />
        </div>
      </header>

      <HubOverview />

      <div className="dashboard-grid">
        <section className="panel calendar-panel" id="calendar">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">01 / THE SERVICE FLOOR</span>
              <h2>Resource calendar</h2>
              <p className="panel-description">Every bay and lifestyle resource, in one view.</p>
            </div>
          </div>
          <CalendarView />
        </section>

        <aside className="side-stack">
          <section className="panel compact-panel" id="dispatch">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">02 / ON THE ROAD</span>
                <h2>Dispatch queue</h2>
              </div>
              <span className="panel-count amber-count">Priority</span>
            </div>
            <RoadsideDispatch />
          </section>

          <section className="panel compact-panel readiness-panel" id="parts">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">03 / READY FOR ARRIVAL</span>
                <h2>Parts readiness</h2>
              </div>
              <span className="panel-count">Live stock</span>
            </div>
            <PartsReadinessView />
          </section>
        </aside>
      </div>
    </main>
  );
}
