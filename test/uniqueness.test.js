'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('one active account per demo person', () => {
  let server;

  before(async () => {
    server = await startServer();
  });

  after(async () => {
    await server.stop();
  });

  test('a second registration with the same person identifier is refused with a recovery path', async () => {
    const first = await server.register({ personId: 'DEMO-UNIQUE-0001', handle: 'unique_one' });
    const dup = await server.api('POST', '/api/onboarding/register', {
      body: { displayName: 'Someone Else', handle: 'unique_two', personId: 'demo-unique-0001' },
    });
    assert.equal(dup.status, 409);
    assert.equal(dup.data.error, 'person_already_registered');
    assert.equal(dup.data.recovery.path, '/api/auth/recover');
    assert.equal(Object.keys(server.app.store.state.accounts).filter((id) => id === first.account.id).length, 1);
    const handles = Object.values(server.app.store.state.accounts).map((a) => a.handle);
    assert.ok(!handles.includes('unique_two'), 'duplicate must not create an account');
  });

  test('simultaneous registrations for one person produce exactly one account', async () => {
    const attempts = Array.from({ length: 8 }, (_, i) =>
      server.api('POST', '/api/onboarding/register', {
        body: { displayName: `Twin ${i}`, handle: `twin_${i}`, personId: 'DEMO-TWIN-SAME' },
      })
    );
    const results = await Promise.all(attempts);
    const created = results.filter((r) => r.status === 201);
    const refused = results.filter((r) => r.status === 409);
    assert.equal(created.length, 1, `expected one 201, got ${results.map((r) => r.status).join(',')}`);
    assert.equal(refused.length, 7);
    const twins = Object.values(server.app.store.state.accounts).filter((a) => a.handle.startsWith('twin_'));
    assert.equal(twins.length, 1);
  });

  test('a duplicate handle is refused even for a new person', async () => {
    await server.register({ handle: 'handle_claimed', personId: 'DEMO-HANDLE-0001' });
    const res = await server.api('POST', '/api/onboarding/register', {
      body: { displayName: 'New Person', handle: 'Handle_Claimed', personId: 'DEMO-HANDLE-0002' },
    });
    assert.equal(res.status, 409);
    assert.equal(res.data.error, 'handle_taken');
  });

  test('recovery returns the existing account and a working session', async () => {
    const original = await server.register({ personId: 'DEMO-RECOVER-0001', handle: 'recover_me' });
    const res = await server.api('POST', '/api/auth/recover', { body: { personId: ' demo-recover-0001 ' } });
    assert.equal(res.status, 200);
    assert.equal(res.data.account.id, original.account.id);
    assert.ok(res.data.session.token);
    assert.notEqual(res.data.session.token, original.token);
    const me = await server.api('GET', '/api/me', { token: res.data.session.token });
    assert.equal(me.data.account.id, original.account.id);
  });

  test('recovery for an unknown person is refused', async () => {
    const res = await server.api('POST', '/api/auth/recover', { body: { personId: 'DEMO-NOBODY-0000' } });
    assert.equal(res.status, 404);
    assert.equal(res.data.error, 'person_not_found');
  });

  test('the raw person identifier is never stored or exposed', async () => {
    const reg = await server.register({ personId: 'DEMO-SECRET-0001', handle: 'secret_keeper' });
    const serialized = JSON.stringify(server.app.store.state);
    assert.ok(!serialized.includes('DEMO-SECRET-0001'), 'state must not contain the raw identifier');
    assert.equal(reg.account.personHash, undefined);
    const member = await server.createVerifiedMember();
    const profile = await server.api('GET', `/api/members/${reg.account.id}`, { token: member.token });
    assert.equal(profile.status, 200);
    assert.equal(profile.data.member.personHash, undefined);
    assert.ok(!JSON.stringify(profile.data).includes('DEMO-SECRET'));
  });
});
