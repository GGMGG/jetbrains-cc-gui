import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { activateLocalOfficeSource, readLocalOffice } from './officeLocalBridge';
import type { LocalOfficeSnapshot, LocalOfficeSource } from './officeLocalModel';

/** A local room observes backend ownership; opening it never starts an AI turn. */
export default function LocalOffice() {
  const { t } = useTranslation();
  const [snapshot, setSnapshot] = useState<LocalOfficeSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [navigation, setNavigation] = useState<string | null>(null);
  const [project, setProject] = useState('');
  const [floor, setFloor] = useState(0);
  const [revision, setRevision] = useState(0);
  const [navigating, setNavigating] = useState(false);
  const navigatingRef = useRef(false);
  const lifetime = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    lifetime.current = controller;
    return () => controller.abort();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const result = await readLocalOffice(controller.signal);
        if (!controller.signal.aborted) { setSnapshot(result); setError(null); }
      } catch (reason) {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'OFFICE_UNAVAILABLE');
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(() => void poll(), 1_000);
      }
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [revision]);
  const projects = useMemo(() => Array.from(new Map(snapshot?.sources.map(source => [source.projectId, source.projectLabel])).entries()), [snapshot]);
  const sources = useMemo(() => snapshot?.sources.filter(source => !project || source.projectId === project) ?? [], [snapshot, project]);
  const lastFloor = Math.max(0, Math.ceil(sources.length / 12) - 1);
  const currentFloor = Math.min(floor, lastFloor);
  async function activate(source: LocalOfficeSource) {
    if (navigatingRef.current || error || source.routeStatus !== 'AVAILABLE') return;
    navigatingRef.current = true;
    setNavigating(true);
    setNavigation(null);
    const signal = lifetime.current?.signal;
    try {
      const result = await activateLocalOfficeSource(source, signal);
      if (!signal?.aborted) setNavigation(result.result);
    } catch (reason) {
      if (!signal?.aborted) setNavigation(reason instanceof Error ? reason.message : 'UNAVAILABLE');
    } finally { navigatingRef.current = false; if (!signal?.aborted) setNavigating(false); }
  }
  return <main className="office-local">
    <header><div><h1>{t('title')}</h1><p>{t('hint')}</p></div>
      <button type="button" onClick={() => setRevision(value => value + 1)}>{t('refresh')}</button></header>
    <div className="office-local-toolbar">
      <label>{t('project')} <select value={project} onChange={event => { setProject(event.target.value); setFloor(0); }}>
        <option value="">{t('allProjects')}</option>{projects.map(([id, label]) => <option key={id} value={id}>{label}</option>)}
      </select></label>
      <span>{t('sessionCount', { count: sources.length })}</span>
      {snapshot && <time dateTime={new Date(snapshot.sampledAt).toISOString()}>{t('updated', { time: new Date(snapshot.sampledAt).toLocaleTimeString() })}</time>}
    </div>
    {error && <p role="alert">{t('readFailed')}</p>}
    {!snapshot && !error && <p role="status">{t('loading')}</p>}
    {snapshot?.capacityReached && <p role="status">{t('capacity')}</p>}
    {navigation && <p role="status">{t(`navigation.${navigation}`, { defaultValue: t('navigation.UNAVAILABLE') })}</p>}
    {snapshot && sources.length === 0 && <p>{t('empty')}</p>}
    <section className="office-local-room" aria-label={t('room')}>
      {sources.slice(currentFloor * 12, (currentFloor + 1) * 12).map(source => <button type="button"
        key={source.sourceKey} className={`office-local-desk office-local-${source.phase.toLowerCase()}`}
        disabled={!!error || navigating || source.routeStatus !== 'AVAILABLE'} onClick={() => void activate(source)}
        aria-label={t('activate', { title: source.tabLabel })}>
        <svg viewBox="0 0 100 80" className="office-local-pet" aria-hidden="true">
          <path d="M50 7V17M34 66V75M66 66V75" /><circle cx="50" cy="6" r="4" />
          <rect x="16" y="19" width="68" height="47" rx="13" /><rect x="29" y="32" width="42" height="20" rx="7" />
          <circle cx="40" cy="41" r="3" /><circle cx="60" cy="41" r="3" /><path d="M42 59H58" />
        </svg>
        <strong>{source.tabLabel}</strong><span>{source.projectLabel}</span><small>{source.provider} {source.modelLabel}</small>
        <span className="office-local-phase">{t(`phases.${source.phase}`)}</span>
      </button>)}
    </section>
    {lastFloor > 0 && <nav aria-label={t('floors')}><button type="button" disabled={currentFloor === 0} onClick={() => setFloor(currentFloor - 1)}>{t('previous')}</button>
      <span>{t('floor', { current: currentFloor + 1, total: lastFloor + 1 })}</span>
      <button type="button" disabled={currentFloor === lastFloor} onClick={() => setFloor(currentFloor + 1)}>{t('next')}</button></nav>}
  </main>;
}
