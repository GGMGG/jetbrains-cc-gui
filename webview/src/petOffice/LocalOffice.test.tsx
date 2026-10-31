import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import LocalOffice from './LocalOffice';
import type { LocalOfficeSnapshot, LocalOfficeSource } from './officeLocalModel';

const bridge = vi.hoisted(() => ({ read: vi.fn(), activate: vi.fn() }));
vi.mock('./officeLocalBridge', () => ({ readLocalOffice: bridge.read, activateLocalOfficeSource: bridge.activate }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({
  t: (key: string, values?: Record<string, unknown>) => key === 'activate' ? `Open ${values?.title}` : key,
}) }));
function source(index: number): LocalOfficeSource {
  return { sourceKey: `instance/tab-${index}/binding`, routeGeneration: 'route', routeStatus: 'AVAILABLE', projectId: 'project',
    projectLabel: 'Project', tabLabel: `Chat ${index}`, provider: 'codex', modelLabel: 'model', phase: 'RUNNING', interactionId: '' };
}
function snapshot(sources = [source(0)]): LocalOfficeSnapshot {
  return { watermark: 1, sampledAt: 1000, capacityReached: false, sources };
}
beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks(); bridge.read.mockResolvedValue(snapshot()); bridge.activate.mockResolvedValue({ result: 'ACTIVATED' }); });
afterEach(() => { cleanup(); vi.useRealTimers(); });
async function mount() { await act(async () => { render(<LocalOffice />); }); }

it('renders real waiting status and activates the original source with one request', async () => {
  const waiting = { ...source(0), phase: 'WAITING_PERMISSION' as const, interactionId: 'permission' };
  bridge.read.mockResolvedValue(snapshot([waiting]));
  await mount();
  expect(screen.getByText('phases.WAITING_PERMISSION')).toBeTruthy();
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Open Chat 0' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Chat 0' }));
  });
  expect(bridge.activate).toHaveBeenCalledTimes(1);
  expect(bridge.activate.mock.calls[0][0]).toEqual(waiting);
  expect(screen.getByText('navigation.ACTIVATED')).toBeTruthy();
});

it('retains the last sample but disables navigation when a refresh fails', async () => {
  await mount();
  bridge.read.mockRejectedValueOnce(new Error('lost'));
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'refresh' })); });
  expect(screen.getByRole('alert').textContent).toBe('readFailed');
  const desk = screen.getByRole('button', { name: 'Open Chat 0' }) as HTMLButtonElement;
  expect(desk.disabled).toBe(true);
  fireEvent.click(desk);
  expect(bridge.activate).not.toHaveBeenCalled();
});

it('keeps more than twelve sessions addressable across floors', async () => {
  bridge.read.mockResolvedValue(snapshot(Array.from({ length: 25 }, (_, index) => source(index))));
  await mount();
  expect(screen.queryByRole('button', { name: 'Open Chat 12' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'next' }));
  expect(screen.getByRole('button', { name: 'Open Chat 12' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'next' }));
  expect(screen.getByRole('button', { name: 'Open Chat 24' })).toBeTruthy();
});

it('aborts the native read and stops polling when the page closes', async () => {
  let signal: AbortSignal | undefined;
  bridge.read.mockImplementation((value: AbortSignal) => { signal = value; return new Promise(() => undefined); });
  await mount();
  cleanup();
  expect(signal?.aborted).toBe(true);
  await vi.advanceTimersByTimeAsync(10000);
  expect(bridge.read).toHaveBeenCalledTimes(1);
});
