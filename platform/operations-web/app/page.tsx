import { AppointmentDialog } from './appointment-dialog';
import { CalendarView } from './calendar-view';

export default function OperationsPage() {
  return <main className="shell"><header className="topbar"><div><span className="eyebrow">SERV60 / HUB 01</span><h1>Today at the hub</h1></div><AppointmentDialog /></header><section className="summary">{[['Scheduled today', 'Live', 'From resource allocations'], ['Ready for express', '18', '2 parts exceptions'], ['Active incidents', '2', '1 tow en route'], ['Capacity', '74%', 'Wash line is the constraint']].map(([label, value, note]) => <article key={label}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>)}</section><section className="panel"><div className="panel-heading"><div><span className="eyebrow">TODAY · LIVE API</span><h2>Resource calendar</h2></div></div><CalendarView /></section></main>;
}
