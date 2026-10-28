import { useEffect, useState } from 'react';
import Button from 'antd/es/button';
import Input from 'antd/es/input';
import Modal from 'antd/es/modal';
import Popconfirm from 'antd/es/popconfirm';
import Select from 'antd/es/select';
import { useTranslation } from 'react-i18next';
import { phaseColor } from './officeModel';
import { assignOfficeGroup, putOfficeGroup, removeOfficeGroup } from './officeGroups';
import type { OfficeOrganization, OfficeSeatedSource } from './officeGroups';

interface OfficeGroupsDialogProps {
  organization: OfficeOrganization; sources: readonly OfficeSeatedSource[]; names: Readonly<Record<string, string>>;
  stale: boolean; disabled: boolean; onChange: (organization: OfficeOrganization) => void; onClose: () => void;
}

/** Names and live membership share one editor; dormant membership stays out of the UI. */
export function OfficeGroupsDialog({ organization, sources, names, stale, disabled, onChange, onClose }: OfficeGroupsDialogProps) {
  const { t } = useTranslation('petOffice');
  const [name, setName] = useState(''), [editing, setEditing] = useState(''), [rename, setRename] = useState('');
  const [query, setQuery] = useState(''), [error, setError] = useState('');
  const act = (work: () => OfficeOrganization, done?: () => void) => {
    if (disabled) return;
    try { onChange(work()); setError(''); done?.(); } catch (error) { setError(error instanceof Error ? error.message : 'groupsInvalid'); }
  };
  useEffect(() => { if (editing && !organization.groups.some(group => group.id === editing)) setEditing(''); }, [organization.groups, editing]);
  const counts = new Map<string, number>();
  for (const source of sources) counts.set(source.officeGroup, (counts.get(source.officeGroup) ?? 0) + 1);
  const text = query.trim().toLocaleLowerCase(), rows = sources.filter(source =>
    !text || ((names[source.sourceKey] ?? '') + ' ' + source.projectLabel + ' ' + source.tabLabel + ' ' + source.provider).toLocaleLowerCase().includes(text));
  const options = [{ value: '', label: t('groupsUngrouped') }, ...organization.groups.map(group => ({
    value: group.id, label: group.name + ' · ' + (counts.get(group.id) ?? 0) + '/12',
  }))];
  return <Modal open title={t('groupsDialog')} onCancel={onClose} width={680} getContainer={false}
    className="office-groups-dialog" styles={{ body: { maxHeight: 'min(660px, 65dvh)', overflowY: 'auto' } }}
    footer={<Button aria-label={t('groupsDone')} onClick={onClose}>{t('groupsDone')}</Button>}>
    <p className="office-groups-hint">{t('groupCapacityHint')}</p>
    {error ? <p className="office-warning" role="alert">{t(error, { defaultValue: t('groupsInvalid') })}</p> : null}
    <form className="office-group-create" onSubmit={event => {
      event.preventDefault();
      act(() => putOfficeGroup(organization, { id: crypto.randomUUID(), name: name.trim() }), () => setName(''));
    }}>
      <Input aria-label={t('groupName')} placeholder={t('groupNamePlaceholder')} value={name} maxLength={48} disabled={disabled} onChange={event => setName(event.target.value)} />
      <Button htmlType="submit" type="primary" disabled={disabled || !name.trim()}>{t('groupCreate')}</Button>
    </form>
    {organization.groups.length ? <ul className="office-group-tags" aria-label={t('groupsRooms')}>{organization.groups.map(group =>
      <li key={group.id}>{editing === group.id ? <form onSubmit={event => {
        event.preventDefault(); act(() => putOfficeGroup(organization, { id: group.id, name: rename.trim() }), () => setEditing(''));
      }}>
        <Input size="small" autoFocus aria-label={t('groupRenameLabel', { name: group.name })} value={rename} maxLength={48} disabled={disabled}
          onChange={event => setRename(event.target.value)} onKeyDown={event => { if (event.key === 'Escape') setEditing(''); }} />
        <Button size="small" htmlType="submit" type="text" aria-label={t('groupSave')} disabled={disabled || !rename.trim()}>✓</Button>
        <Button size="small" type="text" aria-label={t('groupsCancel')} onClick={() => setEditing('')}>×</Button>
      </form> : <>
        <span className="office-group-dot" aria-hidden="true" /><strong title={group.name}>{group.name}</strong><span className="office-group-tag-count">{counts.get(group.id) ?? 0}/12</span>
        <Button size="small" type="text" aria-label={t('groupRenameLabel', { name: group.name })} disabled={disabled}
          onClick={() => { setEditing(group.id); setRename(group.name); setError(''); }} icon={<svg aria-hidden="true" viewBox="0 0 20 20"><path d="m13 3 4 4-9 9-5 1 1-5ZM11 5l4 4" /></svg>} />
        <Popconfirm title={t('groupDeleteConfirm')} onConfirm={() => act(() => removeOfficeGroup(organization, group.id))}>
          <Button size="small" type="text" danger aria-label={t('groupDeleteLabel', { name: group.name })} disabled={disabled}
            icon={<svg aria-hidden="true" viewBox="0 0 20 20"><path d="M3 5h14M7 5V3h6v2M5 5l1 12h8l1-12M8 8v6M12 8v6" /></svg>} />
        </Popconfirm>
      </>}</li>)}</ul> : null}
    <div className="office-group-employees-heading"><h3>{t('groupsMembers')} <span>{sources.length}</span></h3>
      {sources.length > 8 ? <Input.Search size="small" aria-label={t('groupSearch')} placeholder={t('groupSearch')} value={query} allowClear onChange={event => setQuery(event.target.value)} /> : null}
    </div>
    <ul className="office-group-member-list" aria-label={t('groupsMembers')}>{rows.map(source => {
      const employeeName = names[source.sourceKey] || source.tabLabel;
      const groupOptions = options.map(option => ({ ...option,
        disabled: !!option.value && option.value !== source.officeGroup && (counts.get(option.value) ?? 0) >= 12 }));
      return <li key={source.sourceKey}>
        <span className="office-group-avatar" aria-hidden="true">{[...employeeName][0]}</span>
        <div className="office-group-employee-info"><strong title={employeeName}>{employeeName}</strong>
          <small title={source.projectLabel + ' / ' + source.tabLabel}>{source.projectLabel} / {source.tabLabel} · {source.provider}</small></div>
        <span className="office-group-employee-phase" title={t('phase.' + source.phase)}><i style={{ background: phaseColor(source.phase) }} />{t('phase.' + source.phase)}</span>
        <Select size="small" aria-label={t('groupEmployeeMembership', { name: employeeName })} value={source.officeGroup} disabled={disabled || stale}
          options={groupOptions} popupMatchSelectWidth={240}
          onChange={group => act(() => assignOfficeGroup(organization, [source.sourceKey], group, sources))} />
      </li>;
    })}</ul>
    {!rows.length ? <p className="office-groups-empty">{t(sources.length ? 'groupsNoEmployees' : 'groupsNoOnline')}</p> : null}
  </Modal>;
}
