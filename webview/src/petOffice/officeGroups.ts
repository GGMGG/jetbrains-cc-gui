import { integer, keys, object } from './officeLiveModel';
import type { LiveSource } from './officeLiveModel';
import { OFFICE_FLOOR_CAPACITY, OFFICE_MAX_SOURCES } from './officeModel';
import { parseOfficeEmployeeName } from './officeEmployeeNames';

export interface OfficeGroup { id: string; name: string }
/** A remembered group and preferred desk; offline preferences never reserve live desks. */
export interface OfficePlacement { group: string; seat: number }
export interface OfficeOrganization { groups: OfficeGroup[]; placements: Record<string, OfficePlacement>; activeGroup: string }
export interface OfficeSeatedSource extends LiveSource { officeGroup: string }
export type OfficeSeatMemory = Map<string, OfficePlacement & { preferred?: number; requested?: string }>;
export function emptyOfficeOrganization(): OfficeOrganization { return { groups: [], placements: {}, activeGroup: '' }; }
const ID = /^[A-Za-z0-9_-]{1,64}$/;
const BINDING = /^[A-Za-z0-9._-]{1,128}$/;
function fail(code = 'groupsInvalid'): never { throw new Error(code); }

/** Older room decorations and project rules are discarded when reading preferences. */
export function parseOfficeOrganization(raw: unknown): OfficeOrganization {
  const value = object(raw); keys(value, ['groups', 'placements', 'activeGroup']);
  if (!Array.isArray(value.groups) || value.groups.length > OFFICE_MAX_SOURCES) fail();
  const ids = new Set<string>(), names = new Set<string>();
  const groups = value.groups.map(rawGroup => {
    const group = object(rawGroup);
    keys(group, 'icon' in group ? ['id', 'name', 'icon', 'theme', 'projects'] : ['id', 'name']);
    if (typeof group.id !== 'string' || !ID.test(group.id) || ids.has(group.id)) fail();
    let name: string;
    try { name = parseOfficeEmployeeName(group.name); } catch { return fail('groupNameInvalid'); }
    if (!name || name !== name.trim() || names.has(name.toLowerCase())) fail('groupNameInvalid');
    ids.add(group.id); names.add(name.toLowerCase());
    return { id: group.id, name };
  });
  if (typeof value.activeGroup !== 'string' || value.activeGroup && !ids.has(value.activeGroup)) fail();
  const entries = Object.entries(object(value.placements));
  if (entries.length > OFFICE_MAX_SOURCES) fail('groupRecordsFull');
  const normalized = entries.map(([key, rawPlacement]) => {
    const parts = key.split('/');
    if (parts.length !== 3 || parts.some(part => !BINDING.test(part) || part === '.' || part === '..')) fail();
    const placement = object(rawPlacement); keys(placement, ['group', 'seat']);
    if (typeof placement.group !== 'string' || placement.group && !ids.has(placement.group)) fail();
    return [key, { group: placement.group, seat: integer(placement.seat, placement.group ? OFFICE_FLOOR_CAPACITY - 1 : OFFICE_MAX_SOURCES - 1) }] as const;
  });
  return { groups, placements: Object.fromEntries(normalized.sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)), activeGroup: value.activeGroup };
}

export function officeSourceGroup(organization: OfficeOrganization, key: string): string { return organization.placements[key]?.group ?? ''; }
export function officeGroupMembers(sources: readonly OfficeSeatedSource[], group: string): OfficeSeatedSource[] {
  return sources.filter(source => source.officeGroup === group);
}
export function putOfficeGroup(organization: OfficeOrganization, group: OfficeGroup): OfficeOrganization {
  const exists = organization.groups.some(item => item.id === group.id);
  return parseOfficeOrganization({ ...organization, groups: exists ? organization.groups.map(item => item.id === group.id ? group : item) : [...organization.groups, group] });
}
export function removeOfficeGroup(organization: OfficeOrganization, id: string): OfficeOrganization {
  return parseOfficeOrganization({ groups: organization.groups.filter(group => group.id !== id),
    placements: Object.fromEntries(Object.entries(organization.placements).filter(([, placement]) => placement.group !== id)),
    activeGroup: organization.activeGroup === id ? '' : organization.activeGroup });
}

/** Existing online colleagues have priority when remembered members return to a full room. */
export function seatOfficeSources(sources: readonly LiveSource[], organization: OfficeOrganization, previous: OfficeSeatMemory): OfficeSeatedSource[] {
  const counts = new Map<string, number>(), rooms = new Map<string, string>(), occupied = new Map<string, Set<number>>();
  const priority = (source: LiveSource) => previous.get(source.sourceKey)?.group === officeSourceGroup(organization, source.sourceKey) ? 0 : 1;
  const ordered = sources.slice().sort((a, b) => priority(a) - priority(b));
  for (const source of ordered) {
    let group = officeSourceGroup(organization, source.sourceKey);
    if (group && (counts.get(group) ?? 0) >= OFFICE_FLOOR_CAPACITY) group = '';
    counts.set(group, (counts.get(group) ?? 0) + 1); rooms.set(source.sourceKey, group);
  }
  const next: OfficeSeatMemory = new Map();
  const preferredSeat = (key: string) => {
    const saved = organization.placements[key]; return saved?.group === rooms.get(key) ? saved.seat : undefined;
  };
  const deskPriority = (source: LiveSource) => {
    const key = source.sourceKey, old = previous.get(key), saved = organization.placements[key];
    if (old && saved && (old.requested !== saved.group || old.preferred !== saved.seat)) return 0;
    if (old?.group === rooms.get(key)) return 1;
    return preferredSeat(key) !== undefined ? 2 : 3;
  };
  for (const source of ordered.slice().sort((a, b) => deskPriority(a) - deskPriority(b))) {
    const key = source.sourceKey, group = rooms.get(key)!;
    const taken = occupied.get(group) ?? new Set<number>(); occupied.set(group, taken);
    const old = previous.get(key), saved = organization.placements[key], preferred = preferredSeat(key);
    let seat = old?.group === group && old.preferred === saved?.seat && old.requested === saved?.group ? old.seat : preferred ?? (old?.group === group ? old.seat : 0);
    if (taken.has(seat)) { seat = 0; while (taken.has(seat)) seat++; }
    next.set(key, { group, seat, preferred: saved?.seat, requested: saved?.group }); taken.add(seat);
  }
  previous.clear(); for (const [key, placement] of next) previous.set(key, placement);
  return sources.map(source => ({ ...source, seat: next.get(source.sourceKey)!.seat, officeGroup: next.get(source.sourceKey)!.group }));
}

/** Membership memory is bounded; new edits may retire old, offline binding records. */
function remember(organization: OfficeOrganization, updates: Record<string, OfficePlacement>, sources: readonly OfficeSeatedSource[]): OfficeOrganization {
  const placements = { ...organization.placements, ...updates }, live = new Set(sources.map(source => source.sourceKey));
  let count = Object.keys(placements).length;
  for (const key of Object.keys(placements)) {
    if (count <= OFFICE_MAX_SOURCES) break;
    if (!live.has(key)) { delete placements[key]; count--; }
  }
  return parseOfficeOrganization({ ...organization, placements });
}

/** Only current employees are edited. An over-capacity batch is rejected as a whole. */
export function assignOfficeGroup(organization: OfficeOrganization, keys: readonly string[], target: string, sources: readonly OfficeSeatedSource[]): OfficeOrganization {
  if (target && !organization.groups.some(group => group.id === target)) fail();
  const selected = new Set(keys), live = new Map(sources.map(source => [source.sourceKey, source]));
  if ([...selected].some(key => !live.has(key))) fail();
  const incoming = [...selected].filter(key => live.get(key)!.officeGroup !== target);
  if (target && officeGroupMembers(sources, target).length + incoming.length > OFFICE_FLOOR_CAPACITY) fail('groupFull');
  const occupied = new Set(sources.filter(source => source.officeGroup === target && !selected.has(source.sourceKey)).map(source => source.seat));
  const updates: Record<string, OfficePlacement> = {};
  for (const key of selected) {
    const source = live.get(key)!;
    let seat = source.officeGroup === target ? source.seat : 0;
    while (occupied.has(seat)) seat++;
    updates[key] = { group: target, seat }; occupied.add(seat);
  }
  return remember(organization, updates, sources);
}

export function swapOfficeSeats(organization: OfficeOrganization, from: string, target: { key: string } | { seat: number }, sources: readonly OfficeSeatedSource[]): OfficeOrganization {
  const source = sources.find(item => item.sourceKey === from); if (!source) fail();
  const group = source.officeGroup;
  let other: OfficeSeatedSource | undefined, seat: number;
  if ('key' in target) {
    other = sources.find(item => item.sourceKey === target.key); if (!other) fail();
    if (other.officeGroup !== group) fail('groupSwapRestricted');
    seat = other.seat;
  } else {
    seat = integer(target.seat, group ? OFFICE_FLOOR_CAPACITY - 1 : OFFICE_MAX_SOURCES - 1);
    other = sources.find(item => item.seat === seat && item.officeGroup === group);
  }
  if (source.seat === seat) return organization;
  return remember(organization, { [from]: { group, seat }, ...(other ? { [other.sourceKey]: { group, seat: source.seat } } : {}) }, sources);
}

/** Apply explicit moves even when a fallback desk differs from an unchanged saved preference. Call after save preflight. */
export function prepareOfficeSeatMove(memory: OfficeSeatMemory, before: OfficeOrganization, next: OfficeOrganization, keys: readonly string[]): void {
  if (before === next) return;
  for (const key of keys) {
    const from = before.placements[key], to = next.placements[key];
    if (from && to && from.group === to.group && from.seat === to.seat) memory.delete(key);
  }
}
