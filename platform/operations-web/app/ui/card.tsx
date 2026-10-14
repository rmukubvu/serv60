import type { HTMLAttributes } from 'react';
import { cn } from './cn';

export function Card({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="card" className={cn('ui-card', className)} {...props} />;
}

export function CardHeader({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="card-header" className={cn('ui-card-header', className)} {...props} />;
}

export function CardTitle({ className = '', ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h3 data-slot="card-title" className={cn('ui-card-title', className)} {...props} />;
}

export function CardDescription({ className = '', ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p data-slot="card-description" className={cn('ui-card-description', className)} {...props} />;
}

export function CardContent({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="card-content" className={cn('ui-card-content', className)} {...props} />;
}

export function CardFooter({ className = '', ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-slot="card-footer" className={cn('ui-card-footer', className)} {...props} />;
}
