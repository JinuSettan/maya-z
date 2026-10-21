
const crypto = require('crypto');

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;

  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');

  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

const requestedTtlHours = Number(process.env.TESSA_SESSION_TTL_HOURS || 12);
const SESSION_TTL_HOURS = Number.isFinite(requestedTtlHours)
  ? Math.min(Math.max(requestedTtlHours, 1), 168)
  : 12;
const SESSION_TTL_MS = SESSION_TTL_HOURS * 3600 * 1000;
const REQUEST_WINDOW_MS = 60 * 1000;

class AuthManager {
  constructor() {
    this.pairedComputers = new Map();
    this.activeSessions = new Map();
    this.seenNonces = new Map();

    this.expectedPairingToken =
      process.env.TESSA_PAIRING_TOKEN ||
      crypto.randomBytes(24).toString('hex');

    if (!process.env.TESSA_PAIRING_TOKEN) {
      console.log('\n======================================');
      console.log('TESSA PAIRING TOKEN (keep it private):');
      console.log(this.expectedPairingToken);
      console.log('======================================\n');
    }
  }

  isValidPairingToken(token) {
    return safeEqual(token, this.expectedPairingToken);
  }

  pair(computerId, deviceName, pairingToken) {
    if (!this.isValidPairingToken(pairingToken)) {
      throw new Error('Invalid pairing token');
    }

    if (
      typeof computerId !== 'string' ||
      !/^[a-zA-Z0-9._-]{1,100}$/.test(computerId)
    ) {
      throw new Error('Invalid computer ID');
    }

    const sessionToken =
      'sess_' + crypto.randomBytes(32).toString('hex');

    const computer = {
      computerId,
      deviceName: String(deviceName || 'Host Computer').slice(0, 100),
      sessionToken,

      // Used internally to verify signed requests.
      // Never return this field in an API response.
      pairingToken,

      createdAt: Date.now(),
      lastSeen: Date.now()
    };

    const previous = this.pairedComputers.get(computerId);

    if (previous) {
      this.activeSessions.delete(previous.sessionToken);
    }

    this.pairedComputers.set(computerId, computer);
    this.activeSessions.set(sessionToken, computerId);

    return sessionToken;
  }

  verifySession(sessionToken) {
    if (
      typeof sessionToken !== 'string' ||
      !this.activeSessions.has(sessionToken)
    ) {
      return null;
    }

    const computerId = this.activeSessions.get(sessionToken);
    const computer = this.pairedComputers.get(computerId);

    if (!computer || computer.sessionToken !== sessionToken) {
      this.activeSessions.delete(sessionToken);
      return null;
    }

    if (Date.now() - computer.createdAt > SESSION_TTL_MS) {
      this.activeSessions.delete(sessionToken);
      this.pairedComputers.delete(computerId);
      return null;
    }

    computer.lastSeen = Date.now();
    return computer;
  }

  // Replay-protected verification. The signature covers
  // `${timestamp}.${nonce}.${rawBody}`; a request is accepted only if the
  // timestamp is recent and the nonce was never used before.
  verifySignedRequest(rawBody, secretKey, { timestamp, nonce, signature }, now = Date.now()) {
    if (
      typeof timestamp !== 'string' || !/^\d{10,16}$/.test(timestamp) ||
      typeof nonce !== 'string' || !/^[A-Za-z0-9_-]{16,64}$/.test(nonce)
    ) {
      return false;
    }

    if (Math.abs(now - Number(timestamp)) > REQUEST_WINDOW_MS) return false;

    // Drop expired nonces so the cache stays small.
    for (const [key, expiry] of this.seenNonces) {
      if (expiry < now) this.seenNonces.delete(key);
    }

    const nonceKey = `${secretKey.slice(0, 8)}:${nonce}`;
    if (this.seenNonces.has(nonceKey)) return false;

    const ok = this.verifySignature(`${timestamp}.${nonce}.${rawBody}`, secretKey, signature);
    if (!ok) return false;

    this.seenNonces.set(nonceKey, now + 2 * REQUEST_WINDOW_MS);
    return true;
  }

  verifySignature(payload, secretKey, expectedSignature) {
    if (
      typeof payload !== 'string' ||
      typeof secretKey !== 'string' ||
      typeof expectedSignature !== 'string' ||
      !/^[a-f0-9]{64}$/i.test(expectedSignature)
    ) {
      return false;
    }

    const calculated = crypto
      .createHmac('sha256', secretKey)
      .update(payload)
      .digest('hex');

    return safeEqual(calculated, expectedSignature.toLowerCase());
  }

  revoke(sessionToken) {
    if (typeof sessionToken !== 'string') return false;

    const computerId = this.activeSessions.get(sessionToken);
    if (!computerId) return false;

    this.activeSessions.delete(sessionToken);
    this.pairedComputers.delete(computerId);

    return true;
  }
}

module.exports = new AuthManager();
