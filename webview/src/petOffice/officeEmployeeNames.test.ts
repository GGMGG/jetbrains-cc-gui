import { describe, expect, it } from 'vitest';
import { editOfficeEmployeeName, parseOfficeEmployeeName, parseOfficeEmployeeNames } from './officeEmployeeNames';
import { filterOfficeSources } from './officeModel';

describe('Office employee identity and display names', () => {
  it('supports Chinese, literal markup and full Unicode characters without mixing bindings', () => {
    const values = { 'ide/tab/old': '夜幕架构师 🐾', 'ide/tab/new': '<研发 & QA>', 'other/tab/old': '星际指挥官' };
    expect(parseOfficeEmployeeNames(values)).toEqual(values);
    expect(parseOfficeEmployeeName('🐾'.repeat(24))).toBe('🐾'.repeat(24));
    const changed = editOfficeEmployeeName(values, 'ide/tab/new', '  代码之神  ', new Set(Object.keys(values)));
    expect(changed).toEqual({ ...values, 'ide/tab/new': '代码之神' });
    expect(editOfficeEmployeeName(changed, 'ide/tab/new', '  ', new Set(Object.keys(values))))
      .toEqual({ 'ide/tab/old': values['ide/tab/old'], 'other/tab/old': values['other/tab/old'] });
    expect(values['ide/tab/new']).toBe('<研发 & QA>');
  });

  it('rejects malformed identities, invisible controls, oversized maps and broken Unicode', () => {
    for (const name of ['x'.repeat(25), '🐾'.repeat(25), 'line\nbreak', 'control\u0085', 'right\u202etoLeft', '\u2028', '\ud800']) {
      expect(() => parseOfficeEmployeeName(name)).toThrow();
    }
    for (const value of [null, [], { 'ide/tab': 'Name' }, { 'ide/tab/..': 'Name' }, { 'ide/tab/key': 1 },
      { 'ide/tab/key': '' }, { 'ide/tab/key': ' \u00a0 ' },
      Object.fromEntries(Array.from({ length: 257 }, (_, i) => [`ide/tab/b${i}`, 'Name'])),
      Object.fromEntries(Array.from({ length: 200 }, (_, i) => [`${'a'.repeat(128)}/tab/b${i}`, '名'.repeat(24)]))]) {
      expect(() => parseOfficeEmployeeNames(value)).toThrow();
    }
  });

  it('reclaims retired names only when capacity is reached', () => {
    const values = Object.fromEntries(Array.from({ length: 256 }, (_, i) => [`ide/tab/b${i}`, `Employee ${i}`]));
    expect(editOfficeEmployeeName(values, 'ide/new/binding', 'New employee', new Set(['ide/tab/b1', 'ide/new/binding'])))
      .toEqual({ 'ide/tab/b1': 'Employee 1', 'ide/new/binding': 'New employee' });
    expect(Object.keys(values)).toHaveLength(256);
  });

  it('finds employees by custom name while preserving the original project and provider filters', () => {
    const source = { sourceKey: 'ide/tab/binding', projectLabel: 'Project', tabLabel: 'AI 1', product: 'IDEA', provider: 'Codex', phase: 'RUNNING' as const, seat: 0 };
    const names = { [source.sourceKey]: '星际指挥官' };
    expect(filterOfficeSources([source], 'all', '指挥官', names)).toEqual([source]);
    expect(filterOfficeSources([source], 'PyCharm', '指挥官', names)).toEqual([]);
    expect(filterOfficeSources([source], 'all', 'Codex', names)).toEqual([source]);
    expect(source.tabLabel).toBe('AI 1');
  });
});
