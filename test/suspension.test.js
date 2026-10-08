'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');
const { SESSION_TTL_MS } = require('../lib/app');

describe('suspension appeals and session expiry', () => {
  let server;
  let reviewer;

  before(async () => {
    server = await startServer();
    reviewer = await server.demoLogin('imani');
  });

  after(async () => {
    await server.stop();
  });

  test('a suspended member can appeal; reviewers see it; reinstatement clears it', async () => {
    const member = await server.createVerifiedMember();
    await server.api('POST', `/api/reviewer/members/${member.id}/suspend`, { token: reviewer.token, body: { reason: 'Spam after warning.' } });

    const short = await server.api('POST', '/api/suspension/appeal', { token: member.token, body: { message: 'sorry' } });
    assert.equal(short.status, 400);
    const ok = await server.api('POST', '/api/suspension/appeal', { token: member.token, body: { message: 'I misread the rule on promotion and will stop posting links.' } });
    assert.equal(ok.status, 200);
    assert.equal(ok.data.account.suspension.appeal.message, 'I misread the rule on promotion and will stop posting links.');

    const me = await server.api('GET', '/api/me', { token: member.token });
    assert.ok(me.data.account.suspension.appeal.at);

    const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    const row = overview.data.members.find((m) => m.id === member.id);
    assert.equal(row.hasAppeal, true);
    assert.equal(row.suspension.appeal.message, 'I misread the rule on promotion and will stop posting links.');
    assert.ok(overview.data.counts.appeals >= 1);

    const updated = await server.api('POST', '/api/suspension/appeal', { token: member.token, body: { message: 'Updated appeal with more context about what happened.' } });
    assert.equal(updated.status, 200);

    await server.api('POST', `/api/reviewer/members/${member.id}/reinstate`, { token: reviewer.token, body: { note: 'Appeal accepted.' } });
    const after2 = await server.api('GET', '/api/me', { token: member.token });
    assert.equal(after2.data.account.status, 'verified');
    assert.equal(after2.data.account.suspension, null);
    const again = await server.api('POST', '/api/suspension/appeal', { token: member.token, body: { message: 'No longer suspended, this should fail.' } });
    assert.equal(again.status, 409);
  });

  test('only suspended accounts can appeal', async () => {
    const fresh = await server.register();
    const res = await server.api('POST', '/api/suspension/appeal', { token: fresh.token, body: { message: 'I am not even suspended, just checking.' } });
    assert.equal(res.status, 409);
    const anon = await server.api('POST', '/api/suspension/appeal', { body: { message: 'Anonymous appeal should be refused.' } });
    assert.equal(anon.status, 401);
  });

  test('a suspended member still reads their own status and the rules', async () => {
    const dex = await server.demoLogin('dex');
    const me = await server.api('GET', '/api/me', { token: dex.token });
    assert.equal(me.status, 200);
    assert.equal(me.data.account.status, 'suspended');
    assert.ok(me.data.account.suspension.reason);
    const rules = await server.api('GET', '/api/rules', { token: dex.token });
    assert.equal(rules.status, 200);
    assert.ok(rules.data.rules.length >= 5);
  });

  test('sessions expire after the demo TTL', async () => {
    const theo = await server.demoLogin('theo');
    const before2 = await server.api('GET', '/api/feed', { token: theo.token });
    assert.equal(before2.status, 200);
    const session = server.app.store.state.sessions[theo.token];
    session.createdAt = new Date(Date.now() - SESSION_TTL_MS - 1000).toISOString();
    const expired = await server.api('GET', '/api/feed', { token: theo.token });
    assert.equal(expired.status, 401);
    assert.equal(server.app.store.state.sessions[theo.token], undefined, 'expired session is dropped');
  });
});
