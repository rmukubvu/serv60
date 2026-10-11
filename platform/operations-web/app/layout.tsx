import type { Metadata } from 'next';
import './styles.css';
export const metadata: Metadata = { title: 'Serv60 Operations', description: 'Hub operations calendar and readiness' };
export default function Layout({ children }: Readonly<{ children: React.ReactNode }>) { return <html lang="en"><body>{children}</body></html>; }
