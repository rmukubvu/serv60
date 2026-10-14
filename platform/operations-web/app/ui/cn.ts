/** Tiny className joiner — enough for owned shadcn-style primitives. */
export function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}
