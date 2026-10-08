'use strict';

const crypto = require('node:crypto');

// Demo only. The salt is a fixed, non-secret string: it exists so the state file
// never stores a raw demo person identifier, while still letting the server
// recognise a returning demo person. Real identity matching is out of scope.
const PERSON_SALT = 'gather-demo-person-v1';

function normalizePersonId(personId) {
  return String(personId || '').trim().toUpperCase();
}

function personHash(personId) {
  return crypto
    .createHash('sha256')
    .update(`${PERSON_SALT}:${normalizePersonId(personId)}`)
    .digest('hex');
}

module.exports = { personHash, normalizePersonId };
