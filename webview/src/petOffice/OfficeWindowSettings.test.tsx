import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import OfficeWindowSettings from './OfficeWindowSettings';
import type { OfficeWindowSettings as Settings } from './officeLocalModel';

const bridge = vi.hoisted(() => ({ update: vi.fn(), read: vi.fn() }));
vi.mock('./officeLocalBridge', () => ({ updateOfficeWindowPreferences: bridge.update, readLocalOffice: bridge.read }));
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
const initial: Settings = { revision: 'initial', generation: 0, officeName: '', topmost: true, zoom: 100, recovered: false };
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

it('waits for native acknowledgement and sends only one save while it is pending', async () => {
  let reply: (value: Settings) => void = () => undefined;
  bridge.update.mockImplementation(() => new Promise<Settings>(resolve => { reply = resolve; }));
  const onSaved = vi.fn();
  render(<OfficeWindowSettings settings={initial} onSaved={onSaved} />);
  fireEvent.change(screen.getByLabelText('officeName'), { target: { value: 'Team' } });
  fireEvent.click(screen.getByRole('button', { name: 'save' }));
  fireEvent.click(screen.getByRole('button', { name: 'saving' }));
  expect(bridge.update).toHaveBeenCalledTimes(1);
  expect(bridge.update.mock.calls[0][0]).toEqual({ expectedRevision: 'initial', officeName: 'Team', topmost: true, zoom: 100 });
  expect(onSaved).not.toHaveBeenCalled();
  await act(async () => reply({ ...initial, revision: 'saved', generation: 1, officeName: 'Team' }));
  expect(onSaved).toHaveBeenCalledOnce();
});

it('keeps a dirty draft and its original revision when polling returns a newer save', async () => {
  bridge.update.mockRejectedValue(new Error('OFFICE_PREFERENCES_CONFLICT'));
  const onSaved = vi.fn();
  const view = render(<OfficeWindowSettings settings={initial} onSaved={onSaved} />);
  fireEvent.change(screen.getByLabelText('officeName'), { target: { value: 'Draft' } });
  view.rerender(<OfficeWindowSettings settings={{ ...initial, revision: 'newer', generation: 2, officeName: 'Saved elsewhere' }} onSaved={onSaved} />);
  expect((screen.getByLabelText('officeName') as HTMLInputElement).value).toBe('Draft');
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'save' })); });
  expect(bridge.update.mock.calls[0][0].expectedRevision).toBe('initial');
  expect(screen.getByRole('alert').textContent).toBe('preferencesFailed');
  expect(onSaved).not.toHaveBeenCalled();
  bridge.read.mockResolvedValue({ settings: { ...initial, revision: 'actual-record', generation: 3, officeName: 'Saved elsewhere' } });
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'reloadSaved' })); });
  expect(bridge.read).toHaveBeenCalledOnce();
  expect((screen.getByLabelText('officeName') as HTMLInputElement).value).toBe('Saved elsewhere');
});

it('aborts an unmounted edit and ignores a late save acknowledgement', async () => {
  let reply: (value: Settings) => void = () => undefined;
  bridge.update.mockImplementation(() => new Promise<Settings>(resolve => { reply = resolve; }));
  const onSaved = vi.fn();
  const view = render(<OfficeWindowSettings settings={initial} onSaved={onSaved} />);
  fireEvent.change(screen.getByLabelText('officeName'), { target: { value: 'Late' } });
  fireEvent.click(screen.getByRole('button', { name: 'save' }));
  const signal = bridge.update.mock.calls[0][1] as AbortSignal;
  view.unmount();
  expect(signal.aborted).toBe(true);
  await act(async () => reply({ ...initial, revision: 'saved', generation: 1 }));
  expect(onSaved).not.toHaveBeenCalled();
});

it('counts Unicode code points consistently with native validation', () => {
  render(<OfficeWindowSettings settings={initial} onSaved={vi.fn()} />);
  fireEvent.change(screen.getByLabelText('officeName'), { target: { value: '🐥'.repeat(64) } });
  expect((screen.getByRole('button', { name: 'save' }) as HTMLButtonElement).disabled).toBe(false);
  fireEvent.change(screen.getByLabelText('officeName'), { target: { value: '🐥'.repeat(65) } });
  expect((screen.getByRole('button', { name: 'save' }) as HTMLButtonElement).disabled).toBe(true);
});
