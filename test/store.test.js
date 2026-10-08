'use strict';

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Store } = require('../lib/store');
const { makeTempDir, startServer } = require('./helpers');

describe('JSON store', () => {
  test('writes atomically and reloads the same state', async () => {
    const dir = makeTempDir();
    const store = new Store(dir);
    store.load();
    store.state.posts.push({ id: 'p1' });
    store.save();
    store.state.posts.push({ id: 'p2' });
    await store.save();
    await store.flush();
    const files = fs.readdirSync(dir);
    assert.deepEqual(files, ['state.json'], 'no temp files are left behind');
    const again = new Store(dir);
    again.load();
    assert.deepEqual(again.state.posts.map((p) => p.id), ['p1', 'p2']);
    fs.rmSync(dir, { recursive: true, force: true });
  });

  test('a corrupt state file is moved aside and the app still starts', async () => {
    const dir = makeTempDir();
    fs.writeFileSync(path.join(dir, 'state.json'), '{"accounts": {"x": ', 'utf8');
    const warnings = [];
    const store = new Store(dir);
    store.load({ warn: (m) => warnings.push(m) });
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /moved to/);
    assert.ok(fs.readdirSync(dir).some((f) => f.startsWith('state.json.corrupt-')), 'backup kept');
    assert.deepEqual(Object.keys(store.state.accounts), []);

    const server = await startServer({ dataDir: dir });
    try {
      const list = await server.api('GET', '/api/auth/demo-accounts');
      assert.equal(list.status, 200);
      assert.equal(list.data.accounts.length, 7, 'fresh seed after recovery');
    } finally {
      await server.stop();
    }
  });

  test('missing collections in an old state file are repaired on load', () => {
    const dir = makeTempDir();
    fs.writeFileSync(path.join(dir, 'state.json'), JSON.stringify({ version: 1, seededAt: 'x', accounts: {} }), 'utf8');
    const store = new Store(dir);
    store.load();
    assert.deepEqual(store.state.posts, []);
    assert.deepEqual(store.state.reports, []);
    assert.deepEqual(store.state.sessions, {});
    fs.rmSync(dir, { recursive: true, force: true });
  });
});
