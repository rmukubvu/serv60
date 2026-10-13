"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

export function OperationsShell({ children }: { children: ReactNode }) {
	const path = usePathname();
	const links = [
		{ href: "/", label: "Overview", mark: "01" },
		{ href: "/#calendar", label: "Service floor", mark: "02" },
		{ href: "/#dispatch", label: "Roadside dispatch", mark: "03" },
		{ href: "/#parts", label: "Parts readiness", mark: "04" },
		{ href: "/settings", label: "Hub settings", mark: "05" },
	];
	return (
		<div className="operations-layout">
			<aside className="operations-sidebar">
				<Link href="/" className="wordmark" aria-label="Serv60 overview">
					serv<span>60</span>
					<i aria-hidden="true" />
				</Link>
				<div className="workspace-label">
					<span className="status-dot" /> HUB OPERATIONS
				</div>
				<nav aria-label="Operations navigation">
					{links.map((link) => (
						<Link
							key={link.href}
							href={link.href}
							aria-current={path === link.href ? "page" : undefined}
						>
							<span aria-hidden="true">{link.mark}</span>
							{link.label}
						</Link>
					))}
				</nav>
				<div className="sidebar-note">
					<span className="eyebrow">YOUR HUB. YOUR RHYTHM.</span>
					<p>
						Keep the floor moving.
						<br />
						Give every hour purpose.
					</p>
				</div>
			</aside>
			<div className="operations-content">{children}</div>
		</div>
	);
}
