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

export interface CodexDatabaseStatus {
  databaseId: string;
  name: string;
  sizes: Record<string, number>;
  supported: boolean;
  unavailableReason?: string;
  maintenancePending: boolean;
  engineVersion?: string;
  freelistCount?: number;
  pageSize?: number;
}

export interface CodexStorageMutationStatus {
  available: boolean;
  unavailableReason?: string;
  deletionAvailable: boolean;
  deletionUnavailableReason?: string;
  runningSessionIds: string[];
  writerSessionIds: string[];
}

export interface CodexMaintenanceOperation {
  operationId: string;
  kind: 'DELETE_SESSIONS' | 'COMPACT_DATABASE' | 'CLEAN_ORPHANED_HISTORY';
  state: string;
  targets: string[];
  createdAt: string;
  updatedAt: string;
  error?: string;
  result?: {
    deletedSessionIds?: string[];
    rolloutBytesRemoved?: number;
    logicalRowsRemovedThisAttempt?: number;
    cleanedSessionCount?: number;
    releasedBytes?: number;
    warning?: string;
    metadataStatus?: 'PENDING' | 'COMPLETE' | 'FAILED';
    before?: Record<string, number>;
    after?: Record<string, number>;
  };
}

export interface CodexOrphanPreview {
  databaseId: string;
  previewToken: string;
  candidateCount: number;
  rowCount: number;
  estimatedBytes: number;
  retainedFileSessions: number;
  skippedBusyCount: number;
  skippedExistingCount: number;
  candidates: Array<{ sessionId: string; title: string; estimatedBytes: number }>;
}

interface StorageData {
  get_ai_storage_database_status: CodexDatabaseStatus;
  get_ai_storage_mutation_status: CodexStorageMutationStatus;
  compact_ai_storage_database: CodexMaintenanceOperation;
  preview_ai_storage_orphans: CodexOrphanPreview;
  clean_ai_storage_orphans: CodexMaintenanceOperation;
  delete_ai_storage_sessions: CodexMaintenanceOperation;
  get_ai_storage_maintenance_operation: CodexMaintenanceOperation;
  list_ai_storage_maintenance_operations: CodexMaintenanceOperation[];
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
function operation(value: unknown): value is CodexMaintenanceOperation {
  return object(value) && typeof value.operationId === 'string' && typeof value.state === 'string'
    && ['DELETE_SESSIONS', 'COMPACT_DATABASE', 'CLEAN_ORPHANED_HISTORY'].includes(String(value.kind)) && strings(value.targets)
    && typeof value.createdAt === 'string' && typeof value.updatedAt === 'string'
    && (value.error === undefined || typeof value.error === 'string')
    && (value.result === undefined || (object(value.result)
      && (value.result.deletedSessionIds === undefined || strings(value.result.deletedSessionIds))
      && (value.result.releasedBytes === undefined || typeof value.result.releasedBytes === 'number')
      && (value.result.rolloutBytesRemoved === undefined || typeof value.result.rolloutBytesRemoved === 'number')
      && (value.result.logicalRowsRemovedThisAttempt === undefined || typeof value.result.logicalRowsRemovedThisAttempt === 'number')
      && (value.result.cleanedSessionCount === undefined || typeof value.result.cleanedSessionCount === 'number')
      && (value.result.metadataStatus === undefined || ['PENDING', 'COMPLETE', 'FAILED'].includes(String(value.result.metadataStatus)))
      && (value.result.warning === undefined || typeof value.result.warning === 'string')
      && (value.result.before === undefined || numberMap(value.result.before))
      && (value.result.after === undefined || numberMap(value.result.after))));
}
function validData(action: keyof StorageData, value: unknown): boolean {
  if (action === 'list_ai_storage_maintenance_operations') return Array.isArray(value) && value.every(operation);
  if (action === 'compact_ai_storage_database' || action === 'clean_ai_storage_orphans'
    || action === 'delete_ai_storage_sessions' || action === 'get_ai_storage_maintenance_operation') return operation(value);
  if (!object(value)) return false;
  switch (action) {
    case 'reveal_ai_storage_session': return typeof value.sessionId === 'string' && value.sessionId.length > 0;
    case 'get_ai_storage_mutation_status':
      return typeof value.available === 'boolean'
        && (value.available ? value.unavailableReason === undefined : typeof value.unavailableReason === 'string' && value.unavailableReason.length > 0)
        && typeof value.deletionAvailable === 'boolean'
        && (value.deletionAvailable ? value.deletionUnavailableReason === undefined
          : typeof value.deletionUnavailableReason === 'string' && value.deletionUnavailableReason.length > 0)
        && strings(value.runningSessionIds) && strings(value.writerSessionIds);
    case 'preview_ai_storage_orphans':
      return typeof value.databaseId === 'string' && typeof value.previewToken === 'string' && /^[a-f0-9]{64}$/.test(value.previewToken)
        && ['candidateCount', 'rowCount', 'estimatedBytes', 'retainedFileSessions', 'skippedBusyCount', 'skippedExistingCount']
          .every(key => { const count = value[key]; return typeof count === 'number' && Number.isFinite(count) && count >= 0; })
        && Array.isArray(value.candidates) && value.candidates.every(item => object(item) && typeof item.sessionId === 'string'
          && typeof item.title === 'string' && typeof item.estimatedBytes === 'number' && Number.isFinite(item.estimatedBytes));
    case 'get_ai_storage_usage':
      return typeof value.canonicalRoot === 'string' && typeof value.physicalRoot === 'string' && typeof value.scannedAt === 'string'
        && typeof value.logicalBytes === 'number' && numberMap(value.categories) && strings(value.skippedLinks) && strings(value.errors) && typeof value.complete === 'boolean';
    case 'get_ai_storage_database_status':
      return typeof value.databaseId === 'string' && typeof value.name === 'string' && typeof value.supported === 'boolean'
        && typeof value.maintenancePending === 'boolean' && numberMap(value.sizes)
        && (value.unavailableReason === undefined || typeof value.unavailableReason === 'string')
        && (value.freelistCount === undefined || typeof value.freelistCount === 'number')
        && (value.pageSize === undefined || typeof value.pageSize === 'number');
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
  else {
    request.resolve(value.data);
    if (operation(value.data)) publishOperation(value.data);
    else if (request.action === 'list_ai_storage_maintenance_operations' && Array.isArray(value.data)) {
      value.data.filter(operation).forEach(publishOperation);
    }
  }
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

export interface CodexDeletionAttempt {
  operationId: string;
  sessionIds: string[];
  origin: 'storage' | 'history';
}
const deletionKey = 'ccgui.codex.session-deletion.pending';
export const deletionPendingEvent = 'codex-deletion-pending';
const completedListeners = new Set<(value: CodexMaintenanceOperation) => void>();
const emittedDeletions = new Set<string>();
let deletionInFlight = false;

export function operationSucceeded(value: CodexMaintenanceOperation): boolean {
  return value.state === 'COMPLETED' || value.state === 'COMPLETED_WITH_WARNINGS';
}
export function pendingCodexDeletion(): CodexDeletionAttempt | null {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(deletionKey) ?? 'null');
    return object(value) && typeof value.operationId === 'string'
      && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value.operationId)
      && strings(value.sessionIds) && value.sessionIds.length > 0 && value.sessionIds.length <= 1000
      && value.sessionIds.every(id => /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id))
      && (value.origin === 'storage' || value.origin === 'history') ? value as unknown as CodexDeletionAttempt : null;
  } catch { return null; }
}
export function codexDeletionInFlight(): boolean { return deletionInFlight; }
function notifyDeletionPending() { window.dispatchEvent(new Event(deletionPendingEvent)); }
function publishOperation(value: CodexMaintenanceOperation) {
  if (pendingCodexDeletion()?.operationId === value.operationId && (
    ['FAILED_CLEAN', 'CANCELLED_CLEAN'].includes(value.state)
    || operationSucceeded(value) && value.result?.metadataStatus === 'COMPLETE')) {
    try { localStorage.removeItem(deletionKey); } catch { /* Retaining an identity is safe to retry. */ }
    notifyDeletionPending();
  }
  if (operationSucceeded(value) && value.result?.deletedSessionIds?.length && !emittedDeletions.has(value.operationId)) {
    emittedDeletions.add(value.operationId);
    if (emittedDeletions.size > 500) emittedDeletions.delete(emittedDeletions.values().next().value!);
    for (const listener of completedListeners) {
      try { listener(value); } catch { console.warn('[CodexStorage] Deletion observer failed'); }
    }
  }
}
function operationId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
async function dispatchDeletion(attempt: CodexDeletionAttempt): Promise<CodexMaintenanceOperation> {
  if (deletionInFlight) throw new CodexStorageError('STORAGE_OPERATION_PENDING');
  // Persist targets as well as identity: a retry must never adopt a newer selection.
  try { localStorage.setItem(deletionKey, JSON.stringify(attempt)); }
  catch { throw new CodexStorageError('STORAGE_RECOVERY_UNAVAILABLE'); }
  deletionInFlight = true;
  notifyDeletionPending();
  try { return await storageRequest('delete_ai_storage_sessions', { ...attempt }); }
  finally { deletionInFlight = false; notifyDeletionPending(); }
}
export function retryCodexDeletion(): Promise<CodexMaintenanceOperation> {
  const attempt = pendingCodexDeletion();
  return attempt ? dispatchDeletion(attempt) : Promise.reject(new CodexStorageError('STORAGE_OPERATION_NOT_FOUND'));
}
function deleteSessions(ids: string[], origin: CodexDeletionAttempt['origin']): Promise<CodexMaintenanceOperation> {
  if (pendingCodexDeletion()) return Promise.reject(new CodexStorageError('STORAGE_OPERATION_PENDING'));
  const sessionIds = Array.from(new Set(ids));
  if (sessionIds.length === 0 || sessionIds.length > 1000
    || sessionIds.some(id => !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id))) {
    return Promise.reject(new CodexStorageError('INVALID_SESSION_SELECTION'));
  }
  return dispatchDeletion({ operationId: operationId(), sessionIds, origin });
}
export function deleteCodexStorageSessions(ids: string[]): Promise<CodexMaintenanceOperation> {
  return deleteSessions(ids, 'storage');
}
export function deleteCodexHistorySessions(ids: string[]): Promise<CodexMaintenanceOperation> {
  return deleteSessions(ids, 'history');
}
export function subscribeCodexDeletion(listener: (value: CodexMaintenanceOperation) => void): () => void {
  completedListeners.add(listener);
  return () => { completedListeners.delete(listener); };
}
