'use strict';

const { test, before, after, describe } = require('node:test');
const assert = require('node:assert/strict');
const { startServer } = require('./helpers');

describe('verification lifecycle: submit, approve, reject, resubmit, suspend, reinstate', () => {
  let server;
  let reviewer;

  before(async () => {
    server = await startServer();
    reviewer = await server.demoLogin('imani');
  });

  after(async () => {
    await server.stop();
  });

  test('submission requires explicit consent', async () => {
    const fresh = await server.register();
    const noConsent = await server.api('POST', '/api/verification/submit', { token: fresh.token, body: { note: 'hi' } });
    assert.equal(noConsent.status, 400);
    assert.equal(noConsent.data.error, 'consent_required');
    const stringConsent = await server.api('POST', '/api/verification/submit', { token: fresh.token, body: { consent: 'true' } });
    assert.equal(stringConsent.status, 400);
    const ok = await server.api('POST', '/api/verification/submit', { token: fresh.token, body: { consent: true, note: 'Ready.' } });
    assert.equal(ok.status, 200);
    assert.equal(ok.data.account.status, 'pending');
    assert.equal(ok.data.account.verification.attempts, 1);
    assert.equal(ok.data.account.verification.note, 'Ready.');
  });

  test('approval moves a pending account to verified and unlocks the feed', async () => {
    const fresh = await server.register();
    await server.submitVerification(fresh.token);
    const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    assert.ok(overview.data.queue.some((a) => a.id === fresh.account.id), 'account should be in the queue');
    const approved = await server.approve(reviewer.token, fresh.account.id);
    assert.equal(approved.status, 'verified');
    assert.equal(approved.verification.reviewerId, reviewer.account.id);
    const feed = await server.api('GET', '/api/feed', { token: fresh.token });
    assert.equal(feed.status, 200);
    const actions = await server.api('GET', '/api/reviewer/actions', { token: reviewer.token });
    assert.ok(actions.data.actions.some((a) => a.type === 'approve_verification' && a.targetId === fresh.account.id));
  });

  test('approving a non-pending account is refused', async () => {
    const theo = server.findAccountByHandle('theo');
    const res = await server.api('POST', `/api/reviewer/verifications/${theo.id}/approve`, { token: reviewer.token, body: {} });
    assert.equal(res.status, 409);
  });

  test('rejection requires a reason, exposes it to the person, and allows resubmission', async () => {
    const fresh = await server.register();
    await server.submitVerification(fresh.token);
    const noReason = await server.api('POST', `/api/reviewer/verifications/${fresh.account.id}/reject`, { token: reviewer.token, body: {} });
    assert.equal(noReason.status, 400);
    const rejected = await server.api('POST', `/api/reviewer/verifications/${fresh.account.id}/reject`, {
      token: reviewer.token,
      body: { reason: 'Handle looks like an impersonation attempt.' },
    });
    assert.equal(rejected.status, 200);
    assert.equal(rejected.data.account.status, 'rejected');

    const me = await server.api('GET', '/api/me', { token: fresh.token });
    assert.equal(me.data.account.status, 'rejected');
    assert.equal(me.data.account.verification.lastDecision.outcome, 'rejected');
    assert.equal(me.data.account.verification.lastDecision.reason, 'Handle looks like an impersonation attempt.');
    const denied = await server.api('GET', '/api/feed', { token: fresh.token });
    assert.equal(denied.status, 403);

    const again = await server.api('POST', '/api/verification/submit', {
      token: fresh.token,
      body: { consent: true, note: 'Appeal: this is my own name.' },
    });
    assert.equal(again.status, 200);
    assert.equal(again.data.account.status, 'pending');
    assert.equal(again.data.account.verification.attempts, 2);
    const queue = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    const entry = queue.data.queue.find((a) => a.id === fresh.account.id);
    assert.ok(entry);
    assert.equal(entry.verification.lastDecision.outcome, 'rejected', 'previous decision stays visible to the reviewer');
  });

  test('pending or verified accounts cannot resubmit', async () => {
    const fresh = await server.register();
    await server.submitVerification(fresh.token);
    const pending = await server.api('POST', '/api/verification/submit', { token: fresh.token, body: { consent: true } });
    assert.equal(pending.status, 409);
    await server.approve(reviewer.token, fresh.account.id);
    const verified = await server.api('POST', '/api/verification/submit', { token: fresh.token, body: { consent: true } });
    assert.equal(verified.status, 409);
  });

  test('suspension removes access; reinstatement restores it', async () => {
    const member = await server.createVerifiedMember();
    const suspended = await server.api('POST', `/api/reviewer/members/${member.id}/suspend`, {
      token: reviewer.token,
      body: { reason: 'Repeated harassment after a warning.' },
    });
    assert.equal(suspended.status, 200);
    assert.equal(suspended.data.account.status, 'suspended');

    const me = await server.api('GET', '/api/me', { token: member.token });
    assert.equal(me.data.account.status, 'suspended');
    assert.equal(me.data.account.suspension.reason, 'Repeated harassment after a warning.');
    const denied = await server.api('POST', '/api/posts', { token: member.token, body: { body: 'still here?' } });
    assert.equal(denied.status, 403);
    assert.equal(denied.data.status, 'suspended');

    const twice = await server.api('POST', `/api/reviewer/members/${member.id}/suspend`, { token: reviewer.token, body: { reason: 'again' } });
    assert.equal(twice.status, 409);

    const reinstated = await server.api('POST', `/api/reviewer/members/${member.id}/reinstate`, { token: reviewer.token, body: { note: 'Apology accepted.' } });
    assert.equal(reinstated.status, 200);
    assert.equal(reinstated.data.account.status, 'verified');
    const feed = await server.api('GET', '/api/feed', { token: member.token });
    assert.equal(feed.status, 200);

    const actions = await server.api('GET', '/api/reviewer/actions', { token: reviewer.token });
    const types = actions.data.actions.filter((a) => a.targetId === member.id).map((a) => a.type);
    assert.ok(types.includes('suspend_member'));
    assert.ok(types.includes('reinstate_member'));
  });

  test('reviewer views never include the person hash', async () => {
    const overview = await server.api('GET', '/api/reviewer/overview', { token: reviewer.token });
    assert.ok(!JSON.stringify(overview.data).includes('personHash'));
  });
});
