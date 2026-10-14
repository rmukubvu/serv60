'use client';

import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { HUB_ID, HUB_OPTIONS } from './config';

type HubContextValue = {
  hubId: string;
  setHubId: (id: string) => void;
  hubs: { id: string; label: string }[];
};

const HubContext = createContext<HubContextValue | null>(null);

export function HubProvider({ children }: { children: ReactNode }) {
  const hubs = useMemo(
    () => HUB_OPTIONS.map((id, index) => ({ id, label: index === 0 ? 'Primary hub' : `Hub ${index + 1}` })),
    [],
  );
  const [hubId, setHubId] = useState(HUB_ID);
  return <HubContext.Provider value={{ hubId, setHubId, hubs }}>{children}</HubContext.Provider>;
}

export function useHub() {
  const value = useContext(HubContext);
  if (!value) throw new Error('useHub requires HubProvider');
  return value;
}

export function HubSwitcher() {
  const { hubId, setHubId, hubs } = useHub();
  if (hubs.length < 2) {
    return <span className="status-pill">Hub {hubId.slice(0, 8)}</span>;
  }
  return (
    <label className="hub-switcher">
      Active hub
      <select value={hubId} onChange={(event) => setHubId(event.target.value)}>
        {hubs.map((hub) => (
          <option key={hub.id} value={hub.id}>
            {hub.label} · {hub.id.slice(0, 8)}
          </option>
        ))}
      </select>
    </label>
  );
}
