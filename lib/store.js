'use strict';

const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');

function emptyState() {
  return {
    version: 1,
    seededAt: null,
    accounts: {},
    persons: {},
    sessions: {},
    posts: [],
    reports: [],
    actions: [],
  };
}

// Windows can refuse to replace a file that another process briefly holds open
// (editors, antivirus). Retry a few times before giving up.
async function renameWithRetry(from, to) {
  let lastError = null;
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      await fsp.rename(from, to);
      return;
    } catch (err) {
      lastError = err;
      if (err.code !== 'EPERM' && err.code !== 'EBUSY' && err.code !== 'EACCES') throw err;
      await new Promise((resolve) => setTimeout(resolve, 25 * (attempt + 1)));
    }
  }
  throw lastError;
}

// JSON-file persistence. Mutations happen synchronously in memory; save() then
// writes the whole state atomically (temp file + rename). Concurrent saves are
// serialised and coalesced so the file is never written by two writers at once.
class Store {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.file = path.join(dataDir, 'state.json');
    this.state = emptyState();
    this.pending = null;
    this.dirty = false;
  }

  // Loads the state file. A file that is not valid JSON (for example a write
  // interrupted by a power cut) is moved aside rather than silently discarded,
  // and the store starts empty so the app can still come up. The warning names
  // the preserved copy.
  load(options) {
    const warn = (options && options.warn) || (() => {});
    fs.mkdirSync(this.dataDir, { recursive: true });
    if (!fs.existsSync(this.file)) return this.state;
    const raw = fs.readFileSync(this.file, 'utf8');
    if (!raw.trim()) return this.state;
    let parsed;
    try {
      parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('state is not an object');
    } catch (err) {
      const backup = `${this.file}.corrupt-${Date.now()}.json`;
      fs.renameSync(this.file, backup);
      warn(`State file could not be parsed (${err.message}). It was moved to ${backup} and a fresh state was created.`);
      return this.state;
    }
    this.state = Object.assign(emptyState(), parsed);
    for (const key of ['accounts', 'persons', 'sessions']) {
      if (!this.state[key] || typeof this.state[key] !== 'object') this.state[key] = {};
    }
    for (const key of ['posts', 'reports', 'actions']) {
      if (!Array.isArray(this.state[key])) this.state[key] = [];
    }
    return this.state;
  }

  save() {
    this.dirty = true;
    if (!this.pending) {
      this.pending = this.flushLoop();
    }
    return this.pending;
  }

  async flushLoop() {
    try {
      while (this.dirty) {
        this.dirty = false;
        const tmp = `${this.file}.${process.pid}.tmp`;
        await fsp.writeFile(tmp, JSON.stringify(this.state, null, 2), 'utf8');
        await renameWithRetry(tmp, this.file);
      }
    } finally {
      this.pending = null;
    }
  }

  flush() {
    return this.pending || Promise.resolve();
  }
}

module.exports = { Store, emptyState };
