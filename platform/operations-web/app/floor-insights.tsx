'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './api';
import { useHub } from './hub-context';
import { Alert, AlertDescription, AlertTitle } from './ui/alert';
import { Badge } from './ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';

export type FloorAlert = {
  severity: string;
  code: string;
  title: string;
  detail: string;
};

export type DispatchPriority = {
  incidentId: string;
  priorityScore: number;
  reason: string;
  suggestedProvider: string | null;
  suggestedEtaMinutes: number | null;
};

export type OpsInsights = {
  alerts: FloorAlert[];
  dispatchPriority: DispatchPriority[];
};

async function fetchInsights(hubId: string): Promise<OpsInsights> {
  const response = await apiFetch(`/v1/hubs/${hubId}/ops-insights`);
  if (!response.ok) throw new Error(`Ops insights failed (${response.status})`);
  return response.json() as Promise<OpsInsights>;
}

export function useOpsInsights() {
  const { hubId } = useHub();
  return useQuery({
    queryKey: ['ops-insights', hubId],
    queryFn: () => fetchInsights(hubId),
    refetchInterval: 30_000,
    staleTime: 15_000,
  });
}

function alertVariant(severity: string) {
  switch (severity.toUpperCase()) {
    case 'CRITICAL':
      return 'destructive' as const;
    case 'WARN':
      return 'default' as const;
    default:
      return 'default' as const;
  }
}

function badgeVariant(severity: string) {
  switch (severity.toUpperCase()) {
    case 'CRITICAL':
      return 'destructive' as const;
    case 'WARN':
      return 'secondary' as const;
    default:
      return 'outline' as const;
  }
}

export function FloorInsights() {
  const insights = useOpsInsights();

  if (insights.isLoading) {
    return (
      <Card className="floor-insights" aria-label="Floor insights" aria-busy="true">
        <CardContent>
          <p className="floor-insights-status">Checking floor pressure and parts…</p>
        </CardContent>
      </Card>
    );
  }

  if (insights.isError) {
    return (
      <Card className="floor-insights" aria-label="Floor insights">
        <CardContent>
          <Alert variant="warning">
            <AlertTitle>Insights unavailable</AlertTitle>
            <AlertDescription>
              Calendar and parts panels still work. Retry in a moment.
            </AlertDescription>
          </Alert>
        </CardContent>
      </Card>
    );
  }

  const alerts = insights.data?.alerts ?? [];
  if (alerts.length === 0) {
    return (
      <Card className="floor-insights" aria-label="Floor insights">
        <CardContent>
          <Alert variant="success">
            <AlertTitle>Floor looks clear</AlertTitle>
            <AlertDescription>
              No parts shortages or bay pressure right now.
            </AlertDescription>
          </Alert>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="floor-insights" aria-label="Floor insights">
      <CardHeader>
        <div className="floor-insights-heading">
          <div>
            <span className="eyebrow">OPERATOR INSIGHTS</span>
            <CardTitle>
              {alerts.length} live signal{alerts.length === 1 ? '' : 's'}
            </CardTitle>
          </div>
          <Badge variant="outline">Auto-ranked</Badge>
        </div>
      </CardHeader>
      <CardContent>
        <ul className="floor-insights-list">
          {alerts.map((alert) => (
            <li key={`${alert.code}-${alert.title}`}>
              <Alert variant={alertVariant(alert.severity)}>
                <div className="floor-alert-row">
                  <div>
                    <AlertTitle>{alert.title}</AlertTitle>
                    <AlertDescription>{alert.detail}</AlertDescription>
                  </div>
                  <Badge variant={badgeVariant(alert.severity)}>{alert.severity}</Badge>
                </div>
              </Alert>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}
