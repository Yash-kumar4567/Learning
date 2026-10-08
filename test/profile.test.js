'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('profile editing', () => {
  let server;

  before(async () => {
    server = await startServer();
  });

  after(async () => {
    await server.stop();
  });

  test('a signed-in account can change display name, handle and bio', async () => {
    const fresh = await server.register({ handle: 'before_edit' });
    const res = await server.api('PATCH', '/api/me/profile', {
      token: fresh.token,
      body: { displayName: 'Renamed Person', handle: '@After_Edit', bio: 'Short bio.' },
    });
    assert.equal(res.status, 200, JSON.stringify(res.data));
    assert.equal(res.data.account.displayName, 'Renamed Person');
    assert.equal(res.data.account.handle, 'after_edit');
    assert.equal(res.data.account.bio, 'Short bio.');
    const me = await server.api('GET', '/api/me', { token: fresh.token });
    assert.equal(me.data.account.handle, 'after_edit');
  });

  test('profile changes cannot touch status, role or identity', async () => {
    const fresh = await server.register();
    const res = await server.api('PATCH', '/api/me/profile', {
      token: fresh.token,
      body: { bio: 'hi', status: 'verified', role: 'reviewer', personHash: 'x', id: 'other', demoLogin: true },
    });
    assert.equal(res.status, 200);
    assert.equal(res.data.account.status, 'unverified');
    assert.equal(res.data.account.role, 'member');
    assert.equal(res.data.account.id, fresh.account.id);
    const stored = server.app.store.state.accounts[fresh.account.id];
    assert.equal(stored.status, 'unverified');
    assert.equal(stored.role, 'member');
    assert.equal(stored.demoLogin, false);
    assert.notEqual(stored.personHash, 'x');
  });

  test('validation applies to every field and an empty change set is rejected', async () => {
    const fresh = await server.register();
    const cases = [
      [{ displayName: 'A' }, 'displayName'],
      [{ handle: 'has space' }, 'handle'],
      [{ handle: 'ab' }, 'handle'],
      [{ bio: 'b'.repeat(201) }, 'bio'],
      [{ bio: 'bad\u0007bell' }, 'bio'],
      [{}, 'profile'],
      [{ status: 'verified' }, 'profile'],
    ];
    for (const [body, field] of cases) {
      const res = await server.api('PATCH', '/api/me/profile', { token: fresh.token, body });
      assert.equal(res.status, 400, JSON.stringify(body));
      assert.equal(res.data.field, field, JSON.stringify(body));
    }
    const cleared = await server.api('PATCH', '/api/me/profile', { token: fresh.token, body: { bio: '' } });
    assert.equal(cleared.status, 200);
    assert.equal(cleared.data.account.bio, '');
  });

  test('a handle already used by another account is refused, but keeping your own is fine', async () => {
    const one = await server.register({ handle: 'owner_one' });
    const two = await server.register({ handle: 'owner_two' });
    const clash = await server.api('PATCH', '/api/me/profile', { token: two.token, body: { handle: 'owner_one' } });
    assert.equal(clash.status, 409);
    assert.equal(clash.data.error, 'handle_taken');
    const same = await server.api('PATCH', '/api/me/profile', { token: one.token, body: { handle: 'owner_one', bio: 'unchanged handle' } });
    assert.equal(same.status, 200);
  });

  test('anonymous and suspended callers cannot edit a profile', async () => {
    const anon = await server.api('PATCH', '/api/me/profile', { body: { bio: 'x' } });
    assert.equal(anon.status, 401);
    const dex = await server.demoLogin('dex');
    const res = await server.api('PATCH', '/api/me/profile', { token: dex.token, body: { bio: 'x' } });
    assert.equal(res.status, 403);
    assert.equal(res.data.status, 'suspended');
  });

  test('a rejected person can change the handle the reviewer objected to and resubmit', async () => {
    const reviewer = await server.demoLogin('imani');
    const fresh = await server.register({ handle: 'imani_official' });
    await server.submitVerification(fresh.token);
    const rejected = await server.api('POST', `/api/reviewer/verifications/${fresh.account.id}/reject`, {
      token: reviewer.token,
      body: { reason: 'The handle imitates a reviewer. Pick one that is clearly your own.' },
    });
    assert.equal(rejected.status, 200);
    const changed = await server.api('PATCH', '/api/me/profile', { token: fresh.token, body: { handle: 'just_me' } });
    assert.equal(changed.status, 200);
    const again = await server.submitVerification(fresh.token, 'Changed my handle as asked.');
    assert.equal(again.status, 'pending');
    assert.equal(again.handle, 'just_me');
    const approved = await server.approve(reviewer.token, fresh.account.id);
    assert.equal(approved.status, 'verified');
    const feed = await server.api('GET', '/api/feed', { token: fresh.token });
    assert.equal(feed.status, 200);
  });

  test('the member-facing self view never includes the reviewer id or person hash', async () => {
    const member = await server.createVerifiedMember();
    const me = await server.api('GET', '/api/me', { token: member.token });
    assert.equal(me.data.account.personHash, undefined);
    assert.equal(me.data.account.verification.reviewerId, undefined);
    assert.ok(!JSON.stringify(me.data).includes('personHash'));
  });
});
