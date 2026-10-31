import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parseLocalOfficeSnapshot } from './officeLocalModel';
import { activateLocalOfficeSource, readLocalOffice, receiveLocalOfficeResponse, updateOfficeWindowPreferences, exitLocalOffice } from './officeLocalBridge';

const scene = () => ({ schemaVersion: 1, instanceId: 'instance', connectionEpoch: 'epoch', watermark: 1, sampledAt: 1000, capacityReached: false,
  sources: [{ sourceKey: { instanceId: 'instance', contentHandleId: 'tab', bindingEpoch: 'binding' }, routeGeneration: 'route', routeStatus: 'AVAILABLE',
    metadata: { projectId: 'project', projectLabel: 'Project', tabLabel: 'Chat' }, provider: 'codex', modelLabel: 'model',
    execution: { phase: 'WAITING_USER', waitReason: 'QUESTION', pendingInteractionId: 'question', pendingInteractionCount: 1 } }] });
let messages: Array<{ requestId: string; pageToken: string; action: string; payload: Record<string, unknown> }>;
function reply(index = 0, data: unknown = scene(), overrides: Record<string, unknown> = {}) {
  receiveLocalOfficeResponse(JSON.stringify({ ...messages[index], success: true, data, ...overrides }));
}
beforeEach(() => {
  vi.useFakeTimers();
  messages = [];
  window.__PET_OFFICE_PAGE__ = 'page';
  window.__PET_OFFICE_SEND__ = json => { messages.push(JSON.parse(json)); return true; };
});
afterEach(() => { vi.useRealTimers(); delete window.__PET_OFFICE_SEND__; delete window.__PET_OFFICE_PAGE__; });

describe('the local Office boundary', () => {
  it('accepts listed peer owners and keeps project filters distinct across IDEs', () => {
    const value = scene();
    const peer = { ...value.sources[0], sourceKey: { ...value.sources[0].sourceKey, instanceId: 'peer' } };
    value.sources.push(peer);
    const result = parseLocalOfficeSnapshot({ ...value, participants: ['instance', 'peer'] });
    expect(result.sources.map(source => source.sourceKey)).toEqual(['instance/tab/binding', 'peer/tab/binding']);
    expect(new Set(result.sources.map(source => source.projectId)).size).toBe(2);
    expect(() => parseLocalOfficeSnapshot({ ...value, participants: ['instance'] })).toThrow();
    expect(() => parseLocalOfficeSnapshot({ ...value, participants: ['instance', 'instance'] })).toThrow();
  });

  it('requires an exit acknowledgement and preserves the page on refusal', async () => {
    const exiting = exitLocalOffice();
    expect(messages[0].action).toBe('exit'); expect(messages[0].payload).toEqual({});
    reply(0, { result: 'ACTIVATED' });
    await expect(exiting).rejects.toThrow('OFFICE_RESPONSE_INVALID');
    const retry = exitLocalOffice(); reply(1, { closing: true });
    await expect(retry).resolves.toEqual({ closing: true });
  });
  it('correlates a settings commit and rejects a malformed matching acknowledgement', async () => {
    const patch = { expectedRevision: 'initial', officeName: 'Office', topmost: false, zoom: 125 };
    const saved = updateOfficeWindowPreferences(patch);
    expect(messages[0].payload).toEqual(patch);
    reply(0, { revision: 'saved', generation: 1, officeName: 'Office', topmost: false, zoom: 125, recovered: false });
    await expect(saved).resolves.toMatchObject({ revision: 'saved', zoom: 125 });
    const invalid = updateOfficeWindowPreferences(patch);
    reply(1, { revision: 'saved' });
    await expect(invalid).rejects.toThrow('OFFICE_RESPONSE_INVALID');
  });
  it('renders every backend activity and distinguishes permission and plan waits', () => {
    for (const phase of ['QUEUED', 'THINKING', 'RUNNING']) {
      const value = scene();
      Object.assign(value.sources[0].execution, { phase, waitReason: 'NONE', pendingInteractionId: '', pendingInteractionCount: 0 });
      expect(parseLocalOfficeSnapshot(value).sources[0].phase).toBe(phase);
    }
    for (const [reason, phase] of [['PERMISSION', 'WAITING_PERMISSION'], ['PLAN', 'WAITING_PLAN'], ['QUESTION', 'WAITING_USER']]) {
      const value = scene(); value.sources[0].execution.waitReason = reason;
      expect(parseLocalOfficeSnapshot(value).sources[0].phase).toBe(phase);
    }
    const invalid = scene(); invalid.sources[0].execution.pendingInteractionCount = 0;
    expect(() => parseLocalOfficeSnapshot(invalid)).toThrow('OFFICE_RESPONSE_INVALID');
  });
  it('retains source, route and interaction identity without selecting by row index', async () => {
    const source = parseLocalOfficeSnapshot(scene()).sources[0];
    const result = activateLocalOfficeSource(source);
    expect(messages[0].payload).toEqual({ sourceKey: 'instance/tab/binding', routeGeneration: 'route', interactionId: 'question' });
    reply(0, { result: 'STALE_INTERACTION' });
    await expect(result).resolves.toEqual({ result: 'STALE_INTERACTION' });
  });

  it('ignores an old page and wrong action before accepting its matching reply', async () => {
    const result = readLocalOffice();
    reply(0, scene(), { pageToken: 'old-page' });
    reply(0, scene(), { action: 'activate' });
    expect(vi.getTimerCount()).toBe(1);
    reply();
    await expect(result).resolves.toMatchObject({ watermark: 1 });
    expect(vi.getTimerCount()).toBe(0);
  });

  it('fails a malformed matching reply immediately and rejects explicit transport refusal', async () => {
    const result = readLocalOffice();
    reply(0, { sources: [] });
    await expect(result).rejects.toThrow('OFFICE_RESPONSE_INVALID');
    window.__PET_OFFICE_SEND__ = () => false;
    await expect(readLocalOffice()).rejects.toThrow('OFFICE_BRIDGE_UNAVAILABLE');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('expires lost replies and aborts closed pages without accepting a late reply', async () => {
    const lost = readLocalOffice();
    const rejected = expect(lost).rejects.toThrow('OFFICE_RESPONSE_TIMEOUT');
    await vi.advanceTimersByTimeAsync(5000);
    await rejected;
    const controller = new AbortController();
    const closed = readLocalOffice(controller.signal);
    controller.abort();
    reply(1);
    await expect(closed).rejects.toThrow('OFFICE_CANCELLED');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('rejects duplicate sources, a foreign instance and timestamps outside the browser range', () => {
    const duplicate = scene();
    duplicate.sources.push(duplicate.sources[0]);
    expect(() => parseLocalOfficeSnapshot(duplicate)).toThrow('OFFICE_RESPONSE_INVALID');
    const foreign = scene(); foreign.sources[0].sourceKey.instanceId = 'foreign';
    expect(() => parseLocalOfficeSnapshot(foreign)).toThrow();
    expect(() => parseLocalOfficeSnapshot({ ...scene(), sampledAt: Number.MAX_SAFE_INTEGER })).toThrow();
  });
});
