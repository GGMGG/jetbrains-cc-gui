import { object, text } from './officeLiveModel';
import { officePreferenceBytes } from './officePreferenceBudget';

export const OFFICE_EMPLOYEE_NAME_MAX_LENGTH = 24;
export type OfficeEmployeeNames = Record<string, string>;
const IDENTITY = /^[A-Za-z0-9._-]{1,128}$/;

export function parseOfficeEmployeeName(value: unknown): string {
  const name = text(value, OFFICE_EMPLOYEE_NAME_MAX_LENGTH);
  if (/[\u0080-\u009f\u2028-\u202e\u2066-\u2069\ud800-\udfff]/u.test(name)) throw new Error('employeeNameInvalid');
  return name;
}

/** Bind names to the complete source identity, never to a reusable seat or tab index. */
export function parseOfficeEmployeeNames(raw: unknown): OfficeEmployeeNames {
  const entries = Object.entries(object(raw));
  if (entries.length > 256 || officePreferenceBytes(raw) > 32000) throw new Error('sharedPreferencesError');
  for (const [key, value] of entries) {
    const parts = key.split('/');
    if (parts.length !== 3 || parts.some(part => !IDENTITY.test(part) || part === '.' || part === '..')
      || !parseOfficeEmployeeName(value).trim()) throw new Error('sharedPreferencesError');
  }
  return Object.fromEntries(entries.sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)) as OfficeEmployeeNames;
}

/** Reclaim retired names only at capacity; active employees retain their independent names. */
export function editOfficeEmployeeName(values: OfficeEmployeeNames, key: string, name: string, admitted: ReadonlySet<string>): OfficeEmployeeNames {
  const next = { ...values }, normalized = parseOfficeEmployeeName(name.trim());
  if (normalized) next[key] = normalized; else delete next[key];
  try { return parseOfficeEmployeeNames(next); }
  catch { return parseOfficeEmployeeNames(Object.fromEntries(Object.entries(next).filter(([source]) => admitted.has(source)))); }
}
