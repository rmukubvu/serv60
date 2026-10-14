import type { HTMLAttributes } from 'react';
import { cn } from './cn';

export function Separator({
  className = '',
  orientation = 'horizontal',
  ...props
}: HTMLAttributes<HTMLDivElement> & { orientation?: 'horizontal' | 'vertical' }) {
  return (
    <div
      role="separator"
      aria-orientation={orientation}
      data-slot="separator"
      className={cn(
        'ui-separator',
        orientation === 'vertical' ? 'ui-separator-vertical' : 'ui-separator-horizontal',
        className,
      )}
      {...props}
    />
  );
}
