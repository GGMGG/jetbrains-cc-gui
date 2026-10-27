const backendPhases = ['IDLE', 'QUEUED', 'RUNNING', 'THINKING', 'WAITING_USER', 'STOPPING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'] as const;
export const officePhases = [...backendPhases, 'WAITING_PERMISSION', 'WAITING_PLAN'] as const;
export type OfficePhase = typeof officePhases[number];
export interface LocalOfficeSource {
  sourceKey: string;
  routeGeneration: string;
  routeStatus: 'AVAILABLE' | 'DETACHED' | 'CLOSED';
  projectId: string;
  projectLabel: string;
  tabLabel: string;
  provider: string;
  modelLabel: string;
  phase: OfficePhase;
  interactionId: string;
}
export interface LocalOfficeSnapshot {
  watermark: number;
  sampledAt: number;
  capacityReached: boolean;
  sources: LocalOfficeSource[];
  settings?: OfficeWindowSettings;
}
export interface OfficeWindowSettings {
  revision: string;
  generation: number;
  officeName: string;
  topmost: boolean;
  zoom: number;
  recovered: boolean;
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function text(value: unknown, limit = 160): value is string { return typeof value === 'string' && Array.from(value).length <= limit; }
function identifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9._-]{1,128}$/.test(value) && value !== '.' && value !== '..';
}
function integer(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0; }
function invalid(): never { throw new Error('OFFICE_RESPONSE_INVALID'); }

export function parseOfficeWindowSettings(value: unknown): OfficeWindowSettings {
  if (!object(value) || !identifier(value.revision) || !integer(value.generation) || !text(value.officeName, 64)
    || /[\u0000-\u001f\u007f-\u009f]/.test(value.officeName) || typeof value.topmost !== 'boolean'
    || !integer(value.zoom) || value.zoom < 75 || value.zoom > 150 || typeof value.recovered !== 'boolean') return invalid();
  return { revision: value.revision, generation: value.generation, officeName: value.officeName, topmost: value.topmost, zoom: value.zoom, recovered: value.recovered };
}

/** Only display metadata and opaque navigation authority enter the standalone page. */
export function parseLocalOfficeSnapshot(value: unknown): LocalOfficeSnapshot {
  if (!object(value) || value.schemaVersion !== 1 || !identifier(value.instanceId) || !identifier(value.connectionEpoch)
    || !integer(value.watermark) || !integer(value.sampledAt) || value.sampledAt > 8_640_000_000_000_000 || typeof value.capacityReached !== 'boolean'
    || !Array.isArray(value.sources) || value.sources.length > 256) return invalid();
  const identities = new Set<string>();
  const owners: unknown = value.participants ?? [value.instanceId];
  if (!Array.isArray(owners) || owners.length === 0 || owners.length > 16 || !owners.every(identifier)
    || new Set(owners).size !== owners.length || !owners.includes(value.instanceId)) return invalid();
  const qualifiedProjects = owners.length > 1;
  const sources = value.sources.map((item): LocalOfficeSource => {
    if (!object(item) || !object(item.sourceKey) || !object(item.metadata) || !object(item.execution)
      || !identifier(item.sourceKey.instanceId) || !owners.includes(item.sourceKey.instanceId) || !identifier(item.sourceKey.contentHandleId)
      || !identifier(item.sourceKey.bindingEpoch) || !identifier(item.routeGeneration)
      || !['AVAILABLE', 'DETACHED', 'CLOSED'].includes(String(item.routeStatus))
      || !identifier(item.metadata.projectId) || !text(item.metadata.projectLabel) || !text(item.metadata.tabLabel)
      || !text(item.provider) || !text(item.modelLabel) || !backendPhases.includes(item.execution.phase as typeof backendPhases[number])
      || !['NONE', 'QUESTION', 'PERMISSION', 'PLAN'].includes(String(item.execution.waitReason))
      || !(item.execution.pendingInteractionId === '' || identifier(item.execution.pendingInteractionId))
      || !integer(item.execution.pendingInteractionCount) || item.execution.pendingInteractionCount > 16) return invalid();
    const waiting = item.execution.phase === 'WAITING_USER';
    if (waiting ? item.execution.waitReason === 'NONE' || item.execution.pendingInteractionId === '' || item.execution.pendingInteractionCount === 0
      : item.execution.waitReason !== 'NONE' || item.execution.pendingInteractionId !== '' || item.execution.pendingInteractionCount !== 0) return invalid();
    const phase: OfficePhase = waiting && item.execution.waitReason === 'PERMISSION' ? 'WAITING_PERMISSION'
      : waiting && item.execution.waitReason === 'PLAN' ? 'WAITING_PLAN' : item.execution.phase as OfficePhase;
    const sourceKey = `${item.sourceKey.instanceId}/${item.sourceKey.contentHandleId}/${item.sourceKey.bindingEpoch}`;
    if (identities.has(sourceKey)) return invalid();
    identities.add(sourceKey);
    return { sourceKey, routeGeneration: item.routeGeneration, routeStatus: item.routeStatus as LocalOfficeSource['routeStatus'],
      projectId: qualifiedProjects ? `${item.sourceKey.instanceId}:${item.metadata.projectId}` : item.metadata.projectId,
      projectLabel: item.metadata.projectLabel, tabLabel: item.metadata.tabLabel,
      provider: item.provider, modelLabel: item.modelLabel, phase,
      interactionId: item.execution.pendingInteractionId };
  });
  return { watermark: value.watermark, sampledAt: value.sampledAt, capacityReached: value.capacityReached, sources,
    settings: value.settings === undefined ? undefined : parseOfficeWindowSettings(value.settings) };
}
