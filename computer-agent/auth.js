
const crypto = require('crypto');

function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;

  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');

  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

class AuthManager {
  constructor() {
    this.pairedComputers = new Map();
    this.activeSessions = new Map();

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

    computer.lastSeen = Date.now();
    return computer;
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
