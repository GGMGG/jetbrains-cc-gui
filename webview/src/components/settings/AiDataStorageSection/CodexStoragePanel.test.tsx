import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexStoragePanel from './CodexStoragePanel';
import { receiveCodexStorageResponse, storageRequest, type CodexStorageMutationStatus, type CodexStoragePage } from './codexStorageBridge';

const activity = vi.hoisted(() => ({ status: null as CodexStorageMutationStatus | null, refresh: vi.fn() }));
vi.mock('./useCodexStorageMutationStatus', () => ({ useCodexStorageMutationStatus: () => ({ status: activity.status, refresh: activity.refresh }) }));

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('./codexStorageBridge', async importOriginal => ({
  ...await importOriginal<typeof import('./codexStorageBridge')>(), storageRequest: vi.fn(),
}));
const session = { sessionId: 'fixture-id', title: 'Local fixture', cwd: '/project', storageMode: 'file',
  fileSize: 100, lastTimestamp: 0, firstTimestamp: 0, archived: false, running: false, readable: true };
const page: CodexStoragePage = { sessions: [session], total: 1, page: 1, pageSize: 20, hasMore: false, complete: true, errors: [] };

describe('storage browser', () => {
  beforeEach(() => {
    vi.mocked(storageRequest).mockReset();
    activity.status = null;
    activity.refresh.mockReset();
    localStorage.clear();
    window.sendToJava = vi.fn();
  });

  it('reads transcript pages on demand and keeps advanced maintenance outside this panel', async () => {
    vi.mocked(storageRequest).mockImplementation(async action => {
      if (action === 'list_ai_storage_sessions') return page;
      if (action === 'get_ai_storage_session') return { sessionId: session.sessionId,
        records: [{ text: '<script>unsafe transcript</script>', truncated: false }], hasMore: false };
      throw new Error('Unexpected action');
    });
    const { container } = render(<CodexStoragePanel />);
    expect(await screen.findByText('Local fixture')).toBeTruthy();
    expect(storageRequest).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByText('settings.codexStorage.view'));
    expect((await screen.findAllByText('<script>unsafe transcript</script>')).length).toBeGreaterThan(0);
    expect(container.querySelector('script')).toBeNull();
    expect(screen.queryByText('settings.codexStorage.deletion')).toBeNull();
    expect(screen.queryByText('settings.codexStorage.compact')).toBeNull();
  });

  it('ignores a superseded search response', async () => {
    let finishFirst!: (result: CodexStoragePage) => void;
    vi.mocked(storageRequest).mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve; }));
    vi.mocked(storageRequest).mockResolvedValue({ ...page, sessions: [{ ...session, title: 'Latest search' }] });
    render(<CodexStoragePanel />);
    fireEvent.change(screen.getByLabelText('settings.codexStorage.search'), { target: { value: 'latest' } });
    expect(await screen.findByText('Latest search')).toBeTruthy();
    await act(async () => finishFirst(page));
    expect(screen.queryByText('Local fixture')).toBeNull();
  });

  it('aborts usage work when the browser unmounts', async () => {
    let scanSignal: AbortSignal | undefined;
    vi.mocked(storageRequest).mockImplementation((action, _fields, signal) => {
      if (action === 'get_ai_storage_usage') { scanSignal = signal; return new Promise(() => {}); }
      return Promise.resolve(page);
    });
    const { unmount } = render(<CodexStoragePanel />);
    fireEvent.click(screen.getByText('settings.codexStorage.scan'));
    expect(scanSignal?.aborted).toBe(false);
    unmount();
    expect(scanSignal?.aborted).toBe(true);
  });

  it('uses requested page sizes and never reports an unknown count as zero after a failed read', async () => {
    vi.mocked(storageRequest).mockResolvedValue(page);
    render(<CodexStoragePanel />);
    await screen.findByText('Local fixture');
    fireEvent.change(screen.getByLabelText('settings.codexStorage.pageSize'), { target: { value: '50' } });
    await waitFor(() => expect(storageRequest).toHaveBeenLastCalledWith('list_ai_storage_sessions',
      expect.objectContaining({ page: 1, limit: 50 }), expect.any(AbortSignal)));
    vi.mocked(storageRequest).mockRejectedValue(new Error('fixture read failure'));
    fireEvent.click(screen.getByText('settings.codexStorage.refresh'));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText('settings.codexStorage.allSessions')).toBeNull();
  });

  it('requires confirmation and retains the row until the database and file receipt arrives', async () => {
    activity.status = { available: true, deletionAvailable: true, runningSessionIds: [], writerSessionIds: [] };
    vi.mocked(storageRequest).mockResolvedValue(page);
    render(<CodexStoragePanel />);
    fireEvent.click(await screen.findByRole('button', { name: 'common.delete' }));
    expect(window.sendToJava).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'common.delete' }));
    expect(screen.getByText('Local fixture')).toBeTruthy();
    const call = vi.mocked(window.sendToJava!).mock.calls[0][0];
    const request = JSON.parse(call.slice(call.indexOf(':') + 1)) as { requestId: string; operationId: string };
    vi.mocked(storageRequest).mockResolvedValue({ ...page, sessions: [], total: 0 });
    await act(async () => receiveCodexStorageResponse(JSON.stringify({ requestId: request.requestId,
      action: 'delete_ai_storage_sessions', success: true, data: { operationId: request.operationId,
        kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: [session.sessionId], createdAt: 'now', updatedAt: 'now',
        result: { deletedSessionIds: [session.sessionId], metadataStatus: 'COMPLETE' } } })));
    await waitFor(() => expect(screen.queryByText('Local fixture')).toBeNull());
  });

  it('cancels confirmation when the target starts running and excludes it from bulk selection', async () => {
    activity.status = { available: true, deletionAvailable: true, runningSessionIds: [], writerSessionIds: [] };
    vi.mocked(storageRequest).mockResolvedValue(page);
    const view = render(<CodexStoragePanel />);
    fireEvent.click(await screen.findByRole('button', { name: 'common.delete' }));
    expect(screen.getByRole('dialog')).toBeTruthy();
    activity.status = { ...activity.status, runningSessionIds: [session.sessionId] };
    view.rerender(<CodexStoragePanel />);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect((screen.getByRole('button', { name: 'common.delete' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'history.selectMode' }) as HTMLButtonElement).disabled).toBe(true);
    activity.status = { ...activity.status, runningSessionIds: [] };
    view.rerender(<CodexStoragePanel />);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(window.sendToJava).not.toHaveBeenCalled();
  });
});
