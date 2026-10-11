'use client';

import { useQuery } from '@tanstack/react-query';
import { API_BASE, HUB_ID } from './config';

type PartsReadiness = { sku: string; usableQuantity: number; reservedQuantity: number; pendingDemand: number };

async function fetchReadiness(): Promise<PartsReadiness[]> {
  const response = await fetch(`${API_BASE}/v1/hubs/${HUB_ID}/parts-readiness?limit=50`);
  if (!response.ok) throw new Error(`Parts readiness request failed (${response.status})`);
  return response.json() as Promise<PartsReadiness[]>;
}

export function PartsReadinessView() {
  const readiness = useQuery({ queryKey: ['parts-readiness', HUB_ID], queryFn: fetchReadiness, staleTime: 30_000 });
  if (readiness.isLoading) return <div className="calendar-state" role="status">Loading parts readiness…</div>;
  if (readiness.isError) return <div className="calendar-state error" role="alert">Unable to load parts readiness.</div>;
  return <div className="readiness-list">{(readiness.data ?? []).map((part) => <div className="readiness-row" key={part.sku}><div><strong>{part.sku}</strong><span>{part.pendingDemand > 0 ? `${part.pendingDemand} pending demand` : 'No pending demand'}</span></div><b className={part.usableQuantity - part.reservedQuantity <= part.pendingDemand ? 'attention' : ''}>{part.usableQuantity - part.reservedQuantity} available</b></div>)}</div>;
}
