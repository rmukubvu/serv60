import type { InputHTMLAttributes } from "react";

/** Owned input primitive using the existing flat shadcn-style form tokens. */
export function Input({
	className = "",
	...props
}: InputHTMLAttributes<HTMLInputElement>) {
	return (
		<input
			data-slot="input"
			className={`ui-input ${className}`.trim()}
			{...props}
		/>
	);
}
