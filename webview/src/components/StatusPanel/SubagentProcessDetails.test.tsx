import { render } from '@testing-library/react';
import SubagentProcessDetails from './SubagentProcessDetails';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

describe('SubagentProcessDetails', () => {
  it('shows history errors only for terminal error status', () => {
    const { container, rerender } = render(
      <SubagentProcessDetails
        history={{ success: false, status: 'running', error: 'Codex subagent activity not found yet' }}
        canLoad
      />,
    );

    expect(container.querySelector('.subagent-error')).toBeNull();

    rerender(
      <SubagentProcessDetails
        history={{ success: false, status: 'error', error: 'Subagent history failed' }}
        canLoad
      />,
    );

    expect(container.querySelector('.subagent-error')?.textContent).toBe('Subagent history failed');
  });
});
