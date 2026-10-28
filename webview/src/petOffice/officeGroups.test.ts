import { describe, expect, it } from 'vitest';
import { liveSceneFixture } from './officeLiveFixtures';
import { parseLiveScene } from './officeLiveModel';
import { assignOfficeGroup, emptyOfficeOrganization, officeGroupMembers, officeSourceGroup, parseOfficeOrganization,
  prepareOfficeSeatMove, putOfficeGroup, removeOfficeGroup, seatOfficeSources, swapOfficeSeats } from './officeGroups';
import type { OfficeGroup, OfficeSeatMemory, OfficeSeatedSource } from './officeGroups';
import { officeSeatAtPoint } from './officeRenderer';
import { OFFICE_ART_SEATS } from './officeArtGeometry';
import { OFFICE_THEME_IDS } from './officeThemes';

const group = (id: string): OfficeGroup => ({ id, name: 'Team ' + id });
const team = () => putOfficeGroup(putOfficeGroup(emptyOfficeOrganization(), group('a')), group('b'));
const employees = (count: number) => parseLiveScene(liveSceneFixture(Array.from({ length: count }, () => 'RUNNING'))).sources;
const unique = (sources: OfficeSeatedSource[]) => expect(new Set(sources.map(source => source.officeGroup + '/' + source.seat)).size).toBe(sources.length);

describe('Simple live employee groups', () => {
  it('keeps legacy names and memberships while removing decorations and project rules', () => {
    const old = { groups: [{ ...group('a'), icon: 'rocket', theme: 'space', projects: ['project-A'] }],
      placements: { 'ide/tab/binding': { group: 'a', seat: 3 } }, activeGroup: 'a' };
    const migrated = parseOfficeOrganization(old);
    expect(migrated.groups).toEqual([group('a')]);
    expect(migrated.placements).toEqual(old.placements);
    expect(migrated.activeGroup).toBe('a');
    expect(seatOfficeSources(employees(1), migrated, new Map())[0].officeGroup).toBe('');
  });

  it('creates and renames groups and returns a deleted group to Ungrouped', () => {
    const raw = employees(3); let org = team();
    org = assignOfficeGroup(org, raw.slice(0, 2).map(source => source.sourceKey), 'a', seatOfficeSources(raw, org, new Map()));
    org = { ...putOfficeGroup(org, { id: 'a', name: 'New name' }), activeGroup: 'a' };
    expect(org.groups[0].name).toBe('New name');
    expect(officeGroupMembers(seatOfficeSources(raw, org, new Map()), 'a')).toHaveLength(2);
    const deleted = removeOfficeGroup(org, 'a');
    expect(deleted.activeGroup).toBe('');
    expect(seatOfficeSources(raw, deleted, new Map()).every(source => !source.officeGroup)).toBe(true);
  });

  it('rejects an atomic batch above twelve online members', () => {
    const raw = employees(14); let org = team();
    org = assignOfficeGroup(org, raw.slice(0, 11).map(source => source.sourceKey), 'a', seatOfficeSources(raw, org, new Map()));
    const before = JSON.stringify(org);
    expect(() => assignOfficeGroup(org, raw.slice(11).map(source => source.sourceKey), 'a', seatOfficeSources(raw, org, new Map()))).toThrow('groupFull');
    expect(JSON.stringify(org)).toBe(before);
    org = assignOfficeGroup(org, [raw[11].sourceKey], 'a', seatOfficeSources(raw, org, new Map()));
    expect(officeGroupMembers(seatOfficeSources(raw, org, new Map()), 'a')).toHaveLength(12);
  });

  it('remembers offline members without reserving seats or evicting online colleagues on return', () => {
    const raw = employees(13), memory: OfficeSeatMemory = new Map(); let org = team();
    org = assignOfficeGroup(org, raw.slice(0, 12).map(source => source.sourceKey), 'a', seatOfficeSources(raw, org, memory));
    seatOfficeSources(raw, org, memory);
    const online = raw.slice(1);
    org = assignOfficeGroup(org, [raw[12].sourceKey], 'a', seatOfficeSources(online, org, memory));
    let seated = seatOfficeSources(online, org, memory);
    expect(officeGroupMembers(seated, 'a')).toHaveLength(12);
    expect(officeSourceGroup(org, raw[0].sourceKey)).toBe('a');
    seated = seatOfficeSources(raw, org, memory);
    expect(seated[0].officeGroup).toBe('');
    expect(seated[12].officeGroup).toBe('a'); unique(seated);
    seated = seatOfficeSources(raw.filter((_, index) => index !== 1), org, memory);
    expect(seated.find(source => source.sourceKey === raw[0].sourceKey)?.officeGroup).toBe('a');
    expect(seated.find(source => source.sourceKey === raw[12].sourceKey)?.seat).toBe(0);
    expect(officeGroupMembers(seated, 'a')).toHaveLength(12); unique(seated);
  });

  it('rejects offline edits and excludes offline reservations from swaps', () => {
    const raw = employees(3); let org = team();
    org = assignOfficeGroup(org, raw.map(source => source.sourceKey), 'a', seatOfficeSources(raw, org, new Map()));
    const seated = seatOfficeSources(raw.slice(1), org, new Map());
    expect(() => assignOfficeGroup(org, [raw[0].sourceKey], 'b', seated)).toThrow('groupsInvalid');
    expect(() => swapOfficeSeats(org, raw[1].sourceKey, { key: raw[0].sourceKey }, seated)).toThrow('groupsInvalid');
    const moved = swapOfficeSeats(org, raw[1].sourceKey, { seat: 0 }, seated);
    expect(moved.placements[raw[0].sourceKey].group).toBe('a');
    expect(moved.placements[raw[1].sourceKey].seat).toBe(0);
    unique(seatOfficeSources(raw, moved, new Map()));
  });

  it('keeps an online fallback desk when its remembered desk becomes vacant', () => {
    const raw = employees(2), memory: OfficeSeatMemory = new Map(), org = team();
    org.placements = Object.fromEntries(raw.map(source => [source.sourceKey, { group: 'a', seat: 0 }]));
    const initial = seatOfficeSources(raw, org, memory);
    expect(initial[1].seat).toBe(1);
    const remaining = seatOfficeSources(raw.slice(1), org, memory);
    expect(remaining[0].seat).toBe(1);
    const moved = swapOfficeSeats(org, raw[1].sourceKey, { seat: 11 }, remaining);
    expect(seatOfficeSources(raw.slice(1), moved, memory)[0].seat).toBe(11);
  });

  it('applies an explicit fallback move even if its saved preference already names the target desk', () => {
    const raw = employees(2), memory: OfficeSeatMemory = new Map(), org = team();
    org.placements = Object.fromEntries(raw.map(source => [source.sourceKey, { group: 'a', seat: 0 }]));
    seatOfficeSources(raw, org, memory);
    const remaining = seatOfficeSources(raw.slice(1), org, memory);
    const noop = swapOfficeSeats(org, raw[1].sourceKey, { seat: 1 }, remaining);
    prepareOfficeSeatMove(memory, org, noop, [raw[1].sourceKey]);
    expect(seatOfficeSources(raw.slice(1), noop, memory)[0].seat).toBe(1);
    const moved = swapOfficeSeats(org, raw[1].sourceKey, { seat: 0 }, remaining);
    prepareOfficeSeatMove(memory, org, moved, [raw[1].sourceKey]);
    expect(seatOfficeSources(raw.slice(1), moved, memory)[0].seat).toBe(0);
  });

  it('does not let a returning ungrouped pin displace an online unpinned colleague', () => {
    const raw = employees(2), memory: OfficeSeatMemory = new Map(), org = team();
    org.placements[raw[1].sourceKey] = { group: '', seat: 0 };
    expect(seatOfficeSources(raw.slice(0, 1), org, memory)[0].seat).toBe(0);
    const returned = seatOfficeSources(raw, org, memory);
    expect(returned[0].seat).toBe(0); expect(returned[1].seat).toBe(1); unique(returned);
  });

  it('swaps only within the current group, including empty desks', () => {
    const raw = employees(4); let org = team();
    org = assignOfficeGroup(org, raw.slice(0, 2).map(source => source.sourceKey), 'a', seatOfficeSources(raw, org, new Map()));
    org = assignOfficeGroup(org, [raw[2].sourceKey], 'b', seatOfficeSources(raw, org, new Map()));
    const seated = seatOfficeSources(raw, org, new Map());
    const swapped = swapOfficeSeats(org, raw[0].sourceKey, { key: raw[1].sourceKey }, seated);
    expect(swapped.placements[raw[0].sourceKey].seat).toBe(1);
    expect(swapped.placements[raw[1].sourceKey].seat).toBe(0);
    const moved = swapOfficeSeats(swapped, raw[0].sourceKey, { seat: 11 }, seatOfficeSources(raw, swapped, new Map()));
    expect(moved.placements[raw[0].sourceKey]).toEqual({ group: 'a', seat: 11 });
    for (const source of raw.slice(2)) expect(() => swapOfficeSeats(org, raw[0].sourceKey, { key: source.sourceKey }, seated)).toThrow('groupSwapRestricted');
  });

  it('preserves ungrouped pins before allocating other employees across reloads and arrivals', () => {
    const raw = employees(16), memory: OfficeSeatMemory = new Map(), org = team();
    const seated = seatOfficeSources(raw, org, memory);
    const swapped = swapOfficeSeats(org, raw[0].sourceKey, { key: raw[14].sourceKey }, seated);
    const reopened = seatOfficeSources(raw, parseOfficeOrganization(JSON.parse(JSON.stringify(swapped))), new Map());
    expect(reopened[0].seat).toBe(14); expect(reopened[14].seat).toBe(0);
    const moved = assignOfficeGroup(swapped, [raw[0].sourceKey], 'a', reopened);
    const next = seatOfficeSources(employees(17), moved, memory);
    expect(next[14].seat).toBe(0); expect(next[0].officeGroup).toBe('a'); unique(next);
  });

  it('restores remembered membership while a new binding never inherits it', () => {
    const raw = employees(2); let org = team();
    org = assignOfficeGroup(org, [raw[0].sourceKey], 'a', seatOfficeSources(raw, org, new Map()));
    expect(seatOfficeSources([], org, new Map())).toEqual([]);
    expect(seatOfficeSources(raw, JSON.parse(JSON.stringify(org)), new Map())[0].officeGroup).toBe('a');
    const replaced = { ...raw[0], sourceKey: raw[0].sourceKey.replace('binding-0', 'new-binding') };
    expect(seatOfficeSources([replaced], org, new Map())[0].officeGroup).toBe('');
  });

  it('bounds dormant membership memory without requiring offline cleanup controls', () => {
    const raw = employees(1); let org = team();
    for (let index = 0; index < 256; index++) org.placements['retired/tab/binding-' + index] = { group: 'a', seat: index % 12 };
    org = assignOfficeGroup(org, [raw[0].sourceKey], 'a', seatOfficeSources(raw, org, new Map()));
    expect(Object.keys(org.placements)).toHaveLength(256);
    expect(org.placements[raw[0].sourceKey].group).toBe('a');
  });

  it('validates names and references while allowing remembered desk preferences to overlap', () => {
    const org = team();
    expect(() => putOfficeGroup(org, { id: 'c', name: 'TEAM A' })).toThrow('groupNameInvalid');
    for (const placements of [{ invalid: { group: 'a', seat: 0 } }, { 'ide/tab/old': { group: 'missing', seat: 0 } }, { 'ide/tab/old': { group: 'a', seat: 12 } }]) {
      expect(() => parseOfficeOrganization({ ...org, placements })).toThrow();
    }
    expect(parseOfficeOrganization({ ...org, placements: { 'ide/tab/old': { group: 'a', seat: 0 }, 'ide/tab/new': { group: 'a', seat: 0 } } }).placements).toHaveProperty('ide/tab/old');
    expect(putOfficeGroup(org, { id: 'c', name: '🐾'.repeat(24) }).groups.at(-1)?.name).toBe('🐾'.repeat(24));
  });

  it('locates all twelve occupied or vacant desks in each built-in room', () => {
    for (const theme of OFFICE_THEME_IDS) {
      OFFICE_ART_SEATS[theme].forEach((seat, slot) => expect(officeSeatAtPoint(seat.x, seat.label, theme)).toBe(slot));
      expect(officeSeatAtPoint(-1, 500, theme)).toBeNull(); expect(officeSeatAtPoint(500, -1, theme)).toBeNull();
    }
    expect(officeSeatAtPoint(194, 840, undefined, 1040)).toBe(8);
    expect(officeSeatAtPoint(194, 840, undefined, 760)).toBeNull();
  });
});
