import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { receiveCodexStorageResponse, storageRequest } from './codexStorageBridge';

function request() {
  const call = vi.mocked(window.sendToJava!).mock.calls.at(-1)![0];
  return JSON.parse(call.slice(call.indexOf(':') + 1)) as { requestId: string; scanId?: string };
}
const page = { sessions: [], total: 0, page: 1, pageSize: 20, hasMore: false, complete: true, errors: [] };

describe('read-only storage transport', () => {
  beforeEach(() => { vi.useFakeTimers(); window.sendToJava = vi.fn(); });
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
});
