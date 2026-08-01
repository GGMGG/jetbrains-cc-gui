import type { SubagentHistoryResponse, ToolResultBlock } from '../types';
import type { GetToolResultRawFn } from '../contexts/SubagentContext';

/**
 * Extract concatenated text content from a tool_result block.
 *
 * Shared by useSubagents, AgentGroupBlock, and TaskExecutionBlock so the
 * extraction logic (string vs array content, text-block filtering) stays in
 * one place. Returns undefined when there is no text to show.
 */
export function extractResultText(result?: ToolResultBlock | null): string | undefined {
  if (!result) return undefined;
  if (typeof result.content === 'string') return result.content;
  if (Array.isArray(result.content)) {
    const text = result.content
      .map((item) => (item && typeof item.text === 'string' ? item.text : ''))
      .filter(Boolean)
      .join('\n');
    return text || undefined;
  }
  return undefined;
}

/**
 * Whether an Agent/Task tool input launches a background (async) subagent.
 *
 * Claude Code triggers async via the `run_in_background: true` input parameter
 * (AgentTool schema), NOT via the tool name. normalizeToolInput preserves the
 * snake_case field via spread for the Agent/Task tools, so both the StatusPanel
 * list (useSubagents, normalized input) and the inline Agent cards
 * (AgentGroupBlock/TaskExecutionBlock, raw block.input) read the same field.
 *
 * Strict === true avoids truthy strings (e.g. "false") flipping the flag. The
 * camelCase `runInBackground` form is also checked as a guard against future
 * normalization changes. Shared by all three call sites so they cannot drift.
 */
export function isAsyncAgentInput(input: unknown, normalizedToolName?: string): boolean {
  if (normalizedToolName?.split('.').at(-1) === 'spawn_agent') return true;
  if (!input || typeof input !== 'object') return false;
  const record = input as Record<string, unknown>;
  return record.run_in_background === true || record.runInBackground === true;
}

export interface SpawnAgentMeta {
  agentId?: string;
  agentPath?: string;
  description?: string;
  identityLabel?: string;
  nickname?: string;
  model?: string;
  reasoningEffort?: string;
}

function getAgentPathName(agentPath: string | undefined): string | undefined {
  if (!agentPath) return undefined;
  return agentPath.split(/[\\/]+/).filter(Boolean).at(-1);
}

/** Parse Codex spawn_agent launch metadata without conflating task_name with an agent UUID. */
export function parseSpawnAgentMeta(
  input: Record<string, unknown>,
  result?: ToolResultBlock | null,
): SpawnAgentMeta {
  const text = extractResultText(result)?.trim();
  let parsed: Record<string, unknown> | null = null;

  if (text && (text.startsWith('{') || text.startsWith('['))) {
    try {
      const candidate = JSON.parse(text);
      if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) {
        parsed = candidate as Record<string, unknown>;
      }
    } catch {
      parsed = null;
    }
  }

  const getString = (...values: unknown[]): string | undefined => {
    for (const value of values) {
      if (typeof value === 'string' && value.trim()) return value.trim();
    }
    return undefined;
  };
  const modelMatch = text?.match(/\(([A-Za-z0-9._:-]+)(?:\s+(low|medium|high|xhigh))?\)/i);
  const agentId = getString(parsed?.agent_id, parsed?.agentId, input.agent_id, input.agentId)
    ?? text?.match(/\b([0-9a-f]{8}-[0-9a-f-]{27})\b/i)?.[1];
  const agentPath = getString(
      parsed?.agent_path,
      parsed?.agentPath,
      parsed?.task_name,
      parsed?.taskName,
      input.agent_path,
      input.agentPath,
      input.task_name,
      input.taskName,
    );
  const inputMessage = getString(input.message);
  const inputDescription = getString(input.description);
  const description = getString(
    parsed?.description,
    inputDescription !== inputMessage ? inputDescription : undefined,
  );
  const nickname = getString(parsed?.nickname, parsed?.name, input.nickname);
  const identityLabel = nickname ?? getAgentPathName(agentPath);
  const model = getString(parsed?.model, input.model) ?? modelMatch?.[1];
  const reasoningEffort = getString(
      parsed?.reasoning_effort,
      parsed?.reasoningEffort,
      input.reasoning_effort,
      input.reasoningEffort,
    ) ?? modelMatch?.[2];
  return {
    ...(agentId && { agentId }),
    ...(agentPath && { agentPath }),
    ...(description && { description }),
    ...(identityLabel && { identityLabel }),
    ...(nickname && { nickname }),
    ...(model && { model }),
    ...(reasoningEffort && { reasoningEffort }),
  };
}

/** True only when a full sidechain transcript has been loaded. */
export function hasSubagentTranscript(history?: Pick<SubagentHistoryResponse, 'messages'>): boolean {
  return Array.isArray(history?.messages);
}

/**
 * Identify historical Codex noise created by an invalid spawn_agent call.
 *
 * The check intentionally requires both missing task identity and an explicit
 * argument parse/validation failure. Valid launches that later fail remain
 * visible in the StatusPanel.
 */
export function isSpawnAgentArgumentFailureNoise(
  input: Record<string, unknown>,
  result?: ToolResultBlock | null,
): boolean {
  const hasTaskIdentity = [input.task_name, input.taskName, input.agent_path, input.agentPath]
    .some((value) => typeof value === 'string' && value.trim().length > 0);
  if (hasTaskIdentity) return false;

  const resultText = extractResultText(result)?.trim();
  if (!resultText) return false;

  return /failed to parse function arguments|invalid function arguments|invalid arguments for (?:tool )?spawn_agent/i.test(resultText)
    || /missing (?:required )?(?:field|property)[^\n]*(?:task_name|taskName)/i.test(resultText);
}

/**
 * Per-agent usage metadata the SDK stamps on the tool_result's toolUseResult
 * field (agentId, totalDurationMs, totalTokens, totalToolUseCount). Shared by
 * AgentGroupBlock and TaskExecutionBlock so the field extraction stays single-
 * sourced. Returns an empty object when there is no usable metadata.
 */
export function parseAgentToolMeta(
  getToolResultRaw: GetToolResultRawFn,
  toolUseId?: string,
): { agentId?: string; totalDurationMs?: number; totalTokens?: number; totalToolUseCount?: number } {
  if (!toolUseId) return {};
  const rawMessage = getToolResultRaw(toolUseId);
  const metadata = rawMessage?.toolUseResult;
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return {};
  const record = metadata as Record<string, unknown>;
  const getString = (value: unknown) => (typeof value === 'string' && value.trim() ? value.trim() : undefined);
  const getNumber = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
  return {
    agentId: getString(record.agentId),
    totalDurationMs: getNumber(record.totalDurationMs),
    totalTokens: getNumber(record.totalTokens),
    totalToolUseCount: getNumber(record.totalToolUseCount),
  };
}
