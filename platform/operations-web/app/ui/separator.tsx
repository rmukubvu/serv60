import type { HTMLAttributes } from 'react';
import { cn } from './cn';

export function Separator({
  className = '',
  orientation = 'horizontal',
  ...props
}: HTMLAttributes<HTMLHRElement> & { orientation?: 'horizontal' | 'vertical' }) {
  return (
    <hr
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
