'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { HubProvider } from './hub-context';
import { OpsAuthProvider } from './ops-auth';

export function Providers({ children }: Readonly<{ children: React.ReactNode }>) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
  }));
  return (
    <QueryClientProvider client={queryClient}>
      <OpsAuthProvider>
        <HubProvider>{children}</HubProvider>
      </OpsAuthProvider>
    </QueryClientProvider>
  );
}
