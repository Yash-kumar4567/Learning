'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('reporting and reviewer moderation', () => {
  let server;
  let reviewer;

  before(async () => {
    server = await startServer();
    reviewer = await server.demoLogin('imani');
  });

  after(async () => {
    await server.stop();
  });

  async function createPost(token, body) {
    const res = await server.api('POST', '/api/posts', { token, body: { body } });
    assert.equal(res.status, 201, JSON.stringify(res.data));
    return res.data.post;
  }

  test('a report needs a valid target and reason, and cannot target your own content', async () => {
    const author = await server.createVerifiedMember();
    const reporter = await server.createVerifiedMember();
    const post = await createPost(author.token, 'A perfectly ordinary post.');

    const badReason = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: post.id, reason: 'rude' } });
    assert.equal(badReason.status, 400);
    const badType = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'thing', targetId: post.id, reason: 'spam' } });
    assert.equal(badType.status, 400);
    const missing = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: 'nope', reason: 'spam' } });
    assert.equal(missing.status, 404);
    const self = await server.api('POST', '/api/reports', { token: author.token, body: { targetType: 'post', targetId: post.id, reason: 'spam' } });
    assert.equal(self.status, 400);
    const otherNoDetails = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: post.id, reason: 'other' } });
    assert.equal(otherNoDetails.status, 400);

    const ok = await server.api('POST', '/api/reports', {
      token: reporter.token,
      body: { targetType: 'post', targetId: post.id, reason: 'harassment', details: 'Keeps targeting me.' },
    });
    assert.equal(ok.status, 201);
    assert.equal(ok.data.report.status, 'open');
  });

  test('resolving a report by removing content hides it from every feed and logs the action', async () => {
    const author = await server.createVerifiedMember();
    const reporter = await server.createVerifiedMember();
    const post = await createPost(author.token, 'Content that will be removed.');
    const report = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: post.id, reason: 'spam' } });

    const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    const listed = overview.data.reports.open.find((r) => r.id === report.data.report.id);
    assert.ok(listed, 'report should be in the open list');
    assert.equal(listed.target.body, 'Content that will be removed.');
    assert.equal(listed.reporter.id, reporter.id);

    const resolved = await server.api('POST', `/api/reviewer/reports/${listed.id}/resolve`, {
      token: reviewer.token,
      body: { resolution: 'remove_content', note: 'Spam.' },
    });
    assert.equal(resolved.status, 200);
    assert.equal(resolved.data.report.status, 'resolved');
    assert.equal(resolved.data.report.target.removed, true);

    for (const token of [author.token, reporter.token, reviewer.token]) {
      const feed = await server.api('GET', '/api/feed', { token });
      assert.ok(!feed.data.posts.some((p) => p.id === post.id), 'removed post must not appear');
    }
    const again = await server.api('POST', `/api/reviewer/reports/${listed.id}/resolve`, { token: reviewer.token, body: { resolution: 'dismiss' } });
    assert.equal(again.status, 409);

    const actions = await server.api('GET', '/api/reviewer/actions', { token: reviewer.token });
    assert.ok(actions.data.actions.some((a) => a.type === 'remove_post' && a.targetId === post.id));
    assert.ok(actions.data.actions.some((a) => a.type === 'resolve_report' && a.targetId === listed.id));
  });

  test('a comment can be reported and removed directly', async () => {
    const author = await server.createVerifiedMember();
    const commenter = await server.createVerifiedMember();
    const post = await createPost(author.token, 'Post with a comment.');
    const comment = await server.api('POST', `/api/posts/${post.id}/comments`, { token: commenter.token, body: { body: 'Unwanted comment.' } });
    assert.equal(comment.status, 201);
    const report = await server.api('POST', '/api/reports', {
      token: author.token,
      body: { targetType: 'comment', targetId: comment.data.comment.id, reason: 'harassment' },
    });
    assert.equal(report.status, 201);
    const removed = await server.api('POST', `/api/reviewer/content/comment/${comment.data.comment.id}/remove`, {
      token: reviewer.token,
      body: { reason: 'Harassment.' },
    });
    assert.equal(removed.status, 200);
    const feed = await server.api('GET', '/api/feed', { token: author.token });
    const found = feed.data.posts.find((p) => p.id === post.id);
    assert.ok(found);
    assert.equal(found.comments.length, 0);
    const twice = await server.api('POST', `/api/reviewer/content/comment/${comment.data.comment.id}/remove`, { token: reviewer.token, body: {} });
    assert.equal(twice.status, 409);
  });

  test('resolving a member report by suspension suspends the member', async () => {
    const target = await server.createVerifiedMember();
    const reporter = await server.createVerifiedMember();
    const report = await server.api('POST', '/api/reports', {
      token: reporter.token,
      body: { targetType: 'member', targetId: target.id, reason: 'impersonation', details: 'Pretends to be a reviewer.' },
    });
    assert.equal(report.status, 201);
    const noContent = await server.api('POST', `/api/reviewer/reports/${report.data.report.id}/resolve`, {
      token: reviewer.token,
      body: { resolution: 'remove_content', note: 'x' },
    });
    assert.equal(noContent.status, 400, 'member reports have no content to remove');
    const resolved = await server.api('POST', `/api/reviewer/reports/${report.data.report.id}/resolve`, {
      token: reviewer.token,
      body: { resolution: 'suspend_member', note: 'Impersonating a reviewer.' },
    });
    assert.equal(resolved.status, 200);
    const me = await server.api('GET', '/api/me', { token: target.token });
    assert.equal(me.data.account.status, 'suspended');
    assert.equal(me.data.account.suspension.reason, 'Impersonating a reviewer.');
  });

  test('dismissing a report takes no action', async () => {
    const author = await server.createVerifiedMember();
    const reporter = await server.createVerifiedMember();
    const post = await createPost(author.token, 'Strong opinion, no rule broken.');
    const report = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: post.id, reason: 'harassment' } });
    const resolved = await server.api('POST', `/api/reviewer/reports/${report.data.report.id}/resolve`, {
      token: reviewer.token,
      body: { resolution: 'dismiss', note: 'Disagreement is allowed.' },
    });
    assert.equal(resolved.status, 200);
    assert.equal(resolved.data.report.resolution.outcome, 'dismiss');
    const feed = await server.api('GET', '/api/feed', { token: reporter.token });
    assert.ok(feed.data.posts.some((p) => p.id === post.id));
    assert.equal(server.app.store.state.accounts[author.id].status, 'verified');
  });

  test('the seeded data includes an open report and a visible action history', async () => {
    const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    assert.ok(overview.data.reports.open.length >= 1);
    assert.ok(overview.data.reports.resolved.length >= 1);
    assert.ok(overview.data.actions.length >= 5);
    assert.ok(overview.data.actions.every((a) => a.reviewerHandle && a.type && a.at));
  });
});
