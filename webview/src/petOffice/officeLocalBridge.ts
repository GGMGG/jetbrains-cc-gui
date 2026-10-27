import { parseLocalOfficeSnapshot, parseOfficeWindowSettings, type OfficeWindowSettings, type LocalOfficeSnapshot, type LocalOfficeSource } from './officeLocalModel';

export const officeNavigationResults = ['ACTIVATED', 'ATTENTION_REQUESTED', 'TARGET_GONE', 'STALE_INTERACTION', 'UNAVAILABLE'] as const;
export type OfficeNavigationResult = typeof officeNavigationResults[number];
interface Replies { scene: LocalOfficeSnapshot; activate: { result: OfficeNavigationResult }; preferences: OfficeWindowSettings; exit: { closing: true }; }
declare global {
  interface Window {
    __PET_OFFICE_PAGE__?: string;
    __PET_OFFICE_LANGUAGE__?: string;
    __PET_OFFICE_SEND__?: (request: string) => boolean | void;
    onPetOfficeResponse?: (response: string) => void;
  }
}
const pending = new Map<string, { action: keyof Replies; accept: (value: unknown) => void; reject: (error: Error) => void }>();
const nonce = Array.from(crypto.getRandomValues(new Uint32Array(2)), value => value.toString(16)).join('-');
let sequence = 0;
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
export function receiveLocalOfficeResponse(json: string) {
  let value: unknown;
  try { value = JSON.parse(json); } catch { return; }
  if (!object(value) || value.pageToken !== window.__PET_OFFICE_PAGE__ || typeof value.requestId !== 'string') return;
  const request = pending.get(value.requestId);
  if (!request || value.action !== request.action) return;
  if (value.success !== true) request.reject(new Error(typeof value.error === 'string' ? value.error : 'OFFICE_RESPONSE_INVALID'));
  else request.accept(value.data);
}
window.onPetOfficeResponse = receiveLocalOfficeResponse;

function request<K extends keyof Replies>(action: K, payload: Record<string, unknown>, signal?: AbortSignal): Promise<Replies[K]> {
  if (signal?.aborted) return Promise.reject(new Error('OFFICE_CANCELLED'));
  if (!window.__PET_OFFICE_PAGE__ || !window.__PET_OFFICE_SEND__) return Promise.reject(new Error('OFFICE_BRIDGE_UNAVAILABLE'));
  const requestId = `office-${nonce}-${++sequence}`;
  return new Promise((resolve, reject) => {
    const cleanup = () => { pending.delete(requestId); clearTimeout(timeout); signal?.removeEventListener('abort', abort); };
    const abort = () => { cleanup(); reject(new Error('OFFICE_CANCELLED')); };
    const timeout = setTimeout(() => { cleanup(); reject(new Error('OFFICE_RESPONSE_TIMEOUT')); }, 5_000);
    pending.set(requestId, {
      action, reject: error => { cleanup(); reject(error); },
      accept: value => {
        cleanup();
        try {
          if (action === 'scene') resolve(parseLocalOfficeSnapshot(value) as Replies[K]);
          else if (action === 'preferences') resolve(parseOfficeWindowSettings(value) as Replies[K]);
          else if (action === 'exit' && object(value) && value.closing === true) resolve(value as Replies[K]);
          else if (action === 'activate' && object(value) && officeNavigationResults.includes(value.result as OfficeNavigationResult)) resolve(value as Replies[K]);
          else reject(new Error('OFFICE_RESPONSE_INVALID'));
        } catch { reject(new Error('OFFICE_RESPONSE_INVALID')); }
      },
    });
    signal?.addEventListener('abort', abort, { once: true });
    try {
      if (window.__PET_OFFICE_SEND__!(JSON.stringify({ requestId, action, pageToken: window.__PET_OFFICE_PAGE__, payload })) === false) {
        cleanup(); reject(new Error('OFFICE_BRIDGE_UNAVAILABLE'));
      }
    } catch { cleanup(); reject(new Error('OFFICE_BRIDGE_UNAVAILABLE')); }
  });
}
export function readLocalOffice(signal?: AbortSignal) { return request('scene', {}, signal); }
export function exitLocalOffice(signal?: AbortSignal) { return request('exit', {}, signal); }
export function activateLocalOfficeSource(source: LocalOfficeSource, signal?: AbortSignal) {
  return request('activate', { sourceKey: source.sourceKey, routeGeneration: source.routeGeneration, interactionId: source.interactionId }, signal);
}
export function updateOfficeWindowPreferences(patch: { expectedRevision: string; officeName: string; topmost: boolean; zoom: number }, signal?: AbortSignal) {
  return request('preferences', { ...patch }, signal);
}
