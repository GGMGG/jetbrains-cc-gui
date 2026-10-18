import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { createPortal } from 'react-dom';
import Modal from 'antd/es/modal';
import Pagination from 'antd/es/pagination';
import ConfirmDialog from '../../ConfirmDialog';
import { HistoryConfirmDialogs } from '../../history/HistoryConfirmDialogs';
import { useHistorySelection } from '../../history/useHistorySelection';
import {
  CodexStorageError, deleteCodexStorageSessions, isTerminalOperation, lastMaintenanceId, newMaintenanceId,
  operationSucceeded, storageRequest, subscribeCodexDeletion,
} from './codexStorageBridge';
import type {
  CodexDatabaseStatus, CodexMaintenanceOperation, CodexStorageContent,
  CodexStoragePage, CodexStorageSession, CodexStorageUsage, CodexOrphanPreview,
} from './codexStorageBridge';
import styles from './codexStorage.module.less';
import layout from './style.module.less';
import CodexStorageContentRecord from './CodexStorageContentRecord';

interface CodexStoragePanelProps {
  addToast: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;
}

export function storageBytes(bytes: number): string {
  if (!Number.isFinite(bytes)) return '—';
  const absolute = Math.abs(bytes);
  const unit = absolute >= 1024 ** 3 ? 3 : absolute >= 1024 ** 2 ? 2 : absolute >= 1024 ? 1 : 0;
  return `${(bytes / 1024 ** unit).toFixed(unit === 0 ? 0 : 1)} ${['B', 'KiB', 'MiB', 'GiB'][unit]}`;
}

function operationNeedsAttention(operation: CodexMaintenanceOperation): boolean {
  return !operationSucceeded(operation) || operation.state === 'COMPLETED_WITH_WARNINGS'
    || !!operation.error || !!operation.result?.warning
    || !!(operation.result?.metadataStatus && operation.result.metadataStatus !== 'COMPLETE');
}

/** Cross-project maintenance uses the existing history selection and irreversible confirmation. */
export default function CodexStoragePanel({ addToast }: CodexStoragePanelProps) {
  const { t } = useTranslation();
  const [usage, setUsage] = useState<CodexStorageUsage | null>(null);
  const [database, setDatabase] = useState<CodexDatabaseStatus | null>(null);
  const [databaseError, setDatabaseError] = useState<string | null>(null);
  const [page, setPage] = useState<CodexStoragePage | null>(null);
  const [sessions, setSessions] = useState<CodexStorageSession[]>([]);
  const [operations, setOperations] = useState<CodexMaintenanceOperation[]>([]);
  const [sessionCriteria, setSessionCriteria] = useState({ query: '', sort: 'recent', filter: 'all', pageSize: 10 });
  const { query, sort, filter, pageSize } = sessionCriteria;
  const [sessionPage, setSessionPage] = useState(1);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [opened, setOpened] = useState<CodexStorageSession | null>(null);
  const [content, setContent] = useState<CodexStorageContent | null>(null);
  const [contentLoading, setContentLoading] = useState(false);
  const [contentError, setContentError] = useState<string | null>(null);
  const [contentPage, setContentPage] = useState(0);
  const [contentCursors, setContentCursors] = useState<Array<string | undefined>>([undefined]);
  const [orphanPreview, setOrphanPreview] = useState<CodexOrphanPreview | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const mounted = useRef(true);
  const listGeneration = useRef(0);
  const contentGeneration = useRef(0);
  const contentView = useRef<HTMLElement>(null);
  const advancedDetails = useRef<HTMLDetailsElement>(null);
  const compactionView = useRef<HTMLDivElement>(null);
  const sessionListView = useRef<HTMLDivElement>(null);
  const scrollToSessionPage = useRef(false);
  const criteria = useRef(sessionCriteria);
  const previousCriteria = useRef(sessionCriteria);
  const currentSessionPage = useRef(1);
  const refreshGeneration = useRef(0);
  const toastRef = useRef(addToast);
  const completedRef = useRef(new Set<string>());
  toastRef.current = addToast;
  criteria.current = sessionCriteria;

  const reason = (code: string) => t(`settings.codexStorage.errors.${code}`, { defaultValue: t('settings.codexStorage.errors.STORAGE_OPERATION_FAILED') });
  const releasedLabel = useCallback((bytes: number) => bytes > 0
    ? t('settings.codexStorage.released', { size: storageBytes(bytes) })
    : bytes < 0 ? t('settings.codexStorage.increased', { size: storageBytes(-bytes) }) : t('settings.codexStorage.noSavings'), [t]);

  const closeContent = useCallback(() => {
    contentGeneration.current++;
    setOpened(null);
    setContent(null);
    setContentError(null);
    setContentLoading(false);
  }, []);

  const loadSessions = useCallback(async (requestedPage = currentSessionPage.current) => {
    const generation = ++listGeneration.current;
    const requestedCriteria = criteria.current;
    setListLoading(true);
    setListError(null);
    setSessions([]);
    closeContent();
    try {
      const result = await storageRequest('list_ai_storage_sessions', {
        query: requestedCriteria.query, sort: requestedCriteria.sort, filter: requestedCriteria.filter,
        limit: requestedCriteria.pageSize, page: requestedPage,
      });
      if (!mounted.current || generation !== listGeneration.current || requestedCriteria !== criteria.current) return;
      setSessions(result.sessions);
      setPage(result);
      setSessionPage(result.page);
      currentSessionPage.current = result.page;
    } catch (cause) {
      if (mounted.current && generation === listGeneration.current) {
        setListError(cause instanceof CodexStorageError ? cause.code : 'STORAGE_OPERATION_FAILED');
      }
    } finally {
      if (mounted.current && generation === listGeneration.current) setListLoading(false);
    }
  }, [closeContent]);

  const changeSessionCriteria = (updates: Partial<typeof sessionCriteria>) => {
    const next = { ...criteria.current, ...updates };
    criteria.current = next;
    listGeneration.current++;
    currentSessionPage.current = 1;
    setSessionPage(1);
    setSessionCriteria(next);
    setSessions([]);
    setPage(null);
    setListLoading(true);
    setListError(null);
    closeContent();
  };

  const refresh = useCallback(async (clearError = true) => {
    const generation = ++refreshGeneration.current;
    setRefreshing(true);
    if (clearError) setError(null);
    const previousId = lastMaintenanceId();
    const results = await Promise.allSettled([
      storageRequest('get_ai_storage_usage').then(value => {
        if (mounted.current && generation === refreshGeneration.current) setUsage(value);
        return value;
      }),
      storageRequest('get_ai_storage_database_status').then(value => {
        if (mounted.current && generation === refreshGeneration.current) {
          setDatabase(value);
          setDatabaseError(null);
        }
        return value;
      }).catch((cause: unknown) => {
        if (mounted.current && generation === refreshGeneration.current) {
          setDatabaseError(cause instanceof CodexStorageError ? cause.code : 'STORAGE_OPERATION_FAILED');
        }
        throw cause;
      }),
      storageRequest('list_ai_storage_maintenance_operations'), loadSessions(),
      previousId ? storageRequest('get_ai_storage_maintenance_operation', { operationId: previousId }) : Promise.resolve(null),
    ]);
    if (!mounted.current || generation !== refreshGeneration.current) return;
    if (results[2].status === 'fulfilled') {
      const known = results[4].status === 'fulfilled' ? results[4].value : null;
      const merged = known ? [known, ...results[2].value.filter(item => item.operationId !== known.operationId)] : results[2].value;
      setOperations(merged.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)));
    }
    const failure = [results[0], results[2]].find(result => result.status === 'rejected');
    if (failure?.status === 'rejected') {
      setError(failure.reason instanceof CodexStorageError ? failure.reason.code : 'STORAGE_OPERATION_FAILED');
    }
    setRefreshing(false);
  }, [loadSessions]);

  const updateOperation = useCallback((operation: CodexMaintenanceOperation) => {
    if (!mounted.current) return;
    setOperations(previous => [operation, ...previous.filter(item => item.operationId !== operation.operationId)].slice(0, 50));
    if (operation.state === 'RECONCILIATION_REQUIRED') {
      setBusyId(current => current === operation.operationId ? null : current);
    }
    if (isTerminalOperation(operation)) {
      setBusyId(current => current === operation.operationId ? null : current);
      if (!completedRef.current.has(operation.operationId)) {
        completedRef.current.add(operation.operationId);
        if (!operationNeedsAttention(operation) && operation.kind !== 'DELETE_SESSIONS') {
          const result = operation.result;
          const summary = operation.kind === 'COMPACT_DATABASE' ? t('settings.codexStorage.compacted')
            : result?.cleanedSessionCount !== undefined ? t('settings.codexStorage.orphans.cleanedCount', { count: result.cleanedSessionCount })
            : t('settings.codexStorage.orphans.cleaned');
          toastRef.current([summary, ...(result?.releasedBytes !== undefined ? [releasedLabel(result.releasedBytes)] : [])].join(' · '),
            (result?.releasedBytes ?? 0) < 0 ? 'warning' : 'success');
        }
        void refresh();
      }
    }
  }, [refresh, releasedLabel, t]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const unsubscribe = subscribeCodexDeletion(() => { if (mounted.current) void refresh(); });
    return () => { mounted.current = false; refreshGeneration.current++; listGeneration.current++; contentGeneration.current++; unsubscribe(); };
  }, [refresh]);

  useEffect(() => {
    if (contentView.current) {
      contentView.current.scrollTop = 0;
      if (content) contentView.current.focus({ preventScroll: true });
    }
  }, [content]);

  useEffect(() => {
    if (listLoading || !scrollToSessionPage.current) return;
    scrollToSessionPage.current = false;
    if (!listError) sessionListView.current?.scrollIntoView?.({ block: 'start' });
  }, [listLoading, listError]);

  useEffect(() => {
    const previous = previousCriteria.current;
    if (previous === sessionCriteria) return;
    previousCriteria.current = sessionCriteria;
    const timeout = setTimeout(() => { void loadSessions(1); }, previous.query !== query ? 300 : 0);
    return () => clearTimeout(timeout);
  }, [sessionCriteria, query, loadSessions]);

  useEffect(() => {
    if (!busyId) return;
    let polling = false;
    const interval = setInterval(() => {
      if (polling) return;
      polling = true;
      void storageRequest('get_ai_storage_maintenance_operation', { operationId: busyId })
        .then(updateOperation).catch(() => { /* The execution request may still be acquiring its durable claim. */ })
        .finally(() => { polling = false; });
    }, 3000);
    return () => clearInterval(interval);
  }, [busyId, updateOperation]);

  const runOperation = async (kind: 'delete' | 'compact' | 'clean', targets: string[], existingId?: string, databaseId?: string) => {
    if (busyId) return;
    const operationId = existingId ?? newMaintenanceId();
    setBusyId(operationId);
    setError(null);
    try {
      const operation = kind === 'delete'
        ? await deleteCodexStorageSessions(targets, operationId)
        : kind === 'clean'
        ? await storageRequest('clean_ai_storage_orphans', { operationId, databaseId: databaseId ?? database?.databaseId, previewToken: targets[0] })
        : await storageRequest('compact_ai_storage_database', { operationId, databaseId: database?.databaseId });
      updateOperation(operation);
      setBusyId(null);
    } catch (cause) {
      if (!mounted.current) return;
      const code = cause instanceof CodexStorageError ? cause.code : 'STORAGE_OPERATION_FAILED';
      setError(code);
      if (code !== 'STORAGE_RESPONSE_TIMEOUT') setBusyId(null);
      void refresh(false);
    }
  };

  const eligible = useMemo(() => sessions.filter(row => !row.running && !row.deletionUnavailableReason)
    .map(row => ({ sessionId: row.sessionId, title: row.title, messageCount: 0 })), [sessions]);
  const onDeleteSessions = (ids: string[]) => { void runOperation('delete', ids); };
  const selection = useHistorySelection(eligible, onDeleteSessions);
  const pendingOperation = operations.find(operation => !isTerminalOperation(operation));
  const latestOperation = operations[0];
  const visibleOperation = pendingOperation ?? operations.find(operation => operation.result?.metadataStatus && operation.result.metadataStatus !== 'COMPLETE')
    ?? (latestOperation && operationNeedsAttention(latestOperation) ? latestOperation : undefined);
  const blocked = busyId !== null || pendingOperation !== undefined;
  const sessionBlocked = blocked || listLoading;
  const hasOrphans = (orphanPreview?.candidateCount ?? 0) > 0;
  const reusableBytes = database && database.freelistCount !== undefined && database.pageSize !== undefined
    ? database.freelistCount * database.pageSize : null;
  const storageCategories = usage ? Object.entries(usage.categories).filter(([, bytes]) => bytes > 0)
    .sort(([, left], [, right]) => right - left) : [];
  const sessionsTitle = page && !listError && (page.complete || page.total > 0)
    ? t(page.complete ? 'settings.codexStorage.allSessions' : 'settings.codexStorage.partialSessions', { count: page.total })
    : t('settings.codexStorage.allSessionsTitle');
  const sessionTitle = (session: CodexStorageSession) => session.title === session.sessionId
    ? `${t('settings.codexStorage.untitled')} · ${session.sessionId.slice(0, 8)}` : session.title;
  const operationTitle = (operation: CodexMaintenanceOperation) => t(operation.kind === 'DELETE_SESSIONS' ? 'settings.codexStorage.deletion'
    : operation.kind === 'CLEAN_ORPHANED_HISTORY' ? 'settings.codexStorage.orphans.cleanup' : 'settings.codexStorage.compact');

  const analyzeOrphans = async () => {
    setAnalyzing(true);
    setError(null);
    try {
      const preview = await storageRequest('preview_ai_storage_orphans');
      if (mounted.current) setOrphanPreview(preview);
    } catch (cause) {
      if (mounted.current) setError(cause instanceof CodexStorageError ? cause.code : 'STORAGE_OPERATION_FAILED');
    } finally {
      if (mounted.current) setAnalyzing(false);
    }
  };

  const showCompaction = () => {
    setOrphanPreview(null);
    if (advancedDetails.current) advancedDetails.current.open = true;
    compactionView.current?.scrollIntoView?.({ block: 'center' });
    compactionView.current?.focus({ preventScroll: true });
  };

  const openContent = async (session: CodexStorageSession, cursor?: string, pageIndex = 0) => {
    const generation = ++contentGeneration.current;
    if (opened?.sessionId !== session.sessionId || pageIndex === 0) {
      setContent(null);
      setContentCursors([undefined]);
      setContentPage(0);
    }
    setOpened(session);
    setContentError(null);
    setContentLoading(true);
    try {
      const result = await storageRequest('get_ai_storage_session', { sessionId: session.sessionId, cursor, limit: 20 });
      if (mounted.current && generation === contentGeneration.current) {
        setContent(result);
        setContentPage(pageIndex);
        setContentCursors(previous => { const next = previous.slice(0, pageIndex + 1); next[pageIndex] = cursor; return next; });
      }
    } catch (cause) {
      if (mounted.current && generation === contentGeneration.current) setContentError(cause instanceof CodexStorageError ? cause.code : 'STORAGE_OPERATION_FAILED');
    } finally {
      if (mounted.current && generation === contentGeneration.current) setContentLoading(false);
    }
  };

  return (
    <section className={styles.panel} aria-label={t('settings.codexStorage.title')}>
      <div className={styles.card}>
        <div className={styles.heading}>
          <h4>{t('settings.codexStorage.title')}</h4>
          <button type="button" className={styles.iconButton} aria-label={t('common.refresh')} title={t('common.refresh')}
            onClick={() => { void refresh(); }} disabled={refreshing}>
            <span className={`codicon codicon-${refreshing ? 'loading codicon-modifier-spin' : 'refresh'}`} aria-hidden="true" />
          </button>
        </div>
        {error ? <div role="alert" className={styles.warning}>{reason(error)}</div> : null}
        {usage ? <div className={styles.overview}>
          {([['logicalSize', usage.logicalBytes], ['categories.sessions', usage.categories.sessions ?? 0],
            ['categories.historyDatabase', usage.categories.historyDatabase ?? 0]] as const).map(([key, bytes]) =>
            <div key={key}><small>{t(`settings.codexStorage.${key}`)}</small><strong>{storageBytes(bytes)}</strong></div>)}
        </div> : <p className={styles.hint} role="status">{t('common.loading')}</p>}
        {usage && !usage.complete ? <p className={styles.warning} role="status">{t('settings.codexStorage.usageIncomplete')}</p> : null}
        <div className={styles.cleanupRow}>
          <div><strong>{t('settings.codexStorage.databaseCleanup')}</strong>
            <p className={styles.hint}>{t('settings.codexStorage.orphans.hint')}</p>
          </div>
          <button type="button" className={styles.primary} disabled={blocked || analyzing || !!databaseError || !database?.supported}
            onClick={() => { void analyzeOrphans(); }}>{t(analyzing ? 'settings.codexStorage.orphans.analyzing' : 'settings.codexStorage.orphans.analyze')}</button>
        </div>
        <details ref={advancedDetails} className={`${styles.details} ${styles.advanced}`}>
          <summary>{t('settings.codexStorage.advancedDetails')}</summary>
          <div className={styles.advancedBody}>
            {storageCategories.length > 0 ? <div>
              <h5>{t('settings.codexStorage.categoryDetails')}</h5>
              <dl className={styles.categoryGrid}>{storageCategories.map(([category, bytes]) =>
                <div key={category}><dt>{t(`settings.codexStorage.categories.${category}`)}</dt><dd>{storageBytes(bytes)}</dd></div>)}</dl>
            </div> : null}
            <div ref={compactionView} className={styles.compaction} role="group" aria-label={t('settings.codexStorage.databaseCompaction')} tabIndex={-1}>
              <div className={styles.compactionHeading}>
                <div><h5>{t('settings.codexStorage.databaseCompaction')}</h5><code>thread_history_1.sqlite</code></div>
                <button type="button" disabled={blocked || !!databaseError || !database?.supported}
                  onClick={() => { void runOperation('compact', []); }}>{t('settings.codexStorage.compact')}</button>
              </div>
              {database ? <dl className={styles.databaseMetrics}>
                <div><dt>{t('settings.codexStorage.databaseSize')}</dt><dd>{database.sizes.mainBytes !== undefined ? storageBytes(database.sizes.mainBytes) : '—'}</dd></div>
                <div><dt>WAL</dt><dd>{database.sizes.walBytes !== undefined ? storageBytes(database.sizes.walBytes) : '—'}</dd></div>
                <div><dt>{t('settings.codexStorage.reusableEstimate')}</dt><dd>{reusableBytes !== null ? storageBytes(reusableBytes) : '—'}</dd></div>
              </dl> : null}
              <p className={styles.hint}>{t(reusableBytes === 0 ? 'settings.codexStorage.compactNoFreePages' : 'settings.codexStorage.compactHint')}</p>
            </div>
            {usage ? <details className={styles.scanDetails}>
              <summary>{t('settings.codexStorage.scanDetails')}</summary>
              <div className={styles.scanInfo}>
                <div><span>{t('settings.codexStorage.dataDirectory')}</span><code title={usage.physicalRoot}>{usage.physicalRoot}</code></div>
                <small>{t('settings.codexStorage.scanTime', { time: new Date(usage.scannedAt).toLocaleString() })}</small>
                {usage.skippedLinks.length ? <small>{t('settings.codexStorage.skippedLinks', { count: usage.skippedLinks.length })}</small> : null}
              </div>
            </details> : null}
          </div>
        </details>
        {database?.unavailableReason ? <p className={styles.warning}>{reason(database.unavailableReason)}</p> : null}
        {databaseError ? <p role="alert" className={styles.warning}>{reason(databaseError)}</p> : null}
      </div>

      {(visibleOperation ? [visibleOperation] : []).map(operation => <div className={styles.operation} key={operation.operationId} role="status">
        <strong>{operationTitle(operation)}</strong>
        <span>{t(`settings.codexStorage.states.${operation.state}`, { defaultValue: t('settings.codexStorage.processing') })}</span>
        {operation.result?.releasedBytes !== undefined ? <span>{releasedLabel(operation.result.releasedBytes)}</span> : null}
        {operation.result?.rolloutBytesRemoved !== undefined && operation.result.rolloutBytesRemoved > 0
          ? <span>{t('settings.codexStorage.rolloutsRemoved', { size: storageBytes(operation.result.rolloutBytesRemoved) })}</span> : null}
        {operation.result?.cleanedSessionCount !== undefined ? <span>{t('settings.codexStorage.orphans.cleanedCount', { count: operation.result.cleanedSessionCount })}</span> : null}
        {operation.error ? <span className={styles.warning}>{reason(operation.error)}</span> : null}
        {operation.result?.warning ? <span className={styles.warning}>{reason(operation.result.warning)}</span> : null}
        {!isTerminalOperation(operation) || (operation.result?.metadataStatus && operation.result.metadataStatus !== 'COMPLETE') ? <button type="button" disabled={busyId !== null || (operation.kind !== 'DELETE_SESSIONS' && !database)}
          onClick={() => { void runOperation(operation.kind === 'DELETE_SESSIONS' ? 'delete'
            : operation.kind === 'CLEAN_ORPHANED_HISTORY' ? 'clean' : 'compact', operation.targets, operation.operationId); }}>{t('settings.codexStorage.continueOperation')}</button> : null}
      </div>)}

      {page && !page.complete && page.total > 0 ? <p className={styles.warning} role="status">{t('settings.codexStorage.incomplete')}</p> : null}
      {listError || page && !page.complete && page.total === 0
        ? <p role="alert" className={styles.warning}>{t('settings.codexStorage.sessionLoadFailed')} {reason(listError ?? 'INVENTORY_INCOMPLETE')}</p> : null}
      <details className={layout.group}>
        <summary className={layout.groupHeader}>
          <span className="codicon codicon-history" aria-hidden="true" /><h4>{sessionsTitle}</h4>
          <span className={layout.groupMeta}>{t('settings.codexStorage.manageSessions')}</span>
          <span className={`codicon codicon-chevron-right ${layout.chevron}`} aria-hidden="true" />
        </summary>
        <div className={layout.groupBody}>
          <div className={styles.toolbar}>
            <input aria-label={t('settings.codexStorage.search')} placeholder={t('settings.codexStorage.search')} value={query} onChange={event => changeSessionCriteria({ query: event.target.value })} maxLength={200} />
            <select aria-label={t('settings.codexStorage.sort')} value={sort} onChange={event => changeSessionCriteria({ sort: event.target.value })}>
              <option value="recent">{t('settings.codexStorage.recent')}</option><option value="size">{t('settings.codexStorage.largest')}</option>
            </select>
            <select aria-label={t('settings.codexStorage.filter')} value={filter} onChange={event => changeSessionCriteria({ filter: event.target.value })}>
              {['all', 'database', 'file', 'archived', 'running', 'idle'].map(value => <option value={value} key={value}>{t(`settings.codexStorage.filters.${value}`)}</option>)}
            </select>
          </div>
          <div className={styles.selectionBar}>
            <label className={styles.selectAll}><input type="checkbox" checked={selection.allVisibleSelected} disabled={sessionBlocked || eligible.length === 0}
              onChange={selection.toggleSelectAllVisible} />{t('settings.codexStorage.selectVisible')}</label>
            {selection.selectedCount > 0 ? <button type="button" className={styles.danger} disabled={sessionBlocked}
              onClick={selection.startDeleteSelected}>{t('history.deleteSelected', { count: selection.selectedCount })}</button> : null}
          </div>
          <div ref={sessionListView} className={styles.sessions} aria-busy={listLoading}>
            {sessions.map(session => <div className={styles.session} key={session.sessionId}>
              <input type="checkbox" aria-label={t('settings.codexStorage.selectSession', { title: sessionTitle(session) })}
                checked={selection.selectedSessionIds.has(session.sessionId)} disabled={sessionBlocked || session.running || !!session.deletionUnavailableReason}
                onChange={() => selection.toggleSessionSelection(session.sessionId)} />
              <div className={styles.identity}><div className={styles.sessionTitle}><strong title={`${sessionTitle(session)}\n${session.sessionId}`}>{sessionTitle(session)}</strong>
                <small>{storageBytes(session.fileSize)}</small></div>
                <code title={session.cwd || session.sessionId}>{session.cwd.replace(/^\\\\\?\\/, '') || session.sessionId}</code>
                <small>{session.lastTimestamp > 0 ? new Date(session.lastTimestamp).toLocaleDateString() : ''}
                  {session.running ? ` · ${t('settings.codexStorage.running')}` : session.deletionUnavailableReason === 'SESSION_WRITER_BUSY'
                    ? ` · ${t('settings.codexStorage.writerBusy')}` : ''}
                  {session.archived ? ` · ${t('settings.codexStorage.filters.archived')}` : ''}
                  {session.metadataWarning ? ` · ${t('settings.codexStorage.metadataIncomplete')}` : ''}</small>
              </div>
              <div className={styles.rowActions}><button type="button" className={styles.iconButton} disabled={!session.readable || blocked}
                aria-label={t('settings.codexStorage.view')} title={t('settings.codexStorage.view')}
                onClick={() => { void openContent(session); }}><span className="codicon codicon-preview" aria-hidden="true" /></button>
              <button type="button" className={`${styles.iconButton} ${styles.danger}`} disabled={sessionBlocked || session.running || !!session.deletionUnavailableReason}
                aria-label={t('common.delete')}
                title={session.running ? reason('SESSION_RUNNING') : session.deletionUnavailableReason ? reason(session.deletionUnavailableReason) : undefined}
                onClick={() => setDeletingId(session.sessionId)}><span className="codicon codicon-trash" aria-hidden="true" /></button></div>
            </div>)}
            {page?.complete && !sessions.length && !listLoading && !listError ? <p>{t('settings.codexStorage.empty')}</p> : null}
          </div>
          {listLoading ? <p role="status">{t('common.loading')}</p> : null}
          {page && page.total > 0 ? <nav className={styles.pagination} aria-label={t('settings.codexStorage.pagination.label')}>
            <div className={styles.pageSize}>
              <span>{t('settings.codexStorage.pagination.total', { count: page.total })}</span>
              <select aria-label={t('settings.codexStorage.pagination.pageSize')} value={pageSize} disabled={sessionBlocked}
                onChange={event => { scrollToSessionPage.current = true; changeSessionCriteria({ pageSize: Number(event.target.value) }); }}>
                {[10, 15, 20, 50, 100].map(size => <option key={size} value={size}>{t('settings.codexStorage.pagination.perPage', { count: size })}</option>)}
              </select>
            </div>
            <Pagination className={styles.sessionPagination} size="small" current={sessionPage} pageSize={pageSize} total={page.total}
              showSizeChanger={false} showLessItems showQuickJumper disabled={sessionBlocked}
              onChange={next => { scrollToSessionPage.current = true; void loadSessions(next); }}
              locale={{ prev_page: t('settings.codexStorage.pagination.previous'), next_page: t('settings.codexStorage.pagination.next'),
                prev_3: t('settings.codexStorage.pagination.previousPages'), next_3: t('settings.codexStorage.pagination.nextPages'),
                prev_5: t('settings.codexStorage.pagination.previousPages'), next_5: t('settings.codexStorage.pagination.nextPages'),
                jump_to: t('settings.codexStorage.pagination.jumpTo'), page: t('settings.codexStorage.pagination.pageUnit') }} />
          </nav> : null}
          <p className={styles.footnote}>{t('settings.codexStorage.sessionSizeHint')}</p>
        </div>
      </details>

      <Modal open={opened !== null} title={t('settings.codexStorage.sessionContent')} centered width={880}
        rootClassName={styles.contentModal} onCancel={closeContent} destroyOnHidden
        closable={{ 'aria-label': t('settings.codexStorage.close') }}
        footer={opened && content && (contentPage > 0 || content.hasMore) ? <nav className={`${styles.panel} ${styles.contentPages}`}>
          <button type="button" disabled={contentLoading || contentPage === 0 || blocked}
            onClick={() => { void openContent(opened, contentCursors[contentPage - 1], contentPage - 1); }}>{t('settings.codexStorage.previousContentPage')}</button>
          <span>{t('settings.codexStorage.contentPage', { page: contentPage + 1 })}</span>
          <button type="button" disabled={contentLoading || !content.hasMore || blocked}
            onClick={() => { void openContent(opened, content.nextCursor, contentPage + 1); }}>{t('settings.codexStorage.nextContentPage')}</button>
        </nav> : null}>
        {opened ? <>
          <div className={styles.contentIdentity}>
            <strong title={`${sessionTitle(opened)}\n${opened.sessionId}`}>{sessionTitle(opened)}</strong>
            {opened.cwd ? <code title={opened.cwd}>{opened.cwd.replace(/^\\\\\?\\/, '')}</code> : null}
          </div>
          <section ref={contentView} className={`${styles.panel} ${styles.contentRecords}`} aria-busy={contentLoading} tabIndex={-1}>
            {contentError ? <div role="alert" className={styles.contentError}>
              <p className={styles.warning}>{reason(contentError)}</p>
              <button type="button" onClick={() => { void openContent(opened); }}>{t('common.retry')}</button>
            </div> : null}
            {contentLoading ? <p className={styles.hint} role="status">{t('common.loading')}</p> : null}
            {content ? <>
              {!content.records.length ? <p className={styles.hint}>{t('settings.codexStorage.noContent')}</p> : null}
              {content.records.map((record, index) => <CodexStorageContentRecord key={`${contentPage}-${index}`} record={record} index={index + 1} />)}
            </> : null}
          </section>
        </> : null}
      </Modal>

      {createPortal(<HistoryConfirmDialogs deletingSessionId={deletingId} convertingSessionId={null} isDeletingSelected={selection.isDeletingSelected}
        selectedCount={selection.selectedCount} t={t} onCancelDelete={() => setDeletingId(null)}
        onConfirmDelete={() => { if (deletingId && !sessionBlocked) { onDeleteSessions([deletingId]); setDeletingId(null); } }}
        onCancelConvert={() => { }} onConfirmConvert={() => { }} onCancelDeleteSelected={selection.cancelDeleteSelected}
        onConfirmDeleteSelected={() => { if (!sessionBlocked) selection.confirmDeleteSelected(); }} />, document.body)}
      {orphanPreview ? createPortal(<div className={layout.dialog}><ConfirmDialog isOpen
        title={t(hasOrphans ? 'settings.codexStorage.orphans.confirmTitle' : 'settings.codexStorage.orphans.analysisTitle')}
        message={hasOrphans ? t('settings.codexStorage.orphans.summary', { count: orphanPreview.candidateCount, size: storageBytes(orphanPreview.estimatedBytes) })
          : t('settings.codexStorage.orphans.none')}
        onCancel={() => setOrphanPreview(null)} cancelText={t(hasOrphans ? 'common.cancel' : 'common.close')}
        confirmText={t(hasOrphans ? 'settings.codexStorage.orphans.confirm' : 'settings.codexStorage.orphans.openCompaction')}
        confirmDisabled={hasOrphans && blocked} danger={hasOrphans}
        onConfirm={() => {
          if (!hasOrphans) {
            showCompaction();
          } else if (!blocked) {
            const preview = orphanPreview;
            setOrphanPreview(null);
            void runOperation('clean', [preview.previewToken], undefined, preview.databaseId);
          }
        }}>
        <div className={styles.preview}>
          {hasOrphans ? <>
            <p>{t('settings.codexStorage.orphans.preserved', { count: orphanPreview.retainedFileSessions })}</p>
            <p className={styles.warning}>{t('settings.codexStorage.orphans.irreversible')}</p>
            <p className={styles.hint}>{t('settings.codexStorage.orphans.estimateHint')}</p>
          </> : <p className={styles.hint}>{t('settings.codexStorage.orphans.compactionHint')}</p>}
          {orphanPreview.skippedBusyCount > 0 ? <p>{t('settings.codexStorage.orphans.skippedBusy', { count: orphanPreview.skippedBusyCount })}</p> : null}
          {orphanPreview.skippedExistingCount > 0 ? <p>{t('settings.codexStorage.orphans.skippedExisting', { count: orphanPreview.skippedExistingCount })}</p> : null}
          {orphanPreview.candidates.length ? <details><summary>{t('settings.codexStorage.orphans.showCandidates')}</summary>
            <ul>{orphanPreview.candidates.map(candidate => <li key={candidate.sessionId}>
              <code>{candidate.sessionId}</code><span>{storageBytes(candidate.estimatedBytes)}</span>
            </li>)}</ul>
          </details> : null}
        </div>
      </ConfirmDialog></div>, document.body) : null}
    </section>
  );
}
