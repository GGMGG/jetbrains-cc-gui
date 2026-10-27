import { execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';
import { hasUnsafeShellMetacharacters } from './command-validator.js';

const execFileAsync = promisify(execFile);
const WINDOWS_SHELL_EXTENSIONS = new Set(['.cmd', '.bat']);
const WINDOWS_EXECUTABLE_EXTENSIONS = new Set(['.cmd', '.bat', '.exe']);
const WINDOWS_SHELL_COMMANDS = new Set(['npx', 'npm', 'pnpm', 'yarn']);

export async function findWindowsCommand(command, env, run = execFileAsync) {
  try {
    // where.exe already applies PATHEXT. One bounded asynchronous lookup keeps
    // slow or offline PATH entries from blocking every MCP verification timer.
    const result = await run('where.exe', [command], {
      env, encoding: 'utf8', windowsHide: true, timeout: 5000, maxBuffer: 64 * 1024
    });
    return typeof result.stdout === 'string'
      ? result.stdout.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
      : [];
  } catch {
    return [];
  }
}

/** Resolve extensionless Windows commands to the executable selected by PATH. */
export async function resolveWindowsCommand(
  command,
  env = process.env,
  platform = process.platform,
  lookup = findWindowsCommand
) {
  if (platform !== 'win32' || typeof command !== 'string') {
    return { command, useShell: false };
  }
  const normalizedCommand = command.trim();
  const extension = path.win32.extname(normalizedCommand).toLowerCase();
  if (WINDOWS_SHELL_EXTENSIONS.has(extension) || extension === '.exe') {
    return { command: normalizedCommand, useShell: WINDOWS_SHELL_EXTENSIONS.has(extension) };
  }
  const candidates = await lookup(normalizedCommand, env);
  const resolvedCommand = candidates.find(candidate =>
    WINDOWS_EXECUTABLE_EXTENSIONS.has(path.win32.extname(candidate).toLowerCase()));
  if (resolvedCommand) {
    return {
      command: resolvedCommand,
      useShell: WINDOWS_SHELL_EXTENSIONS.has(path.win32.extname(resolvedCommand).toLowerCase())
    };
  }
  return {
    command: normalizedCommand,
    useShell: WINDOWS_SHELL_COMMANDS.has(normalizedCommand.toLowerCase())
  };
}

/** Node joins shell command/args without quoting; preserve each cmd.exe token. */
export function prepareStdioLaunch(launch, args) {
  if (!Array.isArray(args) || args.some(arg => typeof arg !== 'string')) {
    throw new Error('Command args must be strings');
  }
  if (!launch.useShell) return { ...launch, args };
  // Reject expansion/quoting syntax as well as chaining. Validate the resolved
  // path too: it can come from a PATH directory rather than the configuration.
  if ([launch.command, ...args].some(hasUnsafeShellMetacharacters)) {
    throw new Error('Command or args contain unsafe shell metacharacters');
  }
  return { ...launch, command: `"${launch.command}"`, args: args.map(arg => `"${arg}"`) };
}
