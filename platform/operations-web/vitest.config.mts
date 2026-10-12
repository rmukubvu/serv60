import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { environment: 'jsdom', include: ['tests/**/*.test.tsx'], restoreMocks: true, coverage: { provider: 'v8', include: ['app/booking-card.tsx', 'app/appointment-dialog.tsx'], thresholds: { perFile: true, lines: 80 } } },
});
