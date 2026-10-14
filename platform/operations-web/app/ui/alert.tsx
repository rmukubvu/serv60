import type { HTMLAttributes } from 'react';
import { cn } from './cn';

type AlertVariant = 'default' | 'destructive' | 'warning' | 'success';

export function Alert({
  className = '',
  variant = 'default',
  ...props
}: HTMLAttributes<HTMLDivElement> & { variant?: AlertVariant }) {
  return (
    <div
      role="alert"
      data-slot="alert"
      className={cn('ui-alert', `ui-alert-${variant}`, className)}
      {...props}
    />
  );
}

export function AlertTitle({ className = '', ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h4 data-slot="alert-title" className={cn('ui-alert-title', className)} {...props} />;
}

export function AlertDescription({
  className = '',
  ...props
}: HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p data-slot="alert-description" className={cn('ui-alert-description', className)} {...props} />
  );
}
