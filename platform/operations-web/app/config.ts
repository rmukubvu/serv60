export const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
export const HUB_ID = process.env.NEXT_PUBLIC_SERV60_HUB_ID ?? '00000000-0000-0000-0000-000000000060';
export const HUB_OPTIONS = (process.env.NEXT_PUBLIC_SERV60_HUB_IDS ?? HUB_ID)
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);
export const DEMO_CUSTOMER_ID = process.env.NEXT_PUBLIC_SERV60_CUSTOMER_ID ?? '00000000-0000-0000-0000-000000000001';
// Local demo only: lets staff enter with an email against a backend running with security off.
// Never enable for a deployment that holds real customer data.
export const DEMO_MODE = process.env.NEXT_PUBLIC_SERV60_DEMO_MODE === 'true';
