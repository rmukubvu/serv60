import type { HTMLAttributes } from 'react';
import { cn } from './cn';

type BadgeVariant = 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'destructive' | 'accent';

export function Badge({
  className = '',
  variant = 'default',
  ...props
}: HTMLAttributes<HTMLSpanElement> & { variant?: BadgeVariant }) {
  return (
    <span
      data-slot="badge"
      className={cn('ui-badge', `ui-badge-${variant}`, className)}
      {...props}
    />
  );
}
