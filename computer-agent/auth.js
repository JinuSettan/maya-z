const crypto = require('crypto');

class AuthManager {
  constructor() {
    this.pairedComputers = new Map();
    this.activeSessions = new Map();
  }

  pair(computerId, deviceName, pairingToken) {
    const sessionToken = 'sess_' + crypto.randomBytes(16).toString('hex');
    this.pairedComputers.set(computerId, {
      computerId,
      deviceName,
      pairingToken,
      sessionToken,
      lastSeen: Date.now()
    });
    this.activeSessions.set(sessionToken, computerId);
    return sessionToken;
  }

  verifySession(sessionToken) {
    if (!sessionToken || !this.activeSessions.has(sessionToken)) {
      return null;
    }
    const computerId = this.activeSessions.get(sessionToken);
    return this.pairedComputers.get(computerId) || null;
  }

  verifySignature(payload, secretKey, expectedSignature) {
    if (!payload || !secretKey || !expectedSignature) return false;
    const hmac = crypto.createHmac('sha256', secretKey);
    hmac.update(payload);
    const calculated = hmac.digest('hex');
    return crypto.timingSafeEqual(Buffer.from(calculated), Buffer.from(expectedSignature));
  }

  revoke(sessionToken) {
    const computerId = this.activeSessions.get(sessionToken);
    if (computerId) {
      this.pairedComputers.delete(computerId);
      this.activeSessions.delete(sessionToken);
      return true;
    }
    return false;
  }
}

module.exports = new AuthManager();
