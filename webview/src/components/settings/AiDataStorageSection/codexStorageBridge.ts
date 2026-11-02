import { sendToJava } from '../../../utils/bridge';

export interface CodexStorageSession {
  sessionId: string;
  title: string;
  cwd: string;
  storageMode: string;
  fileSize: number;
  lastTimestamp: number;
  firstTimestamp: number;
  archived: boolean;
  running: boolean;
  readable: boolean;
  deletionUnavailableReason?: string;
  metadataWarning?: string;
  subagent?: boolean;
  parentId?: string;
}

export interface CodexStorageUsage {
  canonicalRoot: string;
  physicalRoot: string;
  scannedAt: string;
  logicalBytes: number;
  categories: Record<string, number>;
  skippedLinks: string[];
  errors: string[];
  complete: boolean;
}

export interface CodexStoragePage {
  sessions: CodexStorageSession[];
  total: number;
  page: number;
  pageSize: number;
  hasMore: boolean;
  nextCursor?: string;
  complete: boolean;
  errors: string[];
}

export interface CodexStorageContent {
  sessionId: string;
  records: Array<{ text: string; truncated: boolean }>;
  hasMore: boolean;
  nextCursor?: string;
}

interface StorageData {
  get_ai_storage_usage: CodexStorageUsage;
  list_ai_storage_sessions: CodexStoragePage;
  get_ai_storage_session: CodexStorageContent;
  reveal_ai_storage_session: { sessionId: string };
  cancel_ai_storage_scan: Record<string, never>;
}
export class CodexStorageError extends Error {
  readonly code: string;
  constructor(code: string) { super(code); this.code = code; }
}
declare global {
  interface Window { onCodexStorageResponse?: (json: string) => void; }
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string');
}
function numberMap(value: unknown): value is Record<string, number> {
  return object(value) && Object.values(value).every(item => typeof item === 'number' && Number.isFinite(item));
}
function validData(action: keyof StorageData, value: unknown): boolean {
  if (!object(value)) return false;
  switch (action) {
    case 'reveal_ai_storage_session': return typeof value.sessionId === 'string' && value.sessionId.length > 0;
    case 'get_ai_storage_usage':
      return typeof value.canonicalRoot === 'string' && typeof value.physicalRoot === 'string' && typeof value.scannedAt === 'string'
        && typeof value.logicalBytes === 'number' && numberMap(value.categories) && strings(value.skippedLinks) && strings(value.errors) && typeof value.complete === 'boolean';
    case 'list_ai_storage_sessions':
      return Array.isArray(value.sessions) && value.sessions.every(item => object(item) && typeof item.sessionId === 'string'
        && typeof item.title === 'string' && typeof item.cwd === 'string' && typeof item.storageMode === 'string'
        && typeof item.running === 'boolean' && typeof item.archived === 'boolean' && typeof item.readable === 'boolean'
        && typeof item.fileSize === 'number' && typeof item.lastTimestamp === 'number' && typeof item.firstTimestamp === 'number'
        && (item.deletionUnavailableReason === undefined || typeof item.deletionUnavailableReason === 'string')
        && (item.subagent === undefined || typeof item.subagent === 'boolean')
        && (item.parentId === undefined || typeof item.parentId === 'string' && item.parentId.trim().length > 0)
)
        && typeof value.total === 'number' && Number.isInteger(value.total) && value.total >= 0
        && typeof value.page === 'number' && Number.isInteger(value.page) && value.page >= 1
        && typeof value.pageSize === 'number' && Number.isInteger(value.pageSize) && value.pageSize >= 1 && value.pageSize <= 100
        && typeof value.hasMore === 'boolean' && typeof value.complete === 'boolean'
        && strings(value.errors) && (!value.hasMore || typeof value.nextCursor === 'string');
    case 'get_ai_storage_session':
      return typeof value.sessionId === 'string' && Array.isArray(value.records)
        && value.records.every(item => object(item) && typeof item.text === 'string' && typeof item.truncated === 'boolean')
        && typeof value.hasMore === 'boolean' && (!value.hasMore || typeof value.nextCursor === 'string');
    case 'cancel_ai_storage_scan': return true;
  }
}
const pending = new Map<string, {
  action: keyof StorageData;
  resolve: (value: unknown) => void;
  reject: (error: CodexStorageError) => void;
}>();
export function receiveCodexStorageResponse(json: string): void {
  let value: unknown;
  try { value = JSON.parse(json); } catch { return; }
  if (!object(value) || typeof value.requestId !== 'string' || typeof value.success !== 'boolean') return;
  const request = pending.get(value.requestId);
  if (!request || request.action !== value.action) return;
  if (!value.success) request.reject(new CodexStorageError(typeof value.error === 'string' ? value.error : 'STORAGE_OPERATION_FAILED'));
  else if (!validData(request.action, value.data)) request.reject(new CodexStorageError('STORAGE_RESPONSE_INVALID'));
  else request.resolve(value.data);
}
if (typeof window !== 'undefined') window.onCodexStorageResponse = receiveCodexStorageResponse;
let nextRequest = 0;
export function storageRequest<K extends keyof StorageData>(action: K, fields: Record<string, unknown> = {}, signal?: AbortSignal): Promise<StorageData[K]> {
  if (signal?.aborted) return Promise.reject(new CodexStorageError('SCAN_CANCELLED'));
  const requestId = `codex-storage-${Date.now()}-${++nextRequest}`;
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      pending.delete(requestId);
      clearTimeout(timeout);
      signal?.removeEventListener('abort', abort);
    };
    const abort = () => {
      cleanup();
      if (action === 'get_ai_storage_usage') sendToJava('cancel_ai_storage_scan', { provider: 'codex', requestId: `${requestId}-cancel`, scanId: requestId });
      reject(new CodexStorageError('SCAN_CANCELLED'));
    };
    const timeout = setTimeout(() => {
      cleanup();
      reject(new CodexStorageError('STORAGE_RESPONSE_TIMEOUT'));
    }, 120_000);
    pending.set(requestId, {
      action,
      resolve: value => { cleanup(); resolve(value as StorageData[K]); },
      reject: error => { cleanup(); reject(error); },
    });
    signal?.addEventListener('abort', abort, { once: true });
    if (sendToJava(action, { ...fields, requestId, provider: 'codex' }) === false) {
      cleanup();
      reject(new CodexStorageError('STORAGE_BRIDGE_UNAVAILABLE'));
    }
  });
}
