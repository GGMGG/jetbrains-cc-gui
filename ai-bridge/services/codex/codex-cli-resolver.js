/**
 * Codex CLI resolver.
 *
 * Locates the installed `codex` CLI like the other headless providers.
 * External installations win; the old dependency directory is a read-only
 * compatibility fallback, never an install/update/uninstall target.
 *
 * Official npm layout (see bin/codex.js of @openai/codex):
 *  - platform package: @openai/codex-<platform>-<arch> holds
 *    vendor/<target-triple>/codex/codex[.exe]
 *  - historical layouts: vendor/<triple>/bin/codex.exe and
 *    vendor/<triple>/codex[.exe]
 *  - fallback: the main package's own vendor/<target-triple> tree
 *  - companion launcher: <main package>/bin/codex.js (node script)
 */

import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { resolveCliPath } from '../../utils/cli-path.js';

export const CODEX_CLI_ENV_KEYS = ['CODEX_BIN', 'CODEX_PATH', 'CODEX_CLI_PATH'];

/** Uses the same PATH, home and login-shell discovery as OpenCode. */
export function discoverCodexCli() {
  const path = resolveCliPath({ binaryName: 'codex', homeCandidates: [
    '{home}/.local/bin/{bin}', '{home}/.cargo/bin/{bin}',
    '{home}/.codex/bin/{bin}',
  ] });
  return path === 'codex' ? null : path;
}

function isCliFile(path) {
  try { return statSync(path).isFile(); } catch { return false; }
}

const TARGET_TRIPLES = {
  'linux:x64': 'x86_64-unknown-linux-musl',
  'linux:arm64': 'aarch64-unknown-linux-musl',
  'android:x64': 'x86_64-unknown-linux-musl',
  'android:arm64': 'aarch64-unknown-linux-musl',
  'darwin:x64': 'x86_64-apple-darwin',
  'darwin:arm64': 'aarch64-apple-darwin',
  'win32:x64': 'x86_64-pc-windows-msvc',
  'win32:arm64': 'aarch64-pc-windows-msvc',
};

const PLATFORM_PACKAGE_PREFIX = {
  linux: 'linux',
  android: 'linux',
  darwin: 'darwin',
  win32: 'win32',
};

const ARCH_SUFFIX = { x64: 'x64', arm64: 'arm64' };

function binaryName(platform) {
  return platform === 'win32' ? 'codex.exe' : 'codex';
}

export function targetTripleFor(platform, arch) {
  return TARGET_TRIPLES[`${platform}:${arch}`] || null;
}

/**
 * Candidate relative layouts of the native binary inside a vendor root.
 * Ordered newest-first; the historical `bin/` layout stays last.
 */
export function vendorBinaryCandidates(vendorRoot, triple, platform) {
  const name = binaryName(platform);
  return [
    join(vendorRoot, triple, 'codex', name),
    join(vendorRoot, triple, name),
    join(vendorRoot, triple, 'bin', name),
  ];
}

function platformPackageName(platform, arch) {
  const plat = PLATFORM_PACKAGE_PREFIX[platform];
  const arc = ARCH_SUFFIX[arch];
  if (!plat || !arc) {
    return null;
  }
  return `@openai/codex-${plat}-${arc}`;
}

function findNativeBinary(codexPackageDir, triple, platform, arch) {
  const platformPackage = platformPackageName(platform, arch);
  if (!platformPackage) {
    return null;
  }
  // Platform packages appear in two places depending on the install:
  // nested under the main package (optionalDependencies of @openai/codex),
  // or hoisted into the node_modules root that holds the @openai scope.
  const nodeModulesRoot = dirname(dirname(codexPackageDir));
  const scanRoots = [join(codexPackageDir, 'node_modules'), nodeModulesRoot];
  for (const scanRoot of scanRoots) {
    const platformDir = join(scanRoot, platformPackage);
    for (const candidate of vendorBinaryCandidates(
      join(platformDir, 'vendor'), triple, platform)) {
      if (isCliFile(candidate)) {
        return candidate;
      }
    }
  }
  for (const candidate of vendorBinaryCandidates(
    join(codexPackageDir, 'vendor'), triple, platform)) {
    if (isCliFile(candidate)) {
      return candidate;
    }
  }
  return null;
}

function readPackageVersion(packageDir) {
  const packageJsonPath = join(packageDir, 'package.json');
  if (!existsSync(packageJsonPath)) {
    return null;
  }
  try {
    const pkg = JSON.parse(readFileSync(packageJsonPath, 'utf8'));
    return typeof pkg.version === 'string' ? pkg.version : null;
  } catch {
    return null;
  }
}

/**
 * Resolve the managed CLI inside a dependency root.
 * @param {string} depsRoot e.g. <deps>/codex-sdk/node_modules
 * @returns {object|null} {packageDir, kind, command, version} or null
 */
export function resolveManagedCodexCli(
  depsRoot,
  { platform = process.platform, arch = process.arch, nodePath = 'node' } = {}
) {
  const triple = targetTripleFor(platform, arch);
  if (!depsRoot || !triple) {
    return null;
  }
  // New installs place @openai/codex directly under node_modules; legacy
  // @openai/codex-sdk installs hoist it beside the SDK or nest it inside.
  const packageCandidates = [
    join(depsRoot, '@openai', 'codex'),
    join(depsRoot, '@openai', 'codex-sdk', 'node_modules', '@openai', 'codex'),
    join(depsRoot, '@openai', 'codex-sdk'),
  ];
  for (const packageDir of packageCandidates) {
    if (!existsSync(packageDir)) {
      continue;
    }
    const binary = findNativeBinary(packageDir, triple, platform, arch);
    if (binary) {
      return {
        packageDir,
        kind: 'native-binary',
        command: [binary],
        version: readPackageVersion(packageDir),
      };
    }
    const launcher = join(packageDir, 'bin', 'codex.js');
    if (packageDir !== join(depsRoot, '@openai', 'codex-sdk') && isCliFile(launcher)) {
      return {
        packageDir,
        kind: 'node-launcher',
        command: [nodePath, launcher],
        version: readPackageVersion(packageDir),
      };
    }
  }
  return null;
}

/**
 * Resolve the Codex CLI from external installations, then an existing legacy directory.
 *
 * @param {object} opts
 * @param {string|null} [opts.explicitPath] user-configured external CLI path
 * @param {string|null} [opts.depsRoot] <deps>/codex-sdk/node_modules
 * @param {string} [opts.platform] override for tests
 * @param {string} [opts.arch] override for tests
 * @param {string} [opts.nodePath] node executable for the companion launcher
 * @returns {object} resolution result with status resolved|unresolved
 */
export function resolveCodexCli({
  explicitPath = null,
  depsRoot = null,
  platform = process.platform,
  arch = process.arch,
  nodePath = 'node',
  env = process.env,
  discoverCli = discoverCodexCli,
} = {}) {
  explicitPath ||= CODEX_CLI_ENV_KEYS.map((key) => env[key]).find((value) => value?.trim()) ?? null;
  if (explicitPath && explicitPath.trim().length > 0) {
    const trimmed = explicitPath.trim();
    if (!isCliFile(trimmed)) {
      return {
        status: 'unresolved',
        source: 'explicit',
        reason: `configured Codex CLI path does not exist or is not a file: ${trimmed}`,
      };
    }
    return {
      status: 'resolved',
      source: 'explicit',
      kind: 'native-binary',
      command: [trimmed],
      version: null,
    };
  }

  const discovered = discoverCli();
  if (discovered && isCliFile(discovered)) {
    return { status: 'resolved', source: 'external', kind: 'native-binary', command: [discovered], version: null };
  }

  const managed = resolveManagedCodexCli(depsRoot, { platform, arch, nodePath });
  if (managed) {
    return { status: 'resolved', source: 'legacy', ...managed };
  }

  return {
    status: 'unresolved',
    source: 'external',
    reason: 'Codex CLI not found; install the official CLI and re-check Settings > Provider Management > CLI',
  };
}
