'use strict';

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startServer, makeTempDir } = require('./helpers');

describe('state persists across server restarts', () => {
  test('accounts, sessions, posts, reports and moderation survive a restart', async () => {
    const dataDir = makeTempDir();
    let server = await startServer({ dataDir });
    let postId;
    let memberToken;
    let memberId;
    let reporterToken;
    try {
      const member = await server.createVerifiedMember({ handle: 'persist_me', personId: 'DEMO-PERSIST-0001' });
      memberToken = member.token;
      memberId = member.id;
      const reporter = await server.createVerifiedMember();
      reporterToken = reporter.token;
      const post = await server.api('POST', '/api/posts', { token: member.token, body: { body: 'Survives restarts.' } });
      assert.equal(post.status, 201);
      postId = post.data.post.id;
      await server.api('POST', `/api/posts/${postId}/comments`, { token: reporter.token, body: { body: 'Still here after restart?' } });
      await server.api('POST', `/api/posts/${postId}/reactions`, { token: reporter.token, body: { type: 'support' } });
      await server.api('POST', `/api/members/${member.id}/mute`, { token: reporter.token, body: {} });
      await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: postId, reason: 'spam' } });
    } finally {
      await server.stop({ keepData: true });
    }

    const file = path.join(dataDir, 'state.json');
    assert.ok(fs.existsSync(file), 'state file should exist');
    assert.ok(!fs.existsSync(`${file}.${process.pid}.tmp`), 'temp file should be renamed away');

    server = await startServer({ dataDir });
    try {
      const me = await server.api('GET', '/api/me', { token: memberToken });
      assert.equal(me.status, 200);
      assert.equal(me.data.account.id, memberId);
      assert.equal(me.data.account.status, 'verified');

      const feed = await server.api('GET', '/api/feed', { token: memberToken });
      const post = feed.data.posts.find((p) => p.id === postId);
      assert.ok(post, 'post should survive');
      assert.equal(post.comments.length, 1);
      assert.equal(post.reactions.counts.support, 1);

      const reporterMe = await server.api('GET', '/api/me', { token: reporterToken });
      assert.deepEqual(reporterMe.data.account.muted, [memberId]);
      const reporterFeed = await server.api('GET', '/api/feed', { token: reporterToken });
      assert.ok(!reporterFeed.data.posts.some((p) => p.id === postId), 'mute should survive');

      const reviewer = await server.demoLogin('imani');
      const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
      assert.ok(overview.data.reports.open.some((r) => r.targetId === postId));

      const recovered = await server.api('POST', '/api/auth/recover', { body: { personId: 'DEMO-PERSIST-0001' } });
      assert.equal(recovered.status, 200);
      assert.equal(recovered.data.account.id, memberId);

      assert.equal(Object.values(server.app.store.state.accounts).filter((a) => a.handle === 'imani').length, 1, 'seed must not run twice');
    } finally {
      await server.stop();
    }
  });

  test('a missing data directory is created and seeded on first start', async () => {
    const dataDir = path.join(makeTempDir(), 'nested', 'data');
    const server = await startServer({ dataDir });
    try {
      assert.ok(fs.existsSync(path.join(dataDir, 'state.json')));
      const list = await server.api('GET', '/api/auth/demo-accounts');
      assert.equal(list.data.accounts.length, 7);
    } finally {
      await server.stop();
    }
  });
});
