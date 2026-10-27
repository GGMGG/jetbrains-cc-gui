import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

interface TestSettings { revision: string; generation: number; officeName: string; topmost: boolean; zoom: number; recovered: boolean; }
interface TestState { fail: boolean; peer: boolean; losePreferenceReply: boolean; settings: TestSettings; activations: Array<Record<string, string>>; actions: string[]; }
declare global { interface Window { __OFFICE_LOCAL_TEST__: TestState; } }

test.beforeEach(async ({ page }) => {
  const html = await readFile(resolve('dist/office/office.html'), 'utf8');
  await page.route('**/office-fixture.html', route => route.fulfill({ status: 200, contentType: 'text/html', body: html }));
  await page.addInitScript(() => {
    const state: TestState = { fail: false, peer: false, losePreferenceReply: false, activations: [], actions: [],
      settings: { revision: 'initial', generation: 0, officeName: '', topmost: true, zoom: 100, recovered: false } };
    window.__OFFICE_LOCAL_TEST__ = state;
    window.__PET_OFFICE_PAGE__ = 'test-page';
    window.__PET_OFFICE_LANGUAGE__ = 'en';
    window.__PET_OFFICE_SEND__ = (message: string) => {
      const request = JSON.parse(message) as { action: string; requestId: string; pageToken: string; payload: Record<string, string> };
      state.actions.push(request.action);
      if (request.action === 'activate') state.activations.push(request.payload);
      const sources = Array.from({ length: 25 }, (_, index) => ({
        sourceKey: { instanceId: 'idea', contentHandleId: `tab-${index}`, bindingEpoch: 'binding' },
        routeGeneration: 'route', routeStatus: 'AVAILABLE', provider: 'codex', modelLabel: 'model',
        metadata: { projectId: index % 2 ? 'project-B' : 'project-A', projectLabel: index % 2 ? 'Project B' : 'Project A', tabLabel: `Chat ${index}` },
        execution: { phase: [0, 3, 4].includes(index) ? 'WAITING_USER' : index === 1 ? 'THINKING' : index === 2 ? 'QUEUED' : 'RUNNING',
          waitReason: index === 0 ? 'QUESTION' : index === 3 ? 'PERMISSION' : index === 4 ? 'PLAN' : 'NONE',
          pendingInteractionId: [0, 3, 4].includes(index) ? 'question' : '', pendingInteractionCount: [0, 3, 4].includes(index) ? 1 : 0 },
      }));
      if (state.peer) sources[0] = { ...sources[0], sourceKey: { instanceId: 'peer', contentHandleId: 'tab-0', bindingEpoch: 'peer-binding' },
        metadata: { projectId: 'project-A', projectLabel: 'Peer project', tabLabel: 'Peer chat' } };
      if (request.action === 'preferences') {
        const patch = request.payload as unknown as { expectedRevision: string; officeName: string; topmost: boolean; zoom: number };
        if (patch.expectedRevision !== state.settings.revision) {
          setTimeout(() => window.onPetOfficeResponse?.(JSON.stringify({ ...request, success: false, error: 'OFFICE_PREFERENCES_CONFLICT' })), 0);
          return true;
        }
        state.settings = { revision: `saved-${state.settings.generation + 1}`, generation: state.settings.generation + 1,
          officeName: patch.officeName, topmost: patch.topmost, zoom: patch.zoom, recovered: false };
        if (state.losePreferenceReply) return true;
      }
      const data = request.action === 'exit' ? { closing: true } : request.action === 'activate' ? { result: 'STALE_INTERACTION' } : request.action === 'preferences' ? state.settings : {
        schemaVersion: 1, instanceId: 'idea', connectionEpoch: 'epoch', watermark: 1, sampledAt: 1000, capacityReached: false, sources, settings: state.settings,
        participants: state.peer ? ['idea', 'peer'] : ['idea'],
      };
      setTimeout(() => window.onPetOfficeResponse?.(JSON.stringify({ requestId: request.requestId, pageToken: request.pageToken,
        action: request.action, success: !state.fail, data, error: state.fail ? 'OFFICE_UNAVAILABLE' : undefined })), 0);
      return true;
    };
  });
  await page.goto('/office-fixture.html');
});

test('filters and pages real desks while retaining exact activation authority', async ({ page }) => {
  await expect(page.getByRole('button', { name: 'Open chat: Chat 0', exact: true })).toBeVisible();
  await expect(page.getByText('Waiting for your answer', { exact: true })).toBeVisible();
  await expect(page.getByText('Thinking', { exact: true })).toBeVisible();
  await expect(page.getByText('Queued', { exact: true })).toBeVisible();
  await expect(page.getByText('Waiting for permission', { exact: true })).toBeVisible();
  await expect(page.getByText('Waiting for plan review', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Open chat: Chat 0', exact: true }).click();
  await expect(page.getByText('This interaction has changed. Refresh and try again.')).toBeVisible();
  expect(await page.evaluate(() => window.__OFFICE_LOCAL_TEST__.activations)).toEqual([
    { sourceKey: 'idea/tab-0/binding', routeGeneration: 'route', interactionId: 'question' },
  ]);
  await page.getByRole('combobox').selectOption('project-B');
  await expect(page.getByRole('button', { name: 'Open chat: Chat 1', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Open chat: Chat 0', exact: true })).toHaveCount(0);
  await page.getByRole('combobox').selectOption('');
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Open chat: Chat 12', exact: true })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflow).toBe(false);
  expect(await page.evaluate(() => window.__OFFICE_LOCAL_TEST__.actions.every(action => action === 'scene' || action === 'activate'))).toBe(true);
});

test('commits name, topmost and zoom with an acknowledged revision', async ({ page }) => {
  await page.getByRole('button', { name: 'Window settings', exact: true }).click();
  await page.getByLabel('Office name', { exact: true }).fill('Team Office');
  await page.getByLabel('Keep window on top', { exact: true }).uncheck();
  await page.getByRole('combobox', { name: 'Zoom', exact: true }).selectOption('125');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Team Office');
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  expect(await page.evaluate(() => window.__OFFICE_LOCAL_TEST__.settings)).toMatchObject({ generation: 1, officeName: 'Team Office', topmost: false, zoom: 125 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
});

test('reconciles a lost save acknowledgement through the confirmed snapshot', async ({ page }) => {
  await page.getByRole('button', { name: 'Window settings', exact: true }).click();
  await page.evaluate(() => { window.__OFFICE_LOCAL_TEST__.losePreferenceReply = true; });
  await page.getByLabel('Office name', { exact: true }).fill('Saved despite timeout');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Saved despite timeout');
  await expect(page.getByRole('alert')).toHaveText('Settings could not be confirmed. Your draft is kept. Reload saved values before retrying.', { timeout: 8000 });
  await page.getByRole('button', { name: 'Reload saved values', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  expect(await page.evaluate(() => window.__OFFICE_LOCAL_TEST__.actions.filter(action => action === 'preferences').length)).toBe(1);
});

test('preserves the visible sample and disables desks until a failed read recovers', async ({ page }) => {
  const desk = page.getByRole('button', { name: 'Open chat: Chat 0', exact: true });
  await expect(desk).toBeEnabled();
  await page.evaluate(() => { window.__OFFICE_LOCAL_TEST__.fail = true; });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(desk).toBeDisabled();
  await page.evaluate(() => { window.__OFFICE_LOCAL_TEST__.fail = false; });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(desk).toBeEnabled();
});

test('keeps peer project identity and forwards the exact remote source', async ({ page }) => {
  await page.evaluate(() => { window.__OFFICE_LOCAL_TEST__.peer = true; });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await page.getByRole('combobox').selectOption('peer:project-A');
  await page.getByRole('button', { name: 'Open chat: Peer chat', exact: true }).click();
  expect(await page.evaluate(() => window.__OFFICE_LOCAL_TEST__.activations)).toEqual([
    { sourceKey: 'peer/tab-0/peer-binding', routeGeneration: 'route', interactionId: 'question' },
  ]);
  await expect(page.getByRole('button', { name: 'Open chat: Chat 2', exact: true })).toHaveCount(0);
});

test('only confirms exit after a valid acknowledgement and permits retry after failure', async ({ page }) => {
  await page.evaluate(() => { window.__OFFICE_LOCAL_TEST__.fail = true; });
  const exit = page.getByRole('button', { name: 'Exit Office', exact: true });
  await exit.click();
  await expect(exit).toBeEnabled();
  await page.evaluate(() => { window.__OFFICE_LOCAL_TEST__.fail = false; });
  await exit.click();
  await expect(exit).toBeDisabled();
  expect(await page.evaluate(() => window.__OFFICE_LOCAL_TEST__.actions.filter(action => action === 'exit').length)).toBe(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
});
