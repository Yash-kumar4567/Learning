'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('member and reviewer authorization', () => {
  let server;

  before(async () => {
    server = await startServer();
  });

  after(async () => {
    await server.stop();
  });

  const reviewerRoutes = (id) => [
    ['GET', '/api/reviewer/overview', undefined],
    ['GET', '/api/reviewer/actions', undefined],
    ['POST', `/api/reviewer/verifications/${id}/approve`, {}],
    ['POST', `/api/reviewer/verifications/${id}/reject`, { reason: 'because' }],
    ['POST', `/api/reviewer/members/${id}/suspend`, { reason: 'because' }],
    ['POST', `/api/reviewer/members/${id}/reinstate`, {}],
    ['POST', `/api/reviewer/content/post/${id}/remove`, {}],
    ['POST', `/api/reviewer/reports/${id}/resolve`, { resolution: 'dismiss' }],
  ];

  test('verified members cannot use reviewer endpoints', async () => {
    const theo = await server.demoLogin('theo');
    const sam = server.findAccountByHandle('samw');
    for (const [method, route, body] of reviewerRoutes(sam.id)) {
      const res = await server.api(method, route, { token: theo.token, body });
      assert.equal(res.status, 403, `${method} ${route}`);
      assert.equal(res.data.error, 'reviewer_required');
    }
    assert.equal(server.findAccountByHandle('samw').status, 'pending', 'nothing should have changed');
  });

  test('anonymous and non-verified callers cannot use reviewer endpoints', async () => {
    const sam = server.findAccountByHandle('samw');
    const pending = await server.demoLogin('samw');
    for (const [method, route, body] of reviewerRoutes(sam.id)) {
      const anon = await server.api(method, route, { body });
      assert.equal(anon.status, 401, `${method} ${route} anonymous`);
      const res = await server.api(method, route, { token: pending.token, body });
      assert.equal(res.status, 403, `${method} ${route} pending`);
      assert.equal(res.data.error, 'not_verified');
    }
  });

  test('a suspended reviewer loses reviewer access', async () => {
    const imani = await server.demoLogin('imani');
    const second = await server.createVerifiedMember({ handle: 'temp_reviewer' });
    // Promote via state directly: there is deliberately no API for this.
    server.app.store.state.accounts[second.id].role = 'reviewer';
    const before = await server.api('GET', '/api/reviewer/overview', { token: second.token });
    assert.equal(before.status, 200);
    const suspend = await server.api('POST', `/api/reviewer/members/${second.id}/suspend`, {
      token: imani.token,
      body: { reason: 'Testing that suspension also removes reviewer access.' },
    });
    assert.equal(suspend.status, 200);
    const after2 = await server.api('GET', '/api/reviewer/overview', { token: second.token });
    assert.equal(after2.status, 403);
    assert.equal(after2.data.error, 'not_verified');
  });

  test('registration ignores attempts to self-assign status or role', async () => {
    const res = await server.api('POST', '/api/onboarding/register', {
      body: {
        displayName: 'Sneaky Person',
        handle: 'sneaky',
        personId: 'DEMO-SNEAKY-0001',
        status: 'verified',
        role: 'reviewer',
        verification: { lastDecision: { outcome: 'approved' } },
      },
    });
    assert.equal(res.status, 201);
    assert.equal(res.data.account.status, 'unverified');
    assert.equal(res.data.account.role, 'member');
    assert.equal(res.data.account.isReviewer, false);
    const stored = server.findAccountByHandle('sneaky');
    assert.equal(stored.status, 'unverified');
    assert.equal(stored.role, 'member');
  });

  test('there is no member endpoint that changes status or role', async () => {
    const fresh = await server.register();
    for (const [method, route] of [
      ['POST', '/api/me'],
      ['PATCH', '/api/me'],
      ['PUT', '/api/me'],
      ['POST', '/api/me/status'],
      ['POST', `/api/members/${fresh.account.id}/verify`],
    ]) {
      const res = await server.api(method, route, { token: fresh.token, body: { status: 'verified', role: 'reviewer' } });
      assert.ok([404, 405].includes(res.status), `${method} ${route} returned ${res.status}`);
    }
    const me = await server.api('GET', '/api/me', { token: fresh.token });
    assert.equal(me.data.account.status, 'unverified');
    assert.equal(me.data.account.role, 'member');
  });

  test('verification submit cannot skip the queue', async () => {
    const fresh = await server.register();
    const res = await server.api('POST', '/api/verification/submit', {
      token: fresh.token,
      body: { consent: true, status: 'verified' },
    });
    assert.equal(res.status, 200);
    assert.equal(res.data.account.status, 'pending');
  });

  test('demo login works only for seeded demo accounts', async () => {
    const fresh = await server.register();
    const res = await server.api('POST', '/api/auth/demo-login', { body: { accountId: fresh.account.id } });
    assert.equal(res.status, 403);
    assert.equal(res.data.error, 'demo_login_not_allowed');
    const list = await server.api('GET', '/api/auth/demo-accounts');
    assert.equal(list.status, 200);
    assert.ok(!list.data.accounts.some((a) => a.id === fresh.account.id));
  });

  test('reviewers cannot suspend themselves', async () => {
    const imani = await server.demoLogin('imani');
    const res = await server.api('POST', `/api/reviewer/members/${imani.account.id}/suspend`, {
      token: imani.token,
      body: { reason: 'Should be refused.' },
    });
    assert.equal(res.status, 400);
    assert.equal(server.findAccountByHandle('imani').status, 'verified');
  });

  test('logout invalidates the session', async () => {
    const theo = await server.demoLogin('theo');
    const out = await server.api('POST', '/api/auth/logout', { token: theo.token, body: {} });
    assert.equal(out.status, 200);
    const feed = await server.api('GET', '/api/feed', { token: theo.token });
    assert.equal(feed.status, 401);
  });
});
