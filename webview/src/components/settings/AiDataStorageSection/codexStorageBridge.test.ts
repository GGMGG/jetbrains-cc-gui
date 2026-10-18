import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  deleteCodexStorageSessions, newMaintenanceId, receiveCodexStorageResponse, storageRequest, subscribeCodexDeletion,
} from './codexStorageBridge';

interface Request { requestId: string; operationId: string; provider: string; sessionIds: string[] }
const sent = vi.fn<(message: string) => void>();
const lastRequest = (): Request => {
  const call = sent.mock.calls.at(-1)?.[0] ?? '';
  return JSON.parse(call.slice(call.indexOf(':') + 1)) as Request;
};
const completed = (id: string) => ({ operationId: id, kind: 'DELETE_SESSIONS', state: 'COMPLETED', targets: ['one'],
  createdAt: '2026-10-16T00:00:00Z', updatedAt: '2026-10-16T00:00:01Z', result: { deletedSessionIds: ['one'] } });

beforeEach(() => { sent.mockClear(); window.sendToJava = sent; });
afterEach(() => { vi.useRealTimers(); });

describe('Codex storage bridge', () => {
  it('validates an orphan preview before accepting a cleanup capability', async () => {
    const response = storageRequest('preview_ai_storage_orphans');
    const request = lastRequest();
    receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'preview_ai_storage_orphans', success: true,
      data: { databaseId: 'registered', previewToken: '../unsafe', candidateCount: 1 } }));
    const data = { databaseId: 'registered', previewToken: 'a'.repeat(64), candidateCount: 1, rowCount: 10, estimatedBytes: 100,
      retainedFileSessions: 17, skippedBusyCount: 0, skippedExistingCount: 0, candidates: [{ sessionId: 'stale', title: 'Stale', estimatedBytes: 100 }] };
    receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'preview_ai_storage_orphans', success: true, data }));
    await expect(response).resolves.toEqual(data);
  });

  it('correlates action and request ID and rejects malformed content without resolving another request', async () => {
    const response = storageRequest('list_ai_storage_sessions');
    const request = lastRequest();
    receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'get_ai_storage_usage', success: true, data: {} }));
    receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'list_ai_storage_sessions', success: true, data: { sessions: 'invalid' } }));
    const data = { sessions: [], total: 0, page: 1, pageSize: 10, hasMore: false, complete: true, errors: [] };
    receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'list_ai_storage_sessions', success: true, data }));
    await expect(response).resolves.toEqual(data);
  });

  it('rejects invalid page metadata before accepting a paginated response', async () => {
    const response = storageRequest('list_ai_storage_sessions', { page: 3, limit: 15 });
    const request = lastRequest();
    const data = { sessions: [], total: 0, page: 1, pageSize: 15, hasMore: false, complete: true, errors: [] };
    for (const invalid of [{ page: 0 }, { page: 1.5 }, { pageSize: 0 }, { pageSize: 101 }, { total: -1 }]) {
      receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'list_ai_storage_sessions', success: true, data: { ...data, ...invalid } }));
    }
    receiveCodexStorageResponse(JSON.stringify({ ...request, action: 'list_ai_storage_sessions', success: true, data }));
    await expect(response).resolves.toEqual(data);
  });

  it('persists the operation ID before dispatch and emits confirmed deletion only once on replay', async () => {
    const operationId = newMaintenanceId();
    expect(localStorage.getItem('ccgui.codex-storage.last-operation.v1')).toBe(operationId);
    const listener = vi.fn();
    const unsubscribe = subscribeCodexDeletion(listener);
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        const response = deleteCodexStorageSessions(['one'], operationId);
        expect(sent.mock.calls.at(-1)?.[0]).toMatch(/^delete_ai_storage_sessions:/);
        const request = lastRequest();
        expect(request).toMatchObject({ operationId, sessionIds: ['one'], provider: 'codex' });
        receiveCodexStorageResponse(JSON.stringify({ action: 'delete_ai_storage_sessions', requestId: request.requestId, success: true, data: completed(operationId) }));
        await expect(response).resolves.toMatchObject({ state: 'COMPLETED' });
      }
      expect(listener).toHaveBeenCalledOnce();
    } finally { unsubscribe(); }
  });

  it('keeps the operation identity after a lost response and exposes a queryable timeout', async () => {
    vi.useFakeTimers();
    const id = newMaintenanceId();
    const response = deleteCodexStorageSessions(['one'], id);
    const rejected = expect(response).rejects.toMatchObject({ code: 'STORAGE_RESPONSE_TIMEOUT' });
    await vi.advanceTimersByTimeAsync(120001);
    await rejected;
    expect(localStorage.getItem('ccgui.codex-storage.last-operation.v1')).toBe(id);
    expect(sent).toHaveBeenCalledOnce();
  });

  it('propagates backend refusal without emitting a deletion event', async () => {
    const listener = vi.fn();
    const unsubscribe = subscribeCodexDeletion(listener);
    try {
      const response = deleteCodexStorageSessions(['one']);
      receiveCodexStorageResponse(JSON.stringify({ action: 'delete_ai_storage_sessions', requestId: lastRequest().requestId, success: false, error: 'SESSION_RUNNING' }));
      await expect(response).rejects.toMatchObject({ code: 'SESSION_RUNNING' });
      expect(listener).not.toHaveBeenCalled();
    } finally { unsubscribe(); }
  });
});
