import type { Metadata } from 'next';
import './styles.css';
import { Providers } from './providers';
export const metadata: Metadata = { title: 'Serv60 Operations', description: 'Hub operations calendar and readiness' };
export default function Layout({ children }: Readonly<{ children: React.ReactNode }>) { return <html lang="en"><body><Providers>{children}</Providers></body></html>; }
