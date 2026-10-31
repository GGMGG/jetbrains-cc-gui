import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { codexDeletionInFlight, CodexStorageError, deletionPendingEvent, pendingCodexDeletion,
  retryCodexDeletion, storageRequest, type CodexMaintenanceOperation } from './codexStorageBridge';

/** Lost responses retain the original targets and identity until explicitly reconciled. */
export default function CodexDeletionRecovery() {
  const { t } = useTranslation();
  const [attempt, setAttempt] = useState(pendingCodexDeletion);
  const [busy, setBusy] = useState(codexDeletionInFlight);
  const [status, setStatus] = useState<CodexMaintenanceOperation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const localBusy = useRef(false);
  useEffect(() => {
    const update = () => { setAttempt(pendingCodexDeletion()); setBusy(codexDeletionInFlight() || localBusy.current); };
    window.addEventListener(deletionPendingEvent, update);
    window.addEventListener('storage', update);
    return () => {
      window.removeEventListener(deletionPendingEvent, update);
      window.removeEventListener('storage', update);
      request.current?.abort();
    };
  }, []);
  async function recover(retry: boolean) {
    if (!attempt || localBusy.current || codexDeletionInFlight()) return;
    localBusy.current = true;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError(null);
    try {
      const result = await (retry ? retryCodexDeletion()
        : storageRequest('get_ai_storage_maintenance_operation', { operationId: attempt.operationId }, controller.signal));
      if (!controller.signal.aborted) setStatus(result);
    } catch (reason) {
      if (!controller.signal.aborted) setError(reason instanceof CodexStorageError ? reason.code : 'STORAGE_OPERATION_FAILED');
    } finally {
      localBusy.current = false;
      if (!controller.signal.aborted) { setAttempt(pendingCodexDeletion()); setBusy(codexDeletionInFlight()); }
    }
  }
  if (!attempt) return null;
  return <div role="status">
    <p>{t('settings.codexDeletion.recovery', { count: attempt.sessionIds.length })}</p>
    {status && <p>{t(`settings.codexStorage.states.${status.state}`, { defaultValue: status.state })}</p>}
    {error && <p role="alert">{t(`settings.codexStorage.errors.${error}`, {
      defaultValue: t('settings.codexStorage.errors.STORAGE_OPERATION_FAILED'),
    })}</p>}
    <button type="button" disabled={busy} onClick={() => void recover(false)}>{t('settings.codexDeletion.check')}</button>
    <button type="button" disabled={busy} onClick={() => void recover(true)}>{t('settings.codexDeletion.retry')}</button>
  </div>;
}
