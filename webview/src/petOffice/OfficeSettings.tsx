import Button from 'antd/es/button';
import Checkbox from 'antd/es/checkbox';
import InputNumber from 'antd/es/input-number';
import Modal from 'antd/es/modal';
import { useTranslation } from 'react-i18next';
import { OFFICE_CACHE_MIN_MIB, OFFICE_CACHE_MAX_MIB, validOfficeCacheLimit } from './officeCachePreferences';
import type { OfficePresentationPreferences } from './officePresentation';

export type OfficePresentationSetting = 'effects' | 'attention' | 'ambience';
interface Props {
  open: boolean;
  onClose: () => void;
  cache: { limitMiB: number; draft: number | null; error: boolean; busy: boolean; onDraft: (value: number | null) => void; onApply: () => void };
  presentation: { value: OfficePresentationPreferences; error: boolean; reducedMotion: boolean; onChange: (name: OfficePresentationSetting, enabled: boolean) => void };
  shared: boolean;
  recovered: boolean;
}

/** Persistent controls; scene actions and read-only diagnostics keep their own entries. */
export function OfficeSettings({ open, onClose, cache, presentation, shared, recovered }: Props) {
  const { t } = useTranslation('petOffice');
  return <Modal title={t('studioSettings')} open={open} onCancel={onClose} footer={null} centered width={720}
    getContainer={false} className="office-settings-modal" styles={{ body: { maxHeight: 'min(700px, calc(100dvh - 160px))', overflowY: 'auto' } }}>
    <section className="office-settings-section"><h3>{t('presentationSettings')}</h3>
      <div className="office-presentation-options">{(['effects', 'attention', 'ambience'] as const).map(name => <Checkbox key={name} checked={presentation.value[name]} onChange={event => presentation.onChange(name, event.target.checked)}>{t(`presentation.${name}`)}</Checkbox>)}</div>
      <p>{t('presentationHint')}</p>{presentation.reducedMotion ? <p>{t('reducedMotionHint')}</p> : null}
      {presentation.error ? <p className="office-warning" role="alert">{t('presentationStorageError')}</p> : null}
    </section>
    <section className="office-settings-section"><h3>{t('performanceSettings')}</h3>
      <div className="office-cache-settings"><label htmlFor="office-cache-limit">{t('cacheLimit')}</label><div>
        <InputNumber<number> id="office-cache-limit" aria-label={t('cacheLimit')} min={OFFICE_CACHE_MIN_MIB} max={OFFICE_CACHE_MAX_MIB} precision={0} step={1} value={cache.draft} onChange={cache.onDraft} />
        <span>MiB</span><Button disabled={cache.busy || !validOfficeCacheLimit(cache.draft) || cache.draft === cache.limitMiB && !cache.error} onClick={cache.onApply}>{t('applyCache')}</Button>
      </div></div>
      <p>{t('cacheHint')}</p>{cache.error ? <p className="office-warning" role="alert">{t('cacheStorageError')}</p> : null}
      <p>{t('imageBudgetHint', { limit: cache.limitMiB })}</p>
    </section>
    {shared ? <p className="office-settings-note">{t('sharedPreferencesHint')}</p> : null}
    {recovered ? <p className="office-warning" role="status">{t('sharedPreferencesRecovered')}</p> : null}
  </Modal>;
}
