import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatClawBotError } from './formatError';
import styles from './style.module.less';

type ClawBotState = 'STOPPED' | 'FOLLOWER' | 'LEADER';
type ClawBotBindingState = 'UNKNOWN' | 'UNBOUND' | 'BOUND';
type ClawBotBindingDiagnostic =
  | 'NONE'
  | 'BINDING_METADATA_UNAVAILABLE'
  | 'BINDING_METADATA_INVALID'
  | 'PASSWORD_SAFE_UNAVAILABLE'
  | 'BOUND_TOKEN_MISSING'
  | 'UNBOUND_TOKEN_PRESENT'
  | 'LEGACY_METADATA_MISSING'
  | 'BINDING_STATUS_UNAVAILABLE';
type ClawBotTransport = 'MOCK' | 'ILINK';
type ClawBotTransportState = 'STOPPED' | 'READY';
type ClawBotPairingState =
  | 'IDLE'
  | 'WAITING_SCAN'
  | 'SCANNED'
  | 'NEED_VERIFY_CODE'
  | 'REDIRECTING'
  | 'BOUND'
  | 'ALREADY_BOUND'
  | 'EXPIRED'
  | 'VERIFY_CODE_BLOCKED'
  | 'TIMEOUT'
  | 'CANCELLED'
  | 'ERROR';

interface ClawBotPairing {
  state: ClawBotPairingState;
  refreshCount: number;
  expiresAt: number | null;
  qrCodeImageContent?: string;
}

interface ClawBotStatus {
  state: ClawBotState;
  transport: ClawBotTransport;
  transportState: ClawBotTransportState;
  transportRecoveryScheduled: boolean;
  sessionCount: number;
  senderAccessCount: number;
  senderAccessStoreAvailable: boolean;
  bindingState: ClawBotBindingState;
  bindingRevision: number;
  bindingDiagnostic: ClawBotBindingDiagnostic;
  pairingState: ClawBotPairingState;
  pairingAttempt: number;
  pairing: ClawBotPairing;
  inboundPollCount: number;
  inboundMessageCount: number;
  inboundDroppedCount: number;
  inboundUncertainCount: number;
  executionJournalAvailable: boolean;
  executionUnknownCount: number;
  inboundLastPollAt: number;
  inboundLastSuccessAt: number;
  inboundPollBackoffMillis: number;
  inboundLastError: string;
  outboundLastSuccessAt: number;
  outboundLastError: string;
  outboundReceiptStoreAvailable: boolean;
  outboundPendingCount: number;
  outboundSentCount: number;
  outboundUnknownCount: number;
  outboundFailedCount: number;
  outboundLatestStatus: string;
  outboundLatestError: string;
}

interface ClawBotOperationResult {
  operation?: string;
  ok?: boolean;
  errorCode?: string;
  authorizedSenders?: string[];
  senderOffset?: number;
  senderHasMore?: boolean;
  senderTotalCount?: number;
  senderLastUsedAt?: Record<string, number>;
}

const PAIRING_STATES: readonly ClawBotPairingState[] = [
  'IDLE',
  'WAITING_SCAN',
  'SCANNED',
  'NEED_VERIFY_CODE',
  'REDIRECTING',
  'BOUND',
  'ALREADY_BOUND',
  'EXPIRED',
  'VERIFY_CODE_BLOCKED',
  'TIMEOUT',
  'CANCELLED',
  'ERROR',
];

const BINDING_DIAGNOSTICS: readonly ClawBotBindingDiagnostic[] = [
  'NONE',
  'BINDING_METADATA_UNAVAILABLE',
  'BINDING_METADATA_INVALID',
  'PASSWORD_SAFE_UNAVAILABLE',
  'BOUND_TOKEN_MISSING',
  'UNBOUND_TOKEN_PRESENT',
  'LEGACY_METADATA_MISSING',
  'BINDING_STATUS_UNAVAILABLE',
];

const ACTIVE_PAIRING_STATES: readonly ClawBotPairingState[] = [
  'WAITING_SCAN',
  'SCANNED',
  'NEED_VERIFY_CODE',
  'REDIRECTING',
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function readNonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function readPairing(
  candidate: Record<string, unknown>,
  topLevelState: unknown,
  topLevelExpiresAt: unknown,
): ClawBotPairing | null {
  const rawPairing = candidate.pairing;
  if (rawPairing !== undefined && !isRecord(rawPairing)) return null;
  const pairing = isRecord(rawPairing) ? rawPairing : {};
  const stateValue = pairing.state ?? topLevelState ?? 'IDLE';
  if (!PAIRING_STATES.includes(stateValue as ClawBotPairingState)) return null;

  const refreshCount = pairing.refreshCount === undefined
    ? 0
    : readNonNegativeInteger(pairing.refreshCount);
  if (refreshCount === null) return null;

  const rawExpiresAt = pairing.expiresAt ?? topLevelExpiresAt ?? null;
  const expiresAt = rawExpiresAt === null
    ? null
    : readNonNegativeInteger(rawExpiresAt);
  if (expiresAt === null && rawExpiresAt !== null) return null;

  const rawQrCode = pairing.qrCodeImageContent;
  if (rawQrCode !== undefined && (typeof rawQrCode !== 'string'
    || rawQrCode.length === 0
    || rawQrCode.length > 32 * 1024
    || !rawQrCode.startsWith('data:image/')
    || /[\u0000-\u001f\u007f]/.test(rawQrCode))) {
    return null;
  }

  return {
    state: stateValue as ClawBotPairingState,
    refreshCount,
    expiresAt,
    ...(rawQrCode === undefined ? {} : { qrCodeImageContent: rawQrCode }),
  };
}

function parseStatus(json: string): ClawBotStatus | null {
  try {
    const value: unknown = JSON.parse(json);
    if (!isRecord(value)) return null;
    const state = value.state;
    const transport = value.transport === undefined ? 'MOCK' : value.transport;
    const transportState = value.transportState === undefined ? 'STOPPED' : value.transportState;
    const transportRecoveryScheduled = value.transportRecoveryScheduled ?? false;
    const sessionCount = readNonNegativeInteger(value.sessionCount);
    const bindingRevision = readNonNegativeInteger(value.bindingRevision);
    const rawBindingDiagnostic = value.bindingDiagnostic;
    let bindingDiagnostic: ClawBotBindingDiagnostic = 'NONE';
    if (rawBindingDiagnostic !== undefined) {
      if (typeof rawBindingDiagnostic === 'string'
        && BINDING_DIAGNOSTICS.includes(rawBindingDiagnostic as ClawBotBindingDiagnostic)) {
        bindingDiagnostic = rawBindingDiagnostic as ClawBotBindingDiagnostic;
      } else if (value.bindingState === 'UNKNOWN') {
        bindingDiagnostic = 'BINDING_STATUS_UNAVAILABLE';
      }
    }
    if (!['STOPPED', 'FOLLOWER', 'LEADER'].includes(state as string)
      || !['MOCK', 'ILINK'].includes(transport as string)
      || !['STOPPED', 'READY'].includes(transportState as string)
      || typeof transportRecoveryScheduled !== 'boolean'
      || sessionCount === null
      || !['UNKNOWN', 'UNBOUND', 'BOUND'].includes(value.bindingState as string)
      || bindingRevision === null) return null;

    const pairing = readPairing(value, value.pairingState, value.pairingExpiresAt);
    if (pairing === null) return null;
    const pairingAttempt = value.pairingAttempt === undefined
      ? 0
      : readNonNegativeInteger(value.pairingAttempt);
    if (pairingAttempt === null) return null;
    const inboundPollCount = readNonNegativeInteger(value.inboundPollCount ?? 0);
    const inboundMessageCount = readNonNegativeInteger(value.inboundMessageCount ?? 0);
    const inboundDroppedCount = readNonNegativeInteger(value.inboundDroppedCount ?? 0);
    const inboundUncertainCount = readNonNegativeInteger(value.inboundUncertainCount ?? 0);
    const executionJournalAvailable = value.executionJournalAvailable ?? true;
    const executionUnknownCount = readNonNegativeInteger(value.executionUnknownCount ?? 0);
    const inboundLastPollAt = readNonNegativeInteger(value.inboundLastPollAt ?? 0);
    const inboundLastSuccessAt = readNonNegativeInteger(value.inboundLastSuccessAt ?? 0);
    const inboundPollBackoffMillis = readNonNegativeInteger(value.inboundPollBackoffMillis ?? 0);
    const senderAccessCount = readNonNegativeInteger(value.senderAccessCount ?? 0);
    const senderAccessStoreAvailable = value.senderAccessStoreAvailable ?? true;
    const inboundLastError = value.inboundLastError === undefined ? '' : value.inboundLastError;
    const outboundLastSuccessAt = readNonNegativeInteger(value.outboundLastSuccessAt ?? 0);
    const outboundLastError = value.outboundLastError === undefined ? '' : value.outboundLastError;
    const outboundReceiptStoreAvailable = value.outboundReceiptStoreAvailable ?? true;
    const outboundPendingCount = readNonNegativeInteger(value.outboundPendingCount ?? 0);
    const outboundSentCount = readNonNegativeInteger(value.outboundSentCount ?? 0);
    const outboundUnknownCount = readNonNegativeInteger(value.outboundUnknownCount ?? 0);
    const outboundFailedCount = readNonNegativeInteger(value.outboundFailedCount ?? 0);
    const outboundLatestStatus = value.outboundLatestStatus ?? '';
    const outboundLatestError = value.outboundLatestError ?? '';
    if (inboundPollCount === null || inboundMessageCount === null || inboundDroppedCount === null
      || inboundUncertainCount === null
      || typeof executionJournalAvailable !== 'boolean'
      || executionUnknownCount === null
      || inboundLastPollAt === null || inboundLastSuccessAt === null || inboundPollBackoffMillis === null
      || senderAccessCount === null
      || typeof senderAccessStoreAvailable !== 'boolean'
      || typeof inboundLastError !== 'string'
      || outboundLastSuccessAt === null || typeof outboundLastError !== 'string'
      || typeof outboundReceiptStoreAvailable !== 'boolean'
      || outboundPendingCount === null || outboundSentCount === null || outboundUnknownCount === null
      || outboundFailedCount === null || typeof outboundLatestStatus !== 'string'
      || typeof outboundLatestError !== 'string') return null;

    return {
      state: state as ClawBotState,
      transport: transport as ClawBotTransport,
      transportState: transportState as ClawBotTransportState,
      transportRecoveryScheduled,
      sessionCount,
      bindingState: value.bindingState as ClawBotBindingState,
      bindingRevision,
      bindingDiagnostic,
      pairingState: pairing.state,
      pairingAttempt,
      pairing,
      inboundPollCount,
      inboundMessageCount,
      inboundDroppedCount,
      inboundUncertainCount,
      executionJournalAvailable,
      executionUnknownCount,
      inboundLastPollAt,
      inboundLastSuccessAt,
      inboundPollBackoffMillis,
      senderAccessCount,
      senderAccessStoreAvailable,
      inboundLastError,
      outboundLastSuccessAt,
      outboundLastError,
      outboundReceiptStoreAvailable,
      outboundPendingCount,
      outboundSentCount,
      outboundUnknownCount,
      outboundFailedCount,
      outboundLatestStatus,
      outboundLatestError,
    };
  } catch {
    return null;
  }
}

function parseOperation(json: string): ClawBotOperationResult | null {
  try {
    const value: unknown = JSON.parse(json);
    if (!isRecord(value)) return null;
    const authorizedSenders = value.authorizedSenders;
    if (authorizedSenders !== undefined && (!Array.isArray(authorizedSenders)
      || authorizedSenders.length > 8
      || authorizedSenders.some((sender) => typeof sender !== 'string'
        || sender.length === 0 || sender.length > 512 || /[\u0000-\u001f\u007f]/.test(sender)))) {
      return null;
    }
    const senderOffset = value.senderOffset === undefined
      ? undefined : readNonNegativeInteger(value.senderOffset);
    const senderTotalCount = value.senderTotalCount === undefined
      ? undefined : readNonNegativeInteger(value.senderTotalCount);
    const rawLastUsedAt = value.senderLastUsedAt;
    if (rawLastUsedAt !== undefined && !isRecord(rawLastUsedAt)) return null;
    const senderLastUsedAt: Record<string, number> = {};
    if (isRecord(rawLastUsedAt)) {
      for (const [senderId, timestamp] of Object.entries(rawLastUsedAt)) {
        const parsedTimestamp = readNonNegativeInteger(timestamp);
        if (senderId.length === 0 || senderId.length > 512
          || /[\u0000-\u001f\u007f]/.test(senderId) || parsedTimestamp === null) return null;
        senderLastUsedAt[senderId] = parsedTimestamp;
      }
    }
    if (senderOffset === null || senderTotalCount === null
      || (value.senderHasMore !== undefined && typeof value.senderHasMore !== 'boolean')) return null;
    return {
      operation: typeof value.operation === 'string' ? value.operation : undefined,
      ok: typeof value.ok === 'boolean' ? value.ok : undefined,
      errorCode: typeof value.errorCode === 'string' ? value.errorCode : undefined,
      ...(Array.isArray(authorizedSenders) ? { authorizedSenders: authorizedSenders as string[] } : {}),
      ...(senderOffset === undefined ? {} : { senderOffset }),
      ...(value.senderHasMore === undefined ? {} : { senderHasMore: value.senderHasMore as boolean }),
      ...(senderTotalCount === undefined ? {} : { senderTotalCount }),
      ...(rawLastUsedAt === undefined ? {} : { senderLastUsedAt }),
    };
  } catch {
    return null;
  }
}

function maskSenderId(senderId: string): string {
  const characters = Array.from(senderId);
  if (characters.length <= 8) return `${characters[0] ?? ''}...`;
  return `${characters.slice(0, 6).join('')}...${characters.slice(-2).join('')}`;
}

export default function ClawBotSection() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<ClawBotStatus | null>(null);
  const [verifyCode, setVerifyCode] = useState('');
  const [senderId, setSenderId] = useState('');
  const [authorizedSenders, setAuthorizedSenders] = useState<string[]>([]);
  const [showAuthorizedSenders, setShowAuthorizedSenders] = useState(false);
  const [senderOffset, setSenderOffset] = useState(0);
  const [senderHasMore, setSenderHasMore] = useState(false);
  const [senderTotalCount, setSenderTotalCount] = useState(0);
  const [senderLastUsedAt, setSenderLastUsedAt] = useState<Record<string, number>>({});
  const [busyOperation, setBusyOperation] = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.sendToJava?.('get_clawbot_status:');
  }, []);

  const sendOperation = useCallback((type: string, payload: Record<string, string | number> = {}) => {
    if (busyOperation !== null) return;
    if (window.sendToJava === undefined) {
      setErrorCode('CLAWBOT_BRIDGE_UNAVAILABLE');
      return;
    }
    setErrorCode(null);
    setBusyOperation(type);
    window.sendToJava(`${type}:${JSON.stringify(payload)}`);
  }, [busyOperation]);

  useEffect(() => {
    const previousStatus = window.onClawBotStatus;
    const previousOperation = window.onClawBotOperation;
    const statusCallback = (json: string) => {
      const next = parseStatus(json);
      if (next) setStatus(next);
    };
    const operationCallback = (json: string) => {
      const result = parseOperation(json);
      if (result === null) return;
      setBusyOperation(null);
      if (result.ok === false) {
        setErrorCode(result.errorCode ?? 'CLAWBOT_OPERATION_FAILED');
      } else {
        setErrorCode(null);
        if (result.operation === 'allow_sender' || result.operation === 'revoke_sender') {
          setSenderId('');
          setShowAuthorizedSenders(true);
          setSenderOffset(0);
          if (window.sendToJava !== undefined) {
            setBusyOperation('clawbot_list_senders');
            window.sendToJava('clawbot_list_senders:{"offset":0}');
          }
        }
        if (result.operation === 'list_senders' && result.authorizedSenders !== undefined) {
          setAuthorizedSenders(result.authorizedSenders);
          setSenderLastUsedAt(result.senderLastUsedAt ?? {});
          setSenderOffset(result.senderOffset ?? 0);
          setSenderHasMore(result.senderHasMore ?? false);
          setSenderTotalCount(result.senderTotalCount ?? result.authorizedSenders.length);
        }
      }
    };
    window.onClawBotStatus = statusCallback;
    window.onClawBotOperation = operationCallback;
    refresh();
    return () => {
      if (window.onClawBotStatus === statusCallback) window.onClawBotStatus = previousStatus;
      if (window.onClawBotOperation === operationCallback) window.onClawBotOperation = previousOperation;
    };
  }, [refresh]);

  useEffect(() => {
    const pairingState = status?.pairingState;
    if (pairingState === undefined || !ACTIVE_PAIRING_STATES.includes(pairingState)) return undefined;
    const timer = window.setInterval(() => {
      if (busyOperation !== null) return;
      const trimmedVerifyCode = verifyCode.trim();
      if (pairingState === 'NEED_VERIFY_CODE' && trimmedVerifyCode.length === 0) return;
      sendOperation('clawbot_poll_pairing', trimmedVerifyCode.length > 0
        ? { verifyCode: trimmedVerifyCode }
        : {});
    }, 2000);
    return () => window.clearInterval(timer);
  }, [busyOperation, sendOperation, status?.pairingState, verifyCode]);

  const stateKey = status?.state.toLowerCase() ?? 'unknown';
  const bindingStateKey = status?.bindingState.toLowerCase() ?? 'unknown';
  const bindingDiagnostic = status?.bindingDiagnostic ?? 'NONE';
  const pairingState = status?.pairingState ?? 'IDLE';
  const pairingStateKey = pairingState.toLowerCase();
  const isPairingActive = ACTIVE_PAIRING_STATES.includes(pairingState);
  const isBusy = busyOperation !== null;

  return (
    <section className={styles.section}>
      <h3 className={styles.title}>{t('settings.clawBot.title')}</h3>
      <p className={styles.description}>{t('settings.clawBot.description')}</p>

      <div className={styles.notice} role="note">
        <span className="codicon codicon-info" />
        <span>{t('settings.clawBot.ilinkNotice')}</span>
      </div>

      <div className={styles.card}>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.gatewayState')}</span>
          <span className={`${styles.badge} ${styles[stateKey]}`}>
            {t(`settings.clawBot.states.${stateKey}`)}
          </span>
        </div>
        {status && !status.senderAccessStoreAvailable && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.senderAccessTitle')}</span>
            <span className={styles.error}>{t('settings.clawBot.senderAccessStoreUnavailable')}</span>
          </div>
        )}
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.transport')}</span>
          <span>{t(status?.transport === 'ILINK'
            ? 'settings.clawBot.ilinkTransport'
            : 'settings.clawBot.mockTransport')}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.transportState')}</span>
          <span>{t(status?.transportState === 'READY'
            ? 'settings.clawBot.ready'
            : 'settings.clawBot.stopped')}</span>
        </div>
        {status?.transportRecoveryScheduled && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.transportRecovery')}</span>
            <span className={styles.warning}>{t('settings.clawBot.transportRecoveryScheduled')}</span>
          </div>
        )}
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.sessionCount')}</span>
          <span>{status?.sessionCount ?? '\u2014'}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.senderAccessCount')}</span>
          <span>{status?.senderAccessCount ?? 0}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.bindingState')}</span>
          <span className={`${styles.badge} ${styles[bindingStateKey]}`}>
            {t(`settings.clawBot.bindingStates.${bindingStateKey}`)}
          </span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.bindingRevision')}</span>
          <span>{status?.bindingRevision ?? '\u2014'}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.pairingState')}</span>
          <span>{t(`settings.clawBot.pairingStates.${pairingStateKey}`)}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.pairingRefreshCount')}</span>
          <span>{status?.pairing.refreshCount ?? 0}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.inboundCounts')}</span>
          <span>{status?.inboundPollCount ?? 0} / {status?.inboundMessageCount ?? 0}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.inboundLastSuccess')}</span>
          <span>{status?.inboundLastSuccessAt
            ? new Date(status.inboundLastSuccessAt).toLocaleTimeString(undefined, {
              hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
            })
            : t('settings.clawBot.never')}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.inboundRetryDelay')}</span>
          <span>{Math.ceil((status?.inboundPollBackoffMillis ?? 0) / 1000)} s</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.inboundDropped')}</span>
          <span>{status?.inboundDroppedCount ?? 0}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.inboundUncertain')}</span>
          <span className={status?.inboundUncertainCount ? styles.error : undefined}>
            {status?.inboundUncertainCount ?? 0}
          </span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.executionUnknown')}</span>
          <span className={status?.executionUnknownCount ? styles.error : undefined}>
            {status?.executionUnknownCount ?? 0}
          </span>
        </div>
        {status && !status.executionJournalAvailable && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.executionJournal')}</span>
            <span className={styles.error}>{t('settings.clawBot.executionJournalUnavailable')}</span>
          </div>
        )}
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.outboundReceipts')}</span>
          <span>{status?.outboundSentCount ?? 0} / {status?.outboundUnknownCount ?? 0} / {status?.outboundFailedCount ?? 0}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.label}>{t('settings.clawBot.outboundLastSuccess')}</span>
          <span>{status?.outboundLastSuccessAt
            ? new Date(status.outboundLastSuccessAt).toLocaleTimeString(undefined, {
              hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
            })
            : t('settings.clawBot.never')}</span>
        </div>
        {status?.outboundPendingCount ? (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.outboundPending')}</span>
            <span>{status.outboundPendingCount}</span>
          </div>
        ) : null}
        {status && !status.outboundReceiptStoreAvailable && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.outboundReceiptStore')}</span>
            <span className={styles.error}>{t('settings.clawBot.outboundReceiptStoreUnavailable')}</span>
          </div>
        )}
        {status?.outboundLatestError && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.outboundLatestError')}</span>
            <span className={styles.error}>{formatClawBotError(status.outboundLatestError, t)}</span>
          </div>
        )}
        {status?.outboundLastError && status.outboundLastError !== status.outboundLatestError && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.outboundLastError')}</span>
            <span className={styles.error}>{formatClawBotError(status.outboundLastError, t)}</span>
          </div>
        )}
        {status?.inboundLastError && (
          <div className={styles.row}>
            <span className={styles.label}>{t('settings.clawBot.inboundLastError')}</span>
            <span className={styles.error}>{formatClawBotError(status.inboundLastError, t)}</span>
          </div>
        )}
      </div>

      {isPairingActive && (
        <div className={styles.pairingPanel}>
          <p className={styles.pairingNotice}>{t('settings.clawBot.pairingNotice')}</p>
          <div className={styles.pairingContent}>
            {status?.pairing.qrCodeImageContent && (
              <img
                className={styles.qrCode}
                src={status.pairing.qrCodeImageContent}
                alt={t('settings.clawBot.qrAlt')}
              />
            )}
            <div className={styles.pairingActions}>
              {pairingState === 'NEED_VERIFY_CODE' && (
                <label className={styles.verifyField}>
                  <span>{t('settings.clawBot.verifyCode')}</span>
                  <input
                    value={verifyCode}
                    maxLength={256}
                    placeholder={t('settings.clawBot.verifyCodePlaceholder')}
                    onChange={(event) => setVerifyCode(event.target.value)}
                  />
                </label>
              )}
              <div className={styles.actionRow}>
                      <button
                  type="button"
                  className={styles.primaryButton}
                  disabled={isBusy || (pairingState === 'NEED_VERIFY_CODE' && verifyCode.trim().length === 0)}
                  onClick={() => sendOperation('clawbot_poll_pairing', verifyCode.trim().length > 0
                    ? { verifyCode: verifyCode.trim() }
                    : {})}
                >
                  <span className="codicon codicon-refresh" />
                  <span>{t('settings.clawBot.poll')}</span>
                </button>
                <button
                  type="button"
                  className={styles.secondaryButton}
                  disabled={isBusy}
                  onClick={() => sendOperation('clawbot_cancel_pairing')}
                >
                  <span className="codicon codicon-close" />
                  <span>{t('settings.clawBot.cancel')}</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {status?.bindingState === 'BOUND' && (
        <>
          <div className={styles.senderAccess}>
            <h4>{t('settings.clawBot.senderAccessTitle')}</h4>
            <p>{t('settings.clawBot.senderAccessNotice')}</p>
            <div className={styles.senderAccessControls}>
              <input
                value={senderId}
                maxLength={512}
                autoComplete="off"
                aria-label={t('settings.clawBot.senderId')}
                placeholder={t('settings.clawBot.senderIdPlaceholder')}
                onChange={(event) => setSenderId(event.target.value)}
              />
              <button
                type="button"
                className={styles.primaryButton}
                disabled={isBusy || senderId.trim().length === 0}
                onClick={() => sendOperation('clawbot_allow_sender', { senderId: senderId.trim() })}
              >
                <span className="codicon codicon-shield" />
                <span>{t('settings.clawBot.allowSender')}</span>
              </button>
              <button
                type="button"
                className={styles.secondaryButton}
                disabled={isBusy}
                onClick={() => {
                  setShowAuthorizedSenders(true);
                  sendOperation('clawbot_list_senders', { offset: 0 });
                }}
              >
                <span className="codicon codicon-list-ordered" />
                <span>{t('settings.clawBot.manageSenders')}</span>
              </button>
            </div>
            {showAuthorizedSenders && (
              <div className={styles.senderList}>
                <div className={styles.senderListHeader}>
                  <span>{t('settings.clawBot.authorizedSenderList', { count: senderTotalCount })}</span>
                  <div className={styles.senderListPages}>
                    <button
                      type="button"
                      className={styles.secondaryButton}
                      disabled={isBusy || senderOffset === 0}
                      onClick={() => sendOperation('clawbot_list_senders', {
                        offset: Math.max(0, senderOffset - 8),
                      })}
                    >
                      {t('settings.clawBot.previousSenders')}
                    </button>
                    <button
                      type="button"
                      className={styles.secondaryButton}
                      disabled={isBusy || !senderHasMore}
                      onClick={() => sendOperation('clawbot_list_senders', {
                        offset: senderOffset + 8,
                      })}
                    >
                      {t('settings.clawBot.nextSenders')}
                    </button>
                  </div>
                </div>
                {authorizedSenders.length === 0 ? (
                  <p className={styles.senderListEmpty}>{t('settings.clawBot.noAuthorizedSenders')}</p>
                ) : authorizedSenders.map((authorizedSender) => (
                  <div className={styles.senderListRow} key={authorizedSender}>
                    <div className={styles.senderDetails}>
                      <code title={t('settings.clawBot.senderIdRedacted')}>{maskSenderId(authorizedSender)}</code>
                      <span className={styles.senderLastUsed}>
                        {t('settings.clawBot.senderLastUsed')}: {senderLastUsedAt[authorizedSender]
                          ? new Date(senderLastUsedAt[authorizedSender]).toLocaleString()
                          : t('settings.clawBot.never')}
                      </span>
                    </div>
                    <button
                      type="button"
                      className={styles.secondaryButton}
                      disabled={isBusy}
                      onClick={() => sendOperation('clawbot_revoke_sender', { senderId: authorizedSender })}
                    >
                      {t('settings.clawBot.revokeListedSender')}
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}

      {status?.bindingState === 'UNKNOWN' && (
        <div className={styles.notice} role="note">
          <span className="codicon codicon-info" />
          <span>
            {t('settings.clawBot.bindingUnknownNotice')}
            {bindingDiagnostic !== 'NONE' && (
              <span className={styles.noticeDetail}>
                {t(`settings.clawBot.bindingDiagnostics.${bindingDiagnostic}`)} ({bindingDiagnostic})
              </span>
            )}
          </span>
        </div>
      )}

      {errorCode !== null && (
        <p className={styles.error} role="alert">
          {formatClawBotError(errorCode, t)}
        </p>
      )}

      <div className={styles.footerActions}>
        {status?.bindingState === 'UNBOUND' && !isPairingActive && (
          <button
            type="button"
            className={styles.primaryButton}
            disabled={isBusy}
            onClick={() => sendOperation('clawbot_start_pairing')}
          >
            <span className="codicon codicon-link" />
            <span>{t(isBusy && busyOperation === 'clawbot_start_pairing'
              ? 'settings.clawBot.binding'
              : 'settings.clawBot.bind')}</span>
          </button>
        )}
        {status?.bindingState === 'BOUND' && status.transportState !== 'READY' && (
          <button
            type="button"
            className={styles.primaryButton}
            disabled={isBusy}
            onClick={() => sendOperation('clawbot_start_transport')}
          >
            <span className="codicon codicon-play" />
            <span>{t('settings.clawBot.startTransport')}</span>
          </button>
        )}
        {(status?.bindingState === 'BOUND' || status?.bindingState === 'UNKNOWN') && (
          <button
            type="button"
            className={styles.dangerButton}
            disabled={isBusy}
            onClick={() => sendOperation('clawbot_unbind')}
          >
            <span className="codicon codicon-trash" />
            <span>{t('settings.clawBot.unbind')}</span>
          </button>
        )}
        <button type="button" className={styles.refreshButton} onClick={refresh} disabled={isBusy}>
          <span className="codicon codicon-refresh" />
          <span>{t('settings.clawBot.refresh')}</span>
        </button>
      </div>
    </section>
  );
}
