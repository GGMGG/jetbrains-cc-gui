import { describe, expect, it } from 'vitest';
import { editOfficeEmployeeMetadata } from './officeEmployeeMetadata';
import { officePreferenceBytes } from './officePreferenceBudget';
import { parseOfficeSharedState } from './officeSharedStorage';
import type { OfficeSharedPreferences } from './officeSharedStorage';

const preferences = (): OfficeSharedPreferences => ({ officeName: '', limitMiB: 50, floors: Array(22).fill('loft'), floor: 0, product: 'all', search: '',
  showIdle: true, paused: false, topmost: true, zoom: 100, presentation: { effects: true, attention: true, ambience: true, depth: true },
  appearanceOverrides: {}, employeeNames: {}, organization: { groups: [], placements: {}, activeGroup: '' } });
const validate = (p: OfficeSharedPreferences) => parseOfficeSharedState({ schemaVersion: 1, revision: 'initial', preferences: p,
  bounds: { x: 0, y: 0, width: 1240, height: 900, placed: false } });
const key = (prefix: string, index: number) => prefix.repeat(36) + '/' + 't'.repeat(36) + '/' + String(index).padStart(36, '0');

function nearQuota(): OfficeSharedPreferences {
  const p = preferences();
  for (let index = 0; index < 130; index++) p.employeeNames[key('i', index)] = 'R'.repeat(24);
  for (let index = 0; index < 101; index++) p.appearanceOverrides[key('p', index)] = 'duck/spritesheet.png';
  p.appearanceOverrides[key('p', 0)] += 'a'.repeat(32000 - officePreferenceBytes(p.employeeNames) - officePreferenceBytes(p.appearanceOverrides));
  validate(p); return p;
}

describe('Shared employee metadata reclamation', () => {
  it.each(['name', 'pet'] as const)('preserves an offline group member when the %s map reaches its record limit', kind => {
    const p = preferences(), reserved = 'offline/tab/member', active = 'live/tab/member';
    p.organization = { groups: [{ id: 'a', name: 'Team A' }], placements: { [reserved]: { group: 'a', seat: 0 } }, activeGroup: '' };
    p.employeeNames[reserved] = 'Remembered'; p.appearanceOverrides[reserved] = 'custom/pet';
    for (let index = 0; index < 255; index++) {
      if (kind === 'name') p.employeeNames['retired/tab/member-' + index] = 'Retired';
      else p.appearanceOverrides['retired/tab/member-' + index] = 'builtin';
    }
    validate(p);
    const next = editOfficeEmployeeMetadata(p, active, kind === 'name' ? { name: 'New employee' } : { petId: 'builtin' }, new Set([active]));
    expect(next.employeeNames[reserved]).toBe('Remembered'); expect(next.appearanceOverrides[reserved]).toBe('custom/pet');
    expect(kind === 'name' ? next.employeeNames[active] : next.appearanceOverrides[active]).toBe(kind === 'name' ? 'New employee' : 'builtin');
    validate({ ...p, ...next });
  });

  it.each(['name', 'pet'] as const)('reclaims both retired maps for a %s edit at the aggregate byte limit', kind => {
    const p = nearQuota(), active = 'live/tab/member';
    const next = editOfficeEmployeeMetadata(p, active, kind === 'name' ? { name: 'N'.repeat(24) } : { petId: 'builtin' }, new Set([active]));
    expect(Object.keys(next.employeeNames)).toEqual(kind === 'name' ? [active] : []);
    expect(Object.keys(next.appearanceOverrides)).toEqual(kind === 'pet' ? [active] : []);
    validate({ ...p, ...next });
  });

  it('matches Gson HTML escaping so native-rejected names fail the frontend preflight too', () => {
    const p = preferences();
    for (let index = 0; index < 140; index++) p.employeeNames[key('i', index)] = '&'.repeat(24);
    expect(new TextEncoder().encode(JSON.stringify(p.employeeNames)).length).toBe(19601);
    expect(officePreferenceBytes(p.employeeNames)).toBe(36401);
    expect(() => validate(p)).toThrow('sharedPreferencesError');
    expect(officePreferenceBytes("&<>='\u2028\u2029")).toBe(44);
  });
});
