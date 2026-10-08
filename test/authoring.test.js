'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('members removing their own content', () => {
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

  test('an author can delete their own post; it disappears for everyone but is not erased', async () => {
    const author = await server.createVerifiedMember();
    const other = await server.createVerifiedMember();
    const post = await createPost(author.token, 'I will take this back.');
    const res = await server.api('DELETE', `/api/posts/${post.id}`, { token: author.token });
    assert.equal(res.status, 200);
    for (const token of [author.token, other.token]) {
      const feed = await server.api('GET', '/api/feed', { token });
      assert.ok(!feed.data.posts.some((p) => p.id === post.id));
    }
    const stored = server.app.store.state.posts.find((p) => p.id === post.id);
    assert.ok(stored, 'the record is kept');
    assert.equal(stored.removed, true);
    assert.equal(stored.removal.by, 'author');
    const twice = await server.api('DELETE', `/api/posts/${post.id}`, { token: author.token });
    assert.equal(twice.status, 404);
  });

  test('nobody else can delete a post through the member endpoint', async () => {
    const author = await server.createVerifiedMember();
    const other = await server.createVerifiedMember();
    const reviewer = await server.demoLogin('imani');
    const post = await createPost(author.token, 'Mine, not yours.');
    const byOther = await server.api('DELETE', `/api/posts/${post.id}`, { token: other.token });
    assert.equal(byOther.status, 404);
    const byReviewer = await server.api('DELETE', `/api/posts/${post.id}`, { token: reviewer.token });
    assert.equal(byReviewer.status, 404, 'reviewers use their own removal endpoint, which is logged');
    const anon = await server.api('DELETE', `/api/posts/${post.id}`);
    assert.equal(anon.status, 401);
    const feed = await server.api('GET', '/api/feed', { token: author.token });
    assert.ok(feed.data.posts.some((p) => p.id === post.id));
  });

  test('an author can delete their own comment, and only on the post it belongs to', async () => {
    const author = await server.createVerifiedMember();
    const commenter = await server.createVerifiedMember();
    const post = await createPost(author.token, 'Comment here.');
    const otherPost = await createPost(author.token, 'Not here.');
    const comment = await server.api('POST', `/api/posts/${post.id}/comments`, { token: commenter.token, body: { body: 'Oops.' } });
    const id = comment.data.comment.id;
    const wrongPost = await server.api('DELETE', `/api/posts/${otherPost.id}/comments/${id}`, { token: commenter.token });
    assert.equal(wrongPost.status, 404);
    const byAuthorOfPost = await server.api('DELETE', `/api/posts/${post.id}/comments/${id}`, { token: author.token });
    assert.equal(byAuthorOfPost.status, 404, 'post authors cannot delete other people\'s comments');
    const ok = await server.api('DELETE', `/api/posts/${post.id}/comments/${id}`, { token: commenter.token });
    assert.equal(ok.status, 200);
    assert.equal(ok.data.post.comments.length, 0);
    const feed = await server.api('GET', '/api/feed', { token: author.token });
    assert.equal(feed.data.posts.find((p) => p.id === post.id).comments.length, 0);
  });

  test('reports on author-deleted content tell the reviewer who removed it', async () => {
    const author = await server.createVerifiedMember();
    const reporter = await server.createVerifiedMember();
    const reviewer = await server.demoLogin('imani');
    const post = await createPost(author.token, 'Reported then deleted.');
    const report = await server.api('POST', '/api/reports', { token: reporter.token, body: { targetType: 'post', targetId: post.id, reason: 'spam' } });
    await server.api('DELETE', `/api/posts/${post.id}`, { token: author.token });
    const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    const listed = overview.data.reports.open.find((r) => r.id === report.data.report.id);
    assert.ok(listed);
    assert.equal(listed.target.removed, true);
    assert.equal(listed.target.removedBy, 'author');
    const resolve = await server.api('POST', `/api/reviewer/reports/${listed.id}/resolve`, { token: reviewer.token, body: { resolution: 'dismiss', note: 'Already gone.' } });
    assert.equal(resolve.status, 200);
  });

  test('a suspended author cannot delete, and a reviewer can still remove author-visible content', async () => {
    const author = await server.createVerifiedMember();
    const reviewer = await server.demoLogin('imani');
    const post = await createPost(author.token, 'Will be hidden by suspension.');
    await server.api('POST', `/api/reviewer/members/${author.id}/suspend`, { token: reviewer.token, body: { reason: 'Testing.' } });
    const denied = await server.api('DELETE', `/api/posts/${post.id}`, { token: author.token });
    assert.equal(denied.status, 403);
    const feed = await server.api('GET', '/api/feed', { token: reviewer.token });
    assert.ok(!feed.data.posts.some((p) => p.id === post.id), 'hidden while suspended');
    await server.api('POST', `/api/reviewer/members/${author.id}/reinstate`, { token: reviewer.token, body: {} });
    const back = await server.api('GET', '/api/feed', { token: reviewer.token });
    assert.ok(back.data.posts.some((p) => p.id === post.id), 'reappears after reinstatement');
  });
});
