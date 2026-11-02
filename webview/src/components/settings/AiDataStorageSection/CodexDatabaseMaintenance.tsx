import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import ConfirmDialog from '../../ConfirmDialog';
import { CodexStorageError, storageRequest, type CodexDatabaseStatus, type CodexMaintenanceOperation,
  type CodexOrphanPreview, type CodexStorageMutationStatus } from './codexStorageBridge';
import styles from './codexStorage.module.less';

interface Attempt {
  action: 'compact_ai_storage_database' | 'clean_ai_storage_orphans';
  operationId: string;
  databaseId: string;
  previewToken?: string;
}
const recoveryKey = 'ccgui.codex.database-maintenance.pending';
const terminal = new Set(['COMPLETED', 'COMPLETED_WITH_WARNINGS', 'PARTIAL_COMPLETED', 'FAILED_CLEAN', 'CANCELLED_CLEAN']);
function recoverAttempt(): Attempt | null {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(recoveryKey) ?? 'null');
    if (!value || typeof value !== 'object') return null;
    const item = value as Partial<Attempt>;
    return (item.action === 'compact_ai_storage_database' || item.action === 'clean_ai_storage_orphans')
      && typeof item.operationId === 'string' && typeof item.databaseId === 'string'
      && (item.action !== 'clean_ai_storage_orphans' || typeof item.previewToken === 'string') ? item as Attempt : null;
  } catch { return null; }
}
function failure(error: unknown): string {
  return error instanceof CodexStorageError ? error.code : 'STORAGE_OPERATION_FAILED';
}
function forgetAttempt(operationId: string) {
  if (recoverAttempt()?.operationId === operationId) localStorage.removeItem(recoveryKey);
}

/** Explicit database maintenance with durable request identity and status recovery. */
export default function CodexDatabaseMaintenance() {
  const { t } = useTranslation();
  const [database, setDatabase] = useState<CodexDatabaseStatus | null>(null);
  const [admission, setAdmission] = useState<CodexStorageMutationStatus | null>(null);
  const [operations, setOperations] = useState<CodexMaintenanceOperation[]>([]);
  const [preview, setPreview] = useState<CodexOrphanPreview | null>(null);
  const [attempt, setAttempt] = useState<Attempt | null>(recoverAttempt);
  const [confirmation, setConfirmation] = useState<'compact' | 'clean' | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const busyRef = useRef(false);
  const life = useRef<AbortController | null>(null);
  const refreshController = useRef<AbortController | null>(null);

  const refresh = useCallback(async (clearError = true) => {
    refreshController.current?.abort();
    const controller = new AbortController();
    refreshController.current = controller;
    setLoading(true);
    if (clearError) setError(null);
    setPreview(null);
    setConfirmation(null);
    // Invalidate authorization before starting reads; stale status cannot enable controls.
    setDatabase(null);
    setAdmission(null);
    try {
      const [nextDatabase, nextAdmission, nextOperations] = await Promise.all([
        storageRequest('get_ai_storage_database_status', {}, controller.signal),
        storageRequest('get_ai_storage_mutation_status', {}, controller.signal),
        storageRequest('list_ai_storage_maintenance_operations', {}, controller.signal),
      ]);
      if (controller.signal.aborted) return;
      setDatabase(nextDatabase);
      setAdmission(nextAdmission);
      setOperations(nextOperations);
      const pending = recoverAttempt();
      if (pending && nextOperations.some(operation => operation.operationId === pending.operationId && terminal.has(operation.state))) {
        forgetAttempt(pending.operationId);
        setAttempt(previous => previous?.operationId === pending.operationId ? null : previous);
      }
    } catch (reason) {
      if (!controller.signal.aborted) setError(failure(reason));
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    life.current = controller;
    void refresh();
    return () => { controller.abort(); refreshController.current?.abort(); };
  }, [refresh]);

  const allowed = !busy && !loading && !attempt && admission?.available === true
    && database?.supported === true && !database.maintenancePending;
  const reason = admission?.unavailableReason ?? database?.unavailableReason;
  const message = (code: string) => t(`settings.codexStorage.errors.${code}`, {
    defaultValue: t('settings.codexStorage.errors.STORAGE_OPERATION_FAILED', { defaultValue: code }),
  });
  function upsert(operation: CodexMaintenanceOperation) {
    setOperations(previous => [operation, ...previous.filter(item => item.operationId !== operation.operationId)]);
    if (terminal.has(operation.state)) {
      forgetAttempt(operation.operationId);
      setAttempt(previous => previous?.operationId === operation.operationId ? null : previous);
    }
  }
  async function analyze() {
    if (!allowed || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setPreview(null);
    setError(null);
    const signal = life.current?.signal;
    try {
      const result = await storageRequest('preview_ai_storage_orphans', {}, signal);
      if (!signal?.aborted) setPreview(result);
    } catch (reason) { if (!signal?.aborted) setError(failure(reason)); }
    finally { busyRef.current = false; if (!signal?.aborted) setBusy(false); }
  }
  async function execute(retry?: Attempt) {
    if (busyRef.current || (!retry && (!allowed || !confirmation || !database))) return;
    const next: Attempt = retry ?? {
      action: confirmation === 'compact' ? 'compact_ai_storage_database' : 'clean_ai_storage_orphans',
      operationId: crypto.randomUUID(), databaseId: database!.databaseId,
      ...(confirmation === 'clean' ? { previewToken: preview?.previewToken } : {}),
    };
    if (next.action === 'clean_ai_storage_orphans' && !next.previewToken) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    setConfirmation(null);
    const signal = life.current?.signal;
    try {
      // Persist before sending. A lost ACK must not generate another operation identity.
      localStorage.setItem(recoveryKey, JSON.stringify(next));
      setAttempt(next);
      const result = await storageRequest(next.action, { ...next }, signal);
      if (!signal?.aborted) { upsert(result); setPreview(null); }
    } catch (reason) { if (!signal?.aborted) setError(failure(reason)); }
    finally { busyRef.current = false; if (!signal?.aborted) { setBusy(false); void refresh(false); } }
  }
  async function checkPending() {
    if (!attempt || busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    const signal = life.current?.signal;
    try {
      const result = await storageRequest('get_ai_storage_maintenance_operation', { operationId: attempt.operationId }, signal);
      if (!signal?.aborted) upsert(result);
    } catch (reason) { if (!signal?.aborted) setError(failure(reason)); }
    finally { busyRef.current = false; if (!signal?.aborted) setBusy(false); }
  }
  return <details className={`${styles.container} ${styles.details}`}>
    <summary>{t('settings.codexMaintenance.title')}</summary>
    <p>{t('settings.codexMaintenance.hint')}</p>
    <div className={styles.toolbar}>
      <button type="button" disabled={busy || loading} onClick={() => void refresh()}>{t('settings.codexStorage.refresh')}</button>
      <button type="button" disabled={!allowed} onClick={() => setConfirmation('compact')}>{t('settings.codexMaintenance.compact')}</button>
      <button type="button" disabled={!allowed} onClick={() => void analyze()}>{t('settings.codexMaintenance.analyze')}</button>
    </div>
    {loading && <p role="status">{t('common.loading')}</p>}
    {reason && <p role="status">{message(reason)}</p>}
    {database?.maintenancePending && <p role="status">{t('settings.codexMaintenance.pending')}</p>}
    {error && <p role="alert">{message(error)}</p>}
    {database && <dl>{Object.entries(database.sizes).map(([name, bytes]) => <div key={name}><dt>{name}</dt><dd>{bytes} B</dd></div>)}</dl>}
    {preview && <div>
      <p>{t('settings.codexMaintenance.preview', { count: preview.candidateCount, rows: preview.rowCount })}</p>
      <ul>{preview.candidates.slice(0, 20).map(item => <li key={item.sessionId}>{item.title} <code>{item.sessionId}</code></li>)}</ul>
      <button type="button" disabled={!allowed || preview.candidateCount === 0} onClick={() => setConfirmation('clean')}>{t('settings.codexMaintenance.clean')}</button>
    </div>}
    {attempt && <div>
      <p>{t('settings.codexMaintenance.recovery')} <code>{attempt.operationId}</code></p>
      <button type="button" disabled={busy} onClick={() => void checkPending()}>{t('settings.codexMaintenance.check')}</button>
      <button type="button" disabled={busy || loading} onClick={() => void execute(attempt)}>{t('settings.codexMaintenance.retry')}</button>
    </div>}
    {operations.length > 0 && <ul>{operations.map(operation => <li key={operation.operationId}>
      <span>{t(`settings.codexMaintenance.kinds.${operation.kind}`)}: {t(`settings.codexStorage.states.${operation.state}`, { defaultValue: operation.state })}</span> <code>{operation.operationId}</code>
      {typeof operation.result?.releasedBytes === 'number' && <span> {t(
        operation.result.releasedBytes >= 0 ? 'settings.codexMaintenance.released' : 'settings.codexMaintenance.increased',
        { bytes: Math.abs(operation.result.releasedBytes) })}</span>}
      {operation.error && <span> {message(operation.error)}</span>}
      {operation.result?.warning && <span> {message(operation.result.warning)}</span>}
    </li>)}</ul>}
    <ConfirmDialog isOpen={confirmation !== null} danger title={t('settings.codexMaintenance.confirmTitle')}
      message={t(confirmation === 'clean' ? 'settings.codexMaintenance.cleanWarning' : 'settings.codexMaintenance.compactWarning')}
      confirmText={t('common.confirm')} cancelText={t('common.cancel')} confirmDisabled={!allowed}
      onCancel={() => setConfirmation(null)} onConfirm={() => void execute()} />
  </details>;
}
