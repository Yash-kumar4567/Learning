'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('input validation, request limits and inert content', () => {
  let server;
  let member;

  before(async () => {
    server = await startServer();
    member = await server.createVerifiedMember();
  });

  after(async () => {
    await server.stop();
  });

  test('malformed JSON and non-object bodies are rejected', async () => {
    const broken = await server.api('POST', '/api/posts', { token: member.token, rawBody: '{"body": ' });
    assert.equal(broken.status, 400);
    assert.equal(broken.data.error, 'invalid_json');
    const array = await server.api('POST', '/api/posts', { token: member.token, rawBody: '["body"]' });
    assert.equal(array.status, 400);
    const empty = await server.api('POST', '/api/posts', { token: member.token, rawBody: '' });
    assert.equal(empty.status, 400);
    assert.equal(empty.data.field, 'body');
  });

  test('oversized bodies are refused with 413', async () => {
    const huge = JSON.stringify({ body: 'x'.repeat(40 * 1024) });
    const res = await server.api('POST', '/api/posts', { token: member.token, rawBody: huge });
    assert.equal(res.status, 413);
    assert.equal(res.data.error, 'payload_too_large');
  });

  test('registration fields are validated', async () => {
    const cases = [
      [{ displayName: 'A', handle: 'okhandle', personId: 'DEMO-V-0001' }, 'displayName'],
      [{ displayName: 'x'.repeat(41), handle: 'okhandle', personId: 'DEMO-V-0001' }, 'displayName'],
      [{ displayName: 'Valid Name', handle: 'no spaces', personId: 'DEMO-V-0001' }, 'handle'],
      [{ displayName: 'Valid Name', handle: 'ab', personId: 'DEMO-V-0001' }, 'handle'],
      [{ displayName: 'Valid Name', handle: 'okhandle', personId: 'ab' }, 'personId'],
      [{ displayName: 'Valid Name', handle: 'okhandle', personId: 'has space' }, 'personId'],
      [{ displayName: 'Valid Name', handle: 'okhandle' }, 'personId'],
      [{ displayName: 42, handle: 'okhandle', personId: 'DEMO-V-0001' }, 'displayName'],
    ];
    for (const [body, field] of cases) {
      const res = await server.api('POST', '/api/onboarding/register', { body });
      assert.equal(res.status, 400, JSON.stringify(body));
      assert.equal(res.data.error, 'validation_error');
      assert.equal(res.data.field, field, JSON.stringify(body));
    }
  });

  test('posts and comments enforce length limits and reject control characters', async () => {
    const blank = await server.api('POST', '/api/posts', { token: member.token, body: { body: '   ' } });
    assert.equal(blank.status, 400);
    const long = await server.api('POST', '/api/posts', { token: member.token, body: { body: 'y'.repeat(1001) } });
    assert.equal(long.status, 400);
    const control = await server.api('POST', '/api/posts', { token: member.token, body: { body: 'bad\u0000char' } });
    assert.equal(control.status, 400);
    const ok = await server.api('POST', '/api/posts', { token: member.token, body: { body: 'z'.repeat(1000) } });
    assert.equal(ok.status, 201);
    const longComment = await server.api('POST', `/api/posts/${ok.data.post.id}/comments`, { token: member.token, body: { body: 'c'.repeat(501) } });
    assert.equal(longComment.status, 400);
    const newline = await server.api('POST', `/api/posts/${ok.data.post.id}/comments`, { token: member.token, body: { body: 'line one\nline two' } });
    assert.equal(newline.status, 201, 'ordinary newlines are allowed');
  });

  test('script-like content is stored verbatim and only ever served as JSON text', async () => {
    const payload = '<script>alert("x")</script><img src=x onerror="alert(1)"> &amp; "quotes"';
    const created = await server.api('POST', '/api/posts', { token: member.token, body: { body: payload } });
    assert.equal(created.status, 201);
    assert.equal(created.data.post.body, payload, 'body is kept as plain text, not escaped or executed');
    assert.match(created.headers.get('content-type'), /^application\/json/);

    const feed = await server.api('GET', '/api/feed', { token: member.token });
    assert.match(feed.headers.get('content-type'), /^application\/json/);
    const found = feed.data.posts.find((p) => p.id === created.data.post.id);
    assert.equal(found.body, payload);

    const page = await fetch(`${server.baseUrl}/`);
    const html = await page.text();
    assert.match(page.headers.get('content-type'), /^text\/html/);
    assert.ok(!html.includes('alert('), 'user content is never rendered into the HTML page');
    assert.ok(!html.includes('<script>alert'));
    assert.match(page.headers.get('content-security-policy'), /script-src 'self'/);
    assert.equal(page.headers.get('x-content-type-options'), 'nosniff');
    assert.ok(!/<script(?![^>]*src=)/i.test(html), 'index.html contains no inline scripts, so the CSP is enforceable');
  });

  test('unknown API routes and wrong methods are rejected', async () => {
    const missing = await server.api('GET', '/api/nope');
    assert.equal(missing.status, 404);
    const method = await server.api('DELETE', '/api/feed', { token: member.token });
    assert.equal(method.status, 405);
  });

  test('static files are served without path traversal', async () => {
    const css = await fetch(`${server.baseUrl}/styles.css`);
    assert.equal(css.status, 200);
    assert.match(css.headers.get('content-type'), /^text\/css/);
    const traversal = await fetch(`${server.baseUrl}/..%2F..%2Fserver.js`);
    assert.ok([400, 404].includes(traversal.status));
    const data = await fetch(`${server.baseUrl}/../data/state.json`);
    assert.notEqual(data.status, 200);
    const spa = await fetch(`${server.baseUrl}/feed`);
    assert.equal(spa.status, 200);
    assert.match(spa.headers.get('content-type'), /^text\/html/);
  });
});
