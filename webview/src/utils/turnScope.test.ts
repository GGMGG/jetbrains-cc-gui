import { describe, expect, it } from 'vitest';
import {
  finalizeTodosForSettledTurn,
  selectLatestSubagentTurn,
} from './turnScope';
import type { ClaudeMessage, SubagentInfo, TodoItem } from '../types';

const subagent = (overrides: Partial<SubagentInfo>): SubagentInfo => ({
  id: 'tu_1',
  type: 'research',
  description: 'task',
  status: 'running',
  messageIndex: 0,
  ...overrides,
});

describe('finalizeTodosForSettledTurn', () => {
  const todos: TodoItem[] = [{ content: 'Implement', status: 'in_progress' }];

  it('preserves Codex plan state when the main turn settles', () => {
    expect(finalizeTodosForSettledTurn(todos, false, 'codex')).toEqual(todos);
  });

  it('keeps the existing Claude settled-task behavior', () => {
    expect(finalizeTodosForSettledTurn(todos, false, 'claude')).toEqual([
      { content: 'Implement', status: 'completed' },
    ]);
  });
});

describe('selectLatestSubagentTurn', () => {
  const user = (content: string): ClaudeMessage => ({ type: 'user', content });
  const assistant = (): ClaudeMessage => ({ type: 'assistant' });

  it('keeps only the most recent turn containing valid extracted subagents', () => {
    const messages = [user('first'), assistant(), user('second'), assistant()];
    const first = subagent({ id: 'first', messageIndex: 1 });
    const second = subagent({ id: 'second', messageIndex: 3 });

    expect(selectLatestSubagentTurn(messages, [first, second])).toEqual([second]);
  });

  it('keeps the previous valid turn when a later turn produced no valid subagent', () => {
    const messages = [user('first'), assistant(), user('noise-only'), assistant()];
    const first = subagent({ id: 'first', messageIndex: 1 });

    expect(selectLatestSubagentTurn(messages, [first])).toEqual([first]);
  });

  it('keeps all valid subagents from the selected turn', () => {
    const messages = [user('first'), assistant(), assistant()];
    const first = subagent({ id: 'first', messageIndex: 1 });
    const second = subagent({ id: 'second', messageIndex: 2 });

    expect(selectLatestSubagentTurn(messages, [first, second])).toEqual([first, second]);
  });
});
