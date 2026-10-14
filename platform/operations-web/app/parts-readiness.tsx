'use client';

import { useQuery } from '@tanstack/react-query';
import { API_BASE } from './config';
import { useHub } from './hub-context';
import { Badge } from './ui/badge';

type PartsReadiness = {
  sku: string;
  usableQuantity: number;
  reservedQuantity: number;
  pendingDemand: number;
};

async function fetchReadiness(hubId: string): Promise<PartsReadiness[]> {
  const response = await fetch(`${API_BASE}/v1/hubs/${hubId}/parts-readiness?limit=50`);
  if (!response.ok) throw new Error(`Parts readiness request failed (${response.status})`);
  return response.json() as Promise<PartsReadiness[]>;
}

export function PartsReadinessView() {
  const { hubId } = useHub();
  const readiness = useQuery({
    queryKey: ['parts-readiness', hubId],
    queryFn: () => fetchReadiness(hubId),
    staleTime: 30_000,
  });
  if (readiness.isLoading)
    return (
      <div className="calendar-state" role="status">
        Loading parts readiness…
      </div>
    );
  if (readiness.isError)
    return (
      <div className="calendar-state error" role="alert">
        Unable to load parts readiness.
      </div>
    );

  return (
    <div className="readiness-list">
      {(readiness.data ?? []).map((part) => {
        const available = part.usableQuantity - part.reservedQuantity;
        const tight = available <= part.pendingDemand;
        return (
          <div className="readiness-row" key={part.sku}>
            <div>
              <strong>{part.sku}</strong>
              <span>
                {part.pendingDemand > 0
                  ? `${part.pendingDemand} pending demand`
                  : 'No pending demand'}
              </span>
            </div>
            <Badge variant={tight ? 'destructive' : 'outline'}>
              {available} available
            </Badge>
          </div>
        );
      })}
    </div>
  );
}
