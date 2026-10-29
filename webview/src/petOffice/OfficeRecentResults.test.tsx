import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { OfficeRecentResults } from './OfficeRecentResults';

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }) }));

it('finds recent results by the same employee name, project and provider as the session list', () => {
  const props = { results: [{ sourceKey: 'ide/tab/binding', product: 'IDEA', projectLabel: 'Project A', tabLabel: 'AI 1', provider: 'codex',
    modelLabel: 'test-model', outcome: 'SUCCEEDED', terminalEventId: 'result-1', completedAt: 1000 }], sources: [],
    names: { 'ide/tab/binding': 'Review Wizard' }, product: 'all', stale: false, pending: false, onOpen: vi.fn() };
  const view = render(<OfficeRecentResults {...props} search="Review Wizard" />);
  expect(screen.getAllByRole('listitem')).toHaveLength(1);
  for (const search of ['Project A', 'codex']) {
    view.rerender(<OfficeRecentResults {...props} search={search} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
  }
  view.rerender(<OfficeRecentResults {...props} search="unrelated" />);
  expect(screen.queryAllByRole('listitem')).toHaveLength(0);
});
