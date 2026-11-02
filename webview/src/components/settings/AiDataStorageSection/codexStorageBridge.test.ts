import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deleteCodexHistorySessions, deleteCodexStorageSessions, pendingCodexDeletion,
  receiveCodexStorageResponse, retryCodexDeletion, storageRequest, subscribeCodexDeletion } from './codexStorageBridge';

function request() {
  const call = vi.mocked(window.sendToJava!).mock.calls.at(-1)![0];
  return JSON.parse(call.slice(call.indexOf(':') + 1)) as { requestId: string; scanId?: string };
}
const page = { sessions: [], total: 0, page: 1, pageSize: 20, hasMore: false, complete: true, errors: [] };

describe('read-only storage transport', () => {
  beforeEach(() => { vi.useFakeTimers(); window.sendToJava = vi.fn(); localStorage.clear(); });
  afterEach(() => { vi.useRealTimers(); window.sendToJava = undefined; });

  it('correlates opaque request IDs and actions when two list requests complete out of order', async () => {
    const first = storageRequest('list_ai_storage_sessions', { query: 'first' });
    const firstId = request().requestId;
    const second = storageRequest('list_ai_storage_sessions', { query: 'second' });
    const secondId = request().requestId;
    expect(firstId).not.toBe(secondId);
    receiveCodexStorageResponse(JSON.stringify({ requestId: firstId, action: 'get_ai_storage_session', success: true, data: page }));
    receiveCodexStorageResponse(JSON.stringify({ requestId: secondId, action: 'list_ai_storage_sessions', success: true, data: page }));
    await expect(second).resolves.toEqual(page);
    receiveCodexStorageResponse(JSON.stringify({ requestId: firstId, action: 'list_ai_storage_sessions', success: true, data: page }));
    await expect(first).resolves.toEqual(page);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('rejects malformed matched responses immediately', async () => {
    const result = storageRequest('list_ai_storage_sessions');
    const rejected = expect(result).rejects.toMatchObject({ code: 'STORAGE_RESPONSE_INVALID' });
    receiveCodexStorageResponse(JSON.stringify({ requestId: request().requestId,
      action: 'list_ai_storage_sessions', success: true, data: { ...page, total: 'unknown' } }));
    await rejected;
    expect(vi.getTimerCount()).toBe(0);
  });

  it('cancels the corresponding usage scan and ignores its late response', async () => {
    const controller = new AbortController();
    const result = storageRequest('get_ai_storage_usage', {}, controller.signal);
    const scanId = request().requestId;
    const rejected = expect(result).rejects.toMatchObject({ code: 'SCAN_CANCELLED' });
    controller.abort();
    expect(vi.mocked(window.sendToJava!).mock.calls.at(-1)![0]).toMatch(/^cancel_ai_storage_scan:/);
    expect(request().scanId).toBe(scanId);
    await rejected;
    receiveCodexStorageResponse(JSON.stringify({ requestId: scanId, action: 'get_ai_storage_usage', success: true, data: {} }));
    expect(vi.getTimerCount()).toBe(0);
  });

  it('reports failed delivery without waiting for timeout', async () => {
    window.sendToJava = vi.fn(() => false);
    await expect(storageRequest('list_ai_storage_sessions')).rejects.toMatchObject({ code: 'STORAGE_BRIDGE_UNAVAILABLE' });
    expect(vi.getTimerCount()).toBe(0);
  });

  it('bounds the wait for a lost response and removes its timer', async () => {
    const result = storageRequest('list_ai_storage_sessions');
    const rejected = expect(result).rejects.toMatchObject({ code: 'STORAGE_RESPONSE_TIMEOUT' });
    await vi.advanceTimersByTimeAsync(120_000);
    await rejected;
    expect(vi.getTimerCount()).toBe(0);
  });

  it('persists exact targets and origin, then retries the same identity after a lost response', async () => {
    const first = deleteCodexHistorySessions(['selected', 'selected']);
    const firstRequest = request();
    const saved = pendingCodexDeletion()!;
    expect(saved.sessionIds).toEqual(['selected']);
    expect(saved.origin).toBe('history');
    const rejected = expect(first).rejects.toMatchObject({ code: 'STORAGE_RESPONSE_TIMEOUT' });
    await vi.advanceTimersByTimeAsync(120_000);
    await rejected;
    await expect(deleteCodexStorageSessions(['new-selection'])).rejects.toMatchObject({ code: 'STORAGE_OPERATION_PENDING' });
    const retry = retryCodexDeletion();
    expect(request().requestId).not.toBe(firstRequest.requestId);
    const call = vi.mocked(window.sendToJava!).mock.calls.at(-1)![0];
    expect(JSON.parse(call.slice(call.indexOf(':') + 1))).toMatchObject(saved);
    receiveCodexStorageResponse(JSON.stringify({ requestId: request().requestId, action: 'delete_ai_storage_sessions', success: true,
      data: { operationId: saved.operationId, kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: saved.sessionIds,
        createdAt: 'now', updatedAt: 'now', result: { deletedSessionIds: saved.sessionIds, metadataStatus: 'COMPLETE' } } }));
    await retry;
    expect(pendingCodexDeletion()).toBeNull();
  });

  it('does not notify deletion observers for an uncommitted result and emits one confirmed receipt', async () => {
    const listener = vi.fn();
    const unsubscribe = subscribeCodexDeletion(listener);
    try {
      const first = deleteCodexStorageSessions(['selected']);
      const attempt = pendingCodexDeletion()!;
      const status = { operationId: attempt.operationId, kind: 'DELETE_SESSIONS', state: 'RECONCILIATION_REQUIRED',
        targets: ['selected'], createdAt: 'now', updatedAt: 'now' };
      receiveCodexStorageResponse(JSON.stringify({ requestId: request().requestId, action: 'delete_ai_storage_sessions', success: true, data: status }));
      await first;
      expect(listener).not.toHaveBeenCalled();
      expect(pendingCodexDeletion()).toEqual(attempt);
      const completed = { ...status, state: 'COMPLETED', result: { deletedSessionIds: ['selected'], metadataStatus: 'COMPLETE' } };
      for (let index = 0; index < 2; index++) {
        const query = storageRequest('get_ai_storage_maintenance_operation', { operationId: attempt.operationId });
        receiveCodexStorageResponse(JSON.stringify({ requestId: request().requestId, action: 'get_ai_storage_maintenance_operation', success: true, data: completed }));
        await query;
      }
      expect(listener).toHaveBeenCalledTimes(1);
      expect(pendingCodexDeletion()).toBeNull();
    } finally { unsubscribe(); }
  });

  it('retains metadata cleanup failures for retry and never dispatches unsafe targets', async () => {
    await expect(deleteCodexStorageSessions(['../outside'])).rejects.toMatchObject({ code: 'INVALID_SESSION_SELECTION' });
    expect(window.sendToJava).not.toHaveBeenCalled();
    const deletion = deleteCodexStorageSessions(['selected']);
    const saved = pendingCodexDeletion()!;
    receiveCodexStorageResponse(JSON.stringify({ requestId: request().requestId, action: 'delete_ai_storage_sessions', success: true,
      data: { operationId: saved.operationId, kind: 'DELETE_SESSIONS', state: 'COMPLETED_WITH_WARNINGS', targets: saved.sessionIds,
        createdAt: 'now', updatedAt: 'now', result: { deletedSessionIds: saved.sessionIds, metadataStatus: 'FAILED', warning: 'METADATA_CLEANUP_FAILED' } } }));
    await deletion;
    expect(pendingCodexDeletion()).toEqual(saved);
  });
});
