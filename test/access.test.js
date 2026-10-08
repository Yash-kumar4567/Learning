'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('community access is limited to verified, unsuspended members', () => {
  let server;
  let postId;

  before(async () => {
    server = await startServer();
    const theo = await server.demoLogin('theo');
    const feed = await server.api('GET', '/api/feed', { token: theo.token });
    postId = feed.data.posts[0].id;
  });

  after(async () => {
    await server.stop();
  });

  function memberEndpoints(id) {
    return [
      ['GET', '/api/feed', undefined],
      ['POST', '/api/posts', { body: 'hello' }],
      ['POST', `/api/posts/${id}/comments`, { body: 'hi' }],
      ['POST', `/api/posts/${id}/reactions`, { type: 'appreciate' }],
      ['POST', '/api/reports', { targetType: 'post', targetId: id, reason: 'spam' }],
      ['GET', `/api/members/${id}`, undefined],
      ['POST', `/api/members/${id}/block`, {}],
      ['POST', `/api/members/${id}/mute`, {}],
    ];
  }

  test('no session: every member endpoint returns 401', async () => {
    for (const [method, route, body] of memberEndpoints(postId)) {
      const res = await server.api(method, route, { body });
      assert.equal(res.status, 401, `${method} ${route}`);
      assert.equal(res.data.error, 'unauthenticated');
    }
  });

  test('unverified account: every member endpoint returns 403 with the status', async () => {
    const fresh = await server.register();
    assert.equal(fresh.account.status, 'unverified');
    for (const [method, route, body] of memberEndpoints(postId)) {
      const res = await server.api(method, route, { token: fresh.token, body });
      assert.equal(res.status, 403, `${method} ${route}`);
      assert.equal(res.data.error, 'not_verified');
      assert.equal(res.data.status, 'unverified');
    }
  });

  test('pending account is denied', async () => {
    const sam = await server.demoLogin('samw');
    assert.equal(sam.account.status, 'pending');
    for (const [method, route, body] of memberEndpoints(postId)) {
      const res = await server.api(method, route, { token: sam.token, body });
      assert.equal(res.status, 403, `${method} ${route}`);
      assert.equal(res.data.status, 'pending');
    }
  });

  test('rejected account is denied', async () => {
    const rowan = await server.demoLogin('rowan');
    assert.equal(rowan.account.status, 'rejected');
    for (const [method, route, body] of memberEndpoints(postId)) {
      const res = await server.api(method, route, { token: rowan.token, body });
      assert.equal(res.status, 403, `${method} ${route}`);
      assert.equal(res.data.status, 'rejected');
    }
  });

  test('suspended account is denied', async () => {
    const dex = await server.demoLogin('dex');
    assert.equal(dex.account.status, 'suspended');
    for (const [method, route, body] of memberEndpoints(postId)) {
      const res = await server.api(method, route, { token: dex.token, body });
      assert.equal(res.status, 403, `${method} ${route}`);
      assert.equal(res.data.status, 'suspended');
    }
  });

  test('non-verified accounts can still read their own status', async () => {
    for (const handle of ['samw', 'rowan', 'dex']) {
      const session = await server.demoLogin(handle);
      const me = await server.api('GET', '/api/me', { token: session.token });
      assert.equal(me.status, 200);
      assert.equal(me.data.account.handle, handle);
      assert.equal(me.data.account.canAccessCommunity, false);
      assert.ok(me.data.account.statusMessage);
    }
  });

  test('verified member can read the feed and post', async () => {
    const priya = await server.demoLogin('priya');
    const feed = await server.api('GET', '/api/feed', { token: priya.token });
    assert.equal(feed.status, 200);
    assert.ok(Array.isArray(feed.data.posts));
    assert.ok(feed.data.posts.length > 0);
    const post = await server.api('POST', '/api/posts', { token: priya.token, body: { body: 'Hello from the test suite.' } });
    assert.equal(post.status, 201);
    assert.equal(post.data.post.author.handle, 'priya');
  });

  test('the session cookie works as well as the bearer token', async () => {
    const theo = await server.demoLogin('theo');
    const res = await server.api('GET', '/api/feed', { cookie: `gather_session=${theo.token}` });
    assert.equal(res.status, 200);
  });

  test('content from suspended members is hidden from the feed', async () => {
    const theo = await server.demoLogin('theo');
    const feed = await server.api('GET', '/api/feed', { token: theo.token });
    const handles = feed.data.posts.map((p) => p.author.handle);
    assert.ok(!handles.includes('dex'), 'suspended member posts should be hidden');
  });
});
