'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const { Store } = require('./store');
const { seedState } = require('./seed');
const { personHash } = require('./identity');
const { RULES } = require('./rules');
const {
  REACTIONS,
  REPORT_REASONS,
  REPORT_TARGETS,
  RESOLUTIONS,
  now,
  newId,
  createAccount,
  createPost,
  createComment,
  createReport,
} = require('./models');

const SESSION_COOKIE = 'gather_session';
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000; // demo sessions expire after 30 days
const BODY_LIMIT = 32 * 1024;
const LIMITS = Object.freeze({
  displayName: [2, 40],
  handle: [3, 20],
  personId: [4, 64],
  post: [1, 1000],
  comment: [1, 500],
  reason: [3, 300],
  details: [0, 500],
  note: [0, 500],
  bio: [0, 200],
  appeal: [10, 1000],
});
const HANDLE_RE = /^[a-z0-9_]{3,20}$/;
const PERSON_ID_RE = /^[A-Za-z0-9-]{4,64}$/;
const CONTROL_RE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;

const DEMO_NOTICE =
  'Demo: simulated verification. Demo sessions and reviewer selection are not production authentication.';

const STATUS_MESSAGES = Object.freeze({
  unverified: 'Your account exists but has not been submitted for verification yet.',
  pending: 'Your verification request is waiting for a reviewer.',
  rejected: 'Your verification request was not approved. You can resubmit or appeal.',
  suspended: 'This account is suspended. Community access is paused until a reviewer reinstates it.',
  verified: 'You are a verified member.',
});

const MIME = Object.freeze({
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
});

const SECURITY_HEADERS = Object.freeze({
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'X-Frame-Options': 'DENY',
  'Content-Security-Policy':
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
});

class HttpError extends Error {
  constructor(status, code, message, extra) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra || {};
  }
}

// ---------------------------------------------------------------------------
// Small HTTP helpers
// ---------------------------------------------------------------------------

function sendJson(res, status, body, extraHeaders) {
  const payload = JSON.stringify(body);
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
  };
  if (extraHeaders) Object.assign(headers, extraHeaders);
  res.writeHead(status, headers);
  res.end(payload);
}

function readJson(req, limit) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length'] || 0);
    if (declared > limit) {
      req.resume();
      reject(new HttpError(413, 'payload_too_large', `Request body must be ${limit} bytes or smaller.`));
      return;
    }
    const chunks = [];
    let size = 0;
    let settled = false;
    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      fn(value);
    };
    req.on('data', (chunk) => {
      if (settled) return;
      size += chunk.length;
      if (size > limit) {
        req.resume();
        finish(reject, new HttpError(413, 'payload_too_large', `Request body must be ${limit} bytes or smaller.`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      if (chunks.length === 0) {
        finish(resolve, {});
        return;
      }
      let parsed;
      try {
        parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch (err) {
        finish(reject, new HttpError(400, 'invalid_json', 'Request body is not valid JSON.'));
        return;
      }
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        finish(reject, new HttpError(400, 'invalid_json', 'Request body must be a JSON object.'));
        return;
      }
      finish(resolve, parsed);
    });
    req.on('error', (err) => finish(reject, err));
  });
}

function parseCookies(header) {
  const out = {};
  if (!header) return out;
  for (const part of header.split(';')) {
    const idx = part.indexOf('=');
    if (idx < 0) continue;
    const key = part.slice(0, idx).trim();
    let value = part.slice(idx + 1).trim();
    try {
      value = decodeURIComponent(value);
    } catch (err) {
      // keep the raw value
    }
    out[key] = value;
  }
  return out;
}

function compilePattern(pattern) {
  const escaped = pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const withParams = escaped.replace(/:([a-zA-Z]+)/g, '(?<$1>[^/]+)');
  return new RegExp(`^${withParams}/?$`);
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

function requireString(body, field, range, options) {
  const opts = options || {};
  const [min, max] = range;
  const raw = body[field];
  if (raw === undefined || raw === null || raw === '') {
    if (opts.optional || min === 0) return '';
    throw new HttpError(400, 'validation_error', `${field} is required.`, { field });
  }
  if (typeof raw !== 'string') {
    throw new HttpError(400, 'validation_error', `${field} must be text.`, { field });
  }
  const value = raw.trim();
  if (value.length < min || value.length > max) {
    throw new HttpError(400, 'validation_error', `${field} must be between ${min} and ${max} characters.`, { field });
  }
  if (CONTROL_RE.test(value)) {
    throw new HttpError(400, 'validation_error', `${field} contains unsupported control characters.`, { field });
  }
  return value;
}

function requireOneOf(body, field, allowed) {
  const value = body[field];
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new HttpError(400, 'validation_error', `${field} must be one of: ${allowed.join(', ')}.`, { field });
  }
  return value;
}

function excerpt(text, max) {
  const limit = max || 80;
  const clean = String(text).replace(/\s+/g, ' ').trim();
  return clean.length > limit ? `${clean.slice(0, limit - 1)}…` : clean;
}

// ---------------------------------------------------------------------------
// Application
// ---------------------------------------------------------------------------

function createApp(options) {
  const opts = options || {};
  const dataDir = opts.dataDir || path.join(__dirname, '..', 'data');
  const publicDir = path.resolve(opts.publicDir || path.join(__dirname, '..', 'public'));
  const log = opts.logger === null ? () => {} : opts.logger || ((...args) => console.log(...args));

  const store = new Store(dataDir);
  store.load({ warn: (message) => log(`Warning: ${message}`) });
  if (opts.seed !== false && !store.state.seededAt) {
    seedState(store.state);
    store.save();
  }

  const routes = [];
  const route = (method, pattern, handler) => {
    routes.push({ method, pattern: compilePattern(pattern), handler });
  };

  // ----- presenters -------------------------------------------------------

  function toPublic(account) {
    return {
      id: account.id,
      displayName: account.displayName,
      handle: account.handle,
      role: account.role,
      verified: account.status === 'verified',
      joinedAt: account.createdAt,
      bio: account.bio,
    };
  }

  function toSelf(account) {
    return Object.assign(toPublic(account), {
      status: account.status,
      statusMessage: STATUS_MESSAGES[account.status],
      canAccessCommunity: account.status === 'verified',
      isReviewer: account.role === 'reviewer' && account.status === 'verified',
      verification: Object.assign({}, account.verification, { reviewerId: undefined }),
      suspension: account.suspension
        ? {
            reason: account.suspension.reason,
            at: account.suspension.at,
            appeal: account.suspension.appeal || null,
          }
        : null,
      blocked: account.blocked.slice(),
      muted: account.muted.slice(),
      demoLogin: account.demoLogin,
    });
  }

  function toReviewerView(account, reviewer) {
    const state = store.state;
    return Object.assign(toPublic(account), {
      status: account.status,
      verification: account.verification,
      suspension: account.suspension,
      postCount: state.posts.filter((p) => p.authorId === account.id && !p.removed).length,
      hiddenPostCount: state.posts.filter((p) => p.authorId === account.id && p.removed).length,
      hasAppeal: Boolean(account.suspension && account.suspension.appeal),
      isSelf: account.id === reviewer.id,
    });
  }

  function accountIsActive(accountId) {
    const account = store.state.accounts[accountId];
    return Boolean(account) && account.status === 'verified';
  }

  function hiddenFor(viewer) {
    const hidden = new Set([...viewer.blocked, ...viewer.muted]);
    for (const other of Object.values(store.state.accounts)) {
      if (other.blocked.includes(viewer.id)) hidden.add(other.id);
    }
    return hidden;
  }

  function reactionSummary(post, viewerId) {
    const counts = {};
    for (const type of REACTIONS) counts[type] = 0;
    for (const type of Object.values(post.reactions)) {
      if (counts[type] !== undefined) counts[type] += 1;
    }
    return { counts, viewer: post.reactions[viewerId] || null };
  }

  function presentComment(comment) {
    return {
      id: comment.id,
      body: comment.body,
      createdAt: comment.createdAt,
      author: toPublic(store.state.accounts[comment.authorId]),
    };
  }

  function presentPost(post, viewer, hidden) {
    const comments = post.comments
      .filter((c) => !c.removed && !hidden.has(c.authorId) && accountIsActive(c.authorId))
      .map(presentComment);
    return {
      id: post.id,
      body: post.body,
      createdAt: post.createdAt,
      author: toPublic(store.state.accounts[post.authorId]),
      comments,
      reactions: reactionSummary(post, viewer.id),
      isOwn: post.authorId === viewer.id,
    };
  }

  function postVisibleTo(post, viewer, hidden) {
    return !post.removed && accountIsActive(post.authorId) && !hidden.has(post.authorId);
  }

  function buildFeed(viewer) {
    const hidden = hiddenFor(viewer);
    return store.state.posts
      .filter((post) => postVisibleTo(post, viewer, hidden))
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0))
      .map((post) => presentPost(post, viewer, hidden));
  }

  function findContent(type, id) {
    const state = store.state;
    if (type === 'post') {
      const post = state.posts.find((p) => p.id === id);
      return post ? { post, comment: null } : null;
    }
    if (type === 'comment') {
      for (const post of state.posts) {
        const comment = post.comments.find((c) => c.id === id);
        if (comment) return { post, comment };
      }
      return null;
    }
    return null;
  }

  function contentLabel(type, id) {
    const found = findContent(type, id);
    if (!found) return `${type} ${id}`;
    const item = found.comment || found.post;
    const author = store.state.accounts[item.authorId];
    return `${type} by @${author ? author.handle : 'unknown'}: "${excerpt(item.body, 60)}"`;
  }

  function memberLabel(account) {
    return `${account.displayName} (@${account.handle})`;
  }

  function presentReportTarget(report) {
    const state = store.state;
    if (report.targetType === 'member') {
      const account = state.accounts[report.targetId];
      return account ? { type: 'member', member: toPublic(account), status: account.status } : { type: 'member', missing: true };
    }
    const found = findContent(report.targetType, report.targetId);
    if (!found) return { type: report.targetType, missing: true };
    const item = found.comment || found.post;
    const author = state.accounts[item.authorId];
    return {
      type: report.targetType,
      id: item.id,
      postId: found.post.id,
      body: item.body,
      createdAt: item.createdAt,
      removed: item.removed,
      removedBy: item.removal ? item.removal.by : null,
      author: author ? toPublic(author) : null,
      authorStatus: author ? author.status : null,
    };
  }

  function presentReport(report) {
    const reporter = store.state.accounts[report.reporterId];
    return {
      id: report.id,
      targetType: report.targetType,
      targetId: report.targetId,
      reason: report.reason,
      details: report.details,
      status: report.status,
      createdAt: report.createdAt,
      resolution: report.resolution,
      reporter: reporter ? toPublic(reporter) : null,
      target: presentReportTarget(report),
    };
  }

  // ----- sessions ----------------------------------------------------------

  function sessionToken(req) {
    const auth = req.headers.authorization;
    if (auth && auth.startsWith('Bearer ')) return auth.slice(7).trim();
    return parseCookies(req.headers.cookie)[SESSION_COOKIE] || null;
  }

  function currentAccount(ctx) {
    const token = sessionToken(ctx.req);
    if (!token) return null;
    const session = store.state.sessions[token];
    if (!session) return null;
    if (Date.parse(session.createdAt) + SESSION_TTL_MS < Date.now()) {
      delete store.state.sessions[token];
      return null;
    }
    const account = store.state.accounts[session.accountId];
    if (!account) {
      delete store.state.sessions[token];
      return null;
    }
    ctx.token = token;
    return account;
  }

  function issueSession(ctx, accountId) {
    const token = crypto.randomBytes(24).toString('hex');
    store.state.sessions[token] = { accountId, createdAt: now() };
    ctx.headers['Set-Cookie'] = `${SESSION_COOKIE}=${token}; Path=/; HttpOnly; SameSite=Lax`;
    return { token, note: DEMO_NOTICE };
  }

  function clearSession(ctx) {
    if (ctx.token) delete store.state.sessions[ctx.token];
    ctx.headers['Set-Cookie'] = `${SESSION_COOKIE}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`;
  }

  function requireAccount(ctx) {
    const account = currentAccount(ctx);
    if (!account) {
      throw new HttpError(401, 'unauthenticated', 'Sign in with a demo account or complete onboarding first.');
    }
    return account;
  }

  function requireMember(ctx) {
    const account = requireAccount(ctx);
    if (account.status !== 'verified') {
      throw new HttpError(403, 'not_verified', STATUS_MESSAGES[account.status], { status: account.status });
    }
    return account;
  }

  function requireReviewer(ctx) {
    const account = requireMember(ctx);
    if (account.role !== 'reviewer') {
      throw new HttpError(403, 'reviewer_required', 'This action needs reviewer privileges.');
    }
    return account;
  }

  function getAccountOr404(id) {
    const account = store.state.accounts[id];
    if (!account) throw new HttpError(404, 'member_not_found', 'No member with that id.');
    return account;
  }

  function findVisiblePost(viewer, postId) {
    const post = store.state.posts.find((p) => p.id === postId);
    if (!post || !postVisibleTo(post, viewer, hiddenFor(viewer))) {
      throw new HttpError(404, 'post_not_found', 'That post is not available.');
    }
    return post;
  }

  // ----- moderation primitives --------------------------------------------

  function logAction(reviewer, type, targetType, targetId, targetLabel, note) {
    const entry = {
      id: newId(),
      at: now(),
      reviewerId: reviewer.id,
      reviewerHandle: reviewer.handle,
      type,
      targetType,
      targetId,
      targetLabel,
      note: note || null,
    };
    store.state.actions.push(entry);
    return entry;
  }

  function suspendAccount(target, reviewer, reason) {
    if (target.id === reviewer.id) {
      throw new HttpError(400, 'invalid_target', 'You cannot suspend your own account.');
    }
    if (target.status === 'suspended') {
      throw new HttpError(409, 'invalid_state', 'This member is already suspended.');
    }
    target.suspension = { reason, at: now(), reviewerId: reviewer.id, previousStatus: target.status };
    target.status = 'suspended';
    logAction(reviewer, 'suspend_member', 'member', target.id, memberLabel(target), reason);
  }

  function reinstateAccount(target, reviewer, note) {
    if (target.status !== 'suspended') {
      throw new HttpError(409, 'invalid_state', 'This member is not suspended.');
    }
    const previous = target.suspension && target.suspension.previousStatus;
    target.status = previous && previous !== 'suspended' ? previous : 'verified';
    target.suspension = null;
    logAction(reviewer, 'reinstate_member', 'member', target.id, memberLabel(target), note);
  }

  function removeContent(type, id, reviewer, reason) {
    const found = findContent(type, id);
    if (!found) throw new HttpError(404, 'content_not_found', `No ${type} with that id.`);
    const item = found.comment || found.post;
    if (item.removed) throw new HttpError(409, 'invalid_state', `This ${type} was already removed.`);
    const label = contentLabel(type, id);
    item.removed = true;
    item.removal = { by: 'reviewer', reason: reason || null, at: now(), reviewerId: reviewer.id };
    logAction(reviewer, type === 'post' ? 'remove_post' : 'remove_comment', type, id, label, reason);
    return item;
  }

  function uniqueHandleOr409(handle, exceptAccountId) {
    const taken = Object.values(store.state.accounts).some((a) => a.handle === handle && a.id !== exceptAccountId);
    if (taken) throw new HttpError(409, 'handle_taken', 'That handle is already in use. Choose another.', { field: 'handle' });
  }

  function normalizeHandle(body) {
    const handle = requireString(body, 'handle', [1, 64]).replace(/^@/, '').toLowerCase();
    if (!HANDLE_RE.test(handle)) {
      throw new HttpError(
        400,
        'validation_error',
        'Handle may contain only lowercase letters, numbers and underscores, 3 to 20 characters.',
        { field: 'handle' }
      );
    }
    return handle;
  }

  // ----- routes: public and session ----------------------------------------

  route('GET', '/api/health', async () => ({ body: { ok: true, demo: true } }));

  route('GET', '/api/rules', async () => ({ body: { rules: RULES } }));

  route('GET', '/api/me', async (ctx) => {
    const account = currentAccount(ctx);
    return {
      body: {
        account: account ? toSelf(account) : null,
        demo: { simulatedVerification: true, notice: DEMO_NOTICE },
      },
    };
  });

  route('GET', '/api/auth/demo-accounts', async () => {
    const accounts = Object.values(store.state.accounts)
      .filter((a) => a.demoLogin)
      .sort((a, b) => a.handle.localeCompare(b.handle))
      .map((a) => ({ id: a.id, displayName: a.displayName, handle: a.handle, status: a.status, role: a.role }));
    return { body: { accounts, notice: DEMO_NOTICE } };
  });

  route('POST', '/api/auth/demo-login', async (ctx) => {
    const accountId = requireString(ctx.body, 'accountId', [1, 100]);
    const account = store.state.accounts[accountId];
    if (!account || !account.demoLogin) {
      throw new HttpError(403, 'demo_login_not_allowed', 'Only seeded demo accounts can be selected this way.');
    }
    const session = issueSession(ctx, account.id);
    await store.save();
    return { body: { account: toSelf(account), session } };
  });

  route('POST', '/api/auth/logout', async (ctx) => {
    currentAccount(ctx);
    clearSession(ctx);
    await store.save();
    return { body: { ok: true } };
  });

  route('POST', '/api/onboarding/register', async (ctx) => {
    const displayName = requireString(ctx.body, 'displayName', LIMITS.displayName);
    const handle = normalizeHandle(ctx.body);
    const personId = requireString(ctx.body, 'personId', LIMITS.personId);
    if (!PERSON_ID_RE.test(personId)) {
      throw new HttpError(
        400,
        'validation_error',
        'Demo person identifier may contain only letters, numbers and dashes, 4 to 64 characters.',
        { field: 'personId' }
      );
    }
    const hash = personHash(personId);
    const state = store.state;

    // Atomic section: no awaits between the uniqueness check and the insert.
    // Node runs this synchronously, so two simultaneous requests cannot both pass.
    if (state.persons[hash]) {
      throw new HttpError(
        409,
        'person_already_registered',
        'An account already exists for this demo person. Gather allows one active account per person, so recover that account instead of creating another.',
        { field: 'personId', recovery: { path: '/api/auth/recover', hint: 'Use the same demo person identifier to recover access.' } }
      );
    }
    uniqueHandleOr409(handle, null);
    const account = createAccount({ displayName, handle, personHash: hash });
    state.accounts[account.id] = account;
    state.persons[hash] = account.id;
    const session = issueSession(ctx, account.id);
    // End of atomic section.

    await store.save();
    return { status: 201, body: { account: toSelf(account), session } };
  });

  route('POST', '/api/auth/recover', async (ctx) => {
    const personId = requireString(ctx.body, 'personId', LIMITS.personId);
    const accountId = store.state.persons[personHash(personId)];
    if (!accountId || !store.state.accounts[accountId]) {
      throw new HttpError(404, 'person_not_found', 'No account matches that demo person identifier.');
    }
    const account = store.state.accounts[accountId];
    const session = issueSession(ctx, account.id);
    await store.save();
    return {
      body: {
        account: toSelf(account),
        session,
        note: 'Simulated recovery. A real service would re-verify identity before restoring access.',
      },
    };
  });

  route('POST', '/api/verification/submit', async (ctx) => {
    const account = requireAccount(ctx);
    if (ctx.body.consent !== true) {
      throw new HttpError(400, 'consent_required', 'Confirm that you understand this verification is simulated.', {
        field: 'consent',
      });
    }
    if (account.status !== 'unverified' && account.status !== 'rejected') {
      throw new HttpError(409, 'invalid_state', `Verification cannot be submitted from status "${account.status}".`, {
        status: account.status,
      });
    }
    const note = requireString(ctx.body, 'note', LIMITS.note, { optional: true });
    account.status = 'pending';
    account.verification.submittedAt = now();
    account.verification.reviewedAt = null;
    account.verification.reviewerId = null;
    account.verification.note = note || null;
    account.verification.attempts += 1;
    await store.save();
    return { body: { account: toSelf(account) } };
  });

  route('PATCH', '/api/me/profile', async (ctx) => {
    const account = requireAccount(ctx);
    if (account.status === 'suspended') {
      throw new HttpError(403, 'not_verified', STATUS_MESSAGES.suspended, { status: 'suspended' });
    }
    const changes = {};
    if (ctx.body.displayName !== undefined) {
      changes.displayName = requireString(ctx.body, 'displayName', LIMITS.displayName);
    }
    if (ctx.body.handle !== undefined) {
      const handle = normalizeHandle(ctx.body);
      uniqueHandleOr409(handle, account.id);
      changes.handle = handle;
    }
    if (ctx.body.bio !== undefined) {
      changes.bio = requireString(ctx.body, 'bio', LIMITS.bio, { optional: true });
    }
    if (Object.keys(changes).length === 0) {
      throw new HttpError(400, 'validation_error', 'Send at least one of displayName, handle or bio.', { field: 'profile' });
    }
    // Only these three fields can change here. Status, role and identity never do.
    Object.assign(account, changes);
    await store.save();
    return { body: { account: toSelf(account) } };
  });

  route('POST', '/api/suspension/appeal', async (ctx) => {
    const account = requireAccount(ctx);
    if (account.status !== 'suspended' || !account.suspension) {
      throw new HttpError(409, 'invalid_state', 'Only a suspended account can appeal a suspension.', { status: account.status });
    }
    const message = requireString(ctx.body, 'message', LIMITS.appeal);
    account.suspension.appeal = { message, at: now() };
    await store.save();
    return { body: { account: toSelf(account) } };
  });

  // ----- routes: verified members -----------------------------------------

  route('GET', '/api/feed', async (ctx) => {
    const viewer = requireMember(ctx);
    return { body: { posts: buildFeed(viewer) } };
  });

  route('POST', '/api/posts', async (ctx) => {
    const author = requireMember(ctx);
    const body = requireString(ctx.body, 'body', LIMITS.post);
    const post = createPost({ authorId: author.id, body });
    store.state.posts.push(post);
    await store.save();
    return { status: 201, body: { post: presentPost(post, author, hiddenFor(author)) } };
  });

  route('POST', '/api/posts/:postId/comments', async (ctx) => {
    const author = requireMember(ctx);
    const post = findVisiblePost(author, ctx.params.postId);
    const body = requireString(ctx.body, 'body', LIMITS.comment);
    const comment = createComment({ authorId: author.id, body });
    post.comments.push(comment);
    await store.save();
    return { status: 201, body: { comment: presentComment(comment), post: presentPost(post, author, hiddenFor(author)) } };
  });

  route('POST', '/api/posts/:postId/reactions', async (ctx) => {
    const member = requireMember(ctx);
    const post = findVisiblePost(member, ctx.params.postId);
    const type = requireOneOf(ctx.body, 'type', REACTIONS);
    if (post.reactions[member.id] === type) {
      delete post.reactions[member.id];
    } else {
      post.reactions[member.id] = type;
    }
    await store.save();
    return { body: { reactions: reactionSummary(post, member.id) } };
  });

  route('DELETE', '/api/posts/:postId', async (ctx) => {
    const member = requireMember(ctx);
    const post = store.state.posts.find((p) => p.id === ctx.params.postId && !p.removed);
    if (!post || post.authorId !== member.id) {
      throw new HttpError(404, 'post_not_found', 'That post is not available or is not yours.');
    }
    post.removed = true;
    post.removal = { by: 'author', reason: null, at: now(), reviewerId: null };
    await store.save();
    return { body: { removed: { type: 'post', id: post.id } } };
  });

  route('DELETE', '/api/posts/:postId/comments/:commentId', async (ctx) => {
    const member = requireMember(ctx);
    const found = findContent('comment', ctx.params.commentId);
    if (!found || found.post.id !== ctx.params.postId || found.comment.removed || found.comment.authorId !== member.id) {
      throw new HttpError(404, 'comment_not_found', 'That comment is not available or is not yours.');
    }
    found.comment.removed = true;
    found.comment.removal = { by: 'author', reason: null, at: now(), reviewerId: null };
    await store.save();
    return { body: { removed: { type: 'comment', id: found.comment.id }, post: presentPost(found.post, member, hiddenFor(member)) } };
  });

  route('GET', '/api/members/:memberId', async (ctx) => {
    const viewer = requireMember(ctx);
    const target = getAccountOr404(ctx.params.memberId);
    return {
      body: {
        member: toPublic(target),
        relationship: {
          blocked: viewer.blocked.includes(target.id),
          muted: viewer.muted.includes(target.id),
          isSelf: viewer.id === target.id,
        },
      },
    };
  });

  function relationshipRoute(action, list, add) {
    route('POST', `/api/members/:memberId/${action}`, async (ctx) => {
      const viewer = requireMember(ctx);
      const target = getAccountOr404(ctx.params.memberId);
      if (target.id === viewer.id) {
        throw new HttpError(400, 'invalid_target', `You cannot ${action} yourself.`);
      }
      const current = viewer[list];
      const index = current.indexOf(target.id);
      if (add && index < 0) current.push(target.id);
      if (!add && index >= 0) current.splice(index, 1);
      await store.save();
      return { body: { blocked: viewer.blocked.slice(), muted: viewer.muted.slice() } };
    });
  }
  relationshipRoute('block', 'blocked', true);
  relationshipRoute('unblock', 'blocked', false);
  relationshipRoute('mute', 'muted', true);
  relationshipRoute('unmute', 'muted', false);

  route('POST', '/api/reports', async (ctx) => {
    const reporter = requireMember(ctx);
    const targetType = requireOneOf(ctx.body, 'targetType', REPORT_TARGETS);
    const targetId = requireString(ctx.body, 'targetId', [1, 100]);
    const reason = requireOneOf(ctx.body, 'reason', REPORT_REASONS);
    const details = requireString(ctx.body, 'details', LIMITS.details, { optional: true });
    if (reason === 'other' && !details) {
      throw new HttpError(400, 'validation_error', 'Add a few words of detail when the reason is "other".', {
        field: 'details',
      });
    }
    let postId = null;
    if (targetType === 'member') {
      const target = getAccountOr404(targetId);
      if (target.id === reporter.id) throw new HttpError(400, 'invalid_target', 'You cannot report yourself.');
    } else {
      const found = findContent(targetType, targetId);
      if (!found) throw new HttpError(404, 'content_not_found', `No ${targetType} with that id.`);
      const item = found.comment || found.post;
      if (item.authorId === reporter.id) {
        throw new HttpError(400, 'invalid_target', 'You cannot report your own content.');
      }
      postId = found.post.id;
    }
    const report = createReport({ reporterId: reporter.id, targetType, targetId, postId, reason, details });
    store.state.reports.push(report);
    await store.save();
    return { status: 201, body: { report: { id: report.id, status: report.status, createdAt: report.createdAt } } };
  });

  // ----- routes: reviewers --------------------------------------------------

  route('GET', '/api/reviewer/overview', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const state = store.state;
    const queue = Object.values(state.accounts)
      .filter((a) => a.status === 'pending')
      .sort((a, b) => String(a.verification.submittedAt).localeCompare(String(b.verification.submittedAt)))
      .map((a) => toReviewerView(a, reviewer));
    const reports = state.reports.map(presentReport);
    const open = reports.filter((r) => r.status === 'open').sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    const resolved = reports
      .filter((r) => r.status !== 'open')
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .slice(0, 50);
    const members = Object.values(state.accounts)
      .sort((a, b) => a.handle.localeCompare(b.handle))
      .map((a) => toReviewerView(a, reviewer));
    const actions = state.actions.slice().sort((a, b) => b.at.localeCompare(a.at)).slice(0, 100);
    return {
      body: {
        queue,
        reports: { open, resolved },
        members,
        actions,
        counts: {
          pending: queue.length,
          openReports: open.length,
          members: members.length,
          appeals: members.filter((m) => m.hasAppeal).length,
        },
      },
    };
  });

  route('GET', '/api/reviewer/actions', async (ctx) => {
    requireReviewer(ctx);
    const actions = store.state.actions.slice().sort((a, b) => b.at.localeCompare(a.at));
    return { body: { actions } };
  });

  route('POST', '/api/reviewer/verifications/:memberId/approve', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const target = getAccountOr404(ctx.params.memberId);
    if (target.status !== 'pending') {
      throw new HttpError(409, 'invalid_state', 'Only pending requests can be approved.', { status: target.status });
    }
    const note = requireString(ctx.body, 'note', LIMITS.note, { optional: true });
    const at = now();
    target.status = 'verified';
    target.verification.reviewedAt = at;
    target.verification.reviewerId = reviewer.id;
    target.verification.lastDecision = { outcome: 'approved', reason: null, at };
    logAction(reviewer, 'approve_verification', 'member', target.id, memberLabel(target), note);
    await store.save();
    return { body: { account: toReviewerView(target, reviewer) } };
  });

  route('POST', '/api/reviewer/verifications/:memberId/reject', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const target = getAccountOr404(ctx.params.memberId);
    if (target.status !== 'pending') {
      throw new HttpError(409, 'invalid_state', 'Only pending requests can be rejected.', { status: target.status });
    }
    const reason = requireString(ctx.body, 'reason', LIMITS.reason);
    const at = now();
    target.status = 'rejected';
    target.verification.reviewedAt = at;
    target.verification.reviewerId = reviewer.id;
    target.verification.lastDecision = { outcome: 'rejected', reason, at };
    logAction(reviewer, 'reject_verification', 'member', target.id, memberLabel(target), reason);
    await store.save();
    return { body: { account: toReviewerView(target, reviewer) } };
  });

  route('POST', '/api/reviewer/members/:memberId/suspend', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const target = getAccountOr404(ctx.params.memberId);
    const reason = requireString(ctx.body, 'reason', LIMITS.reason);
    suspendAccount(target, reviewer, reason);
    await store.save();
    return { body: { account: toReviewerView(target, reviewer) } };
  });

  route('POST', '/api/reviewer/members/:memberId/reinstate', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const target = getAccountOr404(ctx.params.memberId);
    const note = requireString(ctx.body, 'note', LIMITS.note, { optional: true });
    reinstateAccount(target, reviewer, note);
    await store.save();
    return { body: { account: toReviewerView(target, reviewer) } };
  });

  route('POST', '/api/reviewer/content/:type/:contentId/remove', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const type = ctx.params.type;
    if (type !== 'post' && type !== 'comment') {
      throw new HttpError(400, 'validation_error', 'Content type must be "post" or "comment".', { field: 'type' });
    }
    const reason = requireString(ctx.body, 'reason', LIMITS.reason, { optional: true });
    const item = removeContent(type, ctx.params.contentId, reviewer, reason);
    await store.save();
    return { body: { removed: { type, id: item.id, at: item.removal.at } } };
  });

  route('POST', '/api/reviewer/reports/:reportId/resolve', async (ctx) => {
    const reviewer = requireReviewer(ctx);
    const report = store.state.reports.find((r) => r.id === ctx.params.reportId);
    if (!report) throw new HttpError(404, 'report_not_found', 'No report with that id.');
    if (report.status !== 'open') throw new HttpError(409, 'invalid_state', 'This report is already resolved.');
    const outcome = requireOneOf(ctx.body, 'resolution', RESOLUTIONS);
    const note = requireString(ctx.body, 'note', LIMITS.note, { optional: true });
    if (outcome !== 'dismiss' && !note) {
      throw new HttpError(400, 'validation_error', 'Add a note explaining the action; it is shown to the member and kept in the log.', {
        field: 'note',
      });
    }

    if (outcome === 'remove_content') {
      if (report.targetType === 'member') {
        throw new HttpError(400, 'validation_error', 'A member report has no content to remove. Suspend or dismiss instead.', {
          field: 'resolution',
        });
      }
      const found = findContent(report.targetType, report.targetId);
      if (!found) throw new HttpError(404, 'content_not_found', 'The reported content no longer exists.');
      const item = found.comment || found.post;
      if (!item.removed) removeContent(report.targetType, report.targetId, reviewer, note);
    } else if (outcome === 'suspend_member') {
      let target;
      if (report.targetType === 'member') {
        target = getAccountOr404(report.targetId);
      } else {
        const found = findContent(report.targetType, report.targetId);
        if (!found) throw new HttpError(404, 'content_not_found', 'The reported content no longer exists.');
        target = getAccountOr404((found.comment || found.post).authorId);
      }
      suspendAccount(target, reviewer, note);
    }

    report.status = 'resolved';
    report.resolution = { outcome, note: note || null, reviewerId: reviewer.id, at: now() };
    logAction(reviewer, 'resolve_report', 'report', report.id, `Report (${report.reason}) on ${report.targetType}`, outcome);
    await store.save();
    return { body: { report: presentReport(report) } };
  });

  // ----- dispatch -----------------------------------------------------------

  async function handleApi(req, res, url) {
    const ctx = { req, res, url, params: {}, body: {}, headers: {}, token: null, store };
    try {
      let matched = null;
      let methodMismatch = false;
      for (const candidate of routes) {
        const match = candidate.pattern.exec(url.pathname);
        if (!match) continue;
        if (candidate.method !== req.method) {
          methodMismatch = true;
          continue;
        }
        matched = candidate;
        ctx.params = {};
        for (const [key, value] of Object.entries(match.groups || {})) {
          try {
            ctx.params[key] = decodeURIComponent(value);
          } catch (err) {
            ctx.params[key] = value;
          }
        }
        break;
      }
      if (!matched) {
        if (methodMismatch) throw new HttpError(405, 'method_not_allowed', `${req.method} is not allowed here.`);
        throw new HttpError(404, 'not_found', 'No such API route.');
      }
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        ctx.body = await readJson(req, BODY_LIMIT);
      } else {
        req.resume();
      }
      const result = (await matched.handler(ctx)) || {};
      sendJson(res, result.status || 200, result.body === undefined ? {} : result.body, ctx.headers);
    } catch (err) {
      if (err instanceof HttpError) {
        sendJson(res, err.status, Object.assign({ error: err.code, message: err.message }, err.extra), ctx.headers);
        return;
      }
      log('Unhandled error:', err);
      sendJson(res, 500, { error: 'internal_error', message: 'Unexpected server error.' });
    }
  }

  function sendFile(req, res, filePath, stat) {
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': stat.size,
      'Cache-Control': 'no-store',
    });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    const stream = fs.createReadStream(filePath);
    stream.on('error', () => res.destroy());
    stream.pipe(res);
  }

  function serveStatic(req, res, url) {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendJson(res, 405, { error: 'method_not_allowed', message: 'Only GET is supported for pages.' });
      return;
    }
    let rel;
    try {
      rel = decodeURIComponent(url.pathname);
    } catch (err) {
      sendJson(res, 400, { error: 'bad_request', message: 'Malformed path.' });
      return;
    }
    if (rel === '/' || rel === '') rel = '/index.html';
    const absolute = path.normalize(path.join(publicDir, rel));
    if (absolute !== publicDir && !absolute.startsWith(publicDir + path.sep)) {
      sendJson(res, 404, { error: 'not_found', message: 'Not found.' });
      return;
    }
    fs.stat(absolute, (err, stat) => {
      if (!err && stat.isFile()) {
        sendFile(req, res, absolute, stat);
        return;
      }
      if (!path.extname(rel)) {
        const index = path.join(publicDir, 'index.html');
        fs.stat(index, (indexErr, indexStat) => {
          if (indexErr) {
            sendJson(res, 404, { error: 'not_found', message: 'Not found.' });
            return;
          }
          sendFile(req, res, index, indexStat);
        });
        return;
      }
      sendJson(res, 404, { error: 'not_found', message: 'Not found.' });
    });
  }

  function onRequest(req, res) {
    for (const [key, value] of Object.entries(SECURITY_HEADERS)) res.setHeader(key, value);
    let url;
    try {
      url = new URL(req.url, 'http://localhost');
    } catch (err) {
      sendJson(res, 400, { error: 'bad_request', message: 'Malformed URL.' });
      return;
    }
    if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
      handleApi(req, res, url);
      return;
    }
    serveStatic(req, res, url);
  }

  const server = http.createServer(onRequest);

  function listen(port, host) {
    // Make sure the initial seed (if any) is on disk before accepting requests.
    return store.flush().then(
      () =>
        new Promise((resolve, reject) => {
          const onError = (err) => reject(err);
          server.once('error', onError);
          server.listen(port, host || '127.0.0.1', () => {
            server.off('error', onError);
            resolve(server.address());
          });
        })
    );
  }

  function close() {
    return new Promise((resolve) => {
      server.close(() => resolve());
      if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
    }).then(() => store.flush());
  }

  return { server, store, listen, close, dataDir, publicDir };
}

module.exports = {
  createApp,
  HttpError,
  LIMITS,
  BODY_LIMIT,
  SESSION_TTL_MS,
  STATUS_MESSAGES,
  SESSION_COOKIE,
  DEMO_NOTICE,
};
