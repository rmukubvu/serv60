import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { environment: 'jsdom', include: ['tests/**/*.test.tsx'], restoreMocks: true, coverage: { provider: 'v8', include: ['app/booking-card.tsx', 'app/appointment-dialog.tsx', 'app/hub-settings-view.tsx', 'app/hub-settings-api.ts', 'app/hub-overview.tsx', 'app/calendar-view.tsx', 'app/operations-shell.tsx', 'app/live-floor.tsx', 'app/event-stream.ts', 'app/ui/input.tsx', 'app/settings/page.tsx'], thresholds: { perFile: true, lines: 80 } } },
});
