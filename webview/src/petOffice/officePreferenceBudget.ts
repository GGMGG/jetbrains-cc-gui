/** Match the native Gson serializer when measuring persisted preference budgets. */
export function officePreferenceBytes(value: unknown): number {
  const json = JSON.stringify(value);
  if (json === undefined) throw new Error('sharedPreferencesError');
  const escaped = json.replace(/[<>&='\u2028\u2029]/g, character => '\\u' + character.charCodeAt(0).toString(16).padStart(4, '0'));
  return new TextEncoder().encode(escaped).length;
}
