import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { AppointmentDialog } from '../app/appointment-dialog';
import { HubProvider } from '../app/hub-context';

// jsdom does not implement native modal methods; real focus trapping and Escape
// behavior are checked in the browser against the built application.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true; });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) { this.open = false; this.dispatchEvent(new Event('close')); });
});

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function renderDialog() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <HubProvider>
        <AppointmentDialog />
      </HubProvider>
    </QueryClientProvider>,
  );
}

it('captures contact and car information in the booking request', async () => {
  const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'hold-1' }) });
  vi.stubGlobal('fetch', fetcher);
  renderDialog();
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Create appointment' }));
  await user.type(screen.getByLabelText('Customer name'), 'Alex Demo');
  await user.type(screen.getByLabelText('Phone number'), '0000000000');
  await user.type(screen.getByLabelText('Vehicle make'), 'Toyota');
  await user.type(screen.getByLabelText('Vehicle model'), 'Corolla');
  await user.type(screen.getByLabelText('Registration'), 'DEMO 060');
  // datetime-local inputs require setting their native value rather than synthetic keystrokes.
  const { fireEvent } = await import('@testing-library/react');
  fireEvent.change(screen.getByLabelText('Starts'), { target: { value: '2033-01-02T08:00' } });
  fireEvent.change(screen.getByLabelText('Ends'), { target: { value: '2033-01-02T09:00' } });
  await user.click(screen.getByRole('button', { name: 'Create hold' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
  expect(JSON.parse(fetcher.mock.calls[0][1].body).contact).toEqual({ customerName: 'Alex Demo', phoneNumber: '0000000000', vehicleMake: 'Toyota', vehicleModel: 'Corolla', registration: 'DEMO 060' });
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it('opens as a modal, supports native dismissal and reopens', async () => {
  renderDialog();
  const user = userEvent.setup();
  const trigger = screen.getByRole('button', { name: 'Create appointment' });
  await user.click(trigger);
  const dialog = screen.getByRole('dialog') as HTMLDialogElement;
  expect(dialog.open).toBe(true);
  expect(dialog.showModal).toHaveBeenCalled();
  await user.click(screen.getByRole('button', { name: 'Close' }));
  await waitFor(() => expect(dialog.open).toBe(false));
  await user.click(trigger);
  expect(dialog.open).toBe(true);
  // Native Escape dismissal closes the dialog and emits a close event.
  const { act } = await import('@testing-library/react');
  await act(async () => { dialog.close(); });
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await user.click(trigger);
  expect(dialog.open).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Cancel' }));
  await waitFor(() => expect(dialog.open).toBe(false));
});
