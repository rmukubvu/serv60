import type { ButtonHTMLAttributes } from 'react';
import { cn } from './cn';

type ButtonVariant = 'default' | 'primary' | 'outline' | 'ghost' | 'destructive';
type ButtonSize = 'default' | 'sm' | 'lg' | 'icon';

export function Button({
  className = '',
  variant = 'default',
  size = 'default',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
}) {
  // Keep legacy "primary" class support used across the app.
  const legacyPrimary = className.split(/\s+/).includes('primary');
  const resolved = legacyPrimary ? 'primary' : variant;
  return (
    <button
      data-slot="button"
      className={cn(
        'ui-button',
        `ui-button-${resolved}`,
        `ui-button-${size}`,
        legacyPrimary ? 'primary' : '',
        className,
      )}
      {...props}
    />
  );
}
