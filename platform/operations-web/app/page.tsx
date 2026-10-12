import { AppointmentDialog } from './appointment-dialog';
import { CalendarView } from './calendar-view';
import { PartsReadinessView } from './parts-readiness';
import { RoadsideDispatch } from './roadside-dispatch';

const metrics = [
  { label: 'Scheduled today', value: 'Live', note: 'Resource allocations' },
  { label: 'Ready for express', value: '18', note: '2 parts exceptions' },
  { label: 'Active incidents', value: 'Live', note: 'Dispatch queue' },
  { label: 'Hub capacity', value: '74%', note: 'Wash line is the constraint' },
];

export default function OperationsPage() {
  return (
    <main className="shell">
      <div className="masthead">
        <div className="wordmark" role="img" aria-label="Serv60">serv<span>60</span><i aria-hidden="true" /></div>
        <span className="masthead-caption">HUB 01 / OPERATIONS</span>
      </div>
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

      <div className="context-strip">
        <span>At a glance</span>
        <span>Demo planning figures</span>
      </div>

      <section className="summary" aria-label="Hub summary">
        {metrics.map((metric) => (
          <article className="metric-card" key={metric.label}>
            <div className="metric-label">{metric.label}</div>
            <strong>{metric.value}</strong>
            <small>{metric.note}</small>
          </article>
        ))}
      </section>

      <div className="dashboard-grid">
        <section className="panel calendar-panel">
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
          <section className="panel compact-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">02 / ON THE ROAD</span>
                <h2>Dispatch queue</h2>
              </div>
              <span className="panel-count amber-count">Priority</span>
            </div>
            <RoadsideDispatch />
          </section>

          <section className="panel compact-panel readiness-panel">
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
