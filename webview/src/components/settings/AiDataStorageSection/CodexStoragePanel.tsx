import CodexStorageSessionRelations from './CodexStorageSessionRelations';
import { useDeferredValue, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import CodexStorageContentRecord from './CodexStorageContentRecord';
import { CodexStorageError, storageRequest, type CodexStorageContent, type CodexStoragePage,
  type CodexStorageSession, type CodexStorageUsage } from './codexStorageBridge';
import styles from './codexStorage.module.less';

function bytes(size: number): string {
  if (size < 1024) return `${size} B`;
  const unit = Math.min(3, Math.floor(Math.log(size) / Math.log(1024)));
  return `${(size / 1024 ** unit).toFixed(1)} ${['B', 'KiB', 'MiB', 'GiB'][unit]}`;
}
function errorCode(error: unknown): string {
  return error instanceof CodexStorageError ? error.code : 'STORAGE_OPERATION_FAILED';
}

/** Cross-project read-only inventory; transcript pages are fetched only on request. */
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

  useEffect(() => () => {
    const scan = scanRef.current;
    scanRef.current = null;
    scan?.abort();
    locationRef.current?.abort();
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
    {listError && <p role="alert">{t('settings.codexStorage.sessionLoadFailed')} {listError}</p>}
    {locationError && <p role="alert">{locationError}</p>}
    {listLoading ? <p role="status">{t('common.loading')}</p> : page && <>
      <h4>{t(page.complete ? 'settings.codexStorage.allSessions' : 'settings.codexStorage.partialSessions', { count: page.total })}</h4>
      {page.sessions.length === 0 ? <p>{t('settings.codexStorage.empty')}</p> : <ul className={styles.sessions}>
        {page.sessions.map(session => <li key={session.sessionId}>
          <div className={styles.identity}><strong>{session.title}</strong><code>{session.cwd}</code><small>{session.sessionId}</small><CodexStorageSessionRelations session={session} /></div>
          <span>{bytes(session.fileSize)}</span>
          <div className={styles.actions}>
            <button type="button" onClick={() => openContent(session)} disabled={!session.readable}>{t('settings.codexStorage.view')}</button>
            <button type="button" onClick={() => reveal(session)}>{t('settings.codexStorage.openLocation')}</button>
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
    {selected && <section className={styles.content} aria-label={t('settings.codexStorage.sessionContent')}>
      <CodexStorageSessionRelations session={selected} />
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
