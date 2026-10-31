import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { readLocalOffice, updateOfficeWindowPreferences } from './officeLocalBridge';
import type { OfficeWindowSettings as Settings } from './officeLocalModel';

interface Props { settings: Settings; onSaved: (settings: Settings) => void; }

/** Keep drafts until a matching native commit is acknowledged; polling never replaces an unsaved edit. */
export default function OfficeWindowSettings({ settings, onSaved }: Props) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(settings);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [error, setError] = useState(false);
  const savingRef = useRef(false);
  const lifetime = useRef<AbortController | null>(null);
  useEffect(() => {
    const controller = new AbortController(); lifetime.current = controller;
    return () => controller.abort();
  }, []);
  useEffect(() => { if (!dirty && !saving) setDraft(settings); }, [settings, dirty, saving]);
  function edit(patch: Partial<Pick<Settings, 'officeName' | 'topmost' | 'zoom'>>) { setDraft(value => ({ ...value, ...patch })); setDirty(true); setError(false); }
  async function save() {
    if (savingRef.current || !dirty || Array.from(draft.officeName).length > 64) return;
    savingRef.current = true; setSaving(true); setError(false);
    const signal = lifetime.current?.signal;
    try {
      const saved = await updateOfficeWindowPreferences({ expectedRevision: draft.revision, officeName: draft.officeName, topmost: draft.topmost, zoom: draft.zoom }, signal);
      if (!signal?.aborted) { onSaved(saved); setDraft(saved); setDirty(false); }
    } catch { if (!signal?.aborted) setError(true); }
    finally { savingRef.current = false; if (!signal?.aborted) setSaving(false); }
  }
  async function reload() {
    if (savingRef.current) return;
    savingRef.current = true; setSaving(true); setReloading(true); setError(false);
    const signal = lifetime.current?.signal;
    try {
      const saved = (await readLocalOffice(signal)).settings;
      if (!saved) throw new Error('OFFICE_RESPONSE_INVALID');
      if (!signal?.aborted) { onSaved(saved); setDraft(saved); setDirty(false); }
    } catch { if (!signal?.aborted) setError(true); }
    finally { savingRef.current = false; if (!signal?.aborted) { setSaving(false); setReloading(false); } }
  }
  return <section className="office-window-settings" aria-label={t('windowSettings')}>
    <h2>{t('windowSettings')}</h2>
    {settings.recovered && <p role="status">{t('preferencesRecovered')}</p>}
    <label>{t('officeName')}<input value={draft.officeName} disabled={saving} onChange={event => edit({ officeName: event.target.value })} /></label>
    {Array.from(draft.officeName).length > 64 && <p role="alert">{t('nameTooLong')}</p>}
    <label><input type="checkbox" checked={draft.topmost} disabled={saving} onChange={event => edit({ topmost: event.target.checked })} />{t('topmost')}</label>
    <label>{t('zoom')}<select value={draft.zoom} disabled={saving} onChange={event => edit({ zoom: Number(event.target.value) })}>
      {Array.from(new Set([75, 90, 100, 110, 125, 150, draft.zoom])).sort((a, b) => a - b).map(value => <option key={value} value={value}>{value}%</option>)}
    </select></label>
    {error && <p role="alert">{t('preferencesFailed')}</p>}
    <div><button type="button" disabled={!dirty || saving || Array.from(draft.officeName).length > 64} onClick={() => void save()}>{t(reloading ? 'loading' : saving ? 'saving' : 'save')}</button>
      <button type="button" disabled={saving} onClick={() => void reload()}>{t('reloadSaved')}</button></div>
  </section>;
}
