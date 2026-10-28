import { useEffect, useRef, useState } from 'react';
import Button from 'antd/es/button';
import Input from 'antd/es/input';
import { useTranslation } from 'react-i18next';
import { OFFICE_EMPLOYEE_NAME_MAX_LENGTH, parseOfficeEmployeeName } from './officeEmployeeNames';

interface OfficeEmployeeNameControlProps {
  value: string; fallback: string; disabled: boolean; onChange: (name: string) => void;
}

/** A draft belongs to the selected binding; late saves must not erase newer typing. */
export function OfficeEmployeeNameControl({ value, fallback, disabled, onChange }: OfficeEmployeeNameControlProps) {
  const { t } = useTranslation('petOffice');
  const [draft, setDraft] = useState(value);
  const previousValue = useRef(value);
  useEffect(() => {
    const previous = previousValue.current; previousValue.current = value;
    setDraft(current => current === previous ? value : current);
  }, [value]);
  let valid = true;
  try { parseOfficeEmployeeName(draft.trim()); } catch { valid = false; }
  const apply = () => {
    if (disabled || !valid) return;
    const name = draft.trim(); setDraft(name); onChange(name);
  };
  return <section className="office-appearance-control office-employee-name-control" aria-label={t('employeeName')}>
    <label htmlFor="office-employee-name" title={t('employeeNameHint')}>{t('employeeName')}</label>
    <div className="office-employee-name-input">
      <Input id="office-employee-name" size="small" aria-label={t('employeeName')} aria-describedby={valid ? undefined : 'office-employee-name-hint'}
        aria-invalid={!valid} status={valid ? undefined : 'error'} value={draft} placeholder={fallback} disabled={disabled}
        suffix={<span className="office-employee-name-count" aria-hidden="true">{[...draft].length}/{OFFICE_EMPLOYEE_NAME_MAX_LENGTH}</span>}
        maxLength={OFFICE_EMPLOYEE_NAME_MAX_LENGTH * 2} onChange={event => setDraft(event.target.value)}
        onPressEnter={event => { if (!event.nativeEvent.isComposing) apply(); }} />
      <Button size="small" type="primary" aria-label={t('applyEmployeeName')} disabled={disabled || !valid || draft.trim() === value} onClick={apply}>{t('applyEmployeeName')}</Button>
      <Button size="small" aria-label={t('resetEmployeeName')} title={t('resetEmployeeName')} disabled={disabled || (!draft && !value)}
        onClick={() => { setDraft(''); onChange(''); }} icon={<svg aria-hidden="true" focusable="false" viewBox="0 0 20 20"><path d="M4 7a6.5 6.5 0 1 1-.2 5M4 3v4h4" /></svg>} />
    </div>
    {!valid ? <small id="office-employee-name-hint" className="office-warning" role="alert">{t('employeeNameInvalid')}</small> : null}
  </section>;
}
