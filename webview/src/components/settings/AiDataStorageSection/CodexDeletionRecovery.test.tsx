import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexDeletionRecovery from './CodexDeletionRecovery';
import { deleteCodexHistorySessions, pendingCodexDeletion, receiveCodexStorageResponse } from './codexStorageBridge';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
function sent() {
  const call = vi.mocked(window.sendToJava!).mock.calls.at(-1)![0];
  return JSON.parse(call.slice(call.indexOf(':') + 1)) as { requestId: string; operationId: string; sessionIds: string[]; origin: string };
}
function complete(action: string, state = 'COMPLETED', metadataStatus = 'COMPLETE') {
  const request = sent();
  const pending = pendingCodexDeletion()!;
  receiveCodexStorageResponse(JSON.stringify({ requestId: request.requestId, action, success: true,
    data: { operationId: pending.operationId, kind: 'DELETE_SESSIONS', state, targets: pending.sessionIds,
      createdAt: 'now', updatedAt: 'now', result: { deletedSessionIds: pending.sessionIds, metadataStatus } } }));
}
beforeEach(() => { localStorage.clear(); window.sendToJava = vi.fn(); });

describe('deletion recovery controls', () => {
  it('recovers an undelivered request after remount and retries its original selection once', async () => {
    window.sendToJava = vi.fn(() => false);
    await expect(deleteCodexHistorySessions(['original'])).rejects.toMatchObject({ code: 'STORAGE_BRIDGE_UNAVAILABLE' });
    const pending = pendingCodexDeletion();
    const first = render(<CodexDeletionRecovery />);
    expect(screen.getByText('settings.codexDeletion.recovery')).toBeTruthy();
    first.unmount();
    window.sendToJava = vi.fn();
    render(<CodexDeletionRecovery />);
    const retry = screen.getByRole('button', { name: 'settings.codexDeletion.retry' });
    fireEvent.click(retry);
    fireEvent.click(retry);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    expect(sent()).toMatchObject(pending!);
    await act(async () => complete('delete_ai_storage_sessions'));
    await waitFor(() => expect(screen.queryByText('settings.codexDeletion.recovery')).toBeNull());
    expect(pendingCodexDeletion()).toBeNull();
  });

  it('keeps a failed metadata receipt available until the same deletion request finishes cleanup', async () => {
    const first = deleteCodexHistorySessions(['original']);
    complete('delete_ai_storage_sessions', 'COMPLETED_WITH_WARNINGS', 'FAILED');
    await first;
    const pending = pendingCodexDeletion();
    render(<CodexDeletionRecovery />);
    fireEvent.click(screen.getByRole('button', { name: 'settings.codexDeletion.check' }));
    await act(async () => complete('get_ai_storage_maintenance_operation', 'COMPLETED_WITH_WARNINGS', 'FAILED'));
    expect(pendingCodexDeletion()).toEqual(pending);
    fireEvent.click(screen.getByRole('button', { name: 'settings.codexDeletion.retry' }));
    expect(sent()).toMatchObject(pending!);
    await act(async () => complete('delete_ai_storage_sessions'));
    expect(pendingCodexDeletion()).toBeNull();
  });
});
