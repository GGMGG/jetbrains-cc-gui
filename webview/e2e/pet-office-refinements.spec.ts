import { expect, test } from '@playwright/test';
import type { BrowserContext, Page } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import { liveSceneFixture } from '../src/petOffice/officeLiveFixtures';
import { OFFICE_ART_SEATS } from '../src/petOffice/officeArtGeometry';
import type { OfficeSharedState } from '../src/petOffice/officeSharedStorage';

declare global { interface Window {
  officeRefinementHost?: (message: string) => Promise<Record<string, unknown>[]>;
  officeTransitionPeak?: number;
  officePaintedEmployeeNames?: Set<string>;
} }
type Fixture = ReturnType<typeof liveSceneFixture>;
const sequences = new WeakMap<Page, number>();
const hash = (bytes: Buffer | string) => createHash('sha256').update(bytes).digest('hex');
function defaults(): OfficeSharedState {
  return { schemaVersion: 1, revision: 'initial', preferences: { officeName: '', limitMiB: 50, floors: Array.from({ length: 22 }, () => 'loft'), floor: 0,
    product: 'all', search: '', showIdle: false, paused: false, topmost: true, zoom: 100,
    presentation: { effects: true, attention: true, ambience: true, depth: true }, appearanceOverrides: {}, employeeNames: {}, organization: { groups: [], placements: {}, activeGroup: '' } },
  bounds: { x: 0, y: 0, width: 1240, height: 900, placed: false } };
}
function packets(pageGeneration: string, type: 'SCENE' | 'ASSET', bytes: Buffer, sequence: number, fields = {}) {
  const envelope = { schemaVersion: 1, pageGeneration, transferId: randomUUID(), sequence };
  return [{ ...envelope, type: `${type}_BEGIN`, byteLength: bytes.length, chunkCount: Math.ceil(bytes.length / 32768), digest: hash(bytes), ...fields },
    ...Array.from({ length: Math.ceil(bytes.length / 32768) }, (_, index) => ({ ...envelope, type: `${type}_CHUNK`, index, data: bytes.subarray(index * 32768, (index + 1) * 32768).toString('base64') })),
    { ...envelope, type: `${type}_COMMIT` }];
}
async function publish(page: Page, data: Fixture) {
  const sequence = (sequences.get(page) ?? 0) + 1; sequences.set(page, sequence);
  const generation = await page.evaluate(() => window.__PET_OFFICE_BOOTSTRAP__!.pageGeneration);
  await page.evaluate(values => values.forEach(value => window.petOfficeReceive?.(value)), packets(generation, 'SCENE', Buffer.from(JSON.stringify(data)), sequence));
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().sourceCount)).toBe(data.snapshot.sources.length);
}
async function open(context: BrowserContext, initial?: OfficeSharedState, language = 'en') {
  const page = await context.newPage();
  await page.addInitScript(({ generation, initial, language }) => {
    localStorage.setItem('language', language);
    window.__PET_OFFICE_BOOTSTRAP__ = { mode: 'live', protocolVersion: 1, pageGeneration: generation, language, sharedPreferences: initial };
    window.petOfficeNativeBridge = message => {
      const value = JSON.parse(message) as { command: string };
      if (value.command === 'ready') window.setTimeout(() => window.petOfficeSetVisible?.(true), 0);
      else void window.officeRefinementHost?.(message).then(values => values.forEach(packet => {
        if (String(packet.type).startsWith('SCENE_') || String(packet.type).startsWith('ASSET_')) window.petOfficeReceive?.(packet);
        else window.petOfficeStorageResult?.(packet);
      }));
    };
  }, { generation: randomUUID(), initial, language });
  await page.goto('/office.html'); return page;
}
async function hoverDesk(page: Page, seat: number) {
  const canvas = page.locator('.office-scene-surface > canvas'); await canvas.scrollIntoViewIfNeeded();
  const d = await page.evaluate(() => window.petOfficeDiagnostics!());
  const bounds = (await canvas.boundingBox())!, geometry = OFFICE_ART_SEATS.loft[seat];
  const x = (geometry.x - Number(d.cameraX)) / Number(d.cameraWidth) * bounds.width;
  const y = (geometry.label - Number(d.cameraY)) / Number(d.cameraHeight) * bounds.height;
  await page.locator('.office-canvas-wrap').evaluate((node, point) => {
    node.scrollLeft = Math.max(0, point.x - node.clientWidth / 2); node.scrollTop = Math.max(0, point.y - node.clientHeight / 2);
  }, { x, y });
  const current = (await canvas.boundingBox())!;
  await page.mouse.move(current.x + x, current.y + y);
}

async function expectCompactSidebar(page: Page) {
  const summary = (await page.locator('.office-summary').boundingBox())!;
  expect(summary.height).toBeLessThanOrEqual(24);
  const control = page.locator('.office-appearance-control').first();
  const row = (await control.boundingBox())!;
  expect(row.height).toBeLessThanOrEqual(28);
  const centers = await control.locator('label, .ant-select, button').evaluateAll(nodes => nodes.map(node => {
    const box = node.getBoundingClientRect(); return box.y + box.height / 2;
  }));
  expect(Math.max(...centers) - Math.min(...centers)).toBeLessThanOrEqual(1);
  expect(await control.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

async function employeeHost(context: BrowserContext, initial = defaults()) {
  const host = { saved: initial, rejectNext: false, rejectWait: undefined as Promise<void> | undefined, backgrounds: new Map<string, Buffer>() };
  const uploads = new Map<string, { key: string; bytes: Buffer[] }>();
  await context.exposeFunction('officeRefinementHost', async (message: string) => {
    const request = JSON.parse(message) as { command: string; operation: string; requestId: string; pageGeneration: string; payload: string };
    const packet = (type: string, fields = {}) => ({ requestId: request.requestId, pageGeneration: request.pageGeneration, type, ...fields });
    if (request.command !== 'storage') return [];
    if (request.operation.startsWith('background')) {
      const payload = JSON.parse(request.payload) as { floor: number; group?: string; transferId: string; data: string };
      const key = `${payload.group ?? ''}/${payload.floor}`;
      if (request.operation === 'backgroundRead') {
        const bytes = host.backgrounds.get(key);
        return bytes ? [packet('begin', { byteLength: bytes.length, mime: 'image/png', digest: hash(bytes) }),
          packet('chunk', { index: 0, data: bytes.toString('base64') }), packet('commit')] : [packet('value', { empty: true })];
      }
      if (request.operation === 'backgroundBegin') {
        uploads.set(payload.transferId, { key, bytes: [] });
      } else if (request.operation === 'backgroundChunk') uploads.get(payload.transferId)!.bytes.push(Buffer.from(payload.data, 'base64'));
      else if (request.operation === 'backgroundCommit') {
        const upload = uploads.get(payload.transferId)!;
        host.backgrounds.set(upload.key, Buffer.concat(upload.bytes)); uploads.delete(payload.transferId);
      } else if (request.operation === 'backgroundRemove') host.backgrounds.delete(key);
      else throw new Error('Unexpected background operation');
      return [packet('value')];
    }
    if (request.operation === 'petChoices') return [packet('value', { choices: [{ id: 'builtin', label: 'Robot' }] })];
    if (request.operation !== 'preferences') throw new Error('Unexpected storage operation');
    if (host.rejectNext) {
      host.rejectNext = false; await host.rejectWait;
      return [packet('error', { code: 'OFFICE_PREFERENCES_UNAVAILABLE' })];
    }
    const payload = JSON.parse(request.payload) as { expectedRevision: string; preferences: OfficeSharedState['preferences'] };
    if (payload.expectedRevision !== host.saved.revision) return [packet('error', { code: 'OFFICE_PREFERENCES_CONFLICT', state: host.saved })];
    host.saved = { ...host.saved, revision: randomUUID(), preferences: payload.preferences };
    return [packet('value', { state: host.saved })];
  });
  return host;
}

function keepOfficeAlive(page: Page, scene: Fixture) {
  let pending = false;
  const timer = setInterval(() => {
    if (pending || page.isClosed()) return;
    pending = true; void publish(page, scene).catch(() => undefined).finally(() => { pending = false; });
  }, 4000);
  page.once('close', () => clearInterval(timer));
}

async function chooseOfficeOption(page: Page, name: string, option: string | RegExp) {
  const select = page.getByRole('combobox', { name, exact: true });
  await select.scrollIntoViewIfNeeded(); await select.click();
  const value = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({
    hasText: typeof option === 'string' ? new RegExp(`^${option.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) : option,
  });
  await value.scrollIntoViewIfNeeded({ timeout: 8000 }); await value.click();
}

test('groups simplify creation, live assignments, renaming and shared scenes across reopening', async ({ context }) => {
  test.setTimeout(90_000);
  const host = await employeeHost(context), scene = liveSceneFixture(['RUNNING', 'WAITING_USER', 'THINKING']);
  let page = await open(context, host.saved, 'zh'); await publish(page, scene); keepOfficeAlive(page, scene);
  await chooseOfficeOption(page, '场景风格', '霓虹赛博');
  await page.getByRole('button', { name: '管理分组', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: '员工分组', exact: true });
  await expect(dialog.getByRole('tab')).toHaveCount(0);
  for (const name of ['分组徽记', '办公室场景', '自动归组的项目']) await expect(dialog.getByRole('combobox', { name, exact: true })).toHaveCount(0);
  await dialog.getByRole('textbox', { name: '分组名称', exact: true }).fill('星舰研发组');
  await dialog.getByRole('button', { name: '新建分组', exact: true }).click();
  await expect.poll(() => host.saved.preferences.organization.groups.length).toBe(1);
  const first = host.saved.preferences.organization.groups[0].id;
  expect(Object.keys(host.saved.preferences.organization.groups[0]).sort()).toEqual(['id', 'name']);
  expect(Object.keys(host.saved.preferences.organization.placements)).toHaveLength(0);
  await dialog.getByRole('textbox', { name: '分组名称', exact: true }).fill('森林实验组');
  await dialog.getByRole('button', { name: '新建分组', exact: true }).click();
  await expect.poll(() => host.saved.preferences.organization.groups.length).toBe(2);
  const second = host.saved.preferences.organization.groups[1].id;
  await dialog.getByRole('button', { name: '重命名星舰研发组', exact: true }).click();
  await dialog.getByRole('textbox', { name: '重命名星舰研发组', exact: true }).fill('星舰架构组');
  await dialog.getByRole('button', { name: '保存名称', exact: true }).click();
  await expect.poll(() => host.saved.preferences.organization.groups[0].name).toBe('星舰架构组');
  await chooseOfficeOption(page, 'AI 1的分组', /星舰架构组/);
  await chooseOfficeOption(page, 'AI 2的分组', /星舰架构组/);
  await chooseOfficeOption(page, 'AI 3的分组', /森林实验组/);
  await expect.poll(() => Object.values(host.saved.preferences.organization.placements).filter(p => p.group === first).length).toBe(2);
  await expect.poll(() => Object.values(host.saved.preferences.organization.placements).filter(p => p.group === second).length).toBe(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath('office-simple-group-management.png'), animations: 'disabled' });
  await dialog.getByRole('button', { name: '完成', exact: true }).click();
  await page.getByRole('button', { name: '进入星舰架构组办公室', exact: true }).click();
  await expect(page.locator('.office-session')).toHaveCount(2);
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'cyberpunk');
  await expect(page.getByRole('button', { name: '下一层', exact: true })).toHaveCount(0);
  const nav = page.locator('.office-group-navigation');
  expect(await nav.evaluate(node => !!node.closest('.office-scene-panel'))).toBe(true);
  expect((await nav.boundingBox())!.height).toBeLessThanOrEqual(52);
  await page.screenshot({ path: test.info().outputPath('office-simple-group-room.png'), animations: 'disabled' });
  await chooseOfficeOption(page, '场景风格', '森林工坊');
  await page.getByRole('button', { name: '进入森林实验组办公室', exact: true }).click();
  await expect(page.locator('.office-session')).toHaveCount(1);
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'forest');
  await expect.poll(() => host.saved.preferences.organization.activeGroup).toBe(second);
  await page.close(); page = await open(context, host.saved, 'zh'); await publish(page, scene); keepOfficeAlive(page, scene);
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'forest');
  await expect(page.locator('.office-session')).toHaveCount(1);
  await page.getByRole('button', { name: '管理分组', exact: true }).click(); dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '删除森林实验组', exact: true }).click();
  await page.locator('.ant-popconfirm .ant-btn-primary').click();
  await expect.poll(() => host.saved.preferences.organization.groups.length).toBe(1);
  await expect.poll(() => host.saved.preferences.organization.activeGroup).toBe('');
  await dialog.getByRole('button', { name: '完成', exact: true }).click();
  await expect(page.locator('.office-session')).toHaveCount(1);
  await page.close();
});

test('groups share the toolbar background across room switches, reopening and reset', async ({ context }) => {
  test.setTimeout(60_000);
  const initial = defaults(), scene = liveSceneFixture(['RUNNING']);
  initial.preferences.floors[0] = 'cyberpunk';
  initial.preferences.organization = { groups: [{ id: 'a', name: '研发组' }, { id: 'b', name: '设计组' }], activeGroup: 'a', placements: {} };
  const host = await employeeHost(context, initial); let page = await open(context, host.saved, 'zh');
  await publish(page, scene); keepOfficeAlive(page, scene);
  const bytes = Buffer.from(await page.evaluate(() => {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 32;
    const ctx = canvas.getContext('2d')!; ctx.fillStyle = '#112233'; ctx.fillRect(0, 0, 32, 32);
    return canvas.toDataURL('image/png').split(',')[1];
  }), 'base64');
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: '选择背景', exact: true }).click();
  await (await chooser).setFiles({ name: 'room.png', mimeType: 'image/png', buffer: bytes });
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'custom');
  await expect.poll(() => host.backgrounds.has('/0')).toBe(true);
  expect([...host.backgrounds.keys()]).toEqual(['/0']);
  await page.getByRole('button', { name: '进入设计组办公室', exact: true }).click();
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'custom');
  await expect.poll(() => host.saved.preferences.organization.activeGroup).toBe('b');
  await page.close(); page = await open(context, host.saved, 'zh'); await publish(page, scene); keepOfficeAlive(page, scene);
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'custom');
  await page.getByRole('button', { name: '恢复默认', exact: true }).click();
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'cyberpunk');
  expect(host.backgrounds.size).toBe(0);
  await page.getByRole('button', { name: '进入研发组办公室', exact: true }).click();
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'cyberpunk');
  await page.getByRole('button', { name: '进入未分组办公室', exact: true }).click();
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'cyberpunk');
  await page.close();
});

test('groups show only live employees and remember offline membership without reserving capacity', async ({ context }) => {
  test.setTimeout(90_000);
  const initial = defaults(), scene = liveSceneFixture(Array.from({ length: 13 }, () => 'RUNNING'));
  const keys = scene.snapshot.sources.map(s => [s.sourceKey.instanceId, s.sourceKey.contentHandleId, s.sourceKey.bindingEpoch].join('/'));
  initial.preferences.organization = { groups: [{ id: 'a', name: '核心小队' }], activeGroup: 'a',
    placements: Object.fromEntries(keys.slice(0, 12).map((key, seat) => [key, { group: 'a', seat }])) };
  const host = await employeeHost(context, initial), page = await open(context, host.saved, 'zh');
  await publish(page, scene); keepOfficeAlive(page, scene);
  await expect(page.locator('.office-session')).toHaveCount(12);
  await expect(page.getByRole('button', { name: '下一层', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '管理分组', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('list', { name: '在线员工', exact: true }).getByRole('listitem')).toHaveCount(13);
  await dialog.getByRole('combobox', { name: 'AI 13的分组', exact: true }).click();
  await expect(page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: '核心小队' })).toHaveClass(/disabled/);
  await page.keyboard.press('Escape');
  const first = scene.snapshot.sources[0], appearance = scene.appearances[keys[0]];
  scene.snapshot.sources = scene.snapshot.sources.slice(1); delete scene.appearances[keys[0]]; await publish(page, scene);
  await expect(dialog.getByRole('list', { name: '在线员工', exact: true }).getByRole('listitem')).toHaveCount(12);
  await expect(dialog.getByRole('combobox', { name: 'AI 1的分组', exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('checkbox')).toHaveCount(0);
  await chooseOfficeOption(page, 'AI 13的分组', /核心小队/);
  await expect.poll(() => host.saved.preferences.organization.placements[keys[12]]?.group).toBe('a');
  expect(host.saved.preferences.organization.placements[keys[0]].group).toBe('a');
  await dialog.getByRole('button', { name: '完成', exact: true }).click();
  scene.snapshot.sources.unshift(first); scene.appearances[keys[0]] = appearance; await publish(page, scene);
  await expect(page.locator('.office-session')).toHaveCount(12);
  await page.getByRole('button', { name: '进入未分组办公室', exact: true }).click();
  await expect(page.locator('.office-session')).toHaveCount(1);
  await expect(page.locator('.office-session')).toContainText('AI 1');
  scene.snapshot.sources = scene.snapshot.sources.filter(s => s.sourceKey.contentHandleId !== 'tab-1');
  delete scene.appearances[keys[1]]; await publish(page, scene);
  await expect(page.locator('.office-session')).toHaveCount(0);
  await page.getByRole('button', { name: '进入核心小队办公室', exact: true }).click();
  await expect(page.locator('.office-session')).toHaveCount(12);
  await page.locator('.office-session').filter({ hasText: 'AI 13' }).click();
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 1');
  await publish(page, scene);
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 1');
  await page.getByRole('button', { name: '管理分组', exact: true }).click(); dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('list', { name: '在线员工', exact: true }).getByRole('listitem')).toHaveCount(12);
  await page.screenshot({ path: test.info().outputPath('office-simple-group-employees.png'), animations: 'disabled' });
  await page.close();
});

test('online fallback desks stay put until explicitly moved to their remembered preference', async ({ context }) => {
  const initial = defaults(), scene = liveSceneFixture(['RUNNING', 'RUNNING']);
  const keys = scene.snapshot.sources.map(s => `${s.sourceKey.instanceId}/${s.sourceKey.contentHandleId}/${s.sourceKey.bindingEpoch}`);
  initial.preferences.organization = { groups: [{ id: 'a', name: '引擎小队' }], activeGroup: 'a',
    placements: Object.fromEntries(keys.map(key => [key, { group: 'a', seat: 0 }])) };
  const host = await employeeHost(context, initial), page = await open(context, host.saved, 'zh');
  await publish(page, scene); keepOfficeAlive(page, scene);
  await page.locator('.office-session').nth(1).click();
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 2');
  scene.snapshot.sources = scene.snapshot.sources.slice(1);
  delete scene.appearances[keys[0]]; await publish(page, scene);
  await expect(page.locator('.office-session')).toHaveCount(1);
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 2');
  await chooseOfficeOption(page, '调整工位', '工位 1 · 空工位');
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 1');
  await publish(page, scene);
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 1');
  await page.close();
});

test('group desks swap by selection and dragging, persist and revert when a save fails', async ({ context }) => {
  test.setTimeout(90_000);
  const initial = defaults(), scene = liveSceneFixture(['RUNNING', 'THINKING', 'RUNNING']);
  const keys = scene.snapshot.sources.map(s => `${s.sourceKey.instanceId}/${s.sourceKey.contentHandleId}/${s.sourceKey.bindingEpoch}`);
  initial.preferences.organization = { groups: [{ id: 'a', name: '引擎小队' },
    { id: 'b', name: '外部小队' }], activeGroup: 'a',
  placements: { [keys[0]]: { group: 'a', seat: 0 }, [keys[1]]: { group: 'a', seat: 1 }, [keys[2]]: { group: 'b', seat: 0 } } };
  const host = await employeeHost(context, initial); let page = await open(context, host.saved, 'zh');
  await publish(page, scene); keepOfficeAlive(page, scene); await page.locator('.office-session').first().click();
  await chooseOfficeOption(page, '调整工位', '工位 2 · AI 2');
  await expect.poll(() => host.saved.preferences.organization.placements[keys[0]].seat).toBe(1);
  expect(host.saved.preferences.organization.placements[keys[1]].seat).toBe(0);
  host.rejectNext = true;
  await chooseOfficeOption(page, '调整工位', '工位 12 · 空工位');
  await expect(page.getByRole('alert').filter({ hasText: '共享设置保存失败' })).toBeVisible();
  expect(host.saved.preferences.organization.placements[keys[0]].seat).toBe(1);
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 2');
  // Mouse drags use the painted camera coordinates, including vacant desks.
  const drag = async (from: number, to: number) => {
    const canvas = page.locator('.office-scene-surface > canvas'); await canvas.scrollIntoViewIfNeeded();
    const d = await page.evaluate(() => window.petOfficeDiagnostics!()), bounds = (await canvas.boundingBox())!;
    const point = (seat: number) => ({ x: bounds.x + (OFFICE_ART_SEATS.loft[seat].x - Number(d.cameraX)) / Number(d.cameraWidth) * bounds.width,
      y: bounds.y + (OFFICE_ART_SEATS.loft[seat].label - Number(d.cameraY)) / Number(d.cameraHeight) * bounds.height });
    const start = point(from), end = point(to);
    await page.mouse.move(start.x, start.y); await page.mouse.down(); await page.mouse.move(end.x, end.y, { steps: 12 }); await page.mouse.up();
  };
  await drag(1, 2);
  await expect.poll(() => host.saved.preferences.organization.placements[keys[0]].seat).toBe(2);
  await drag(2, 0);
  await expect.poll(() => host.saved.preferences.organization.placements[keys[0]].seat).toBe(0);
  expect(host.saved.preferences.organization.placements[keys[1]].seat).toBe(2);
  expect(host.saved.preferences.organization.placements[keys[2]]).toEqual({ group: 'b', seat: 0 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.close(); page = await open(context, host.saved, 'zh'); await publish(page, scene);
  await page.locator('.office-session').nth(1).click();
  await expect(page.locator('#office-group-seat').locator('xpath=..')).toContainText('工位 3');
  await page.close();
});

test('ungrouped employees swap across floors and follow membership changes to the correct office', async ({ context }) => {
  test.setTimeout(60_000);
  const initial = defaults(), scene = liveSceneFixture(Array.from({ length: 16 }, () => 'RUNNING'));
  initial.preferences.organization.groups = [{ id: 'a', name: '引擎小队' }];
  const keys = scene.snapshot.sources.map(s => `${s.sourceKey.instanceId}/${s.sourceKey.contentHandleId}/${s.sourceKey.bindingEpoch}`);
  const host = await employeeHost(context, initial), page = await open(context, host.saved, 'zh');
  await publish(page, scene); keepOfficeAlive(page, scene); await page.locator('.office-session').first().click();
  await page.getByRole('combobox', { name: '调整工位', exact: true }).click();
  for (let index = 0; index < 14; index++) await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await expect.poll(() => host.saved.preferences.organization.placements[keys[0]]?.seat).toBe(14);
  expect(host.saved.preferences.organization.placements[keys[14]]?.seat).toBe(0);
  await expect(page.locator('.office-floor-label')).toHaveText('第 2 / 2 层');
  await chooseOfficeOption(page, '所属分组', /引擎小队/);
  await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', 'loft');
  await expect(page.locator('.office-session')).toHaveCount(1);
  await chooseOfficeOption(page, '所属分组', '未分组');
  await expect(page.locator('.office-floor-label')).toHaveText('第 2 / 2 层');
  await expect.poll(() => host.saved.preferences.organization.placements[keys[0]]).toEqual({ group: '', seat: 14 });
  await page.close();
});

async function recordEmployeeNameplates(page: Page) {
  await page.locator('.office-scene-surface > canvas').waitFor();
  await page.evaluate(() => {
    window.officePaintedEmployeeNames = new Set();
    const ctx = document.querySelector<HTMLCanvasElement>('.office-scene-surface > canvas')!.getContext('2d')!;
    const fill = ctx.fillText.bind(ctx);
    ctx.fillText = (text, x, y, maxWidth) => {
      if (ctx.font.includes('800 19px')) window.officePaintedEmployeeNames!.add(text);
      if (maxWidth === undefined) fill(text, x, y); else fill(text, x, y, maxWidth);
    };
  });
}

test('employee names persist per binding, render above Pets, survive save failures and reset independently', async ({ context }) => {
  test.setTimeout(60_000);
  const host = await employeeHost(context);
  const scene = liveSceneFixture(['RUNNING', 'THINKING']);
  const keys = scene.snapshot.sources.map(source => `${source.sourceKey.instanceId}/${source.sourceKey.contentHandleId}/${source.sourceKey.bindingEpoch}`);
  let page = await open(context, host.saved, 'zh');
  await recordEmployeeNameplates(page); await publish(page, scene); keepOfficeAlive(page, scene);
  await page.locator('.office-session').nth(0).click();
  const firstName = '黑骑·代码魔神';
  let input = page.getByRole('textbox', { name: '员工名称', exact: true });
  await input.fill(`  ${firstName}  `); await input.press('Enter');
  await expect.poll(() => host.saved.preferences.employeeNames[keys[0]]).toBe(firstName);
  await expect.poll(() => page.evaluate(name => window.officePaintedEmployeeNames!.has(name), firstName)).toBe(true);
  await expect(input).toHaveValue(firstName);
  await page.locator('.office-session').nth(1).click(); await expect(input).toHaveValue('');
  await input.fill('星际架构师'); await page.getByRole('button', { name: '应用', exact: true }).click();
  await expect.poll(() => host.saved.preferences.employeeNames[keys[1]]).toBe('星际架构师');
  expect(host.saved.preferences.employeeNames[keys[0]]).toBe(firstName);
  const search = page.getByRole('searchbox', { name: '搜索项目、页签或 Provider', exact: true });
  await search.fill('代码魔神'); await expect(page.locator('.office-session')).toHaveCount(1); await search.fill('');
  await expect.poll(() => host.saved.preferences.search).toBe('');
  await page.close(); page = await open(context, host.saved, 'zh');
  await recordEmployeeNameplates(page); await publish(page, scene); keepOfficeAlive(page, scene);
  await page.locator('.office-session').nth(0).click();
  input = page.getByRole('textbox', { name: '员工名称', exact: true });
  await expect(input).toHaveValue(firstName);
  host.rejectNext = true;
  let releaseRejection!: () => void;
  host.rejectWait = new Promise<void>(resolve => { releaseRejection = resolve; });
  await input.fill('保存失败的名字'); await input.press('Enter'); await input.fill('继续输入的新草稿');
  releaseRejection(); host.rejectWait = undefined;
  await expect(page.getByRole('alert').filter({ hasText: '共享设置保存失败' })).toBeVisible();
  await expect(input).toHaveValue('继续输入的新草稿'); expect(host.saved.preferences.employeeNames[keys[0]]).toBe(firstName);
  await input.press('Enter'); await expect.poll(() => host.saved.preferences.employeeNames[keys[0]]).toBe('继续输入的新草稿');
  await page.evaluate(() => window.officePaintedEmployeeNames!.clear());
  await page.getByRole('button', { name: '重置员工名称', exact: true }).click();
  await expect.poll(() => host.saved.preferences.employeeNames[keys[0]]).toBeUndefined();
  await expect(input).toHaveValue(''); expect(host.saved.preferences.employeeNames[keys[1]]).toBe('星际架构师');
  await expect.poll(() => page.evaluate(() => window.officePaintedEmployeeNames!.has('AI 1'))).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.close();
});

test('employee nameplates follow all nine scenes and become static when animation is paused or reduced', async ({ context }) => {
  test.setTimeout(90_000);
  const initial = defaults(), scene = liveSceneFixture(['RUNNING', 'THINKING', 'WAITING_USER']);
  const names = ['黑骑·代码魔神', '星际架构师', '深海御码者'];
  initial.preferences.employeeNames = Object.fromEntries(scene.snapshot.sources.map((source, i) =>
    [`${source.sourceKey.instanceId}/${source.sourceKey.contentHandleId}/${source.sourceKey.bindingEpoch}`, names[i]]));
  const host = await employeeHost(context, initial);
  const page = await open(context, host.saved, 'zh');
  await recordEmployeeNameplates(page); await publish(page, scene); await page.locator('.office-session').first().click();
  const themes = { loft: '暖色阁楼', cyberpunk: '霓虹赛博', cultivation: '云海仙阁', space: '星舰指挥舱', forest: '森林工坊',
    library: '魔法图书馆', ocean: '深海观测站', zen: '禅意庭院', retro: '复古游戏工作室' };
  for (const [theme, label] of Object.entries(themes)) {
    await publish(page, scene);
    await page.getByRole('combobox', { name: '场景风格', exact: true }).click();
    await page.getByRole('option', { name: label, exact: true }).click();
    await expect(page.locator('.office-live')).toHaveAttribute('data-ui-theme', theme);
    await expect.poll(() => page.evaluate(names => names.every(name => window.officePaintedEmployeeNames!.has(name)), names)).toBe(true);
    if (test.info().project.name === 'chromium-full-hd') {
      await page.screenshot({ path: test.info().outputPath(`employee-nameplate-${theme}.png`), animations: 'disabled' });
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  const fullRoom = liveSceneFixture(Array.from({ length: 12 }, () => 'RUNNING' as const));
  await publish(page, fullRoom);
  await page.getByRole('button', { name: '聚焦会话', exact: true }).click();
  await page.waitForTimeout(3000);
  await test.info().attach('employee-nameplate-performance.json', {
    body: JSON.stringify(await page.evaluate(() => window.petOfficeDiagnostics!()), null, 2), contentType: 'application/json',
  });
  if (test.info().project.name === 'chromium-full-hd') {
    await page.screenshot({ path: test.info().outputPath('employee-nameplate-full-room.png'), animations: 'disabled' });
  }
  await page.getByRole('button', { name: '暂停动画', exact: true }).click();
  const canvas = page.locator('.office-scene-surface > canvas');
  await expect(page.getByRole('button', { name: '继续动画', exact: true })).toBeVisible();
  const paused = await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL());
  await page.waitForTimeout(1100);
  expect(await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL())).toBe(paused);
  await page.getByRole('button', { name: '继续动画', exact: true }).click();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(page.getByRole('button', { name: '继续动画', exact: true })).toBeDisabled();
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().reducedMotion)).toBe(true);
  const reduced = await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL());
  await page.waitForTimeout(1100);
  expect(await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL())).toBe(reduced);
  await page.close();
});

test('office naming trims, persists, rolls back failed saves and restores the default titles', async ({ context }) => {
  let saved = defaults(), rejectNext = false;
  await context.exposeFunction('officeRefinementHost', (message: string) => {
    const request = JSON.parse(message) as { command: string; operation: string; requestId: string; pageGeneration: string; payload: string };
    if (request.command !== 'storage') return [];
    const packet = (type: string, fields = {}) => ({ requestId: request.requestId, pageGeneration: request.pageGeneration, type, ...fields });
    if (request.operation === 'backgroundRead') return [packet('value', { empty: true })];
    if (request.operation !== 'preferences') throw new Error('Unexpected storage operation');
    if (rejectNext) { rejectNext = false; return [packet('error', { code: 'OFFICE_PREFERENCES_UNAVAILABLE' })]; }
    const payload = JSON.parse(request.payload) as { expectedRevision: string; preferences: OfficeSharedState['preferences'] };
    if (payload.expectedRevision !== saved.revision) return [packet('error', { code: 'OFFICE_PREFERENCES_CONFLICT', state: saved })];
    saved = { ...saved, revision: randomUUID(), preferences: payload.preferences };
    return [packet('value', { state: saved })];
  });
  let page = await open(context, saved, 'zh');
  const name = '研发办公室 🐾 <team>';
  await page.getByRole('button', { name: '工作室设置', exact: true }).click();
  await page.getByRole('textbox', { name: '办公室名称', exact: true }).fill(`  ${name}  `);
  await page.getByRole('textbox', { name: '办公室名称', exact: true }).press('Enter');
  await expect.poll(() => saved.preferences.officeName).toBe(name);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name); await expect(page).toHaveTitle(name);
  await expect(page.getByRole('textbox', { name: '办公室名称', exact: true })).toHaveValue(name);
  await page.keyboard.press('Escape'); await page.close(); page = await open(context, saved, 'zh');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name); await expect(page).toHaveTitle(name);
  await page.getByRole('button', { name: '工作室设置', exact: true }).click();
  const input = page.getByRole('textbox', { name: '办公室名称', exact: true });
  await input.fill('Unsaved draft'); await page.keyboard.press('Escape');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);
  await page.getByRole('button', { name: '工作室设置', exact: true }).click(); await expect(input).toHaveValue(name);
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: test.info().outputPath('office-name-settings-zh.png'), animations: 'disabled' });
  rejectNext = true;
  await input.fill('Failed save'); await page.getByRole('button', { name: '应用名称', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: '共享设置保存失败' })).toBeVisible();
  await expect(input).toHaveValue(name); await expect(page).toHaveTitle(name);
  expect(saved.preferences.officeName).toBe(name);
  await input.fill(''); await page.getByRole('button', { name: '应用名称', exact: true }).click();
  await expect.poll(() => saved.preferences.officeName).toBe('');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Pet 办公室'); await expect(page).toHaveTitle('Pet 办公室');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: test.info().outputPath('office-inline-zoom-zh.png'), animations: 'disabled' });
  await page.close();
});

test('hover previews follow live metadata without selecting or navigating and dismiss safely', async ({ context }) => {
  const commands: string[] = [];
  await context.exposeFunction('officeRefinementHost', (message: string) => { commands.push((JSON.parse(message) as { command: string }).command); return []; });
  const page = await open(context); await publish(page, liveSceneFixture(['RUNNING', 'WAITING_USER']));
  await hoverDesk(page, 1);
  const preview = page.getByRole('tooltip', { name: 'Desk preview' });
  await expect(preview).toContainText('Project A / AI 2'); await expect(preview).toContainText('Question');
  expect(await page.locator('.office-session[aria-pressed=true]').count()).toBe(0); expect(commands).not.toContain('navigate');
  const bounds = (await preview.boundingBox())!, viewport = page.viewportSize()!;
  expect(bounds.x).toBeGreaterThanOrEqual(0); expect(bounds.y).toBeGreaterThanOrEqual(0);
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width + 1); expect(bounds.y + bounds.height).toBeLessThanOrEqual(viewport.height + 1);
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: '../.codex/office-neon-compact-20261017/screenshots/desk-hover.png' });
  await page.getByRole('combobox', { name: 'Scene zoom' }).click();
  await page.getByRole('option', { name: '150%', exact: true }).click();
  await hoverDesk(page, 1); await expect(preview).toContainText('Project A / AI 2');
  await publish(page, liveSceneFixture(['RUNNING', 'RUNNING']));
  await expect(preview).toContainText('Working'); await expect(preview).not.toContainText('Question');
  await page.keyboard.press('Escape'); await expect(preview).not.toBeVisible();
  await page.mouse.move(0, 0); await hoverDesk(page, 1); await expect(preview).toBeVisible();
  await publish(page, liveSceneFixture(['RUNNING'])); await expect(preview).not.toBeVisible();
  await publish(page, liveSceneFixture(['RUNNING', 'WAITING_USER'])); await expect(preview).not.toBeVisible();
  expect(commands).not.toContain('navigate'); await page.close();
});

test('the last retiring Pet has a finite tail with immediate source retirement and an idle clock', async ({ context }) => {
  const page = await open(context); await publish(page, liveSceneFixture(['RUNNING']));
  await expect.poll(() => page.evaluate(() => Number(window.petOfficeDiagnostics?.().frames ?? 0))).toBeGreaterThan(0);
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().presentationTransition)).toBe(false);
  await page.evaluate(() => {
    window.officeTransitionPeak = 0; let frames = 0;
    const observe = () => { window.officeTransitionPeak = Math.max(window.officeTransitionPeak ?? 0, Number(window.petOfficeDiagnostics?.().retiringActors ?? 0)); if (++frames < 45) requestAnimationFrame(observe); };
    requestAnimationFrame(observe);
  });
  await publish(page, liveSceneFixture([]));
  await expect(page.locator('.office-session')).toHaveCount(0);
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().hitTargets)).toBe(0);
  await expect.poll(() => page.evaluate(() => window.officeTransitionPeak)).toBe(1);
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().retiringActors)).toBe(0);
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().running)).toBe(false);
  const frames = await page.evaluate(() => window.petOfficeDiagnostics?.().frames);
  await page.waitForTimeout(250); expect(await page.evaluate(() => window.petOfficeDiagnostics?.().frames)).toBe(frames);
  await publish(page, liveSceneFixture(['RUNNING'])); await page.getByRole('button', { name: 'Pause animation' }).click();
  await publish(page, liveSceneFixture([])); expect(await page.evaluate(() => window.petOfficeDiagnostics?.().retiringActors)).toBe(0);
  await page.close();
});

test('session Pets render independently, survive owner change and reset to inherited appearance', async ({ context }) => {
  const instanceId = randomUUID(), tabs = [randomUUID(), randomUUID()], bindings = [randomUUID(), randomUUID()];
  const keys = tabs.map((tab, index) => `${instanceId}/${tab}/${bindings[index]}`);
  const sessions = () => {
    const data = liveSceneFixture(['RUNNING', 'RUNNING']); data.snapshot.instanceId = instanceId;
    for (const [index, source] of data.snapshot.sources.entries()) {
      const oldKey = `instance-A/tab-${index}/binding-${index}`;
      source.sourceKey = { instanceId, contentHandleId: tabs[index], bindingEpoch: bindings[index] };
      source.metadata.contentHandleId = tabs[index]; source.execution.bindingEpoch = bindings[index];
      data.appearances[keys[index]] = data.appearances[oldKey]; delete data.appearances[oldKey];
    }
    return data;
  };
  let saved = defaults(); let latest = sessions(); let nativeSequence = 100;
  const images = new Map<string, Buffer>();
  const render = () => {
    const data = sessions();
    for (const [key, visual] of Object.entries(data.appearances)) {
      const id = saved.preferences.appearanceOverrides[key], bytes = id && images.get(id);
      if (id) Object.assign(visual, { scope: 'session', revision: hash(id), status: bytes ? 'READY' : 'BUILTIN',
        manifest: bytes ? { digest: hash(bytes), kind: 'image', mime: 'image/png', width: 8, height: 8, byteLength: bytes.length } : visual.manifest });
    }
    latest = data; return data;
  };
  await context.exposeFunction('officeRefinementHost', (message: string) => {
    const request = JSON.parse(message) as { command: string; operation: string; requestId: string; pageGeneration: string; payload: string; sourceKey: string; appearanceRevision: string };
    const reply = (fields: Record<string, unknown>) => ({ requestId: request.requestId, pageGeneration: request.pageGeneration, type: 'value', ...fields });
    if (request.command === 'asset') {
      const visual = latest.appearances[request.sourceKey], id = saved.preferences.appearanceOverrides[request.sourceKey], bytes = images.get(id);
      return visual && bytes ? packets(request.pageGeneration, 'ASSET', bytes, 0, { requestId: request.requestId, sourceKey: request.sourceKey, appearanceRevision: request.appearanceRevision, manifest: visual.manifest }) : [];
    }
    if (request.command !== 'storage') return [];
    if (request.operation === 'backgroundRead') return [reply({ empty: true })];
    if (request.operation === 'petChoices') return [reply({ choices: [{ id: 'builtin', label: 'Robot' }, { id: 'red.png', label: 'Red Pet' }, { id: 'blue.png', label: 'Blue Pet' }] })];
    if (request.operation === 'preferences') {
      const payload = JSON.parse(request.payload) as { expectedRevision: string; preferences: OfficeSharedState['preferences'] };
      if (payload.expectedRevision !== saved.revision) return [{ ...reply({}), type: 'error', code: 'OFFICE_PREFERENCES_CONFLICT', state: saved }];
      saved = { ...saved, revision: randomUUID(), preferences: payload.preferences };
      return [reply({ state: saved }), ...packets(request.pageGeneration, 'SCENE', Buffer.from(JSON.stringify(render())), ++nativeSequence)];
    }
    return [];
  });
  let page = await open(context, saved);
  for (const [id, color] of [['red.png', '#ff3322'], ['blue.png', '#2255ff']]) {
    const encoded = await page.evaluate(color => { const canvas = document.createElement('canvas'); canvas.width = canvas.height = 8;
      const ctx = canvas.getContext('2d')!; ctx.fillStyle = color; ctx.fillRect(0, 0, 8, 8); return canvas.toDataURL().split(',')[1]; }, color);
    images.set(id, Buffer.from(encoded, 'base64'));
  }
  await publish(page, render());
  const choose = async (index: number, label: string) => {
    await page.getByRole('list', { name: 'Session list' }).getByRole('button').nth(index).click();
    await page.getByRole('combobox', { name: 'Session Pet', exact: true }).click();
    await page.locator('.ant-select-item-option-content').filter({ hasText: new RegExp(`^${label}$`) }).click();
  };
  await choose(0, 'Red Pet'); await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().petImages)).toBe(1);
  expect(saved.preferences.appearanceOverrides[keys[1]]).toBeUndefined();
  await choose(1, 'Blue Pet'); await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().petImages)).toBe(2);
  await expectCompactSidebar(page);
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: '../.codex/office-neon-compact-20261017/screenshots/session-appearance.png' });
  await page.close(); page = await open(context, saved); await publish(page, render());
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().petImages)).toBe(2);
  await page.getByRole('list', { name: 'Session list' }).getByRole('button').first().click();
  await expect(page.getByRole('region', { name: 'Session Pet', exact: true })).toContainText('Red Pet');
  await choose(0, 'Follow project settings'); await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().petImages)).toBe(1);
  expect(saved.preferences.appearanceOverrides).toEqual({ [keys[1]]: 'blue.png' });
  await choose(1, 'Follow project settings'); await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().petImages)).toBe(0);
  expect(saved.preferences.appearanceOverrides).toEqual({}); await page.close();
});

test('Chinese desk appearance controls and searchable Pet names remain readable', async ({ context }) => {
  await context.exposeFunction('officeRefinementHost', (message: string) => {
    const request = JSON.parse(message) as { operation: string; requestId: string; pageGeneration: string };
    const reply = { type: 'value', requestId: request.requestId, pageGeneration: request.pageGeneration };
    if (request.operation === 'backgroundRead') return [{ ...reply, empty: true }];
    if (request.operation === 'petChoices') return [{ ...reply, choices: [{ id: 'builtin', label: 'Built-in robot' },
      { id: 'pet-package-17/spritesheet.png', label: '橘子 🐥' }, { id: 'local-import-93/spritesheet.png', label: '墨镜小鸭' }] }];
    return [];
  });
  const page = await open(context, defaults(), 'zh'), fixture = liveSceneFixture(['RUNNING']); await publish(page, fixture);
  await hoverDesk(page, 0); await expect(page.getByRole('tooltip', { name: '工位预览' })).toBeVisible();
  await page.getByRole('list', { name: '会话列表' }).getByRole('button').first().click();
  const control = page.getByRole('region', { name: '当前工位 Pet', exact: true });
  await expectCompactSidebar(page);
  await expect(control).toContainText('跟随项目设置'); await expect(control).not.toContainText('仅更换当前会话');
  await expect(control.locator('label')).toHaveAttribute('title', /仅更换当前会话/);
  const selection = (await page.locator('.office-selection').boundingBox())!;
  // Four compact controls now share the selected workstation: Pet, name, group and desk.
  expect(selection.height).toBeLessThan(240);
  for (const row of await page.locator('.office-selection .office-appearance-control').all()) {
    expect((await row.boundingBox())!.height).toBeLessThanOrEqual(28);
    expect(await row.evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
  }
  expect(await page.locator('.office-selection').evaluate(node => node.scrollHeight <= node.clientHeight)).toBe(true);
  const first = page.getByRole('list', { name: '会话列表' }).getByRole('button').first();
  const card = (await first.boundingBox())!, phase = (await first.locator('.office-session-phase').boundingBox())!;
  expect(phase.x).toBeGreaterThan(card.x + card.width * .6);
  expect(card.x + card.width - phase.x - phase.width).toBeLessThan(24);
  await expect(control).not.toContainText('??'); await expect(control.getByRole('button', { name: '刷新 Pet' })).toBeVisible();
  await page.locator('.office-session-help').focus();
  await expect(page.locator('.ant-popover:visible')).toContainText('单击查看工位 · 双击或 Enter 打开页签');
  await publish(page, fixture);
  const select = page.getByRole('combobox', { name: '当前工位 Pet', exact: true }); await select.click();
  await expect(page.locator('.ant-select-item-option-content').filter({ hasText: /^橘子 🐥$/ })).toBeVisible();
  await expect(page.locator('.ant-select-item-option-content').filter({ hasText: /^墨镜小鸭$/ })).toBeVisible();
  await select.fill('墨镜'); await expect(page.locator('.ant-select-item-option-content').filter({ hasText: /^墨镜小鸭$/ })).toBeVisible();
  await expect(page.locator('.ant-select-item-option-content').filter({ hasText: /^橘子 🐥$/ })).toHaveCount(0);
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: '../.codex/office-neon-compact-20261017/screenshots/chinese-pet-names.png' });
  await page.keyboard.press('Escape'); await expect(page.locator('.pet-office')).not.toContainText('桌位');
  await page.close();
});

test('Chinese margin effect picker retains keyboard selection and focus', async ({ context }) => {
  const page = await open(context, defaults(), 'zh'); await publish(page, liveSceneFixture(['RUNNING']));
  const effects = page.getByRole('button', { name: '留白特效', exact: true });
  await effects.click();
  const options = page.locator('.office-effects-popover .office-effects-options');
  await expect(options.getByRole('radio')).toHaveCount(6);
  await options.getByText('霓虹光幕', { exact: true }).click();
  await expect(options.getByRole('radio', { name: '霓虹光幕', exact: true })).toBeChecked();
  await expect(options.getByText('仅装饰办公室两侧留白。', { exact: false })).toBeVisible();
  await options.getByRole('radio', { name: '飘雪', exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(options.getByRole('radio', { name: '霓虹光幕', exact: true })).toBeChecked();
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: test.info().outputPath('header-effects-zh.png') });
  await page.keyboard.press('Escape');
  await expect(effects).toBeFocused();
  await expect(effects).toHaveAttribute('aria-expanded', 'false');
  await expect(effects).toContainText('霓虹光幕');
  await page.close();
});

test('Motion result chips follow live outcomes and cancel their compositor animations when paused', async ({ context }) => {
  const page = await open(context); await publish(page, liveSceneFixture(['RUNNING', 'RUNNING', 'RUNNING']));
  await expect(page.locator('.office-feedback-chip')).toHaveCount(0);
  await publish(page, liveSceneFixture(['SUCCEEDED', 'FAILED', 'CANCELLED']));
  const chips = page.locator('.office-feedback-chip'); await expect(chips).toHaveCount(3);
  await expect(chips.nth(0)).toContainText('Succeeded'); await expect(chips.nth(1)).toContainText('Failed'); await expect(chips.nth(2)).toContainText('Cancelled');
  expect(await page.locator('.office-feedback-layer').evaluate(node => node.getAnimations({ subtree: true }).length)).toBeGreaterThanOrEqual(3);
  expect(await page.locator('.office-feedback-layer').evaluate(node => getComputedStyle(node).pointerEvents)).toBe('none');
  if (test.info().project.name === 'chromium-full-hd') {
    await page.waitForTimeout(450);
    await page.screenshot({ path: '../.codex/office-neon-compact-20261017/screenshots/task-feedback.png' });
  }
  await page.getByRole('button', { name: 'Pause animation' }).click(); await expect(chips).toHaveCount(0);
  expect(await page.locator('.office-feedback-layer').evaluate(node => node.getAnimations({ subtree: true }).length)).toBe(0);
  await page.getByRole('button', { name: 'Resume animation' }).click(); await expect(chips).toHaveCount(0);
  await page.close();
});

test('diagnostics sit above the workspace and settings have an independent header entry', async ({ context }) => {
  const page = await open(context); await publish(page, liveSceneFixture(['RUNNING', 'WAITING_USER']));
  const diagnostics = page.getByRole('button', { name: 'Scene diagnostics', exact: true });
  const bounds = (await diagnostics.boundingBox())!, workspace = (await page.locator('.office-workspace').boundingBox())!;
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(workspace.y);
  const root = page.locator('.pet-office'), rootBox = (await root.boundingBox())!;
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(rootBox.x + rootBox.width);
  await diagnostics.click();
  await expect(page.locator('.office-diagnostics-details')).toContainText('Pet image diagnostics');
  await expect(page.getByRole('dialog')).toHaveCount(0); await expect(page.getByRole('spinbutton')).toHaveCount(0);
  await page.keyboard.press('Escape'); await expect(page.locator('.office-diagnostics-details')).not.toBeVisible();
  await diagnostics.click(); await page.getByRole('button', { name: 'Close diagnostics', exact: true }).click();
  await expect(page.locator('.office-diagnostics-details')).not.toBeVisible();
  const settings = page.locator('.office-scene-header').getByRole('button', { name: 'Studio settings', exact: true });
  await settings.click();
  const dialog = page.getByRole('dialog', { name: 'Studio settings', exact: true });
  await expect(dialog).toBeVisible(); await expect(page.locator('.office-diagnostics-details')).not.toBeVisible();
  await expect(dialog.getByRole('checkbox')).toHaveCount(3); await expect(dialog).not.toContainText('2.5D');
  await expect(dialog.getByRole('heading', { name: 'Pet image diagnostics' })).toHaveCount(0);
  await expect(dialog.getByRole('spinbutton', { name: 'Decoded image cache limit' })).toHaveValue('50');
  await page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible(); await expect(settings).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.close();
});

test('fitted room furniture and input stay still even with a legacy enabled depth preference', async ({ context }) => {
  await context.addInitScript(() => localStorage.setItem('ccgui.pet.office.presentation.v1', JSON.stringify({ effects: true, attention: true, ambience: true, depth: true })));
  const page = await open(context); await publish(page, liveSceneFixture(Array.from({ length: 12 }, () => 'RUNNING')));
  await page.getByRole('button', { name: 'Focus sessions' }).click();
  const canvas = page.locator('.office-scene-surface > canvas'); await canvas.scrollIntoViewIfNeeded();
  const bounds = (await canvas.boundingBox())!;
  const transforms = () => page.locator('.office-vector-scene [data-depth]').evaluateAll(nodes => nodes.map(node => node.getAttribute('transform')));
  const before = await transforms(), view = await page.evaluate(() => window.petOfficeDiagnostics!());
  for (const fraction of [.1, .9, .4]) {
    await page.mouse.move(bounds.x + bounds.width * fraction, bounds.y + bounds.height * .5); await page.waitForTimeout(300);
    expect(await transforms()).toEqual(before);
    const current = await page.evaluate(() => window.petOfficeDiagnostics!());
    expect([current.cameraX, current.cameraY, current.cameraWidth, current.cameraHeight]).toEqual([view.cameraX, view.cameraY, view.cameraWidth, view.cameraHeight]);
    expect(current.depthMotion).toBeUndefined();
  }
  const geometry = OFFICE_ART_SEATS.loft[8];
  await page.mouse.click(bounds.x + (geometry.x - Number(view.cameraX)) / Number(view.cameraWidth) * bounds.width,
    bounds.y + (geometry.label - Number(view.cameraY)) / Number(view.cameraHeight) * bounds.height);
  await expect(page.locator('.office-session[aria-pressed=true]')).toContainText('AI 9');
  await page.close();
});

test('scene controls wrap to stay visible and decorative effects use only spare margins', async ({ context }) => {
  const commands: string[] = [];
  await context.exposeFunction('officeRefinementHost', (message: string) => { commands.push((JSON.parse(message) as { command: string }).command); return []; });
  const page = await open(context); await publish(page, liveSceneFixture(['RUNNING', 'WAITING_USER', 'FAILED', 'RUNNING', 'RUNNING', 'RUNNING', 'RUNNING', 'RUNNING', 'RUNNING', 'RUNNING', 'RUNNING', 'RUNNING']));
  const size = page.viewportSize()!, sidebar = (await page.locator('.office-sidebar').boundingBox())!;
  await expect(page.locator('.office-scene-rail')).toHaveCount(0);
  if (size.width >= 1600) expect(sidebar.width).toBeGreaterThanOrEqual(430);
  if (size.width >= 1180) expect(sidebar.width).toBeGreaterThanOrEqual(350);
  const header = (await page.locator('.office-scene-header').boundingBox())!;
  expect(await page.locator('.office-scene-header').evaluate(node => node.scrollWidth <= node.clientWidth)).toBe(true);
  if (size.width >= 1600) {
    const floor = (await page.locator('.office-floor-navigation').boundingBox())!;
    expect(Math.abs(floor.x + floor.width / 2 - header.x - header.width / 2)).toBeLessThanOrEqual(2);
  }
  for (const name of ['Choose background', 'Margin effects', 'Studio settings']) {
    const button = page.locator('.office-scene-header').getByRole('button', { name, exact: true });
    await expect(button).toBeVisible();
    const box = (await button.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(header.x);
    expect(box.x + box.width).toBeLessThanOrEqual(header.x + header.width + 1);
  }
  const room = (await page.locator('.office-scene-surface').boundingBox())!;
  for (const strip of await page.locator('.office-margin-effect:visible').all()) {
    const box = (await strip.boundingBox())!;
    expect(box.x + box.width <= room.x + 1 || box.x >= room.x + room.width - 1).toBe(true);
    expect(await strip.evaluate(node => getComputedStyle(node).pointerEvents)).toBe('none');
  }
  const theme = page.getByRole('combobox', { name: 'Scene style', exact: true });
  await theme.focus(); await theme.click();
  const choices = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
  await expect(choices).toHaveCount(9);
  if (size.height >= 700) {
    await expect.poll(async () => {
      const popup = (await page.locator('.ant-select-dropdown:visible').boundingBox())!;
      const last = (await choices.last().boundingBox())!;
      return popup.height > 240 && last.y + last.height <= popup.y + popup.height + 1;
    }).toBe(true);
  }
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Margin effects', exact: true }).click();
  await expect(page.getByRole('radio')).toHaveCount(6);
  await expect(page.getByRole('switch', { name: 'Enable margin effects' })).toBeChecked();
  await page.locator('.office-effects-popover').getByText('Code rain', { exact: true }).click();
  await expect(page.getByRole('radio', { name: 'Code rain', exact: true })).toBeChecked();
  await page.getByRole('radio', { name: 'Code rain', exact: true }).focus();
  await page.keyboard.press('ArrowRight'); await expect(page.getByRole('radio', { name: 'Rainy glass', exact: true })).toBeChecked();
  await page.keyboard.press('Escape');
  await expect(page.locator('.office-effects-popover')).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Margin effects', exact: true })).toBeFocused();
  expect(await page.evaluate(() => window.petOfficeDiagnostics?.().marginEffectMode)).toBe('rain');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(commands).not.toContain('navigate'); await page.close();
});

test('margin effects switch cleanly, freeze with the room and remember off without retaining bitmaps', async ({ context }) => {
  test.skip(test.info().project.name !== 'chromium-full-hd', 'Requires actual side margins at the fitted room size.');
  test.setTimeout(60_000);
  await context.exposeFunction('officeRefinementHost', (message: string) => {
    const request = JSON.parse(message) as { operation: string; requestId: string; pageGeneration: string };
    const reply = { type: 'value', requestId: request.requestId, pageGeneration: request.pageGeneration };
    if (request.operation === 'backgroundRead') return [{ ...reply, empty: true }];
    if (request.operation === 'petChoices') return [{ ...reply, choices: [{ id: 'builtin', label: 'Built-in robot' }] }];
    return [];
  });
  const initial = defaults(); initial.preferences.floors[0] = 'cyberpunk';
  const page = await open(context, initial);
  const fixture = liveSceneFixture(Array.from({ length: 12 }, () => 'RUNNING'));
  await publish(page, fixture);
  await page.getByRole('button', { name: 'Focus sessions', exact: true }).click();
  const diagnostics = () => page.evaluate(() => window.petOfficeDiagnostics!());
  await expect.poll(async () => (await diagnostics()).marginEffectActive).toBe(true);
  const canvas = page.locator('.office-margin-effect.is-left');
  const pixels = () => canvas.evaluate(node => {
    const target = node as HTMLCanvasElement;
    const values = target.getContext('2d')!.getImageData(0, 0, target.width, target.height).data;
    let lit = 0; for (let i = 3; i < values.length; i += 4) if (values[i] > 0) lit++;
    return lit;
  });
  const view = await diagnostics();
  for (const [label, mode] of [['Code rain', 'matrix'], ['Rainy glass', 'rain'], ['Light mist', 'aurora'], ['Star trails', 'stars'], ['Soft snow', 'snow'], ['Neon veil', 'neon']]) {
    await publish(page, fixture);
    await page.getByRole('button', { name: 'Margin effects', exact: true }).click();
    await page.locator('.office-effects-popover').getByText(label, { exact: true }).click();
    await expect(page.getByRole('radio', { name: label, exact: true })).toBeChecked(); await page.keyboard.press('Escape');
    await expect.poll(async () => (await diagnostics()).marginEffectMode).toBe(mode);
    await expect.poll(pixels).toBeGreaterThan(30);
    const current = await diagnostics();
    expect(Number(current.marginEffectBytes)).toBeLessThanOrEqual(4 * 1024 * 1024);
    expect([current.cameraX, current.cameraY, current.cameraWidth, current.cameraHeight]).toEqual([view.cameraX, view.cameraY, view.cameraWidth, view.cameraHeight]);
    if (mode === 'neon') {
      await page.getByRole('list', { name: 'Session list' }).getByRole('button').first().click();
      await expectCompactSidebar(page);
      const before = await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL());
      await expect.poll(() => canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL())).not.toBe(before);
      await page.screenshot({ path: test.info().outputPath('neon-veil.png') });
    }
    if (mode === 'matrix') await page.screenshot({ path: test.info().outputPath('code-rain.png') });
  }
  // Refresh the simulated host lease after the screenshot-heavy mode checks.
  await publish(page, fixture);
  await expect.poll(async () => (await diagnostics()).stale).toBe(false);
  await expect.poll(async () => (await diagnostics()).marginEffectActive).toBe(true);
  await page.getByRole('button', { name: 'Pause animation', exact: true }).click();
  const paused = await diagnostics(), image = await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL());
  await page.waitForTimeout(450);
  expect((await diagnostics()).marginEffectFrames).toBe(paused.marginEffectFrames);
  expect(await canvas.evaluate(node => (node as HTMLCanvasElement).toDataURL())).toBe(image);
  expect((await diagnostics()).marginEffectActive).toBe(false);
  await page.getByRole('button', { name: 'Resume animation', exact: true }).click();
  await expect.poll(async () => Number((await diagnostics()).marginEffectFrames)).toBeGreaterThan(Number(paused.marginEffectFrames));
  await page.evaluate(() => window.petOfficeSetVisible?.(false)); await page.waitForTimeout(100);
  const hidden = await diagnostics(); await page.waitForTimeout(300);
  expect((await diagnostics()).marginEffectFrames).toBe(hidden.marginEffectFrames);
  expect((await diagnostics()).marginEffectActive).toBe(false);
  await page.evaluate(() => window.petOfficeSetVisible?.(true)); await publish(page, fixture);
  await page.getByRole('button', { name: 'Margin effects', exact: true }).click();
  await page.locator('.office-effects-popover').getByText('Soft snow', { exact: true }).click();
  await page.getByRole('switch', { name: 'Enable margin effects' }).uncheck(); await page.keyboard.press('Escape');
  await expect(canvas).not.toBeVisible(); expect((await diagnostics()).marginEffectBytes).toBe(0);
  const off = await diagnostics(); await page.waitForTimeout(200);
  expect((await diagnostics()).marginEffectFrames).toBe(off.marginEffectFrames);
  await page.reload(); await publish(page, fixture);
  await expect(page.getByRole('button', { name: 'Margin effects', exact: true })).toContainText('Effects off');
  await page.getByRole('button', { name: 'Margin effects', exact: true }).click();
  const options = page.locator('.office-effects-popover');
  await expect(options.getByRole('switch', { name: 'Enable margin effects' })).not.toBeChecked();
  await expect(options.getByRole('radio', { name: 'Soft snow', exact: true })).toBeChecked();
  await options.getByRole('switch', { name: 'Enable margin effects' }).check(); await page.keyboard.press('Escape');
  await expect(canvas).toBeVisible();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(canvas).not.toBeVisible(); expect((await diagnostics()).marginEffectActive).toBe(false);
  await page.emulateMedia({ reducedMotion: 'no-preference' }); await publish(page, fixture);
  await expect(canvas).toBeVisible();
  await page.getByRole('combobox', { name: 'Scene zoom' }).click();
  await page.getByRole('option', { name: '150%', exact: true }).click();
  await expect(canvas).not.toBeVisible(); expect((await diagnostics()).marginEffectBytes).toBe(0);
  await page.getByRole('button', { name: 'Fit window', exact: true }).click(); await expect(canvas).toBeVisible();
  await page.getByRole('searchbox', { name: 'Search project, tab or provider' }).fill('no-such-project');
  await expect(canvas).not.toBeVisible(); await expect.poll(async () => (await diagnostics()).running).toBe(false);
  await page.close();
});

test('header effect picker fits the available width and retains a keyboard-selected neon veil', async ({ context }) => {
  const page = await open(context); const fixture = liveSceneFixture(['RUNNING']); await publish(page, fixture);
  await page.getByRole('button', { name: 'Studio settings', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Studio settings', exact: true });
  await expect(dialog.getByRole('radio')).toHaveCount(0);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Margin effects', exact: true }).click();
  const options = page.locator('.office-effects-popover .office-effects-options'), grid = options.locator('.ant-radio-group');
  await expect(grid.getByRole('radio')).toHaveCount(6);
  await expect.poll(() => grid.evaluate(node => {
    const picker = node.closest('.office-effects-options')!.getBoundingClientRect(); return picker.width - node.getBoundingClientRect().width;
  })).toBeLessThanOrEqual(2);
  const columns = 3;
  const { cards, right } = await grid.evaluate(node => ({ right: node.getBoundingClientRect().right,
    cards: [...node.querySelectorAll('.ant-radio-button-wrapper')].map(card => {
      const box = card.getBoundingClientRect(); return { y: box.y, width: box.width, right: box.right };
    }) }));
  expect(new Set(cards.map(card => Math.round(card.y))).size).toBe(6 / columns);
  for (const card of cards) { expect(card.width).toBeGreaterThan(90); expect(card.right).toBeLessThanOrEqual(right + 1); }
  await grid.getByRole('radio', { name: 'Soft snow', exact: true }).focus(); await page.keyboard.press('ArrowRight');
  const neon = grid.getByRole('radio', { name: 'Neon veil', exact: true }); await expect(neon).toBeChecked();
  if (test.info().project.name === 'chromium-full-hd') await page.screenshot({ path: test.info().outputPath('header-effects-en.png') });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Margin effects', exact: true })).toContainText('Neon veil');
  await page.reload(); await publish(page, fixture);
  await expect(page.getByRole('button', { name: 'Margin effects', exact: true })).toContainText('Neon veil');
  await page.close();
});

const windowSceneGroups: Readonly<Record<string, string>>[] = [
  { loft: 'Warm loft', cyberpunk: 'Neon cyberpunk', cultivation: 'Cloud sanctuary' },
  { space: 'Starship deck', forest: 'Forest workshop', library: 'Arcane library' },
  { ocean: 'Deep-sea observatory', zen: 'Zen courtyard', retro: 'Retro game studio' },
];
for (const themes of windowSceneGroups) {
  test(`built-in windows loop behind stationary architecture without accumulating actors (${Object.keys(themes).join(', ')})`, async ({ context }) => {
    test.setTimeout(60_000);
    const page = await open(context), errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    const fixture = liveSceneFixture(['RUNNING']); await publish(page, fixture);
    await page.getByRole('button', { name: 'Focus sessions', exact: true }).click();
    for (const [theme, label] of Object.entries(themes)) {
      await publish(page, fixture);
      const selector = page.getByRole('combobox', { name: 'Scene style', exact: true });
      await selector.focus(); await selector.click();
      await page.locator('.ant-select-dropdown:visible .ant-select-item-option-content').filter({ hasText: label }).click();
      const scenery = page.locator(`[data-window-scenery="${theme}"]`);
      await expect(scenery).toHaveAttribute('visibility', 'visible');
      await expect(page.locator('[data-window-scenery]')).toHaveCount(1);
      const pose = () => scenery.locator('[data-window-motion]').evaluateAll(nodes => nodes.map(node => `${node.getAttribute('transform')}:${node.getAttribute('opacity')}`).join('|'));
      const before = await pose(), count = await scenery.locator('*').count();
      const structure = () => page.locator('.office-vector-environment [data-depth]').evaluateAll(nodes => nodes.map(node => node.getAttribute('transform')));
      const stationary = await structure();
      await expect.poll(pose).not.toBe(before);
      expect(await scenery.locator('*').count()).toBe(count);
      expect(await structure()).toEqual(stationary);
      const views = await page.locator('.office-vector-scene').evaluateAll(nodes => nodes.map(node => node.getAttribute('viewBox')));
      expect(new Set(views).size).toBe(1);
      if (test.info().project.name === 'chromium-full-hd') await page.locator('.office-scene-surface').screenshot({ path: test.info().outputPath(`window-${theme}.png`) });
    }
    expect(errors).toEqual([]); await page.close();
  });
}

test('window scenery respects ambience, pause, visibility, reduced motion and custom backgrounds', async ({ context }) => {
  const page = await open(context), fixture = liveSceneFixture(['RUNNING']); await publish(page, fixture);
  const scenery = page.locator('[data-window-scenery]');
  const pose = () => scenery.locator('[data-window-motion]').evaluateAll(nodes => nodes.map(node => `${node.getAttribute('transform')}:${node.getAttribute('opacity')}`).join('|'));
  await expect(scenery).toHaveAttribute('visibility', 'visible');
  const first = await pose(); await expect.poll(pose).not.toBe(first);
  await page.getByRole('button', { name: 'Pause animation', exact: true }).click();
  const paused = await pose(); await page.waitForTimeout(220); expect(await pose()).toBe(paused);
  await page.getByRole('button', { name: 'Resume animation', exact: true }).click();
  await expect.poll(pose).not.toBe(paused);
  await page.evaluate(() => window.petOfficeSetVisible?.(false));
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().running)).toBe(false);
  const hidden = await pose(); await page.waitForTimeout(220); expect(await pose()).toBe(hidden);
  await page.evaluate(() => window.petOfficeSetVisible?.(true)); await publish(page, fixture);
  await expect.poll(pose).not.toBe(hidden);
  await page.getByRole('button', { name: 'Studio settings', exact: true }).click();
  const ambience = page.getByRole('checkbox', { name: 'Environmental animation', exact: true });
  await ambience.uncheck(); await expect(scenery).toHaveAttribute('visibility', 'hidden');
  const off = await pose(); await page.waitForTimeout(180); expect(await pose()).toBe(off);
  await ambience.check(); await expect(scenery).toHaveAttribute('visibility', 'visible');
  await page.keyboard.press('Escape');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(scenery).toHaveAttribute('visibility', 'hidden');
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().running)).toBe(false);
  await page.emulateMedia({ reducedMotion: 'no-preference' }); await publish(page, fixture);
  await expect(scenery).toHaveAttribute('visibility', 'visible');
  const actors = await scenery.locator('[data-window-motion]').count();
  await page.getByRole('combobox', { name: 'Scene zoom' }).click();
  await page.getByRole('option', { name: '150%', exact: true }).click();
  expect(await scenery.locator('[data-window-motion]').count()).toBe(actors);
  const views = await page.locator('.office-vector-scene').evaluateAll(nodes => nodes.map(node => node.getAttribute('viewBox')));
  expect(new Set(views).size).toBe(1);
  await page.getByRole('button', { name: 'Fit window', exact: true }).click();
  const png = await page.evaluate(() => { const canvas = document.createElement('canvas'); canvas.width = 20; canvas.height = 20;
    canvas.getContext('2d')!.fillRect(0, 0, 20, 20); return canvas.toDataURL().split(',')[1]; });
  await page.locator('input[type=file]').setInputFiles({ name: 'custom.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64') });
  await expect(scenery).toHaveCount(0);
  await page.getByRole('button', { name: 'Restore default', exact: true }).click();
  await expect(scenery).toHaveCount(1); await expect(scenery).toHaveAttribute('visibility', 'visible');
  await page.getByRole('searchbox', { name: 'Search project, tab or provider' }).fill('no-matching-project');
  await expect.poll(() => page.evaluate(() => window.petOfficeDiagnostics?.().running)).toBe(false);
  const empty = await pose(); await page.waitForTimeout(180); expect(await pose()).toBe(empty);
  await page.close();
});
