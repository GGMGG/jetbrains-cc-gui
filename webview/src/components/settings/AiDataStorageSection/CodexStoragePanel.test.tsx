import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexStoragePanel from './CodexStoragePanel';
import { storageRequest, type CodexStoragePage } from './codexStorageBridge';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
vi.mock('./codexStorageBridge', async importOriginal => ({
  ...await importOriginal<typeof import('./codexStorageBridge')>(), storageRequest: vi.fn(),
}));
const session = { sessionId: 'fixture-id', title: 'Local fixture', cwd: '/project', storageMode: 'file',
  fileSize: 100, lastTimestamp: 0, firstTimestamp: 0, archived: false, running: false, readable: true };
const page: CodexStoragePage = { sessions: [session], total: 1, page: 1, pageSize: 20, hasMore: false, complete: true, errors: [] };

describe('read-only storage browser', () => {
  beforeEach(() => { vi.mocked(storageRequest).mockReset(); });

  it('reads transcript pages on demand and offers no mutation controls', async () => {
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
});
