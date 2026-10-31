import { editOfficeAppearanceOverride, parseOfficeAppearanceOverrides } from './officeAppearanceOverrides';
import { editOfficeEmployeeName, parseOfficeEmployeeNames } from './officeEmployeeNames';
import { officePreferenceBytes } from './officePreferenceBudget';
import type { OfficeSharedPreferences } from './officeSharedStorage';

/** Reclaim both maps together while preserving currently admitted employee bindings. */
export function editOfficeEmployeeMetadata(preferences: OfficeSharedPreferences, key: string, change: { name: string } | { petId: string }, live: ReadonlySet<string>) {
  const protectedKeys = live;
  let employeeNames = 'name' in change ? editOfficeEmployeeName(preferences.employeeNames, key, change.name, protectedKeys) : preferences.employeeNames;
  let appearanceOverrides = 'petId' in change ? editOfficeAppearanceOverride(preferences.appearanceOverrides, key, change.petId, protectedKeys) : preferences.appearanceOverrides;
  if (officePreferenceBytes(employeeNames) + officePreferenceBytes(appearanceOverrides) > 32002
    || officePreferenceBytes({ ...preferences, employeeNames, appearanceOverrides }) > 48000) {
    employeeNames = parseOfficeEmployeeNames(Object.fromEntries(Object.entries(employeeNames).filter(([key]) => protectedKeys.has(key))));
    appearanceOverrides = parseOfficeAppearanceOverrides(Object.fromEntries(Object.entries(appearanceOverrides).filter(([key]) => protectedKeys.has(key))));
  }
  if (officePreferenceBytes(employeeNames) + officePreferenceBytes(appearanceOverrides) > 32002) throw new Error('sharedPreferencesError');
  return { employeeNames, appearanceOverrides };
}
