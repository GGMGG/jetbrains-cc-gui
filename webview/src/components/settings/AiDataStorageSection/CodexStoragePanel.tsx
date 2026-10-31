import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { HistoryConfirmDialogs } from '../../history/HistoryConfirmDialogs';
import { useHistorySelection } from '../../history/useHistorySelection';
import CodexDeletionRecovery from './CodexDeletionRecovery';
import CodexStorageContentRecord from './CodexStorageContentRecord';
import { CodexStorageError, deleteCodexStorageSessions, deletionPendingEvent, pendingCodexDeletion,
  operationSucceeded, storageRequest, subscribeCodexDeletion, type CodexStorageContent, type CodexStoragePage,
  type CodexStorageSession, type CodexStorageUsage } from './codexStorageBridge';
import { useCodexStorageMutationStatus } from './useCodexStorageMutationStatus';
import styles from './codexStorage.module.less';

function bytes(size: number): string {
  if (size < 1024) return `${size} B`;
  const unit = Math.min(3, Math.floor(Math.log(size) / Math.log(1024)));
  return `${(size / 1024 ** unit).toFixed(1)} ${['B', 'KiB', 'MiB', 'GiB'][unit]}`;
}
function errorCode(error: unknown): string {
  return error instanceof CodexStorageError ? error.code : 'STORAGE_OPERATION_FAILED';
}

/** Cross-project inventory with confirmed, journaled deletion of eligible sessions. */
export default function CodexStoragePanel() {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const [sort, setSort] = useState('recent');
  const [filter, setFilter] = useState('all');
  const [pageNumber, setPageNumber] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [revision, setRevision] = useState(0);
  const [page, setPage] = useState<CodexStoragePage | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [usage, setUsage] = useState<CodexStorageUsage | null>(null);
  const [usageError, setUsageError] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const scanRef = useRef<AbortController | null>(null);
  const [selected, setSelected] = useState<CodexStorageSession | null>(null);
  const [contentCursors, setContentCursors] = useState<Array<string | undefined>>([undefined]);
  const [contentPage, setContentPage] = useState(0);
  const [content, setContent] = useState<CodexStorageContent | null>(null);
  const [contentError, setContentError] = useState<string | null>(null);
  const [contentLoading, setContentLoading] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);
  const locationRef = useRef<AbortController | null>(null);
  const { status: admission, refresh: refreshAdmission } = useCodexStorageMutationStatus();
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deletionError, setDeletionError] = useState<string | null>(null);
  const [hasPendingDeletion, setHasPendingDeletion] = useState(() => pendingCodexDeletion() !== null);
  const lifetime = useRef<AbortController | null>(null);
  const allowed = admission?.deletionAvailable === true && !hasPendingDeletion && !listLoading;
  const eligible = useMemo(() => !allowed || !admission ? [] : (page?.sessions ?? []).filter(session => {
    if (admission.runningSessionIds.includes(session.sessionId) || admission.writerSessionIds.includes(session.sessionId)) return false;
    // The backend can unload the current window's idle writer. Other owners stay protected.
    return !session.deletionUnavailableReason || session.deletionUnavailableReason === 'SESSION_WRITER_BUSY';
  }), [admission, allowed, page]);
  const eligibleIds = useMemo(() => new Set(eligible.map(session => session.sessionId)), [eligible]);
  const deleteSessions = useCallback((ids: string[]) => {
    if (ids.some(id => !eligibleIds.has(id))) return;
    setDeletionError(null);
    const signal = lifetime.current?.signal;
    void deleteCodexStorageSessions(ids).then(result => {
      if (!signal?.aborted && (!operationSucceeded(result) || result.result?.warning)) {
        setDeletionError(result.error ?? result.result?.warning ?? 'STORAGE_OPERATION_FAILED');
      }
    }).catch(error => { if (!signal?.aborted) setDeletionError(errorCode(error)); })
      .finally(() => { if (!signal?.aborted) void refreshAdmission(); });
  }, [eligibleIds, refreshAdmission]);
  const selectionRows = useMemo(() => eligible.map(({ sessionId, title, running }) => ({ sessionId, title, running })), [eligible]);
  const selection = useHistorySelection(selectionRows, deleteSessions);

  useEffect(() => {
    if (deletingId && !eligibleIds.has(deletingId)) setDeletingId(null);
  }, [deletingId, eligibleIds]);

  useEffect(() => {
    const changed = () => setHasPendingDeletion(pendingCodexDeletion() !== null);
    window.addEventListener(deletionPendingEvent, changed);
    window.addEventListener('storage', changed);
    return () => {
      window.removeEventListener(deletionPendingEvent, changed);
      window.removeEventListener('storage', changed);
    };
  }, []);
  useEffect(() => subscribeCodexDeletion(operation => {
    const deleted = new Set(operation.result?.deletedSessionIds);
    setSelected(previous => previous && deleted.has(previous.sessionId) ? null : previous);
    setUsage(null);
    setRevision(value => value + 1);
  }), []);

  useEffect(() => {
    const controller = new AbortController();
    setListLoading(true);
    setListError(null);
    void storageRequest('list_ai_storage_sessions', {
      query: deferredQuery, sort, filter, page: pageNumber, limit: pageSize,
    }, controller.signal).then(result => {
      if (!controller.signal.aborted) setPage(result);
    }).catch(error => {
      if (!controller.signal.aborted) { setPage(null); setListError(errorCode(error)); }
    }).finally(() => {
      if (!controller.signal.aborted) setListLoading(false);
    });
    return () => controller.abort();
  }, [deferredQuery, filter, pageNumber, pageSize, revision, sort]);

  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    setContent(null);
    setContentError(null);
    setContentLoading(true);
    void storageRequest('get_ai_storage_session', {
      sessionId: selected.sessionId, cursor: contentCursors[contentPage], limit: 50,
    }, controller.signal).then(result => {
      if (!controller.signal.aborted) setContent(result);
    }).catch(error => {
      if (!controller.signal.aborted) setContentError(errorCode(error));
    }).finally(() => {
      if (!controller.signal.aborted) setContentLoading(false);
    });
    return () => controller.abort();
  }, [contentCursors, contentPage, selected]);

  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => {
      controller.abort();
      const scan = scanRef.current;
      scanRef.current = null;
      scan?.abort();
      locationRef.current?.abort();
    };
  }, []);

  function scan() {
    if (scanRef.current) return;
    const controller = new AbortController();
    scanRef.current = controller;
    setScanning(true);
    setUsageError(null);
    void storageRequest('get_ai_storage_usage', {}, controller.signal).then(result => {
      if (!controller.signal.aborted) setUsage(result);
    }).catch(error => {
      if (!controller.signal.aborted) setUsageError(errorCode(error));
    }).finally(() => {
      if (scanRef.current === controller) { scanRef.current = null; setScanning(false); }
    });
  }
  function openContent(session: CodexStorageSession) {
    setSelected(session);
    setContentPage(0);
    setContentCursors([undefined]);
  }
  function reveal(session: CodexStorageSession) {
    locationRef.current?.abort();
    const controller = new AbortController();
    locationRef.current = controller;
    setLocationError(null);
    void storageRequest('reveal_ai_storage_session', { sessionId: session.sessionId }, controller.signal)
      .catch(error => { if (!controller.signal.aborted) setLocationError(errorCode(error)); });
  }
  const errors = [...(page?.errors ?? []), ...(usage?.errors ?? [])];

  return <section className={styles.container}>
    <h3>{t('settings.codexStorage.title')}</h3>
    <p>{t('settings.codexStorage.description')}</p>
    <div className={styles.toolbar}>
      <button type="button" onClick={scan} disabled={scanning}>{t('settings.codexStorage.scan')}</button>
      {scanning && <button type="button" onClick={() => scanRef.current?.abort()}>{t('common.cancel')}</button>}
      {usage && <span>{t('settings.codexStorage.logicalSize')}: {bytes(usage.logicalBytes)}</span>}
      {usage && <span>{t('settings.codexStorage.scanTime', { time: usage.scannedAt })}</span>}
    </div>
    {usage && <details className={styles.details}>
      <summary>{t('settings.codexStorage.categoryDetails')}</summary>
      <code>{usage.physicalRoot}</code>
      <dl>{Object.entries(usage.categories).map(([category, size]) => <div key={category}>
        <dt>{t(`settings.codexStorage.categories.${category}`)}</dt><dd>{bytes(size)}</dd>
      </div>)}</dl>
      {usage.skippedLinks.length > 0 && <p>{t('settings.codexStorage.skippedLinks', { count: usage.skippedLinks.length })}</p>}
    </details>}
    {usage?.complete === false && <p role="status">{t('settings.codexStorage.usageIncomplete')}</p>}
    {usageError && <p role="alert">{usageError}</p>}
    <div className={styles.toolbar}>
      <input aria-label={t('settings.codexStorage.search')} placeholder={t('settings.codexStorage.search')} value={query}
        onChange={event => { setQuery(event.target.value); setPageNumber(1); }} />
      <select aria-label={t('settings.codexStorage.sort')} value={sort} onChange={event => { setSort(event.target.value); setPageNumber(1); }}>
        <option value="recent">{t('settings.codexStorage.recent')}</option>
        <option value="size">{t('settings.codexStorage.largest')}</option>
      </select>
      <select aria-label={t('settings.codexStorage.filter')} value={filter} onChange={event => { setFilter(event.target.value); setPageNumber(1); }}>
        {['all', 'active', 'archived'].map(value => <option key={value} value={value}>{t(`settings.codexStorage.filters.${value}`)}</option>)}
      </select>
      <button type="button" disabled={listLoading} onClick={() => setRevision(value => value + 1)}>{t('settings.codexStorage.refresh')}</button>
    </div>
    <p>{t('settings.codexStorage.sessionSizeHint')}</p>
    <CodexDeletionRecovery />
    {admission && !admission.deletionAvailable && <p role="status">{t(
      `settings.codexStorage.errors.${admission.deletionUnavailableReason}`, {
        defaultValue: t('settings.codexStorage.errors.STORAGE_OPERATION_FAILED'),
      })}</p>}
    {deletionError && <p role="alert">{t(`settings.codexStorage.errors.${deletionError}`, {
      defaultValue: t('settings.codexStorage.errors.STORAGE_OPERATION_FAILED'),
    })}</p>}
    <div className={styles.toolbar}>
      {selection.isSelectionMode ? <>
        <button type="button" onClick={selection.exitSelectionMode}>{t('history.exitSelectMode')}</button>
        <button type="button" disabled={!allowed || eligible.length === 0} onClick={selection.toggleSelectAllVisible}>
          {t(selection.allVisibleSelected ? 'history.clearSelection' : 'history.selectAll')}
        </button>
        <button type="button" disabled={!allowed || selection.selectedCount === 0} onClick={selection.startDeleteSelected}>
          {t('history.deleteSelected', { count: selection.selectedCount })}
        </button>
      </> : <button type="button" disabled={!allowed || eligible.length === 0} onClick={selection.enterSelectionMode}>{t('history.selectMode')}</button>}
    </div>
    {listError && <p role="alert">{t('settings.codexStorage.sessionLoadFailed')} {listError}</p>}
    {locationError && <p role="alert">{locationError}</p>}
    {listLoading ? <p role="status">{t('common.loading')}</p> : page && <>
      <h4>{t(page.complete ? 'settings.codexStorage.allSessions' : 'settings.codexStorage.partialSessions', { count: page.total })}</h4>
      {page.sessions.length === 0 ? <p>{t('settings.codexStorage.empty')}</p> : <ul className={styles.sessions}>
        {page.sessions.map(session => <li key={session.sessionId}>
          {selection.isSelectionMode && <input type="checkbox" disabled={!eligibleIds.has(session.sessionId)}
            aria-label={t('settings.codexDeletion.selectSession', { title: session.title })}
            checked={selection.selectedSessionIds.has(session.sessionId)} onChange={() => selection.toggleSessionSelection(session.sessionId)} />}
          <div className={styles.identity}><strong>{session.title}</strong><code>{session.cwd}</code><small>{session.sessionId}</small></div>
          <span>{bytes(session.fileSize)}</span>
          <div className={styles.actions}>
            <button type="button" onClick={() => openContent(session)} disabled={!session.readable}>{t('settings.codexStorage.view')}</button>
            <button type="button" onClick={() => reveal(session)}>{t('settings.codexStorage.openLocation')}</button>
            <button type="button" disabled={!eligibleIds.has(session.sessionId)} onClick={() => setDeletingId(session.sessionId)}>{t('common.delete')}</button>
          </div>
        </li>)}
      </ul>}
      <nav className={styles.toolbar} aria-label={t('settings.codexStorage.pagination')}>
        <button type="button" disabled={page.page <= 1} onClick={() => setPageNumber(page.page - 1)}>{t('settings.codexStorage.previousContentPage')}</button>
        <span>{t('settings.codexStorage.contentPage', { page: page.page })}</span>
        <button type="button" disabled={!page.hasMore} onClick={() => setPageNumber(page.page + 1)}>{t('settings.codexStorage.nextContentPage')}</button>
        <select aria-label={t('settings.codexStorage.pageSize')} value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); setPageNumber(1); }}>
          {[10, 15, 20, 50, 100].map(size => <option key={size} value={size}>{size}</option>)}
        </select>
      </nav>
    </>}
    {errors.length > 0 && <details className={styles.details}>
      <summary>{t('settings.codexStorage.scanErrors', { count: errors.length })}</summary>
      <ul>{errors.slice(0, 20).map((error, index) => <li key={index}>{error}</li>)}</ul>
    </details>}
    <HistoryConfirmDialogs deletingSessionId={deletingId && eligibleIds.has(deletingId) ? deletingId : null} convertingSessionId={null}
      isDeletingSelected={selection.isDeletingSelected && allowed && selection.selectedCount > 0} selectedCount={selection.selectedCount} t={t}
      onCancelDelete={() => setDeletingId(null)} onConfirmDelete={() => {
        if (deletingId && eligibleIds.has(deletingId)) deleteSessions([deletingId]);
        setDeletingId(null);
      }} onCancelConvert={() => {}} onConfirmConvert={() => {}}
      onCancelDeleteSelected={selection.cancelDeleteSelected} onConfirmDeleteSelected={selection.confirmDeleteSelected} />
    {selected && <section className={styles.content} aria-label={t('settings.codexStorage.sessionContent')}>
      <div className={styles.toolbar}><h4>{selected.title}</h4><button type="button" onClick={() => setSelected(null)}>{t('settings.codexStorage.close')}</button></div>
      {contentLoading && <p role="status">{t('common.loading')}</p>}
      {contentError && <p role="alert">{contentError}</p>}
      {content?.records.length === 0 && <p>{t('settings.codexStorage.noContent')}</p>}
      {content?.records.map((record, index) => <CodexStorageContentRecord key={`${contentPage}-${index}`} record={record} index={contentPage * 50 + index + 1} />)}
      <div className={styles.toolbar}>
        <button type="button" disabled={contentLoading || contentPage === 0} onClick={() => setContentPage(value => value - 1)}>{t('settings.codexStorage.previousContentPage')}</button>
        <span>{t('settings.codexStorage.contentPage', { page: contentPage + 1 })}</span>
        <button type="button" disabled={contentLoading || !content?.hasMore} onClick={() => {
          if (!content?.nextCursor) return;
          setContentCursors(current => [...current.slice(0, contentPage + 1), content.nextCursor]);
          setContentPage(value => value + 1);
        }}>{t('settings.codexStorage.nextContentPage')}</button>
      </div>
    </section>}
  </section>;
}
