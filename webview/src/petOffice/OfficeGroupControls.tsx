import Select from 'antd/es/select';
import Button from 'antd/es/button';
import { useTranslation } from 'react-i18next';
import { OFFICE_FLOOR_CAPACITY, OFFICE_MAX_SOURCES } from './officeModel';
import { officeGroupMembers } from './officeGroups';
import type { OfficeOrganization, OfficeSeatedSource } from './officeGroups';

interface SharedProps { organization: OfficeOrganization; sources: readonly OfficeSeatedSource[] }
/** A compact room switcher lives inside the scene panel and follows its palette. */
export function OfficeGroupNavigation({ organization, sources, onEnter, onManage }: SharedProps & {
  onEnter: (id: string) => void; onManage: () => void;
}) {
  const { t } = useTranslation('petOffice');
  const rooms = [{ id: '', name: t('groupsUngrouped') }, ...organization.groups];
  const counts = new Map<string, number>();
  for (const source of sources) counts.set(source.officeGroup, (counts.get(source.officeGroup) ?? 0) + 1);
  return <nav className="office-group-navigation" aria-label={t('groupsRooms')}>
    <span className="office-group-caption" aria-hidden="true"><svg viewBox="0 0 20 20"><circle cx="7" cy="7" r="2.5" /><path d="M2.5 16v-2a4.5 4.5 0 0 1 9 0v2M13 4.8a2.5 2.5 0 0 1 0 4.4M14 12a4 4 0 0 1 3.5 4" /></svg></span>
    <div className="office-group-room-tabs">{rooms.map(group =>
      <button key={group.id} type="button" aria-pressed={organization.activeGroup === group.id}
        aria-label={t('groupEnterLabel', { name: group.name })} title={group.name} onClick={() => onEnter(group.id)}>
        <span className="office-group-dot" aria-hidden="true" /><strong>{group.name}</strong>
        <span className="office-group-count">{counts.get(group.id) ?? 0}</span>
      </button>)}</div>
    <Button size="small" type="text" className="office-group-manage" aria-label={t('groupsManage')} title={t('groupsManage')} onClick={onManage}
      icon={<svg aria-hidden="true" viewBox="0 0 20 20"><path d="M10 4v12M4 10h12" /></svg>}>{t(organization.groups.length ? 'groupsManage' : 'groupCreate')}</Button>
  </nav>;
}

/** Membership and desk controls only offer currently admitted employees. */
export function OfficeWorkstationGroupControls({ organization, sources, source, names, disabled, onAssign, onSwap }: SharedProps & {
  source: OfficeSeatedSource; names: Readonly<Record<string, string>>; disabled: boolean; onAssign: (group: string) => void; onSwap: (seat: number) => void;
}) {
  const { t } = useTranslation('petOffice'), group = source.officeGroup;
  const sameGroup = officeGroupMembers(sources, group), occupants = new Map(sameGroup.map(source => [source.seat, source]));
  const count = group ? OFFICE_FLOOR_CAPACITY : Math.min(OFFICE_MAX_SOURCES,
    Math.max(OFFICE_FLOOR_CAPACITY, Math.ceil((Math.max(source.seat, ...sameGroup.map(source => source.seat)) + 1) / OFFICE_FLOOR_CAPACITY) * OFFICE_FLOOR_CAPACITY));
  const options = [{ value: '', label: t('groupsUngrouped') }, ...organization.groups.map(item => ({
    value: item.id, label: item.name + ' · ' + officeGroupMembers(sources, item.id).length + '/12',
    disabled: item.id !== group && officeGroupMembers(sources, item.id).length >= OFFICE_FLOOR_CAPACITY,
  }))];
  return <>
    <section className="office-appearance-control" aria-label={t('groupMembership')}><label htmlFor="office-group-membership">{t('groupMembership')}</label>
      <div><Select id="office-group-membership" aria-label={t('groupMembership')} size="small" value={group} disabled={disabled}
        popupMatchSelectWidth={240} options={options} onChange={onAssign} /></div></section>
    <section className="office-appearance-control" aria-label={t('groupSeat')}><label htmlFor="office-group-seat" title={t('groupSeatHint')}>{t('groupSeat')}</label>
      <div><Select id="office-group-seat" aria-label={t('groupSeat')} size="small" value={source.seat} disabled={disabled} title={t('groupSeatHint')} virtual={!group} popupMatchSelectWidth={260}
        options={Array.from({ length: count }, (_, seat) => { const employee = occupants.get(seat); return { value: seat,
          label: t('groupSeatOption', { seat: seat + 1, name: employee ? names[employee.sourceKey] || employee.tabLabel : t('groupEmptySeat') }) }; })}
        onChange={onSwap} /></div></section>
  </>;
}
