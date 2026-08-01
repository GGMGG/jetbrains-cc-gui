import type {
  SubagentHistoryResponse,
  SubagentStatusesResponse,
  SubagentStatusSnapshot,
} from '../../types';

function isTerminal(history: SubagentHistoryResponse | undefined): boolean {
  return history?.completed === true || history?.status === 'completed' || history?.status === 'error';
}

export function mergeSubagentHistory(
  existing: SubagentHistoryResponse | undefined,
  incoming: SubagentHistoryResponse,
): SubagentHistoryResponse {
  const merged = { ...existing, ...incoming };
  if (!isTerminal(existing)) {
    return merged;
  }

  // Lifecycle is monotonic, but late responses may still contribute identity
  // fields or the transcript loaded by an expanded row.
  return {
    ...merged,
    success: existing?.success ?? incoming.success,
    completed: existing?.completed,
    status: existing?.status,
    error: existing?.error,
  };
}

export function toSubagentHistoryResponse(
  snapshot: SubagentStatusSnapshot,
  batch: SubagentStatusesResponse,
): SubagentHistoryResponse {
  return {
    ...snapshot,
    sessionId: batch.sessionId,
    provider: batch.provider,
  };
}

export function isCurrentSubagentResponse(
  result: { sessionId?: string; provider?: string },
  currentSessionId: string | null,
  currentProvider: string,
): boolean {
  if (result.sessionId && result.sessionId !== currentSessionId) {
    return false;
  }
  return !result.provider || result.provider === currentProvider;
}
