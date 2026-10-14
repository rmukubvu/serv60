"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useHub, HubSwitcher } from "./hub-context";
import {
	type Capacity,
	type HubSettings,
	type SettingsUpdate,
	settingsKey,
	settingsRequest,
	useHubSettings,
	weekdays,
} from "./hub-settings-api";
import { Button } from "./ui/button";
import { Input } from "./ui/input";

const facilities = [
	{
		key: "barberChairs",
		title: "Barber",
		unit: "chairs",
		description:
			"One guest per chair. Set to 0 to hide barber from customer discovery.",
		mark: "B",
	},
	{
		key: "massageRooms",
		title: "Massage",
		unit: "rooms",
		description:
			"One guest per room. Set to 0 to hide massage from customer discovery.",
		mark: "M",
	},
	{
		key: "workspaces",
		title: "Private workspace",
		unit: "spaces",
		description:
			"One guest per space. Set to 0 to hide workspaces from customers.",
		mark: "W",
	},
] as const;

export function HubSettingsView() {
	const query = useHubSettings();
	const [saved, setSaved] = useState(false);
	return (
		<>
			<header className="topbar settings-header">
				<div>
					<span className="eyebrow">THE WAY YOUR HUB WORKS</span>
					<h1>Make it your hub.</h1>
					<p className="page-description">
						Set your capacity, shape the experience and decide when your doors
						open. Zero complimentary capacity hides that option from customers.
					</p>
				</div>
				<HubSwitcher />
			</header>
			{saved && (
				<p className="settings-notice" role="status">
					Settings saved. Customer availability now uses this configuration.
				</p>
			)}
			{query.isPending ? (
				<p role="status">Loading hub settings…</p>
			) : query.isError ? (
				<div className="calendar-state error" role="alert">
					<p>{query.error.message}</p>
					<Button onClick={() => query.refetch()}>Try again</Button>
				</div>
			) : (
				<SettingsForm
					key={query.data.revision}
					settings={query.data}
					onSaved={() => setSaved(true)}
					onEdit={() => setSaved(false)}
				/>
			)}
		</>
	);
}

function SettingsForm({
	settings,
	onSaved,
	onEdit,
}: {
	settings: HubSettings;
	onSaved: () => void;
	onEdit: () => void;
}) {
	const { hubId } = useHub();
	const client = useQueryClient();
	const [draft, setDraft] = useState<SettingsUpdate>({
		revision: settings.revision,
		capacity: settings.capacity,
		tradingHours: settings.tradingHours,
	});
	const [reloading, setReloading] = useState(false);
	const [reloadError, setReloadError] = useState("");
	const dirty =
		JSON.stringify(draft.capacity) !== JSON.stringify(settings.capacity) ||
		JSON.stringify(draft.tradingHours) !==
			JSON.stringify(settings.tradingHours);
	const save = useMutation({
		mutationFn: (update: SettingsUpdate) => settingsRequest(hubId, update),
		onSuccess: (data) => {
			client.setQueryData(settingsKey(hubId), data);
			client.invalidateQueries({ queryKey: ["hub-calendar", hubId] });
			onSaved();
		},
	});
	useEffect(() => {
		const protect = (event: BeforeUnloadEvent) => {
			if (dirty) {
				event.preventDefault();
				event.returnValue = "";
			}
		};
		window.addEventListener("beforeunload", protect);
		return () => window.removeEventListener("beforeunload", protect);
	}, [dirty]);
	const changeCapacity = (key: keyof Capacity, value: number) => {
		onEdit();
		save.reset();
		setDraft((old) => ({
			...old,
			capacity: { ...old.capacity, [key]: value },
		}));
	};
	const changeDay = (
		index: number,
		values: Partial<SettingsUpdate["tradingHours"][number]>,
	) => {
		onEdit();
		save.reset();
		setDraft((old) => ({
			...old,
			tradingHours: old.tradingHours.map((day, i) =>
				i === index ? { ...day, ...values } : day,
			),
		}));
	};
	const capacity = draft.capacity;
	const weeklyHours = draft.tradingHours.reduce((total, day) => {
		if (day.closed) return total;
		const minutes = (time: string) =>
			Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
		return (
			total + Math.max(0, minutes(day.closesAt) - minutes(day.opensAt)) / 60
		);
	}, 0);
	const validHours = draft.tradingHours.every(
		(day) =>
			day.closed ||
			Number(day.closesAt.slice(0, 2)) * 60 +
				Number(day.closesAt.slice(3, 5)) -
				(Number(day.opensAt.slice(0, 2)) * 60 +
					Number(day.opensAt.slice(3, 5))) >=
				60,
	);
	return (
		<form
			onSubmit={(event) => {
				event.preventDefault();
				if (validHours) save.mutate(draft);
			}}
		>
			<div className="settings-toolbar">
				<div>
					<strong>{settings.name}</strong>
					<span>
						{settings.timezone} ·{" "}
						{settings.published ? "Visible to customers" : "Not yet published"}
					</span>
				</div>
				<div className="settings-actions">
					<span className="edit-status">
						{dirty ? "Unsaved changes" : "All changes saved"}
					</span>
					<Button
						className="primary"
						type="submit"
						disabled={!dirty || !validHours || save.isPending}
					>
						{save.isPending ? "Saving…" : "Save changes"}
					</Button>
				</div>
			</div>
			{save.isError && (
				<div className="settings-error" role="alert">
					<p>{save.error.message}</p>
					<p>
						Your unsaved changes are still here. Reloading replaces them with
						the latest saved settings.
					</p>
					<Button
						type="button"
						disabled={reloading}
						onClick={async () => {
							setReloading(true);
							setReloadError("");
							try {
								const fresh = await settingsRequest(hubId);
								client.setQueryData(settingsKey(hubId), fresh);
								setDraft({
									revision: fresh.revision,
									capacity: fresh.capacity,
									tradingHours: fresh.tradingHours,
								});
								save.reset();
							} catch {
								setReloadError(
									"Could not reload. Your unsaved changes are still here.",
								);
							} finally {
								setReloading(false);
							}
						}}
					>
						Reload saved settings
					</Button>
				</div>
			)}
			{reloadError && (
				<p className="form-error" role="alert">
					{reloadError}
				</p>
			)}
			<div className="settings-grid">
				<div className="settings-sections">
					<section className="settings-section" aria-labelledby="bay-heading">
						<div className="settings-section-heading">
							<span className="section-number">01</span>
							<div>
								<h2 id="bay-heading">The service floor</h2>
								<p>
									Your express bays set how many cars can be serviced at once.
								</p>
							</div>
						</div>
						<div className="bay-setting">
							<div>
								<label htmlFor="express-bays">Express service bays</label>
								<p>Every booking includes a 60-minute vehicle service.</p>
							</div>
							<Input
								id="express-bays"
								type="number"
								min={1}
								max={100}
								required
								value={capacity.expressBays}
								disabled={save.isPending}
								onChange={(e) =>
									changeCapacity("expressBays", Number(e.target.value))
								}
							/>
						</div>
						<p className="field-note">
							Reducing capacity never cancels a booking. Busy resources must be
							cleared first.
						</p>
					</section>
					<section
						className="settings-section"
						aria-labelledby="facilities-heading"
					>
						<div className="settings-section-heading">
							<span className="section-number">02</span>
							<div>
								<h2 id="facilities-heading">A little extra, included.</h2>
								<p>
									Guests can choose one complimentary experience during their
									service.
								</p>
							</div>
						</div>
						{facilities.map((facility) => (
							<div className="facility-setting" key={facility.key}>
								<div className="facility-mark" aria-hidden="true">
									{facility.mark}
								</div>
								<div className="facility-copy">
									<label htmlFor={facility.key}>{facility.title}</label>
									<p>{facility.description}</p>
									<span
										className={
											capacity[facility.key]
												? "facility-available"
												: "field-note"
										}
									>
										{capacity[facility.key]
											? "Available to book"
											: "Not offered at this hub"}
									</span>
								</div>
								<div className="facility-count">
									<Input
										id={facility.key}
										type="number"
										min={0}
										max={50}
										required
										value={capacity[facility.key]}
										disabled={save.isPending}
										onChange={(e) =>
											changeCapacity(facility.key, Number(e.target.value))
										}
									/>
									<span>{facility.unit}</span>
								</div>
							</div>
						))}
						<p className="field-note">
							Set a facility to 0 to remove it from new customer bookings.
						</p>
					</section>
					<section className="settings-section" aria-labelledby="hours-heading">
						<div className="settings-section-heading">
							<span className="section-number">03</span>
							<div>
								<h2 id="hours-heading">Open on your terms.</h2>
								<p>
									Trading hours in {settings.timezone}. The final service must
									finish before closing.
								</p>
							</div>
						</div>
						<div className="trading-head" aria-hidden="true">
							<span>Trading day</span>
							<span>Open</span>
							<span>From</span>
							<span>Until</span>
						</div>
						{draft.tradingHours.map((day, index) => (
							<div
								className={`trading-row ${day.closed ? "day-closed" : ""}`}
								key={day.dayOfWeek}
							>
								<strong>{weekdays[day.dayOfWeek - 1]}</strong>
								<label className="day-toggle">
									<input
										type="checkbox"
										aria-label={`${weekdays[index]} open`}
										checked={!day.closed}
										disabled={save.isPending}
										onChange={(e) =>
											changeDay(index, { closed: !e.target.checked })
										}
									/>
									<span>{day.closed ? "Closed" : "Open"}</span>
								</label>
								<Input
									aria-label={`${weekdays[index]} opening time`}
									type="time"
									required
									disabled={day.closed || save.isPending}
									value={day.opensAt.slice(0, 5)}
									onChange={(e) =>
										changeDay(index, { opensAt: `${e.target.value}:00` })
									}
								/>
								<Input
									aria-label={`${weekdays[index]} closing time`}
									type="time"
									required
									disabled={day.closed || save.isPending}
									value={day.closesAt.slice(0, 5)}
									onChange={(e) =>
										changeDay(index, { closesAt: `${e.target.value}:00` })
									}
								/>
							</div>
						))}
						{!validHours && (
							<p className="form-error" role="alert">
								Each open day must allow at least one full hour. Closing must be
								later than opening.
							</p>
						)}
						<p className="field-note">
							Changes apply to new availability immediately. Hours that clash
							with existing bookings cannot be saved.
						</p>
					</section>
				</div>
				<aside className="settings-preview" aria-label="Configuration summary">
					<span className="eyebrow">YOUR HUB, AT A GLANCE</span>
					<h2>
						Built around
						<br />a useful hour.
					</h2>
					<div className="preview-stat">
						<strong>{capacity.expressBays}</strong>
						<span>cars serviced at once</span>
					</div>
					<div className="preview-stat">
						<strong>
							{capacity.barberChairs +
								capacity.massageRooms +
								capacity.workspaces}
						</strong>
						<span>complimentary places</span>
					</div>
					<div className="preview-stat">
						<strong>
							{weeklyHours.toLocaleString()}
							<small> hrs</small>
						</strong>
						<span>open each week</span>
					</div>
					<p>
						Vehicle service always comes first. Guests choose one available
						extra, at no additional cost.
					</p>
					<span className="preview-caption">
						{dirty
							? "Preview of your unsaved settings"
							: `Saved configuration · revision ${settings.revision}`}
					</span>
				</aside>
			</div>
		</form>
	);
}
