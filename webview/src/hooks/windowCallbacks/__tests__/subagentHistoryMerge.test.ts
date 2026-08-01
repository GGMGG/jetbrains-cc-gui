import { describe, expect, it } from 'vitest';
import {
  isCurrentSubagentResponse,
  mergeSubagentHistory,
  toSubagentHistoryResponse,
} from '../subagentHistoryMerge';

describe('subagentHistoryMerge', () => {
  it('does not let a late running response overwrite a completed status', () => {
    expect(mergeSubagentHistory(
      { success: true, completed: true, status: 'completed' },
      { success: false, completed: false, status: 'running', error: 'not found yet' },
    )).toEqual({
      success: true,
      completed: true,
      status: 'completed',
      error: undefined,
    });
  });

  it('adds a transcript without regressing an existing terminal status', () => {
    expect(mergeSubagentHistory(
      { success: true, completed: true, status: 'completed' },
      { success: true, completed: false, status: 'running', messages: [{ type: 'assistant' }] },
    )).toMatchObject({
      completed: true,
      status: 'completed',
      messages: [{ type: 'assistant' }],
    });
  });

  it('attaches batch session identity to a lightweight snapshot', () => {
    expect(toSubagentHistoryResponse(
      { success: false, toolUseId: 'call-1', status: 'running' },
      { sessionId: 'session-1', provider: 'codex', requestId: 'request-1' },
    )).toEqual({
      success: false,
      toolUseId: 'call-1',
      status: 'running',
      sessionId: 'session-1',
      provider: 'codex',
    });
  });

  it('rejects responses from an inactive session or provider', () => {
    expect(isCurrentSubagentResponse(
      { sessionId: 'old-session', provider: 'codex' },
      'current-session',
      'codex',
    )).toBe(false);
    expect(isCurrentSubagentResponse(
      { sessionId: 'current-session', provider: 'claude' },
      'current-session',
      'codex',
    )).toBe(false);
    expect(isCurrentSubagentResponse(
      { sessionId: 'current-session', provider: 'codex' },
      'current-session',
      'codex',
    )).toBe(true);
  });
});
