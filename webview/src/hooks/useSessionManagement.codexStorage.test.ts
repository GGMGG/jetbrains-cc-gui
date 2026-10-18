import { act, renderHook } from '@testing-library/react';
import type { TFunction } from 'i18next';
import type { HistoryData } from '../types';
import { useSessionManagement } from './useSessionManagement';
import { deleteCodexStorageSessions, receiveCodexStorageResponse } from '../components/settings/AiDataStorageSection/codexStorageBridge';

describe('legacy history deletion and independent Codex storage deletion', () => {
  const t = ((key: string) => key) as TFunction;
  const sent = vi.fn<(message: string) => void>();
  const request = () => JSON.parse((sent.mock.calls.at(-1)?.[0] ?? '').split(/:(.*)/s)[1]!) as { requestId: string; operationId: string };
  const setup = (loading = false, initialHistory?: HistoryData) => {
    let history: HistoryData = initialHistory ?? {
      success: true, sessions: [{ sessionId: 'codex-idle', title: 'Codex', provider: 'codex', messageCount: 2 }], total: 2,
    };
    const mocks = {
      setHistoryData: vi.fn((value: React.SetStateAction<HistoryData | null>) => { history = (typeof value === 'function' ? value(history) : value)!; }),
      setMessages: vi.fn(), setCurrentView: vi.fn(), setCurrentSessionId: vi.fn(), setCustomSessionTitle: vi.fn(),
      setUsagePercentage: vi.fn(), setUsageUsedTokens: vi.fn(), setUsageMaxTokens: vi.fn(), setStatus: vi.fn(),
      setLoading: vi.fn(), setIsThinking: vi.fn(), setStreamingActive: vi.fn(), clearToasts: vi.fn(), addToast: vi.fn(),
    };
    const hook = renderHook(() => useSessionManagement({ messages: [], historyData: history, loading,
      currentSessionId: 'codex-idle', currentProvider: 'codex', ...mocks, t }));
    return { ...hook, mocks, history: () => history };
  };
  beforeEach(() => {
    sent.mockClear(); window.sendToJava = sent;
    window.__sessionTransitioning = false; window.__pendingSessionTransitionToast = undefined;
  });

  it('keeps the original raw single-delete action and immediate history update for Codex', () => {
    const hook = setup();
    act(() => hook.result.current.deleteHistorySession('codex-idle'));
    expect(sent).toHaveBeenNthCalledWith(1, 'delete_session:codex-idle');
    expect(hook.history().sessions).toHaveLength(0);
    expect(hook.mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(sent).toHaveBeenCalledWith('create_new_session:');
    expect(sent).not.toHaveBeenCalledWith('interrupt_session:');
    expect(sent.mock.calls.some(([message]) => message.startsWith('delete_ai_storage_sessions:'))).toBe(false);
    expect(window.__pendingSessionTransitionToast?.message).toBe('history.sessionDeleted');
  });

  it('keeps the original batch-delete and active-session interruption flow for Codex', () => {
    const hook = setup(true);
    act(() => hook.result.current.deleteHistorySessions(['codex-idle', 'codex-idle']));
    expect(sent).toHaveBeenNthCalledWith(1, 'delete_sessions:["codex-idle"]');
    expect(sent).toHaveBeenCalledWith('interrupt_session:');
    expect(sent).toHaveBeenCalledWith('create_new_session:');
    expect(hook.history().sessions).toHaveLength(0);
    expect(hook.mocks.addToast).not.toHaveBeenCalledWith('settings.codexStorage.errors.SESSION_RUNNING', 'warning');
    expect(sent.mock.calls.some(([message]) => message.startsWith('delete_ai_storage_sessions:'))).toBe(false);
  });

  it('synchronizes only a confirmed storage deletion without changing the legacy request path', async () => {
    const hook = setup();
    await act(async () => {
      const deletion = deleteCodexStorageSessions(['codex-idle']);
      const pending = request();
      expect(sent.mock.calls[0]?.[0]).toMatch(/^delete_ai_storage_sessions:/);
      expect(hook.history().sessions).toHaveLength(1);
      receiveCodexStorageResponse(JSON.stringify({ action: 'delete_ai_storage_sessions', requestId: pending.requestId, success: true,
        data: { operationId: pending.operationId, kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: ['codex-idle'],
          createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:01Z', result: { deletedSessionIds: ['codex-idle'] } } }));
      await deletion;
    });
    expect(hook.history().sessions).toHaveLength(0);
    expect(hook.mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(sent).toHaveBeenCalledWith('create_new_session:');
    expect(sent).not.toHaveBeenCalledWith('delete_session:codex-idle');
  });

  it.each([undefined, 5])('preserves native history count semantics after deleting an unknown-count session (%s remaining)', async remainingCount => {
    const hook = setup(false, { success: true, source: 'native', sessions: [
      { sessionId: 'codex-idle', title: 'Deleted', provider: 'codex' },
      { sessionId: 'retained', title: 'Retained', provider: 'codex', messageCount: remainingCount },
    ] });
    await act(async () => {
      const deletion = deleteCodexStorageSessions(['codex-idle']);
      const pending = request();
      receiveCodexStorageResponse(JSON.stringify({ action: 'delete_ai_storage_sessions', requestId: pending.requestId, success: true,
        data: { operationId: pending.operationId, kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: ['codex-idle'],
          createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:01Z', result: { deletedSessionIds: ['codex-idle'] } } }));
      await deletion;
    });
    expect(hook.history().sessions?.map(session => session.sessionId)).toEqual(['retained']);
    expect(hook.history().total).toBe(remainingCount);
  });

  it('does not duplicate storage errors through the legacy history hook', async () => {
    const hook = setup();
    await act(async () => {
      const deletion = deleteCodexStorageSessions(['codex-idle']);
      const rejected = expect(deletion).rejects.toMatchObject({ code: 'SESSION_RUNNING' });
      receiveCodexStorageResponse(JSON.stringify({ action: 'delete_ai_storage_sessions', requestId: request().requestId, success: false, error: 'SESSION_RUNNING' }));
      await rejected;
    });
    expect(hook.history().sessions).toHaveLength(1);
    expect(hook.mocks.setCurrentSessionId).not.toHaveBeenCalled();
    expect(hook.mocks.addToast).not.toHaveBeenCalled();
    expect(sent).not.toHaveBeenCalledWith('interrupt_session:');
  });
});
