"use client";
import { useQuery } from "@tanstack/react-query";
import { fetchCalendar } from "./calendar-view";
import { HUB_ID } from "./config";
import { useHubSettings, weekdays } from "./hub-settings-api";

export function HubOverview() {
	const settings = useHubSettings();
	const calendar = useQuery({
		queryKey: ["hub-calendar", HUB_ID],
		queryFn: fetchCalendar,
	});
	if (settings.isPending) return <p role="status">Loading hub overview…</p>;
	if (settings.isError)
		return (
			<p className="form-error" role="alert">
				Hub configuration is unavailable. Open settings to retry.
			</p>
		);
	const hub = settings.data;
	const today = new Intl.DateTimeFormat("en-GB", {
		weekday: "long",
		timeZone: hub.timezone,
	}).format(new Date());
	const hours = hub.tradingHours.find(
		(day) => weekdays[day.dayOfWeek - 1] === today,
	);
	const metrics = [
		{
			label: "Scheduled today",
			value: calendar.isError
				? "Unavailable"
				: calendar.data
					? new Set(calendar.data.map((entry) => entry.holdId)).size
					: "…",
			note: "Live appointments",
		},
		{
			label: "Express bays",
			value: hub.capacity.expressBays,
			note: "Cars that can be serviced together",
		},
		{
			label: "Complimentary places",
			value:
				hub.capacity.barberChairs +
				hub.capacity.massageRooms +
				hub.capacity.workspaces,
			note: "Barber, massage & workspace",
		},
		{
			label: `${today} trading`,
			value:
				hours && !hours.closed
					? `${hours.opensAt.slice(0, 5)}–${hours.closesAt.slice(0, 5)}`
					: "Closed",
			note: hub.timezone,
		},
	];
	return (
		<>
			<div className="context-strip">
				<span>{hub.name}</span>
				<span>Live hub configuration</span>
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
		</>
	);
}
