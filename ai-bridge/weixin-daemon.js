import {
  IlinkRuntime,
  publicErrorCode,
  publicErrorDetails,
} from './services/weixin/ilink-runtime.js';

const MAX_LINE_LENGTH = 256 * 1024;
const MAX_PENDING = 64;
const runtime = new IlinkRuntime();
let queue = Promise.resolve();
let partial = Buffer.alloc(0);
let failed = false;
const pending = new Set();

process.stdout.write(`${JSON.stringify({ type: 'daemon', event: 'ready' })}\n`);

// Bound bytes before decoding or looking for a newline; readline buffers an unlimited line.
process.stdin.on('data', (chunk) => {
  if (failed) return;
  let offset = 0;
  while (offset < chunk.length && !failed) {
    const newline = chunk.indexOf(10, offset);
    const end = newline < 0 ? chunk.length : newline;
    if (partial.length + end - offset > MAX_LINE_LENGTH) {
      failInput('ILINK_DAEMON_REQUEST_INVALID');
      return;
    }
    partial = Buffer.concat([partial, chunk.subarray(offset, end)]);
    if (newline < 0) break;
    let line;
    try {
      line = new TextDecoder('utf-8', { fatal: true }).decode(partial);
    } catch {
      failInput('ILINK_DAEMON_REQUEST_INVALID');
      return;
    }
    partial = Buffer.alloc(0);
    acceptLine(line);
    offset = newline + 1;
  }
});

process.stdin.on('end', () => {
  if (failed) return;
  if (partial.length !== 0) {
    failInput('ILINK_DAEMON_REQUEST_INVALID');
    return;
  }
  Promise.allSettled([...pending]).finally(() => process.exit(0));
});

function failInput(code) {
  if (failed) return;
  failed = true;
  partial = Buffer.alloc(0);
  writeResponse(undefined, undefined, code);
  process.stdin.destroy();
  setImmediate(() => process.exit(1));
}

function acceptLine(line) {
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    writeResponse(undefined, undefined, 'ILINK_DAEMON_REQUEST_INVALID');
    return;
  }
  if (request === null || typeof request !== 'object' || Array.isArray(request)
    || typeof request.id !== 'string' || request.id.length === 0 || request.id.length > 256
    || typeof request.method !== 'string' || !/^[a-z_]{1,64}$/.test(request.method)
    || Object.keys(request).some((key) => !['id', 'method', 'params'].includes(key))) {
    writeResponse(undefined, undefined, 'ILINK_DAEMON_REQUEST_INVALID');
    return;
  }

  if (pending.size >= MAX_PENDING) {
    failInput('ILINK_DAEMON_REQUEST_CAPACITY');
    return;
  }
  const task = request.method === 'send_text'
    ? handleRequest(request)
    : queue.then(() => handleRequest(request));
  if (request.method !== 'send_text') queue = task.catch(() => undefined);
  pending.add(task);
  void task.finally(() => pending.delete(task));
}

async function handleRequest(request) {
  try {
    const result = await dispatch(request.method, request.params);
    writeResponse(request.id, result);
    if (request.method === 'stop') {
      setImmediate(() => process.exit(0));
    }
  } catch (error) {
    writeResponse(request.id, undefined, publicErrorCode(error), publicErrorDetails(error));
  }
}

async function dispatch(method, params) {
  const values = params === undefined ? {} : params;
  if (values === null || typeof values !== 'object' || Array.isArray(values)) {
    throw new Error('invalid params');
  }
  switch (method) {
    case 'start_pairing':
      return runtime.startPairing(values);
    case 'poll_pairing':
      return runtime.pollPairing(values);
    case 'cancel_pairing':
      return runtime.cancelPairing();
    case 'start_transport':
      return runtime.startTransport(values);
    case 'stop_transport':
      return runtime.stopTransport();
    case 'get_updates':
      return runtime.getUpdates(values);
    case 'send_text':
      return runtime.sendText(values);
    case 'status':
      return runtime.status();
    case 'stop':
      return runtime.stopTransport();
    default:
      throw new Error('unsupported method');
  }
}

function writeResponse(id, result, errorCode, errorDetail) {
  const response = { id };
  if (errorCode !== undefined) {
    response.error = { code: errorCode };
    if (errorDetail !== undefined) {
      response.error.detail = errorDetail;
    }
  } else {
    response.result = result;
  }
  const encoded = JSON.stringify(response);
  if (Buffer.byteLength(encoded, 'utf8') > MAX_LINE_LENGTH) {
    process.stdout.write(`${JSON.stringify({ id, error: { code: 'ILINK_DAEMON_RESPONSE_TOO_LARGE' } })}\n`);
  } else {
    process.stdout.write(`${encoded}\n`);
  }
}
