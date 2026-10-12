import { AppointmentDialog } from './appointment-dialog';
import { CalendarView } from './calendar-view';
import { PartsReadinessView } from './parts-readiness';
import { RoadsideDispatch } from './roadside-dispatch';

const metrics = [
  { label: 'Scheduled today', value: 'Live', note: 'Resource allocations', tone: 'lime' },
  { label: 'Ready for express', value: '18', note: '2 parts exceptions', tone: 'blue' },
  { label: 'Active incidents', value: 'Live', note: 'Dispatch queue', tone: 'amber' },
  { label: 'Hub capacity', value: '74%', note: 'Wash line is the constraint', tone: 'violet' },
];

export default function OperationsPage() {
  return (
    <main className="shell">
      <header className="topbar">
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true">S60</div>
          <div>
            <span className="eyebrow">SERV60 / HUB 01</span>
            <h1>Today at the hub</h1>
          </div>
        </div>
        <div className="header-actions">
          <span className="status-pill"><span className="status-dot" aria-hidden="true" /> Live operations</span>
          <AppointmentDialog />
        </div>
      </header>

      <section className="context-strip" aria-label="Hub context">
        <span>Monday, 12 October 2026</span>
        <span className="context-sync"><span className="sync-dot" aria-hidden="true" /> Last sync just now</span>
      </section>

      <section className="summary" aria-label="Hub summary">
        {metrics.map((metric) => (
          <article className={`metric-card metric-${metric.tone}`} key={metric.label}>
            <div className="metric-label"><span className="metric-icon" aria-hidden="true" />{metric.label}</div>
            <strong>{metric.value}</strong>
            <small>{metric.note}</small>
          </article>
        ))}
      </section>

      <div className="dashboard-grid">
        <section className="panel calendar-panel">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">TODAY · LIVE API</span>
              <h2>Resource calendar</h2>
              <p className="panel-description">Every bay and lifestyle resource, in one view.</p>
            </div>
            <span className="panel-count">3 resources active</span>
          </div>
          <CalendarView />
        </section>

        <aside className="side-stack">
          <section className="panel compact-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">ROADSIDE · LIVE API</span>
                <h2>Dispatch queue</h2>
              </div>
              <span className="panel-count amber-count">Priority</span>
            </div>
            <RoadsideDispatch />
          </section>

          <section className="panel compact-panel readiness-panel">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">INVENTORY · LIVE API</span>
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
