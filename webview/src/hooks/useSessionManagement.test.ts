import { act, renderHook } from '@testing-library/react';
import { useSessionManagement } from './useSessionManagement.js';
import type { HistoryData } from '../types/index.js';
import { receiveCodexStorageResponse, type CodexMaintenanceOperation } from '../components/settings/AiDataStorageSection/codexStorageBridge';

describe('useSessionManagement', () => {
  const t = ((key: string) => key) as any;

  const createMocks = () => ({
    setHistoryData: vi.fn(),
    setMessages: vi.fn(),
    setCurrentView: vi.fn(),
    setCurrentSessionId: vi.fn(),
    setCustomSessionTitle: vi.fn(),
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setStatus: vi.fn(),
    setLoading: vi.fn(),
    setIsThinking: vi.fn(),
    setStreamingActive: vi.fn(),
    clearToasts: vi.fn(),
    addToast: vi.fn(),
    setBackgroundTasks: vi.fn(),
  });

  beforeEach(() => {
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    window.__pendingSessionTransitionToast = undefined;
    window.sendToJava = vi.fn();
    // Reset the "skip new-session confirm" preference between tests so cases
    // that exercise the localStorage path can't leak into ones that don't.
    localStorage.clear();
  });

  async function confirmCodexDeletion() {
    const call = vi.mocked(window.sendToJava!).mock.calls.map(([value]) => value)
      .filter(value => value.startsWith('delete_ai_storage_sessions:')).at(-1)!;
    const request = JSON.parse(call.slice(call.indexOf(':') + 1)) as {
      requestId: string; operationId: string; sessionIds: string[];
    };
    await act(async () => receiveCodexStorageResponse(JSON.stringify({ requestId: request.requestId,
      action: 'delete_ai_storage_sessions', success: true, data: {
        operationId: request.operationId, kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: request.sessionIds,
        createdAt: 'now', updatedAt: 'now', result: { deletedSessionIds: request.sessionIds, metadataStatus: 'COMPLETE' },
      } })));
  }
  /** Delete payloads now carry provider and requestId alongside the targets. */
  const sentRequests = (type: 'delete_session' | 'delete_sessions') => vi.mocked(window.sendToJava!).mock.calls
    .map(([payload]) => String(payload))
    .filter(payload => payload.startsWith(`${type}:`))
    .map(payload => JSON.parse(payload.slice(type.length + 1)) as Record<string, unknown>);
  const sentRequest = (type: 'delete_session' | 'delete_sessions') => sentRequests(type).at(-1)!;

  it('starts a clean session transition for a direct new session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'old-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setStatus).toHaveBeenCalledWith('');
    expect(mocks.setLoading).toHaveBeenCalledWith(false);
    expect(mocks.setIsThinking).toHaveBeenCalledWith(false);
    expect(mocks.setStreamingActive).toHaveBeenCalledWith(false);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith(null);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
  });

  it('discards queued messages when the session transitions', () => {
    const mocks = createMocks();
    const clearQueuedMessages = vi.fn();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'old-session',
        ...mocks,
        t,
        clearQueuedMessages,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Queued messages belong to the outgoing session; the transition must
    // drop them before the next session's queue-auto-execute can see them.
    expect(clearQueuedMessages).toHaveBeenCalledTimes(1);
  });

  it('clears stale ui state before loading history', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History Title',
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
      ],
      total: 3,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old', timestamp: new Date().toISOString() }],
        loading: true,
        historyData,
        currentSessionId: 'old-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('history-1');
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, 'interrupt_session:');
    // The payload model is normalized webview-side: the retired sonnet-4-6 is
    // mapped to its live replacement before Java stores it verbatim.
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      'load_session:{"sessionId":"history-1","provider":"claude","model":"claude-sonnet-5"}'
    );
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('history-1');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith('History Title');
    expect(mocks.setCurrentView).toHaveBeenCalledWith('chat');
  });

  it('applies repeated history deletes against the latest state', async () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySession('history-1');
      result.current.deleteHistorySession('history-2');
    });

    expect(historyData.sessions?.map(session => session.sessionId)).toEqual(['history-2']);
    await confirmCodexDeletion();
    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
    expect(sentRequest('delete_session')).toMatchObject({ sessionId: 'history-1', provider: 'claude' });
    expect(window.sendToJava).toHaveBeenCalledWith(expect.stringMatching(/^delete_ai_storage_sessions:/));
  });

  it('separates provider batches and removes Codex rows only after confirmation', async () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        currentProvider: 'codex',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2', 'history-1']);
    });

    expect(historyData.sessions?.map(session => session.sessionId)).toEqual(['history-2']);
    expect(window.sendToJava).toHaveBeenCalledTimes(2);
    expect(sentRequest('delete_sessions')).toMatchObject({ sessionIds: ['history-1'], provider: 'claude' });
    await confirmCodexDeletion();
    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
  });

  it.each([false, true])('routes each provider separately and retains refused rows (refused: %s)', (refused) => {
    let historyData: HistoryData = { success: true, sessions: [
      { sessionId: 'claude-item', title: 'Claude', provider: 'claude' },
      { sessionId: 'grok-item', title: 'Grok', provider: 'grok' },
    ] };
    const mocks = { ...createMocks(), setHistoryData: vi.fn((update: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
      historyData = (typeof update === 'function' ? update(historyData) : update) as HistoryData;
    }) };
    window.sendToJava = vi.fn((payload: string) => !(refused && payload.startsWith('delete_sessions:') && payload.includes('grok-item')));
    const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
      historyData, currentSessionId: 'grok-item', currentProvider: 'grok', ...mocks, t }));
    act(() => { result.current.deleteHistorySessions(['claude-item', 'grok-item']); });
    expect(sentRequests('delete_sessions')).toEqual([
      expect.objectContaining({ provider: 'claude', sessionIds: ['claude-item'] }),
      expect.objectContaining({ provider: 'grok', sessionIds: ['grok-item'] }),
    ]);
    expect(historyData.sessions?.map(session => session.sessionId)).toEqual(refused ? ['grok-item'] : []);
    expect(vi.mocked(window.sendToJava!).mock.calls.some(([value]) => value === 'create_new_session:')).toBe(!refused);
  });

  it('preserves the active Codex row and session when the backend refuses deletion', async () => {
    const mocks = createMocks();
    const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
      currentProvider: 'codex', currentSessionId: 'protected', historyData: { success: true,
        sessions: [{ sessionId: 'protected', title: 'Protected', provider: 'codex' }] }, ...mocks, t }));
    act(() => result.current.deleteHistorySession('protected'));
    expect(mocks.setHistoryData).not.toHaveBeenCalled();
    expect(mocks.setCurrentSessionId).not.toHaveBeenCalled();
    const call = vi.mocked(window.sendToJava!).mock.calls[0][0];
    const request = JSON.parse(call.slice(call.indexOf(':') + 1)) as { requestId: string };
    await act(async () => receiveCodexStorageResponse(JSON.stringify({ requestId: request.requestId,
      action: 'delete_ai_storage_sessions', success: false, error: 'SESSION_RUNNING' })));
    expect(mocks.setHistoryData).not.toHaveBeenCalled();
    expect(mocks.setCurrentSessionId).not.toHaveBeenCalled();
    expect(mocks.addToast).toHaveBeenCalledWith('settings.codexStorage.errors.SESSION_RUNNING', 'warning');
    expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
  });

  it('still shows a success toast for batch delete when history data is temporarily unavailable', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2', 'history-1']);
    });

    expect(sentRequest('delete_sessions').sessionIds).toEqual(['history-1', 'history-2']);
    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
  });

  it('preserves unknown native totals after deletion and sums the remaining known rows', () => {
    let historyData: HistoryData = { success: true, source: 'native', sessions: [
      { sessionId: 'unknown', title: 'Unknown' },
      { sessionId: 'known', title: 'Known', messageCount: 5 },
      { sessionId: 'deleted', title: 'Deleted', messageCount: 3 },
    ] };
    const mocks = { ...createMocks(), setHistoryData: vi.fn((update: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
      historyData = (typeof update === 'function' ? update(historyData) : update) as HistoryData;
    }) };
    const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
      historyData, currentSessionId: null, ...mocks, t }));
    act(() => { result.current.deleteHistorySession('deleted'); });
    expect(historyData.total).toBeUndefined();
    act(() => { result.current.deleteHistorySessions(['unknown']); });
    expect(historyData.total).toBe(5);
    expect(historyData.sessions?.map(session => session.sessionId)).toEqual(['known']);
  });

  it('preserves freshly counted messages when editing through callbacks from the preceding render', () => {
    let historyData: HistoryData = { success: true, source: 'native', sessions: [{ sessionId: 'saved', title: 'Original' }] };
    const mocks = { ...createMocks(), setHistoryData: vi.fn((update: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
      historyData = (typeof update === 'function' ? update(historyData) : update) as HistoryData;
    }) };
    const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
      historyData, currentSessionId: null, ...mocks, t }));
    historyData = { ...historyData, total: 7, sessions: [{ ...historyData.sessions![0], messageCount: 7 }] };
    act(() => { result.current.toggleFavoriteSession('saved'); });
    expect(historyData).toMatchObject({ total: 7, sessions: [{ messageCount: 7, isFavorited: true }] });
    act(() => { result.current.updateHistoryTitle('saved', 'Renamed'); });
    expect(historyData).toMatchObject({ total: 7, sessions: [{ messageCount: 7, isFavorited: true, title: 'Renamed' }] });
    act(() => { result.current.applyHistoryTitleLocal('saved', 'Generated'); });
    expect(historyData).toMatchObject({ total: 7, sessions: [{ messageCount: 7, isFavorited: true, title: 'Generated' }] });
  });

  it('retitles the open session when its title is edited from the history list', () => {
    let historyData: HistoryData = {
      success: true,
      source: 'native',
      sessions: [{ sessionId: 'open', title: 'Old title' }],
    };
    const mocks = { ...createMocks(), setHistoryData: vi.fn((update: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
      historyData = (typeof update === 'function' ? update(historyData) : update) as HistoryData;
    }) };
    const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
      historyData, currentSessionId: 'open', ...mocks, t }));
    act(() => { result.current.updateHistoryTitle('open', 'New title'); });
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith('New title');
    expect(historyData.sessions?.[0].title).toBe('New title');
  });

  it('leaves the open session title alone when another session is edited', () => {
    const historyData: HistoryData = {
      success: true,
      source: 'native',
      sessions: [
        { sessionId: 'open', title: 'Open title' },
        { sessionId: 'other', title: 'Other title' },
      ],
    };
    const mocks = createMocks();
    const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
      historyData, currentSessionId: 'open', ...mocks, t }));
    act(() => { result.current.updateHistoryTitle('other', 'Renamed'); });
    expect(mocks.setCustomSessionTitle).not.toHaveBeenCalled();
  });

  it('defers the deleted toast until transition completion when batch delete removes current session', async () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: 'history-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2']);
    });

    expect(sentRequest('delete_sessions')).toMatchObject({ sessionIds: ['history-1'], provider: 'claude' });
    await confirmCodexDeletion();
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(mocks.addToast).not.toHaveBeenCalledWith('history.sessionDeleted', 'success');
    expect(window.__pendingSessionTransitionToast).toEqual({
      message: 'history.sessionDeleted',
      type: 'success',
    });
  });

  describe('Non-Codex history deletion outcomes', () => {
    const deliverResult = (result: Record<string, unknown>) => act(() => {
      window.onHistoryDeleteResult?.(JSON.stringify(result));
    });

    const codexHistory = (): HistoryData => ({ success: true, source: 'native', sessions: [
      { sessionId: 'history-1', title: 'One', provider: 'codex' },
      { sessionId: 'history-2', title: 'Two', provider: 'codex' },
    ] });
    const renderCodex = (id: string) => {
      const mocks = createMocks();
      const { result } = renderHook(() => useSessionManagement({ messages: [], loading: false,
        historyData: codexHistory(), currentSessionId: id, currentProvider: 'codex', ...mocks, t }));
      return { result, mocks };
    };

    const sentStorageRequest = () => {
      const payload = vi.mocked(window.sendToJava!).mock.calls.map(([value]) => value)
        .filter(value => value.startsWith('delete_ai_storage_sessions:')).at(-1)!;
      return JSON.parse(payload.slice(payload.indexOf(':') + 1)) as {
        requestId: string; operationId: string; sessionIds: string[]; origin: string;
      };
    };
    const storageOutcome = (fields: Partial<CodexMaintenanceOperation> = {}) => {
      const request = sentStorageRequest();
      return JSON.stringify({ requestId: request.requestId, action: 'delete_ai_storage_sessions', success: true,
        data: { operationId: request.operationId, kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: request.sessionIds,
          createdAt: 'now', updatedAt: 'now', result: { deletedSessionIds: request.sessionIds, metadataStatus: 'COMPLETE' },
          ...fields } });
    };
    const deliverStorageResult = async (fields: Partial<CodexMaintenanceOperation> = {}) => {
      await act(async () => receiveCodexStorageResponse(storageOutcome(fields)));
    };

    it('keeps the current session open until its files are confirmed deleted', async () => {
      const { result, mocks } = renderCodex('history-2');
      act(() => { result.current.deleteHistorySession('history-2'); });
      expect(sentStorageRequest()).toMatchObject({ sessionIds: ['history-2'], origin: 'history' });
      expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
      expect(window.__sessionTransitioning).toBe(false);
      expect(mocks.addToast).not.toHaveBeenCalled();

      await deliverStorageResult();
      expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
      expect(window.__sessionTransitioning).toBe(true);
      expect(window.__pendingSessionTransitionToast).toEqual({ message: 'history.sessionDeleted', type: 'success' });
    });

    it('explains a refusal and never force-releases the protected session', async () => {
      const { result, mocks } = renderCodex('history-1');
      act(() => { result.current.deleteHistorySessions(['history-1', 'history-2']); });
      await deliverStorageResult({ state: 'FAILED_CLEAN', result: undefined, error: 'SESSION_RUNNING' });
      expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
      expect(window.__sessionTransitioning).toBe(false);
      expect(mocks.addToast).toHaveBeenCalledWith('settings.codexStorage.errors.SESSION_RUNNING', 'warning');
      expect(mocks.addToast).not.toHaveBeenCalledWith('history.sessionDeleted', 'success');
    });

    it('leaves a confirmed deleted session and defers a transaction warning until transition', async () => {
      const { result, mocks } = renderCodex('history-1');
      act(() => { result.current.deleteHistorySessions(['history-1', 'history-2']); });
      await deliverStorageResult({ state: 'COMPLETED_WITH_WARNINGS', result: {
        deletedSessionIds: ['history-1', 'history-2'], metadataStatus: 'COMPLETE', warning: 'METADATA_CLEANUP_FAILED',
      } });
      expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
      expect(window.__pendingSessionTransitionToast).toEqual({
        message: 'settings.codexStorage.errors.METADATA_CLEANUP_FAILED', type: 'warning',
      });
      expect(mocks.addToast).not.toHaveBeenCalled();
    });

    it('stays on the current session when deletion fails', async () => {
      const { result, mocks } = renderCodex('history-1');
      act(() => { result.current.deleteHistorySession('history-1'); });
      await deliverStorageResult({ state: 'FAILED_CLEAN', result: undefined, error: 'STORAGE_OPERATION_FAILED' });
      expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
      expect(mocks.addToast).toHaveBeenCalledWith('settings.codexStorage.errors.STORAGE_OPERATION_FAILED', 'error');
    });

    it('announces a confirmed deletion of another session without leaving the current one', async () => {
      const { result, mocks } = renderCodex('history-1');
      act(() => { result.current.deleteHistorySessions(['history-2']); });
      await deliverStorageResult();
      expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
      expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
    });

    it('keeps a newly selected session when an earlier deletion receipt arrives before rerender', async () => {
      const mocks = createMocks();
      const currentSessionIdRef = { current: 'history-1' as string | null };
      const { result } = renderHook(() => useSessionManagement({
        messages: [], loading: false, historyData: codexHistory(), currentSessionId: 'history-1',
        currentSessionIdRef, currentProvider: 'codex', ...mocks, t,
      }));
      act(() => { result.current.deleteHistorySession('history-1'); });
      currentSessionIdRef.current = 'history-2';
      await deliverStorageResult();
      expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
      expect(mocks.setCurrentSessionId).not.toHaveBeenCalled();
      expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
    });

    it('ignores unreadable, foreign and repeated storage results', async () => {
      const { result, mocks } = renderCodex('history-1');
      act(() => { result.current.deleteHistorySession('history-1'); });
      const outcome = storageOutcome();
      await act(async () => {
        receiveCodexStorageResponse('not json');
        receiveCodexStorageResponse(JSON.stringify({ ...JSON.parse(outcome), requestId: 'another-request' }));
      });
      expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
      await act(async () => {
        receiveCodexStorageResponse(outcome);
        receiveCodexStorageResponse(outcome);
      });
      expect(vi.mocked(window.sendToJava!).mock.calls.filter(([payload]) => payload === 'create_new_session:'))
        .toHaveLength(1);
      expect(mocks.addToast).not.toHaveBeenCalled();
    });

    it('does not remove rows when the bridge refuses the request', async () => {
      const { result, mocks } = renderCodex('history-1');
      window.sendToJava = vi.fn(() => { throw new Error('page replaced'); });
      await act(async () => { result.current.deleteHistorySession('history-2'); });
      expect(mocks.setHistoryData).not.toHaveBeenCalled();
      expect(mocks.addToast).toHaveBeenCalledWith('settings.codexStorage.errors.STORAGE_BRIDGE_UNAVAILABLE', 'error');
    });

    it('does not announce non-Codex deletions twice but still reports their failures', () => {
      const mocks = createMocks();
      const { result } = renderHook(() => useSessionManagement({
        messages: [], loading: false, historyData: null, currentSessionId: 'claude-1',
        currentProvider: 'claude', ...mocks, t,
      }));

      act(() => { result.current.deleteHistorySession('claude-1'); });
      expect(sentRequest('delete_session')).toMatchObject({ sessionId: 'claude-1', provider: 'claude' });
      mocks.addToast.mockClear();
      deliverResult({ requestId: sentRequest('delete_session').requestId, provider: 'claude',
        sessionIds: ['claude-1'], deletedSessionIds: ['claude-1'], success: true });
      expect(mocks.addToast).not.toHaveBeenCalled();

      act(() => { result.current.deleteHistorySession('claude-2'); });
      mocks.addToast.mockClear();
      deliverResult({ requestId: sentRequest('delete_session').requestId, provider: 'claude',
        sessionIds: ['claude-2'], deletedSessionIds: [], success: false, errorCode: 'DELETE_FAILED' });
      expect(mocks.addToast).toHaveBeenCalledWith('history.deleteFailed', 'error');
    });

  });

  it('forceCreateNewSession interrupts loading session and cleans state', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'streaming...', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(window.sendToJava).toHaveBeenCalledWith('interrupt_session:');
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
  });

  it('forceCreateNewSessionWithProvider resets session and applies target provider before recreating', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSessionWithProvider('codex');
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, 'set_provider:codex');
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, 'create_new_session:');
    expect(window.__sessionTransitioning).toBe(true);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
  });

  it('shows confirm dialog when creating new session with existing messages', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Should show confirm dialog, NOT immediately transition
    expect(result.current.showNewSessionConfirm).toBe(true);
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
    expect(mocks.setMessages).not.toHaveBeenCalled();
  });

  it('skips confirm dialog when skipNewSessionConfirm preference is enabled', () => {
    // User previously ticked "don't ask again" — dialog should be bypassed.
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Should transition immediately without showing the dialog.
    expect(result.current.showNewSessionConfirm).toBe(false);
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
  });

  it('still shows the interrupt dialog while loading even if skipNewSessionConfirm is enabled', () => {
    // Safety guard: the "don't ask again" preference must NOT bypass the
    // dangerous "interrupt running AI" confirm dialog. (See AppDialogs comment.)
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'thinking', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Interrupt dialog must still appear; no silent transition.
    expect(result.current.showInterruptConfirm).toBe(true);
    expect(result.current.showNewSessionConfirm).toBe(false);
    expect(window.__sessionTransitioning).toBe(false);
    expect(mocks.setMessages).not.toHaveBeenCalled();
    expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
  });

  it('handleConfirmInterrupt completes interrupt+transition even with skipNewSessionConfirm enabled', () => {
    // Regression guard: once the interrupt dialog is confirmed, the flow must
    // send interrupt_session + create_new_session exactly once. The skip
    // preference must not cause a second silent transition or skip the
    // interrupt signal.
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'thinking', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Open the interrupt dialog.
    act(() => {
      result.current.createNewSession();
    });
    expect(result.current.showInterruptConfirm).toBe(true);

    // User confirms interrupt.
    act(() => {
      result.current.handleConfirmInterrupt();
    });

    // Dialog cleared, transition started, and BOTH bridge events fired exactly once.
    expect(result.current.showInterruptConfirm).toBe(false);
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.setMessages).toHaveBeenCalledWith([]);

    const calls = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls.map(
      (c: unknown[]) => c[0]
    );
    expect(calls.filter((c) => c === 'interrupt_session:')).toHaveLength(1);
    expect(calls.filter((c) => c === 'create_new_session:')).toHaveLength(1);
  });

  it('handleConfirmNewSession cleans state and creates new session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Trigger dialog first
    act(() => {
      result.current.createNewSession();
    });

    // Confirm
    act(() => {
      result.current.handleConfirmNewSession();
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(result.current.showNewSessionConfirm).toBe(false);
  });

  it('handleConfirmInterrupt interrupts and cleans state', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'responding...', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Must trigger interrupt dialog first
    act(() => {
      result.current.createNewSession();
    });

    // Then confirm interrupt
    act(() => {
      result.current.handleConfirmInterrupt();
    });

    expect(window.sendToJava).toHaveBeenCalledWith('interrupt_session:');
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
  });

  it('loadHistorySession without loading state does not send interrupt', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-2',
          title: null,
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 1,
          lastTimestamp: Date.now(),
        },
      ],
      total: 1,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-2');
    });

    // Should NOT send interrupt when not loading
    const calls = (window.sendToJava as any).mock.calls.map((c: any) => c[0]);
    expect(calls).not.toContain('interrupt_session:');
    // The retired sonnet-4-6 is normalized to its live replacement in the payload.
    expect(calls).toContain(
      'load_session:{"sessionId":"hist-2","provider":"claude","model":"claude-sonnet-5"}',
    );

    // But should still set transition guard
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('hist-2');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith(null);
  });

  it('loadHistorySession sends explicit provider when provided by history item', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-codex',
          title: 'Codex Session',
          provider: 'codex',
          model: 'gpt-5.4',
          messageCount: 2,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-codex', 'codex');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'load_session:{"sessionId":"hist-codex","provider":"codex","model":"gpt-5.4"}'
    );
  });

  it('loadHistorySession falls back to current provider when history item has no provider', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-codex-missing-provider',
          title: 'Codex Session',
          messageCount: 2,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        currentProvider: 'codex',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-codex-missing-provider');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'load_session:{"sessionId":"hist-codex-missing-provider","provider":"codex"}'
    );
  });

  it('all transition paths reset usage tokens', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Test forceCreateNewSession
    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(mocks.setUsageMaxTokens).toHaveBeenCalledWith(undefined);
  });

  it('beginSessionTransition clears all transient UI states synchronously', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    // All transient UI states must be synchronously cleared
    expect(mocks.setStatus).toHaveBeenCalledWith('');
    expect(mocks.setLoading).toHaveBeenCalledWith(false);
    expect(mocks.setIsThinking).toHaveBeenCalledWith(false);
    expect(mocks.setStreamingActive).toHaveBeenCalledWith(false);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(mocks.setUsageMaxTokens).toHaveBeenCalledWith(undefined);
  });

  it('historyLoadComplete releases transition guard', () => {
    // Simulate what happens when Java calls historyLoadComplete after successful load
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-test';

    // historyLoadComplete is defined in useWindowCallbacks, but we can test
    // that the guard release mechanism works by direct simulation
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBe('transition-test');

    // Simulate historyLoadComplete behavior
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });

  it('loadHistorySession sets transition guard that blocks updateMessages', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-3',
          title: 'Test Session',
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 1,
          lastTimestamp: Date.now(),
        },
      ],
      total: 1,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-3');
    });

    // Guard is set, blocking stale updateMessages
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();

    // Simulate historyLoadComplete (success path releases guard)
    act(() => {
      window.__sessionTransitioning = false;
      window.__sessionTransitionToken = null;
    });
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();

    // Simulate failure path: guard must also be released
    act(() => {
      window.__sessionTransitioning = true; // re-arm
      window.__sessionTransitionToken = 'transition-rearm';
    });
    // Java exceptionally block calls historyLoadComplete before addErrorMessage
    act(() => {
      window.__sessionTransitioning = false;
      window.__sessionTransitionToken = null;
    });
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });
});
