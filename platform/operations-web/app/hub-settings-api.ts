import { useQuery } from "@tanstack/react-query";
import { API_BASE } from "./config";
import { useHub } from "./hub-context";

export type TradingDay = {
	dayOfWeek: number;
	closed: boolean;
	opensAt: string;
	closesAt: string;
};
export type Capacity = {
	expressBays: number;
	barberChairs: number;
	massageRooms: number;
	workspaces: number;
};
export type HubSettings = {
	hubId: string;
	name: string;
	timezone: string;
	published: boolean;
	revision: number;
	capacity: Capacity;
	tradingHours: TradingDay[];
	resources: { id: string; code: string; kind: string; active?: boolean }[];
};
export type SettingsUpdate = Pick<
	HubSettings,
	"revision" | "capacity" | "tradingHours"
>;
export const weekdays = [
	"Monday",
	"Tuesday",
	"Wednesday",
	"Thursday",
	"Friday",
	"Saturday",
	"Sunday",
];

export function settingsKey(hubId: string) {
	return ["hub-settings", hubId] as const;
}

export async function settingsRequest(
	hubId: string,
	update?: SettingsUpdate,
): Promise<HubSettings> {
	const response = await fetch(`${API_BASE}/v1/hubs/${hubId}/settings`, {
		method: update ? "PUT" : "GET",
		headers: update ? { "Content-Type": "application/json" } : undefined,
		body: update ? JSON.stringify(update) : undefined,
		signal: AbortSignal.timeout(15_000),
		cache: "no-store",
	});
	if (!response.ok) {
		let message = `Unable to ${update ? "save" : "load"} hub settings (${response.status}). Please try again.`;
		if (response.status === 409) {
			const problem = (await response.json()) as { error?: string };
			message =
				problem.error ??
				"Settings conflict with current bookings or another update. Reload and try again.";
		}
		throw new Error(message);
	}
	return response.json() as Promise<HubSettings>;
}

export function useHubSettings() {
	const { hubId } = useHub();
	return useQuery({
		queryKey: settingsKey(hubId),
		queryFn: () => settingsRequest(hubId),
		refetchOnWindowFocus: false,
		refetchOnReconnect: false,
	});
}
