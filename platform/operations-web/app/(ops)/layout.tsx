import { OperationsShell } from '../operations-shell';
import { OpsAuthGate } from '../ops-auth';

export default function OpsLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <OpsAuthGate>
      <OperationsShell>{children}</OperationsShell>
    </OpsAuthGate>
  );
}
