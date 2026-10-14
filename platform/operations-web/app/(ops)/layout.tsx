import { OpsAuthGate } from '../ops-auth';
import { OperationsShell } from '../operations-shell';

export default function OpsLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <OpsAuthGate>
      <OperationsShell>{children}</OperationsShell>
    </OpsAuthGate>
  );
}
