import { randomInt } from 'node:crypto';
import QRCode from 'qrcode';

import {
  ILINK_REFERENCE_VERSION,
  buildGetUpdatesRequest,
  buildQrCodeRequest,
  buildQrCodeStatusRequest,
  buildSendTextMessageRequest,
  parseBusinessResponse,
  parseGetUpdatesResponse,
  parseIlinkJson,
  parseQrCodeResponse,
  parseQrCodeStatusResponse,
} from './ilink-contract.js';

const DEFAULT_TIMEOUT_MS = 15_000;
const DEFAULT_GET_UPDATES_TIMEOUT_MS = 35_000;
const MAX_RESPONSE_BYTES = 1024 * 1024;

export class IlinkClientError extends Error {
  constructor(code, { httpStatus, ret, errorCode, errorMessage } = {}) {
    super(code);
    this.name = 'IlinkClientError';
    this.code = code;
    this.httpStatus = httpStatus;
    this.ret = ret;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
  }
}

export class IlinkClient {
  constructor({
    enabled = false,
    baseUrl,
    channelVersion = ILINK_REFERENCE_VERSION,
    botAgent = 'CCGUI/1.0.0',
    routeTag,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    getUpdatesTimeoutMs = DEFAULT_GET_UPDATES_TIMEOUT_MS,
    maxResponseBytes = MAX_RESPONSE_BYTES,
    fetchImpl = globalThis.fetch,
    wechatUinFactory = () => randomInt(0, 0x1_0000_0000),
  } = {}) {
    if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 120_000) {
      throw new RangeError('iLink timeout must be between 1 and 120000 milliseconds');
    }
    if (!Number.isInteger(getUpdatesTimeoutMs) || getUpdatesTimeoutMs < 1 || getUpdatesTimeoutMs > 120_000) {
      throw new RangeError('iLink getUpdates timeout must be between 1 and 120000 milliseconds');
    }
    if (!Number.isInteger(maxResponseBytes) || maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_BYTES) {
      throw new RangeError('iLink response size limit is invalid');
    }
    if (typeof fetchImpl !== 'function') {
      throw new TypeError('Fetch API is required');
    }

    this.enabled = enabled === true;
    this.baseUrl = baseUrl;
    this.channelVersion = channelVersion;
    this.botAgent = botAgent;
    this.routeTag = routeTag;
    this.timeoutMs = timeoutMs;
    this.getUpdatesTimeoutMs = getUpdatesTimeoutMs;
    this.maxResponseBytes = maxResponseBytes;
    this.fetchImpl = fetchImpl;
    this.wechatUinFactory = wechatUinFactory;
  }

  async getQrCode({ localTokenList, signal } = {}) {
    this.ensureEnabled();
    const request = buildQrCodeRequest({
      baseUrl: this.baseUrl,
      channelVersion: this.channelVersion,
      routeTag: this.routeTag,
      wechatUin: this.wechatUinFactory(),
      localTokenList,
    });
    const qr = parseQrCodeResponse(await this.#sendJson(request, { signal }));
    if (!qr.qrcodeImageContent.startsWith('https://')) {
      return qr;
    }
    let qrUrl;
    try {
      qrUrl = new URL(qr.qrcodeImageContent);
    } catch {
      throw new IlinkClientError('ILINK_PAIRING_QR_INVALID');
    }
    if (qr.qrcodeImageContent.length > 2048 || qrUrl.hostname !== 'liteapp.weixin.qq.com'
      || qrUrl.username || qrUrl.password || qrUrl.hash) {
      throw new IlinkClientError('ILINK_PAIRING_QR_INVALID');
    }
    try {
      return {
        ...qr,
        qrcodeImageContent: await QRCode.toDataURL(qr.qrcodeImageContent, {
          errorCorrectionLevel: 'M', margin: 2, width: 256,
        }),
      };
    } catch {
      throw new IlinkClientError('ILINK_PAIRING_QR_INVALID');
    }
  }

  async getQrCodeStatus({ baseUrl = this.baseUrl, qrcode, verifyCode, signal } = {}) {
    this.ensureEnabled();
    const request = buildQrCodeStatusRequest({
      baseUrl,
      channelVersion: this.channelVersion,
      routeTag: this.routeTag,
      qrcode,
      verifyCode,
    });
    return parseQrCodeStatusResponse(await this.#sendJson(request, { signal }));
  }

  async getUpdates({ botToken, cursor = '', signal } = {}) {
    this.ensureEnabled();
    const request = buildGetUpdatesRequest({
      baseUrl: this.baseUrl,
      botToken,
      channelVersion: this.channelVersion,
      botAgent: this.botAgent,
      routeTag: this.routeTag,
      wechatUin: this.wechatUinFactory(),
      cursor,
    });
    let response;
    try {
      response = await this.#sendJson(request, {
        signal,
        operation: 'GET_UPDATES',
        timeoutMs: this.getUpdatesTimeoutMs,
      });
    } catch (error) {
      if (error instanceof IlinkClientError && error.code === 'ILINK_GET_UPDATES_TIMEOUT') {
        return parseGetUpdatesResponse({ ret: 0, msgs: [], get_updates_buf: cursor });
      }
      throw error;
    }
    const result = parseGetUpdatesResponse(response);
    if (!result.ok) {
      if (result.ret === -14 || result.errorCode === -14
        || result.errorCode === 401 || result.errorCode === 403) {
        throw new IlinkClientError('ILINK_AUTH_REJECTED', {
          ret: result.ret,
          errorCode: result.errorCode,
          errorMessage: result.errorMessage,
        });
      }
      throw new IlinkClientError('ILINK_GET_UPDATES_REJECTED', {
        ret: result.ret,
        errorCode: result.errorCode,
        errorMessage: result.errorMessage,
      });
    }
    return result;
  }

  async sendText({
    botToken,
    toUserId,
    clientId,
    text,
    contextToken,
    fromUserId,
    runId,
    signal,
  } = {}) {
    this.ensureEnabled();
    const request = buildSendTextMessageRequest({
      baseUrl: this.baseUrl,
      botToken,
      channelVersion: this.channelVersion,
      botAgent: this.botAgent,
      routeTag: this.routeTag,
      wechatUin: this.wechatUinFactory(),
      toUserId,
      clientId,
      text,
      contextToken,
      fromUserId,
      runId,
    });
    const response = await this.#requestJson(request, { signal, operation: 'SEND' });
    let result;
    try {
      result = parseBusinessResponse(response);
    } catch {
      throw new IlinkClientError('ILINK_SEND_RESULT_UNKNOWN');
    }
    if (!result.ok) {
      // Preserve numeric service diagnostics so the gateway can distinguish a
      // rate limit, expired context, or another business rejection without
      // exposing the server's free-form error text.
      throw new IlinkClientError('ILINK_SEND_REJECTED', {
        ret: result.ret,
        errorCode: result.errorCode,
        errorMessage: result.errorMessage,
      });
    }
    return result;
  }

  ensureEnabled() {
    if (!this.enabled) {
      throw new IlinkClientError('ILINK_TRANSPORT_DISABLED');
    }
  }

  async #sendJson(request, { signal, operation = 'READ', timeoutMs = this.timeoutMs } = {}) {
    return this.#requestJson(request, { signal, operation, timeoutMs });
  }

  async #requestJson(request, { signal, operation, timeoutMs = this.timeoutMs } = {}) {
    this.ensureEnabled();
    const controller = new AbortController();
    let requestTimedOut = false;
    const timeout = setTimeout(() => {
      requestTimedOut = true;
      controller.abort();
    }, timeoutMs);
    timeout.unref?.();
    const abortFromCaller = () => controller.abort(signal.reason);
    if (signal?.aborted) {
      abortFromCaller();
    } else {
      signal?.addEventListener('abort', abortFromCaller, { once: true });
    }

    try {
      const response = await this.fetchImpl(request.url, {
        method: request.method,
        headers: request.headers,
        body: request.body === undefined ? undefined : JSON.stringify(request.body),
        signal: controller.signal,
        redirect: 'error',
      });
      if (!response.ok) {
        throw new IlinkClientError(
          operation === 'SEND'
            ? 'ILINK_SEND_RESULT_UNKNOWN'
            : (response.status === 401 || response.status === 403
              ? 'ILINK_AUTH_REJECTED' : 'ILINK_HTTP_STATUS'),
          { httpStatus: response.status },
        );
      }
      const contentType = response.headers.get('content-type') || '';
      const text = await readBoundedResponse(response, this.maxResponseBytes);
      const hasJsonContentType = /^(?:application\/json|application\/[a-z0-9.+-]+\+json)(?:\s*;|$)/i
        .test(contentType.trim());
      try {
        return parseIlinkJson(text);
      } catch {
        if (!hasJsonContentType) {
          throw new IlinkClientError(operation === 'SEND'
            ? 'ILINK_SEND_RESULT_UNKNOWN' : 'ILINK_RESPONSE_CONTENT_TYPE_INVALID');
        }
        throw new IlinkClientError(operation === 'SEND'
          ? 'ILINK_SEND_RESULT_UNKNOWN' : 'ILINK_RESPONSE_JSON_INVALID');
      }
    } catch (error) {
      if (error instanceof IlinkClientError) {
        if (operation === 'SEND' && error.code !== 'ILINK_SEND_RESULT_UNKNOWN') {
          throw new IlinkClientError('ILINK_SEND_RESULT_UNKNOWN', { httpStatus: error.httpStatus });
        }
        throw error;
      }
      if (controller.signal.aborted) {
        if (signal?.aborted) {
          throw new IlinkClientError(operation === 'SEND'
            ? 'ILINK_SEND_RESULT_UNKNOWN' : 'ILINK_REQUEST_ABORTED');
        }
        if (operation === 'GET_UPDATES' && requestTimedOut) {
          throw new IlinkClientError('ILINK_GET_UPDATES_TIMEOUT');
        }
        throw new IlinkClientError(operation === 'SEND'
          ? 'ILINK_SEND_RESULT_UNKNOWN' : 'ILINK_REQUEST_ABORTED');
      }
      throw new IlinkClientError(operation === 'SEND'
        ? 'ILINK_SEND_RESULT_UNKNOWN' : 'ILINK_REQUEST_FAILED');
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener('abort', abortFromCaller);
    }
  }
}

async function readBoundedResponse(response, maxBytes) {
  const contentLength = response.headers.get('content-length');
  if (contentLength !== null && Number(contentLength) > maxBytes) {
    await response.body?.cancel();
    throw new IlinkClientError('ILINK_RESPONSE_TOO_LARGE');
  }

  if (!response.body) {
    return '';
  }

  const reader = response.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel();
        throw new IlinkClientError('ILINK_RESPONSE_TOO_LARGE');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}
