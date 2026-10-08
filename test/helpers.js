'use strict';

// Shared helpers for the Gather test suite. Not a test file itself.

const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createApp } = require('../server');

function makeTempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'gather-test-'));
}

async function startServer(options) {
  const opts = options || {};
  const dataDir = opts.dataDir || makeTempDir();
  const app = createApp({ dataDir, logger: null });
  const address = await app.listen(0, '127.0.0.1');
  const baseUrl = `http://127.0.0.1:${address.port}`;

  async function api(method, route, options2) {
    const o = options2 || {};
    const headers = { accept: 'application/json' };
    let body;
    if (o.rawBody !== undefined) {
      headers['content-type'] = o.contentType || 'application/json';
      body = o.rawBody;
    } else if (o.body !== undefined) {
      headers['content-type'] = 'application/json';
      body = JSON.stringify(o.body);
    }
    if (o.token) headers.authorization = `Bearer ${o.token}`;
    if (o.cookie) headers.cookie = o.cookie;
    const response = await fetch(baseUrl + route, { method, headers, body });
    const text = await response.text();
    let data;
    try {
      data = JSON.parse(text);
    } catch (err) {
      data = text;
    }
    return { status: response.status, data, headers: response.headers };
  }

  function findAccountByHandle(handle) {
    return Object.values(app.store.state.accounts).find((a) => a.handle === handle);
  }

  async function demoLogin(handle) {
    const account = findAccountByHandle(handle);
    assert.ok(account, `seeded account @${handle} should exist`);
    const res = await api('POST', '/api/auth/demo-login', { body: { accountId: account.id } });
    assert.equal(res.status, 200, `demo-login for @${handle}: ${JSON.stringify(res.data)}`);
    return { token: res.data.session.token, account: res.data.account };
  }

  let counter = 0;
  async function register(overrides) {
    counter += 1;
    const fields = Object.assign(
      {
        displayName: `Test Person ${counter}`,
        handle: `tester${counter}_${process.pid % 1000}`,
        personId: `DEMO-TEST-${process.pid}-${counter}`,
      },
      overrides || {}
    );
    const res = await api('POST', '/api/onboarding/register', { body: fields });
    assert.equal(res.status, 201, `register: ${JSON.stringify(res.data)}`);
    return { token: res.data.session.token, account: res.data.account, fields };
  }

  async function submitVerification(token, note) {
    const res = await api('POST', '/api/verification/submit', { token, body: { consent: true, note: note || '' } });
    assert.equal(res.status, 200, `submit verification: ${JSON.stringify(res.data)}`);
    return res.data.account;
  }

  async function approve(reviewerToken, accountId) {
    const res = await api('POST', `/api/reviewer/verifications/${accountId}/approve`, { token: reviewerToken, body: {} });
    assert.equal(res.status, 200, `approve: ${JSON.stringify(res.data)}`);
    return res.data.account;
  }

  // Register, submit and approve a brand-new member in one go.
  async function createVerifiedMember(overrides) {
    const reviewer = await demoLogin('imani');
    const member = await register(overrides);
    await submitVerification(member.token);
    await approve(reviewer.token, member.account.id);
    return { token: member.token, id: member.account.id, fields: member.fields, reviewerToken: reviewer.token };
  }

  async function stop(options3) {
    const o = options3 || {};
    await app.close();
    if (!o.keepData) fs.rmSync(dataDir, { recursive: true, force: true });
  }

  return {
    app,
    baseUrl,
    dataDir,
    api,
    demoLogin,
    register,
    submitVerification,
    approve,
    createVerifiedMember,
    findAccountByHandle,
    stop,
  };
}

module.exports = { startServer, makeTempDir };
