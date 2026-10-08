# Gather — a verified-community web prototype

Gather is a local prototype of a smaller social network built around
authenticity: you can only read or post after a reviewer verifies you, every
person gets one active account, and harassment is handled by people rather than
by an algorithm that guesses at "negativity".

> **Demo: simulated verification.** Nothing here checks a real identity. The app
> asks for a made-up "demo person identifier" instead of an ID document or a
> selfie, and a demo reviewer approves or rejects requests by hand. Demo sessions
> and the reviewer account switcher are **not** production authentication.

The name "Gather" and the branding are provisional.

## Requirements

- Node.js 18 or newer (20 LTS recommended). Nothing else: no `npm install`, no
  build step, no external services, no paid APIs.
- A modern browser (Edge, Chrome or Firefox).

## Run it on Windows

Open PowerShell or Command Prompt in the folder that contains `server.js`, then:

```powershell
node --check server.js
node server.js
```

You should see:

```
Gather demo is running at http://127.0.0.1:3000
State file: C:\...\Learning\data\state.json
Demo: simulated verification. Not for production use.
```

Open <http://127.0.0.1:3000> in a browser. Press `Ctrl+C` in the terminal to
stop the server. State is written to `data\state.json` on every change and is
reloaded on the next start, so posts, accounts, reports and sessions survive
restarts. The `data` folder is ignored by Git.

Optional environment variables (PowerShell syntax):

```powershell
$env:GATHER_PORT = "4000"              # default 3000
$env:GATHER_DATA_DIR = "C:\temp\gather" # default .\data
$env:GATHER_HOST = "127.0.0.1"         # loopback by default; change deliberately
node server.js
```

To start over with fresh seed data, stop the server and delete `data\state.json`.

## Run the tests

```powershell
node --test
```

Tests live in `test\*.test.js`, use only `node:test` and `node:assert`, start the
server on a random loopback port in a temporary data directory, and clean up
after themselves. They cover:

- access denial for every non-verified state (no session, unverified, pending,
  rejected, suspended) on every member endpoint, including direct API calls;
- one account per person, including eight simultaneous registrations for the
  same identifier, duplicate handles, and recovery;
- member vs reviewer authorisation, self-assignment of status or role, and
  demo-login restrictions;
- submit, approve, reject with reason, resubmit or appeal, suspend, reinstate;
- suspension appeals, their visibility to reviewers, and demo session expiry;
- profile editing (name, handle, bio) with validation, handle uniqueness, and
  proof that it cannot touch status, role or identity;
- authors deleting their own posts and comments, and nobody else's;
- reporting, report resolution (dismiss, remove content, suspend), direct
  content removal and the action history;
- block and mute filtering of posts and comments, in both directions for blocks;
- persistence across a server restart, atomic writes, and recovery from a
  corrupt state file;
- JSON validation, body size limits, field validation, control characters, and
  script-like content staying inert.

## Demo accounts

Open **Demo accounts** in the app to act as any seeded account. Every person
below is fictional. The "person identifier" column is what you type on the
**Recover** page to get back into that account, or on the **Join** page to
trigger the duplicate-person rule.

| Handle   | Name            | Status    | Role     | Person identifier |
|----------|-----------------|-----------|----------|-------------------|
| `imani`  | Imani Okafor    | verified  | reviewer | `DEMO-IMANI-0001` |
| `theo`   | Theo Lindqvist  | verified  | member   | `DEMO-THEO-0002`  |
| `priya`  | Priya Raman     | verified  | member   | `DEMO-PRIYA-0003` |
| `mateo`  | Mateo Álvarez   | verified  | member   | `DEMO-MATEO-0004` |
| `samw`   | Sam Whitaker    | pending   | member   | `DEMO-SAM-0005`   |
| `rowan`  | Rowan Vale      | rejected  | member   | `DEMO-ROWAN-0006` |
| `dex`    | Dex Marlow      | suspended | member   | `DEMO-DEX-0007`   |

### Suggested walkthrough

1. **Onboarding → pending → approval → feed.** On the landing page choose
   *Request to join*. Create an account (any name, a handle such as `ada_test`,
   an identifier such as `DEMO-ADA-0101`). Submit for verification. Open
   *Demo accounts*, act as `imani`, approve the request in the reviewer queue.
   Open *Recover*, enter `DEMO-ADA-0101`, and you land in the feed.
2. **Duplicate person.** Sign out, choose *Join* again and reuse
   `DEMO-ADA-0101` with a different name and handle. The server refuses it and
   the page offers account recovery instead.
3. **Denied direct access.** With no session, or as `samw`, `rowan` or `dex`,
   request `http://127.0.0.1:3000/api/feed` directly (browser address bar or
   `curl`). You get a 401 or 403 JSON response, never the feed.
4. **Posting, comments, reactions.** As `theo` or `priya`, post, reply, and
   react. Click a name to open the profile card.
5. **Report, block, mute.** From a profile card, mute or block a member and
   watch their content disappear from your feed only. Use *Report* on a post
   or comment.
6. **Moderation.** As `imani`, open *Reviewer*: dismiss or act on reports,
   remove content, suspend and reinstate members, and read the history.
7. **Rejected-user recovery.** As `rowan`, read the rejection reason on the
   status page, open *Profile* to change the handle the reviewer objected to,
   then resubmit with an appeal note. It reappears in the queue.
8. **Suspension appeal.** As `dex`, read the suspension reason and send an
   appeal from the status page. As `imani`, open *Reviewer → Members*: the
   appeal shows next to the suspension, and *Reinstate* clears it.
9. **Your own content.** As any verified member, edit your name, handle or bio
   from *Profile*, and delete one of your own posts or comments from the feed.
   Deleted content is hidden, not erased; a reviewer reading a report on it is
   told it was removed by its author.

## How it is built

```
server.js          entry point: node server.js
lib/app.js         HTTP server, routing, sessions, access control, feed, moderation
lib/store.js       JSON-file persistence with atomic writes
lib/seed.js        fictional demo accounts, posts, reports and action history
lib/models.js      record factories and enumerations
lib/identity.js    hashing of the demo person identifier
lib/rules.js       community rules (served by GET /api/rules)
public/            index.html, styles.css, app.js, favicon.svg (no build step)
test/              node --test suites plus helpers.js
```

Only Node built-ins are used (`node:http`, `node:fs`, `node:path`,
`node:crypto`). The frontend is a single-page app with hash routes; it builds
every element with `document.createElement` and `textContent`, never with
`innerHTML`, so user content is always rendered as text.

### Status model

`unverified → pending → verified | rejected`, plus `suspended` which a reviewer
can apply to any account and lift again. Only `verified` accounts can read the
feed or create anything, and this is enforced on every member endpoint on the
server, not only in the UI. Pending, rejected and suspended people can read
their own status through `GET /api/me`.

### One account per person

The demo person identifier is normalised, salted with a fixed non-secret string
and hashed with SHA-256. The hash maps to exactly one account. The check and the
insert happen synchronously in one request handler, so concurrent registrations
cannot both succeed. A second attempt gets `409 person_already_registered` and a
pointer to `POST /api/auth/recover`. **This only simulates uniqueness**: matching
real people to real identities is outside this prototype.

### Sessions

A session is a random token stored on the server and sent as an `HttpOnly`
cookie (`gather_session`) or as `Authorization: Bearer <token>`. Tokens are
returned in JSON so tests can use them and expire after 30 days. Seeded
accounts can be selected through `POST /api/auth/demo-login`; accounts created
through onboarding cannot, and must use recovery instead. All of this is
labelled insecure in the UI.

### Content removal

Nothing is erased. A post or comment removed by a reviewer, or deleted by its
author, is marked removed and hidden from every feed; the record stays so that
reports and the action history still make sense. Content from a suspended
member is hidden while the suspension lasts and reappears on reinstatement.

## API

All routes return JSON. Errors look like
`{ "error": "code", "message": "human text", ...extra }`.

| Method | Route | Who | Purpose |
|--------|-------|-----|---------|
| GET | `/api/health` | anyone | liveness |
| GET | `/api/rules` | anyone | community rules |
| GET | `/api/me` | anyone | current account (or `null`) and demo notice |
| GET | `/api/auth/demo-accounts` | anyone | seeded accounts for the switcher |
| POST | `/api/auth/demo-login` | anyone | `{ accountId }` → session for a seeded account |
| POST | `/api/auth/logout` | anyone | clear the session |
| POST | `/api/onboarding/register` | anyone | `{ displayName, handle, personId }` → unverified account + session; `409` on duplicate person or handle |
| POST | `/api/auth/recover` | anyone | `{ personId }` → session for the existing account |
| POST | `/api/verification/submit` | signed in | `{ consent: true, note? }` from `unverified` or `rejected` → `pending` |
| PATCH | `/api/me/profile` | signed in, not suspended | any of `{ displayName, handle, bio }`; nothing else can change |
| POST | `/api/suspension/appeal` | suspended | `{ message }` (10–1000 chars); shown to reviewers, cleared on reinstatement |
| GET | `/api/feed` | verified | posts visible to the viewer |
| POST | `/api/posts` | verified | `{ body }` |
| POST | `/api/posts/:postId/comments` | verified | `{ body }` |
| POST | `/api/posts/:postId/reactions` | verified | `{ type }` toggles `appreciate`, `insightful` or `support` |
| DELETE | `/api/posts/:postId` | verified, author only | hides your own post |
| DELETE | `/api/posts/:postId/comments/:commentId` | verified, author only | hides your own comment |
| GET | `/api/members/:memberId` | verified | public profile + viewer relationship |
| POST | `/api/members/:memberId/block` `/unblock` `/mute` `/unmute` | verified | relationship changes |
| POST | `/api/reports` | verified | `{ targetType: post\|comment\|member, targetId, reason, details? }` |
| GET | `/api/reviewer/overview` | reviewer | queue, reports, members, actions, counts |
| GET | `/api/reviewer/actions` | reviewer | full action history |
| POST | `/api/reviewer/verifications/:memberId/approve` | reviewer | `{ note? }` |
| POST | `/api/reviewer/verifications/:memberId/reject` | reviewer | `{ reason }` (required) |
| POST | `/api/reviewer/members/:memberId/suspend` | reviewer | `{ reason }` (required) |
| POST | `/api/reviewer/members/:memberId/reinstate` | reviewer | `{ note? }` |
| POST | `/api/reviewer/content/:type/:contentId/remove` | reviewer | `type` is `post` or `comment`; `{ reason? }` |
| POST | `/api/reviewer/reports/:reportId/resolve` | reviewer | `{ resolution: dismiss\|remove_content\|suspend_member, note }` |

"Verified" means status `verified`; a suspended reviewer loses reviewer access.
There is deliberately no endpoint through which a member can change their own
status or role.

Limits: JSON bodies up to 32 KB; display names 2–40 characters; handles
`[a-z0-9_]{3,20}`; identifiers `[A-Za-z0-9-]{4,64}`; posts 1–1000 characters;
comments 1–500; reasons 3–300; notes and details up to 500; bios up to 200;
appeals 10–1000. Control characters are rejected.

## Security notes

- Everything submitted is treated as untrusted text. The API never returns
  HTML, and the page never injects user content as HTML. A strict
  `Content-Security-Policy` (`script-src 'self'`, no inline scripts or styles)
  is sent with every response, along with `X-Content-Type-Options: nosniff`
  and `X-Frame-Options: DENY`.
- The raw demo person identifier is never stored; only its salted hash is. The
  hash is never included in any API response.
- Static files are served from `public/` only; path traversal is refused.
- The server binds to `127.0.0.1` unless `GATHER_HOST` says otherwise.

## Known limitations and production gaps

This is a prototype for testing flows, not a product. Before anything like it
could be used for real, at least the following are missing:

- **Real verification provider.** The demo identifier proves nothing. A real
  service needs a vetted identity-verification provider, liveness checks, and a
  process for people the provider cannot verify.
- **Real authentication.** Demo sessions are random tokens with no expiry,
  no password or passkey, no device management and no rate limiting. The
  demo account switcher must not exist in production.
- **Identity deduplication and recovery.** One-account-per-person is simulated
  by hashing a string the user typed. Real deduplication needs the provider's
  identity assertion, a recovery flow that re-verifies the person, and a policy
  for shared or changed identities.
- **Privacy and retention.** Decide what verification data is kept, for how
  long, who can see it, how deletion requests are handled, and what the legal
  basis is in each jurisdiction.
- **Moderation staffing and appeals.** Reviewers here are one seeded account.
  A real community needs trained reviewers, escalation, conflict-of-interest
  rules, a written appeals process with deadlines, and transparency reporting.
- **Abuse resistance.** No rate limits, no spam heuristics, no account-age
  gating, no audit of reviewer actions by a second reviewer. Fake accounts are
  not impossible; verification raises the cost, it does not eliminate them.
- **Scale and operations.** A single JSON file is fine for a demo and wrong for
  anything else: no concurrency across processes, no backups, no migrations.

Other limitations of the prototype itself:

- Posts and comments can be deleted by their authors but not edited.
- Content from suspended members is hidden rather than deleted; reinstating
  the member brings it back.
- Comments by blocked or muted members are hidden only for the viewer; blocks
  hide content in both directions, mutes in one.
- There is no notification system; a pending user has to refresh the status
  page or sign back in after approval, and a suspended member is not told when
  an appeal is read.
- Appeals have no deadline and no second-reviewer requirement.
- A corrupt `state.json` is moved aside (`state.json.corrupt-<timestamp>.json`)
  and the app reseeds; nothing is merged back automatically.
