import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexDatabaseMaintenance from './CodexDatabaseMaintenance';
import { CodexStorageError } from './codexStorageBridge';

const request = vi.hoisted(() => vi.fn());
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('./codexStorageBridge', async importOriginal => ({
  ...await importOriginal<typeof import('./codexStorageBridge')>(), storageRequest: request,
}));
const database = { databaseId: 'fixture-database', name: 'history.sqlite', sizes: {}, supported: true, maintenancePending: false };
const admission = { available: true, deletionAvailable: true, runningSessionIds: [], writerSessionIds: [] };
const recoveryKey = 'ccgui.codex.database-maintenance.pending';
function operation(operationId: string) {
  return { operationId, state: 'COMPLETED', kind: 'COMPACT_DATABASE', targets: [], createdAt: 'now', updatedAt: 'now' };
}
function mount() {
  const view = render(<CodexDatabaseMaintenance />);
  fireEvent.click(screen.getByText('settings.codexMaintenance.title'));
  return view;
}
async function confirmCompaction() {
  const compact = await screen.findByRole('button', { name: 'settings.codexMaintenance.compact' });
  await waitFor(() => expect((compact as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(compact);
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'common.confirm' })));
}
beforeEach(() => {
  localStorage.clear();
  request.mockReset();
  request.mockImplementation((action: string) => Promise.resolve(action === 'get_ai_storage_database_status' ? database
    : action === 'get_ai_storage_mutation_status' ? admission : []));
});

describe('database maintenance admission and recovery', () => {
  it('keeps maintenance disabled on an unsupported platform', async () => {
    request.mockImplementation((action: string) => Promise.resolve(action === 'get_ai_storage_database_status'
      ? { ...database, supported: false, unavailableReason: 'MAINTENANCE_PLATFORM_UNSUPPORTED' }
      : action === 'get_ai_storage_mutation_status' ? admission : []));
    mount();
    await screen.findByText('settings.codexStorage.errors.MAINTENANCE_PLATFORM_UNSUPPORTED');
    expect((screen.getByRole('button', { name: 'settings.codexMaintenance.compact' }) as HTMLButtonElement).disabled).toBe(true);
    expect(request.mock.calls.some(([action]) => action === 'compact_ai_storage_database')).toBe(false);
  });

  it('sends one confirmed operation and persists its ID before dispatch', async () => {
    let finish!: (value: unknown) => void;
    request.mockImplementation((action: string, fields?: { operationId: string }) => {
      if (action === 'compact_ai_storage_database') {
        expect(JSON.parse(localStorage.getItem(recoveryKey)!)).toMatchObject({ operationId: fields!.operationId });
        return new Promise(resolve => { finish = resolve; });
      }
      return Promise.resolve(action === 'get_ai_storage_database_status' ? database
        : action === 'get_ai_storage_mutation_status' ? admission : []);
    });
    mount();
    await confirmCompaction();
    const writes = request.mock.calls.filter(([action]) => action === 'compact_ai_storage_database');
    expect(writes).toHaveLength(1);
    expect(writes[0][1]).toMatchObject({ databaseId: database.databaseId });
    await act(async () => finish(operation(writes[0][1].operationId)));
    expect(localStorage.getItem(recoveryKey)).toBeNull();
  });

  it('recovers a lost response after remount and retries the identical operation', async () => {
    request.mockImplementation((action: string) => action === 'compact_ai_storage_database'
      ? Promise.reject(new CodexStorageError('STORAGE_RESPONSE_TIMEOUT'))
      : Promise.resolve(action === 'get_ai_storage_database_status' ? database
        : action === 'get_ai_storage_mutation_status' ? admission : []));
    const first = mount();
    await confirmCompaction();
    await screen.findByRole('alert');
    const pending = JSON.parse(localStorage.getItem(recoveryKey)!);
    first.unmount();
    request.mockClear();
    request.mockImplementation((action: string) => Promise.resolve(action === 'compact_ai_storage_database'
      ? operation(pending.operationId) : action === 'get_ai_storage_database_status' ? database
        : action === 'get_ai_storage_mutation_status' ? admission : []));
    mount();
    await waitFor(() => expect((screen.getByRole('button', { name: 'settings.codexMaintenance.retry' }) as HTMLButtonElement).disabled).toBe(false));
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'settings.codexMaintenance.retry' })));
    await waitFor(() => expect(localStorage.getItem(recoveryKey)).toBeNull());
    const writes = request.mock.calls.filter(([action]) => action === 'compact_ai_storage_database');
    expect(writes).toHaveLength(1);
    expect(writes[0][1]).toEqual(pending);
  });

  it('requires an orphan preview and an explicit cleanup confirmation', async () => {
    request.mockImplementation((action: string, fields?: { operationId: string }) => Promise.resolve(
      action === 'get_ai_storage_database_status' ? database : action === 'get_ai_storage_mutation_status' ? admission
        : action === 'preview_ai_storage_orphans' ? { databaseId: database.databaseId, previewToken: 'a'.repeat(64),
          candidateCount: 1, rowCount: 2, candidates: [{ sessionId: 'orphan', title: 'Fixture' }] }
          : action === 'clean_ai_storage_orphans' ? operation(fields!.operationId) : []));
    mount();
    await waitFor(() => expect((screen.getByRole('button', { name: 'settings.codexMaintenance.analyze' }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'settings.codexMaintenance.analyze' }));
    const clean = await screen.findByRole('button', { name: 'settings.codexMaintenance.clean' });
    fireEvent.click(clean);
    expect(request.mock.calls.some(([action]) => action === 'clean_ai_storage_orphans')).toBe(false);
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'common.confirm' })));
    await waitFor(() => expect(request.mock.calls.filter(([action]) => action === 'clean_ai_storage_orphans')).toHaveLength(1));
    expect(request.mock.calls.find(([action]) => action === 'clean_ai_storage_orphans')![1]).toMatchObject({ previewToken: 'a'.repeat(64) });
  });
});
