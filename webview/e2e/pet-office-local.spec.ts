import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

interface TestState { fail: boolean; activations: Array<Record<string, string>>; actions: string[]; }
declare global { interface Window { __OFFICE_LOCAL_TEST__: TestState; } }

test.beforeEach(async ({ page }) => {
  const html = await readFile(resolve('dist/office/office.html'), 'utf8');
  await page.route('**/office-fixture.html', route => route.fulfill({ status: 200, contentType: 'text/html', body: html }));
  await page.addInitScript(() => {
    const state: TestState = { fail: false, activations: [], actions: [] };
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
        execution: { phase: index === 0 ? 'WAITING_USER' : 'RUNNING', pendingInteractionId: index === 0 ? 'question' : '' },
      }));
      const data = request.action === 'activate' ? { result: 'STALE_INTERACTION' } : {
        schemaVersion: 1, instanceId: 'idea', connectionEpoch: 'epoch', watermark: 1, sampledAt: 1000, capacityReached: false, sources,
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
