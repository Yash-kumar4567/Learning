'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('block and mute filtering', () => {
  let server;

  before(async () => {
    server = await startServer();
  });

  after(async () => {
    await server.stop();
  });

  async function createPost(token, body) {
    const res = await server.api('POST', '/api/posts', { token, body: { body } });
    assert.equal(res.status, 201, JSON.stringify(res.data));
    return res.data.post;
  }

  test('muting hides the member\'s posts and comments from the viewer only', async () => {
    const viewer = await server.createVerifiedMember();
    const noisy = await server.createVerifiedMember();
    const bystander = await server.createVerifiedMember();
    const noisyPost = await createPost(noisy.token, 'Noisy post.');
    const viewerPost = await createPost(viewer.token, 'Viewer post.');
    const comment = await server.api('POST', `/api/posts/${viewerPost.id}/comments`, { token: noisy.token, body: { body: 'Noisy comment.' } });
    assert.equal(comment.status, 201);

    const mute = await server.api('POST', `/api/members/${noisy.id}/mute`, { token: viewer.token, body: {} });
    assert.equal(mute.status, 200);
    assert.deepEqual(mute.data.muted, [noisy.id]);

    const feed = await server.api('GET', '/api/feed', { token: viewer.token });
    assert.ok(!feed.data.posts.some((p) => p.id === noisyPost.id), 'muted post hidden');
    const own = feed.data.posts.find((p) => p.id === viewerPost.id);
    assert.ok(own);
    assert.equal(own.comments.length, 0, 'muted comment hidden');

    const other = await server.api('GET', '/api/feed', { token: bystander.token });
    assert.ok(other.data.posts.some((p) => p.id === noisyPost.id), 'bystander still sees the post');
    const otherOwn = other.data.posts.find((p) => p.id === viewerPost.id);
    assert.equal(otherOwn.comments.length, 1);

    const unmute = await server.api('POST', `/api/members/${noisy.id}/unmute`, { token: viewer.token, body: {} });
    assert.deepEqual(unmute.data.muted, []);
    const after2 = await server.api('GET', '/api/feed', { token: viewer.token });
    assert.ok(after2.data.posts.some((p) => p.id === noisyPost.id));
  });

  test('blocking hides content in both directions and prevents interaction', async () => {
    const viewer = await server.createVerifiedMember();
    const blocked = await server.createVerifiedMember();
    const blockedPost = await createPost(blocked.token, 'Post by the blocked member.');
    const viewerPost = await createPost(viewer.token, 'Post by the viewer.');

    const block = await server.api('POST', `/api/members/${blocked.id}/block`, { token: viewer.token, body: {} });
    assert.equal(block.status, 200);
    assert.deepEqual(block.data.blocked, [blocked.id]);

    const viewerFeed = await server.api('GET', '/api/feed', { token: viewer.token });
    assert.ok(!viewerFeed.data.posts.some((p) => p.id === blockedPost.id));
    const blockedFeed = await server.api('GET', '/api/feed', { token: blocked.token });
    assert.ok(!blockedFeed.data.posts.some((p) => p.id === viewerPost.id), 'blocked member cannot see the viewer');

    const comment = await server.api('POST', `/api/posts/${viewerPost.id}/comments`, { token: blocked.token, body: { body: 'hi' } });
    assert.equal(comment.status, 404, 'blocked member cannot comment on a hidden post');
    const reaction = await server.api('POST', `/api/posts/${blockedPost.id}/reactions`, { token: viewer.token, body: { type: 'support' } });
    assert.equal(reaction.status, 404);

    const profile = await server.api('GET', `/api/members/${blocked.id}`, { token: viewer.token });
    assert.equal(profile.data.relationship.blocked, true);

    const unblock = await server.api('POST', `/api/members/${blocked.id}/unblock`, { token: viewer.token, body: {} });
    assert.deepEqual(unblock.data.blocked, []);
    const restored = await server.api('GET', '/api/feed', { token: viewer.token });
    assert.ok(restored.data.posts.some((p) => p.id === blockedPost.id));
  });

  test('you cannot block or mute yourself, and relationships are idempotent', async () => {
    const viewer = await server.createVerifiedMember();
    const other = await server.createVerifiedMember();
    const self = await server.api('POST', `/api/members/${viewer.id}/block`, { token: viewer.token, body: {} });
    assert.equal(self.status, 400);
    await server.api('POST', `/api/members/${other.id}/mute`, { token: viewer.token, body: {} });
    const twice = await server.api('POST', `/api/members/${other.id}/mute`, { token: viewer.token, body: {} });
    assert.deepEqual(twice.data.muted, [other.id]);
    const me = await server.api('GET', '/api/me', { token: viewer.token });
    assert.deepEqual(me.data.account.muted, [other.id]);
    const missing = await server.api('POST', '/api/members/does-not-exist/block', { token: viewer.token, body: {} });
    assert.equal(missing.status, 404);
  });

  test('reactions toggle and are counted per member', async () => {
    const a = await server.createVerifiedMember();
    const b = await server.createVerifiedMember();
    const post = await createPost(a.token, 'React to me.');
    const first = await server.api('POST', `/api/posts/${post.id}/reactions`, { token: b.token, body: { type: 'insightful' } });
    assert.equal(first.status, 200);
    assert.equal(first.data.reactions.counts.insightful, 1);
    assert.equal(first.data.reactions.viewer, 'insightful');
    const switched = await server.api('POST', `/api/posts/${post.id}/reactions`, { token: b.token, body: { type: 'support' } });
    assert.equal(switched.data.reactions.counts.insightful, 0);
    assert.equal(switched.data.reactions.counts.support, 1);
    const cleared = await server.api('POST', `/api/posts/${post.id}/reactions`, { token: b.token, body: { type: 'support' } });
    assert.equal(cleared.data.reactions.counts.support, 0);
    assert.equal(cleared.data.reactions.viewer, null);
    const bad = await server.api('POST', `/api/posts/${post.id}/reactions`, { token: b.token, body: { type: 'angry' } });
    assert.equal(bad.status, 400);
  });
});
