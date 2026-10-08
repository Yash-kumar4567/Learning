/* Gather demo client. Plain JavaScript, no build step.
   All user content is inserted with textContent / createTextNode, never as HTML. */
(function () {
  'use strict';

  const REACTION_LABELS = { appreciate: 'Appreciate', insightful: 'Insightful', support: 'Support' };
  const REPORT_REASONS = [
    ['harassment', 'Harassment'],
    ['threat', 'Threat'],
    ['impersonation', 'Impersonation'],
    ['spam', 'Spam'],
    ['other', 'Something else'],
  ];
  const STATUS_LABELS = {
    unverified: 'Not yet submitted',
    pending: 'Pending review',
    verified: 'Verified',
    rejected: 'Not approved',
    suspended: 'Suspended',
  };
  const RESOLUTION_LABELS = {
    dismiss: 'dismissed',
    remove_content: 'content removed',
    suspend_member: 'member suspended',
  };
  const ACTION_LABELS = {
    approve_verification: 'Approved verification',
    reject_verification: 'Rejected verification',
    suspend_member: 'Suspended member',
    reinstate_member: 'Reinstated member',
    remove_post: 'Removed post',
    remove_comment: 'Removed comment',
    resolve_report: 'Resolved report',
  };

  const app = { me: null, demo: null, firstRender: true, reviewerTab: 'queue' };
  const $nav = document.getElementById('nav');
  const $main = document.getElementById('main');
  const $toasts = document.getElementById('toasts');

  // ---------------------------------------------------------------------
  // DOM helper
  // ---------------------------------------------------------------------

  function append(el, children) {
    for (const child of children) {
      if (child === null || child === undefined || child === false || child === '') continue;
      if (Array.isArray(child)) {
        append(el, child);
        continue;
      }
      el.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
  }

  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    if (props) {
      for (const key of Object.keys(props)) {
        const value = props[key];
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') el.className = value;
        else if (key === 'text') el.textContent = value;
        else if (key === 'value') el.value = value;
        else if (key === 'checked') el.checked = Boolean(value);
        else if (key.startsWith('on') && typeof value === 'function') {
          el.addEventListener(key.slice(2).toLowerCase(), value);
        } else el.setAttribute(key, value === true ? '' : String(value));
      }
    }
    append(el, children);
    return el;
  }

  // ---------------------------------------------------------------------
  // API
  // ---------------------------------------------------------------------

  class ApiError extends Error {
    constructor(status, code, message, data) {
      super(message);
      this.status = status;
      this.code = code;
      this.data = data || {};
    }
  }

  async function api(method, path, body) {
    const options = { method, headers: { Accept: 'application/json' }, credentials: 'same-origin' };
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    let response;
    try {
      response = await fetch(path, options);
    } catch (err) {
      throw new ApiError(0, 'network_error', 'Could not reach the local server. Is "node server.js" still running?');
    }
    let data = {};
    try {
      data = await response.json();
    } catch (err) {
      data = {};
    }
    if (!response.ok) {
      throw new ApiError(
        response.status,
        data.error || 'error',
        data.message || `Request failed with status ${response.status}.`,
        data
      );
    }
    return data;
  }

  async function refreshMe() {
    const data = await api('GET', '/api/me');
    app.me = data.account;
    app.demo = data.demo;
    return app.me;
  }

  // ---------------------------------------------------------------------
  // Feedback: toasts, dialogs, errors
  // ---------------------------------------------------------------------

  function toast(message, kind) {
    const el = h('div', { class: `toast ${kind || ''}`.trim(), role: kind === 'error' ? 'alert' : 'status' }, message);
    $toasts.append(el);
    setTimeout(() => el.remove(), 4500);
  }

  let dialogSeq = 0;

  function confirmDialog(spec) {
    return new Promise((resolve) => {
      dialogSeq += 1;
      const titleId = `dialog-title-${dialogSeq}`;
      const fieldEls = (spec.fields || []).map((field) => {
        const id = `dialog-${dialogSeq}-${field.name}`;
        let input;
        if (field.type === 'textarea') {
          input = h('textarea', {
            id,
            name: field.name,
            required: Boolean(field.required),
            maxlength: field.maxlength || 500,
            placeholder: field.placeholder || null,
            rows: 3,
          });
        } else if (field.type === 'select') {
          input = h(
            'select',
            { id, name: field.name, required: Boolean(field.required) },
            field.options.map(([value, label]) => h('option', { value }, label))
          );
        } else {
          input = h('input', {
            id,
            name: field.name,
            type: 'text',
            required: Boolean(field.required),
            maxlength: field.maxlength || 300,
            placeholder: field.placeholder || null,
          });
        }
        return h(
          'div',
          { class: 'field' },
          h('label', { for: id }, field.label),
          field.hint ? h('span', { class: 'hint' }, field.hint) : null,
          input
        );
      });

      const cancel = h('button', { type: 'button', class: 'btn quiet' }, spec.cancelLabel || 'Cancel');
      const confirm = h(
        'button',
        { type: 'submit', class: `btn ${spec.danger ? 'danger' : 'primary'}` },
        spec.confirmLabel || 'Confirm'
      );
      const form = h(
        'form',
        { class: 'dialog-form' },
        h('h2', { id: titleId }, spec.title),
        spec.message ? h('p', { class: 'muted' }, spec.message) : null,
        fieldEls,
        h('div', { class: 'dialog-actions' }, cancel, confirm)
      );
      const dialog = h('dialog', { class: 'dialog', 'aria-labelledby': titleId }, form);
      let result = null;

      form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (!form.reportValidity()) return;
        const data = {};
        new FormData(form).forEach((value, key) => {
          data[key] = typeof value === 'string' ? value.trim() : value;
        });
        result = data;
        dialog.close();
      });
      cancel.addEventListener('click', () => {
        result = null;
        dialog.close();
      });
      dialog.addEventListener('cancel', () => {
        result = null;
      });
      dialog.addEventListener('close', () => {
        dialog.remove();
        resolve(result);
      });

      document.body.append(dialog);
      dialog.showModal();
      const first = form.querySelector('input, select, textarea');
      if (first) first.focus();
      else confirm.focus();
    });
  }

  function errorCard(err, retry) {
    return h(
      'div',
      { class: 'card danger', role: 'alert' },
      h('h2', {}, 'Something went wrong'),
      h('p', {}, err && err.message ? err.message : 'Unknown error.'),
      retry ? h('div', { class: 'btn-row' }, h('button', { type: 'button', class: 'btn', onClick: retry }, 'Try again')) : null
    );
  }

  function signedOutCard(title, text) {
    return h(
      'div',
      { class: 'narrow' },
      h(
        'div',
        { class: 'card' },
        h('h1', {}, title),
        h('p', { class: 'muted' }, text),
        h(
          'div',
          { class: 'btn-row' },
          h('a', { class: 'btn primary', href: '#/join' }, 'Request to join'),
          h('a', { class: 'btn', href: '#/recover' }, 'Recover an account'),
          h('a', { class: 'btn quiet', href: '#/demo' }, 'Demo accounts')
        )
      )
    );
  }

  // ---------------------------------------------------------------------
  // Formatting and small components
  // ---------------------------------------------------------------------

  function formatTimeText(iso) {
    if (!iso) return 'unknown time';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return 'unknown time';
    const diff = Date.now() - date.getTime();
    const minutes = Math.round(diff / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} min ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 24) return `${hours} h ago`;
    const days = Math.round(hours / 24);
    if (days < 7) return `${days} d ago`;
    return date.toLocaleDateString();
  }

  function formatTime(iso) {
    const date = new Date(iso);
    return h('time', { datetime: iso, title: Number.isNaN(date.getTime()) ? null : date.toLocaleString() }, formatTimeText(iso));
  }

  function initials(name) {
    const parts = String(name || '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2);
    return parts.map((part) => part[0].toUpperCase()).join('') || '?';
  }

  function avatarHue(id) {
    let hash = 0;
    for (const ch of String(id || '')) hash = (hash * 31 + ch.charCodeAt(0)) % 360;
    return 130 + (hash % 80);
  }

  function avatar(member, size) {
    const el = h('span', { class: `avatar ${size || ''}`.trim(), 'aria-hidden': 'true' }, initials(member.displayName));
    el.style.background = `hsl(${avatarHue(member.id)} 40% 36%)`;
    return el;
  }

  function badge(member) {
    if (member.verified) return h('span', { class: 'badge verified' }, 'Verified');
    return null;
  }

  function reviewerBadge() {
    return h('span', { class: 'badge reviewer' }, 'Reviewer');
  }

  function statusBadge(status) {
    return h('span', { class: `badge ${status}` }, STATUS_LABELS[status] || status);
  }

  function identity(member, options) {
    const opts = options || {};
    const names = h(
      'span',
      { class: 'names' },
      h('span', { class: 'name', id: opts.id || null }, member.displayName, ' ', badge(member), member.role === 'reviewer' ? [' ', reviewerBadge()] : null),
      h('span', { class: 'handle' }, `@${member.handle}`)
    );
    if (opts.onClick) {
      return h(
        'button',
        { type: 'button', class: 'member-button identity', onClick: opts.onClick, 'aria-label': `View profile of ${member.displayName}` },
        avatar(member),
        names
      );
    }
    return h('span', { class: 'identity' }, avatar(member), names);
  }

  function textField(spec) {
    const id = `field-${spec.name}`;
    const input = h('input', {
      type: 'text',
      id,
      name: spec.name,
      maxlength: spec.maxlength || null,
      placeholder: spec.placeholder || null,
      autocomplete: spec.autocomplete || 'off',
      autocapitalize: spec.autocapitalize || null,
      spellcheck: 'false',
      'aria-describedby': spec.hint ? `${id}-hint` : null,
      required: true,
    });
    const error = h('p', { class: 'error', id: `${id}-error`, hidden: true });
    const wrap = h(
      'div',
      { class: 'field' },
      h('label', { for: id }, spec.label),
      spec.hint ? h('span', { class: 'hint', id: `${id}-hint` }, spec.hint) : null,
      input,
      error
    );
    return {
      wrap,
      input,
      setError(message) {
        if (message) {
          error.textContent = message;
          error.hidden = false;
          wrap.classList.add('invalid');
          input.setAttribute('aria-invalid', 'true');
        } else {
          error.textContent = '';
          error.hidden = true;
          wrap.classList.remove('invalid');
          input.removeAttribute('aria-invalid');
        }
      },
    };
  }

  function reasonLabel(code) {
    const found = REPORT_REASONS.find(([value]) => value === code);
    return found ? found[1] : code;
  }

  // ---------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------

  function currentPath() {
    const hash = window.location.hash || '#/';
    const path = hash.slice(1);
    return path.startsWith('/') ? path : `/${path}`;
  }

  function navigate(path) {
    if (currentPath() === path) {
      render();
      return;
    }
    window.location.hash = `#${path}`;
  }

  function renderNav(current) {
    const me = app.me;
    const link = (href, label) => h('a', { href, 'aria-current': current === href.slice(1) ? 'page' : null }, label);
    const items = [];
    if (!me) {
      items.push(link('#/', 'Home'), link('#/join', 'Join'), link('#/recover', 'Recover'), link('#/rules', 'Rules'), link('#/demo', 'Demo accounts'));
    } else {
      items.push(me.canAccessCommunity ? link('#/feed', 'Feed') : link('#/status', 'Your status'));
      if (me.status !== 'suspended') items.push(link('#/profile', 'Profile'));
      items.push(link('#/rules', 'Rules'));
      if (me.isReviewer) items.push(link('#/reviewer', 'Reviewer'));
      items.push(link('#/demo', 'Demo accounts'));
      items.push(h('span', { class: 'who' }, `@${me.handle}`));
      items.push(h('button', { type: 'button', onClick: signOut }, 'Sign out'));
    }
    $nav.replaceChildren(...items);
  }

  async function signOut() {
    try {
      await api('POST', '/api/auth/logout', {});
    } catch (err) {
      // Clearing the local state is enough for the demo.
    }
    app.me = null;
    toast('Signed out.');
    navigate('/');
  }

  // ---------------------------------------------------------------------
  // Views: landing, rules, demo accounts, recover
  // ---------------------------------------------------------------------

  function viewLanding() {
    const me = app.me;
    const primary = me
      ? h('a', { class: 'btn primary', href: me.canAccessCommunity ? '#/feed' : '#/status' }, me.canAccessCommunity ? 'Open the feed' : 'View your status')
      : h('a', { class: 'btn primary', href: '#/join' }, 'Request to join');

    const valueCard = (title, text) => h('div', { class: 'card' }, h('h3', {}, title), h('p', { class: 'muted' }, text));

    $main.replaceChildren(
      h(
        'section',
        { class: 'hero' },
        h('span', { class: 'eyebrow' }, 'A smaller, verified community'),
        h('h1', {}, 'Real people. One account each. Room to disagree.'),
        h(
          'p',
          { class: 'lede' },
          'Gather is a quieter kind of social space. Every member is a verified person with a single account, so conversations happen between people who stand behind what they say.'
        ),
        h(
          'div',
          { class: 'callout warn' },
          h(
            'span',
            {},
            h('strong', {}, 'Demo: simulated verification. '),
            'This prototype pretends to verify you. It asks for a made-up demo person identifier instead of an ID or selfie, and a demo reviewer approves or rejects the request by hand.'
          )
        ),
        h(
          'div',
          { class: 'btn-row' },
          primary,
          me ? null : h('a', { class: 'btn', href: '#/recover' }, 'Recover an existing account'),
          h('a', { class: 'btn quiet', href: '#/demo' }, 'Try a demo account')
        )
      ),
      h(
        'section',
        { class: 'grid three', 'aria-label': 'What makes Gather different' },
        valueCard('Verified people only', 'You can only read or post once a reviewer has approved your request. No anonymous drive-by accounts, and one active account per person.'),
        valueCard('Respectful by design', 'Disagreement is welcome. Harassment, threats, impersonation and spam are not. People review reports; nothing is auto-flagged for being negative.'),
        valueCard('Private by default', 'Profiles show a name, a handle and a bio. Verification details stay with reviewers and are never shown on a public profile.')
      ),
      h(
        'section',
        { class: 'card', 'aria-labelledby': 'how-title' },
        h('h2', { id: 'how-title' }, 'How joining works'),
        h(
          'ol',
          { class: 'steps' },
          h('li', {}, h('span', {}, h('strong', {}, 'Create your account. '), 'Pick a display name, a handle and a demo person identifier.')),
          h('li', {}, h('span', {}, h('strong', {}, 'Submit for verification. '), 'Read what the demo collects, tick the consent box, and join the review queue.')),
          h('li', {}, h('span', {}, h('strong', {}, 'A reviewer decides. '), 'Approved members get the feed. Rejected requests get a reason and a way to resubmit or appeal.'))
        ),
        h('p', { class: 'faint' }, 'Testing tip: the Demo accounts page lets you act as the reviewer to approve yourself.')
      ),
      h(
        'section',
        { class: 'grid two', 'aria-label': 'What Gather promises and what it does not' },
        h(
          'div',
          { class: 'card success' },
          h('h3', {}, 'What we aim for'),
          h(
            'ul',
            {},
            h('li', {}, 'One active account per person, so reputation and accountability mean something.'),
            h('li', {}, 'Rules that target behaviour, not opinions: harassment, threats, impersonation and spam are out; disagreement is in.'),
            h('li', {}, 'Moderation by people. Every reviewer action is logged, and rejected requests can be resubmitted or appealed.'),
            h('li', {}, 'Privacy by default. Verification details stay with reviewers and never appear on a profile.')
          )
        ),
        h(
          'div',
          { class: 'card notice' },
          h('h3', {}, 'What this prototype cannot promise'),
          h(
            'ul',
            {},
            h('li', {}, 'It does not verify anyone. The demo person identifier is a stand-in, and whoever types the same one is treated as the same person.'),
            h('li', {}, 'Fake accounts are not impossible. Verification raises the cost of abuse; it does not eliminate it.'),
            h('li', {}, 'Nothing detects negativity automatically. Reports are read by a person, and reasonable people will sometimes disagree with the outcome.'),
            h('li', {}, 'Sessions and the demo account switcher are testing shortcuts, not real sign-in.')
          )
        )
      ),
      h(
        'section',
        { class: 'card', 'aria-labelledby': 'rules-title' },
        h('h2', { id: 'rules-title' }, 'Rules, appeals and privacy, in short'),
        h(
          'div',
          { class: 'grid three' },
          h('div', {}, h('h3', {}, 'Respectful disagreement'), h('p', { class: 'muted' }, 'Say a take is wrong and why. Do not attack the person saying it. Reviewers are asked to leave disagreement alone.')),
          h('div', {}, h('h3', {}, 'Appeals'), h('p', { class: 'muted' }, 'A rejected verification shows its reason and can be resubmitted with a note. A suspended member can send an appeal that reviewers see next to the suspension.')),
          h('div', {}, h('h3', {}, 'Privacy'), h('p', { class: 'muted' }, 'Profiles show a name, a handle and a bio. The demo identifier is stored only as a hash and never shown, not even to reviewers.'))
        ),
        h('div', { class: 'btn-row' }, h('a', { class: 'btn', href: '#/rules' }, 'Read the full rules'))
      )
    );
  }

  async function viewRules() {
    const data = await api('GET', '/api/rules');
    $main.replaceChildren(
      h(
        'div',
        { class: 'narrow stack' },
        h(
          'div',
          {},
          h('span', { class: 'eyebrow' }, 'Community rules'),
          h('h1', {}, 'How we treat each other'),
          h('p', { class: 'muted' }, 'Short, specific, and enforced by people. Disagreement is fine. Cruelty is not.')
        ),
        data.rules.map((rule) =>
          h(
            'section',
            { class: 'card rule', 'aria-labelledby': `rule-${rule.id}` },
            h('h3', { id: `rule-${rule.id}` }, rule.title),
            h('p', { class: 'muted' }, rule.summary),
            h(
              'div',
              { class: 'rule-cols' },
              h('div', {}, h('span', { class: 'ok' }, 'Fine'), h('ul', {}, rule.allowed.map((text) => h('li', {}, text)))),
              h('div', {}, h('span', { class: 'no' }, 'Not fine'), h('ul', {}, rule.notAllowed.map((text) => h('li', {}, text))))
            )
          )
        )
      )
    );
  }

  async function demoLogin(account) {
    try {
      const data = await api('POST', '/api/auth/demo-login', { accountId: account.id });
      app.me = data.account;
      toast(`Now acting as ${account.displayName}.`, 'success');
      navigate(app.me.canAccessCommunity ? '/feed' : '/status');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  async function viewDemo() {
    const data = await api('GET', '/api/auth/demo-accounts');
    const list = h(
      'ul',
      { class: 'demo-list' },
      data.accounts.map((account) => {
        const current = app.me && app.me.id === account.id;
        return h(
          'li',
          { class: `card ${current ? 'current' : ''}`.trim() },
          avatar(account),
          h(
            'div',
            { class: 'grow' },
            h('div', {}, h('strong', {}, account.displayName), ' ', h('span', { class: 'faint' }, `@${account.handle}`)),
            h('div', { class: 'row' }, statusBadge(account.status), account.role === 'reviewer' ? reviewerBadge() : null)
          ),
          h(
            'button',
            { type: 'button', class: `btn small ${current ? '' : 'primary'}`.trim(), disabled: current, onClick: () => demoLogin(account) },
            current ? 'Current account' : 'Use this account'
          )
        );
      })
    );
    $main.replaceChildren(
      h(
        'div',
        { class: 'narrow stack' },
        h(
          'div',
          {},
          h('h1', {}, 'Demo accounts'),
          h('p', { class: 'muted' }, 'Switch between seeded accounts to try every state: a reviewer, verified members, and pending, rejected and suspended people.')
        ),
        h(
          'div',
          { class: 'callout warn' },
          h('span', {}, h('strong', {}, 'Insecure by design. '), 'Picking an account here is a local testing shortcut, not authentication. A real product needs real sign-in. The server still checks every member and reviewer action.')
        ),
        list,
        h('p', { class: 'faint' }, 'Accounts you create through onboarding are not listed here. Use Recover with the same demo person identifier instead.')
      )
    );
  }

  async function recover(personId) {
    try {
      const data = await api('POST', '/api/auth/recover', { personId });
      app.me = data.account;
      toast(`Welcome back, ${data.account.displayName}.`, 'success');
      navigate(app.me.canAccessCommunity ? '/feed' : '/status');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function viewRecover() {
    const personField = textField({
      name: 'personId',
      label: 'Demo person identifier',
      hint: 'The same made-up identifier you used when you joined, for example DEMO-THEO-0002.',
      maxlength: 64,
      placeholder: 'DEMO-XXXX-0000',
    });
    const button = h('button', { type: 'submit', class: 'btn primary' }, 'Recover access');
    const form = h(
      'form',
      {
        class: 'card',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          const personId = personField.input.value.trim();
          personField.setError('');
          if (!/^[A-Za-z0-9-]{4,64}$/.test(personId)) {
            personField.setError('Use 4 to 64 letters, numbers or dashes.');
            personField.input.focus();
            return;
          }
          button.disabled = true;
          try {
            await recover(personId);
          } finally {
            button.disabled = false;
          }
        },
      },
      h('h1', {}, 'Recover an existing account'),
      h('p', { class: 'muted' }, 'Gather allows one active account per person. If you already joined, recover that account instead of creating a new one.'),
      personField.wrap,
      h('div', { class: 'callout warn' }, 'Simulated recovery: in this demo the identifier alone restores access. A real service would re-verify you first.'),
      h('div', { class: 'btn-row' }, button, h('a', { class: 'btn quiet', href: '#/join' }, 'I am new here'))
    );
    $main.replaceChildren(h('div', { class: 'narrow' }, form));
  }

  // ---------------------------------------------------------------------
  // Views: onboarding and status
  // ---------------------------------------------------------------------

  function verificationForm(spec) {
    const consent = h('input', { type: 'checkbox', id: 'consent', name: 'consent' });
    const note = h('textarea', {
      id: 'verification-note',
      name: 'note',
      maxlength: 500,
      rows: 3,
      placeholder: spec.appeal ? 'Explain what changed, or why the decision should be reconsidered.' : 'Optional. Anything the reviewer should know.',
    });
    const error = h('div', { class: 'callout error', role: 'alert', hidden: true });
    const button = h('button', { type: 'submit', class: 'btn primary' }, spec.buttonLabel);
    const showError = (message) => {
      error.textContent = message;
      error.hidden = false;
    };
    return h(
      'form',
      {
        class: 'card',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          error.hidden = true;
          if (!consent.checked) {
            showError('Tick the consent box to continue.');
            consent.focus();
            return;
          }
          button.disabled = true;
          try {
            const data = await api('POST', '/api/verification/submit', { consent: true, note: note.value.trim() });
            app.me = data.account;
            toast('Submitted. A reviewer will look at your request.', 'success');
            navigate('/status');
          } catch (err) {
            showError(err.message);
          } finally {
            button.disabled = false;
          }
        },
      },
      h('h2', {}, spec.heading),
      h('p', { class: 'muted' }, spec.intro),
      h(
        'div',
        { class: 'card sunken' },
        h('h3', {}, 'What this demo collects'),
        h(
          'ul',
          {},
          h('li', {}, 'No ID document, selfie, phone number or address. Nothing is checked against a real identity source.'),
          h('li', {}, 'Your display name, your handle, and a hashed form of the demo person identifier you typed.'),
          h('li', {}, 'A demo reviewer approves or rejects the request by hand and the decision is logged.')
        )
      ),
      h('div', { class: 'field' }, h('label', { for: 'verification-note' }, spec.appeal ? 'Message to the reviewer (appeal)' : 'Note to the reviewer (optional)'), note),
      h(
        'div',
        { class: 'checkbox' },
        consent,
        h('label', { for: 'consent' }, 'I understand this verification is simulated for testing. No real identity check happens and this is not a production identity service.')
      ),
      error,
      h('div', { class: 'btn-row' }, button)
    );
  }

  function recoveryPanel(personId) {
    return h(
      'div',
      { class: 'card notice', role: 'alert' },
      h('h2', {}, 'This person already has an account'),
      h('p', {}, 'Gather allows one active account per person. Instead of creating another, recover the existing one.'),
      h('p', { class: 'faint' }, 'The demo matches on the synthetic identifier only. Real-world identity matching is outside this prototype.'),
      h(
        'div',
        { class: 'btn-row' },
        h('button', { type: 'button', class: 'btn primary', onClick: () => recover(personId) }, 'Recover the existing account'),
        h('a', { class: 'btn quiet', href: '#/recover' }, 'Go to the recovery page')
      )
    );
  }

  function viewJoin() {
    if (app.me) {
      $main.replaceChildren(
        h(
          'div',
          { class: 'narrow' },
          h(
            'div',
            { class: 'card' },
            h('h1', {}, 'You already have an account'),
            h('p', { class: 'muted' }, `You are signed in as @${app.me.handle}. One person, one account: there is nothing more to create.`),
            h(
              'div',
              { class: 'btn-row' },
              h('a', { class: 'btn primary', href: app.me.canAccessCommunity ? '#/feed' : '#/status' }, app.me.canAccessCommunity ? 'Open the feed' : 'View your status'),
              h('button', { type: 'button', class: 'btn quiet', onClick: signOut }, 'Sign out')
            )
          )
        )
      );
      return;
    }

    const displayName = textField({ name: 'displayName', label: 'Display name', hint: '2 to 40 characters. The name people see.', maxlength: 40, placeholder: 'Ada Example', autocomplete: 'nickname' });
    const handle = textField({ name: 'handle', label: 'Community handle', hint: 'Lowercase letters, numbers and underscores, 3 to 20 characters.', maxlength: 20, placeholder: 'ada_example', autocapitalize: 'none' });
    const personId = textField({
      name: 'personId',
      label: 'Demo person identifier',
      hint: 'A made-up code that stands in for a real identity check, for example DEMO-ADA-0101. Reusing a code someone already used is treated as the same person.',
      maxlength: 64,
      placeholder: 'DEMO-ADA-0101',
      autocapitalize: 'characters',
    });
    const formError = h('div', { class: 'callout error', role: 'alert', hidden: true });
    const extra = h('div', { class: 'stack' });
    const button = h('button', { type: 'submit', class: 'btn primary' }, 'Create account');

    const form = h(
      'form',
      {
        class: 'card',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          formError.hidden = true;
          extra.replaceChildren();
          const values = {
            displayName: displayName.input.value.trim(),
            handle: handle.input.value.trim().replace(/^@/, '').toLowerCase(),
            personId: personId.input.value.trim(),
          };
          let firstInvalid = null;
          const check = (field, ok, message) => {
            field.setError(ok ? '' : message);
            if (!ok && !firstInvalid) firstInvalid = field;
          };
          check(displayName, values.displayName.length >= 2 && values.displayName.length <= 40, 'Use 2 to 40 characters.');
          check(handle, /^[a-z0-9_]{3,20}$/.test(values.handle), 'Use 3 to 20 lowercase letters, numbers or underscores.');
          check(personId, /^[A-Za-z0-9-]{4,64}$/.test(values.personId), 'Use 4 to 64 letters, numbers or dashes.');
          if (firstInvalid) {
            firstInvalid.input.focus();
            return;
          }
          button.disabled = true;
          try {
            const data = await api('POST', '/api/onboarding/register', values);
            app.me = data.account;
            toast(`Account created for @${data.account.handle}.`, 'success');
            navigate('/status');
          } catch (err) {
            if (err.code === 'person_already_registered') {
              personId.setError('An account already exists for this demo person.');
              extra.replaceChildren(recoveryPanel(values.personId));
              extra.querySelector('button').focus();
            } else if (err.data && err.data.field === 'handle') {
              handle.setError(err.message);
              handle.input.focus();
            } else if (err.data && err.data.field === 'displayName') {
              displayName.setError(err.message);
              displayName.input.focus();
            } else if (err.data && err.data.field === 'personId') {
              personId.setError(err.message);
              personId.input.focus();
            } else {
              formError.textContent = err.message;
              formError.hidden = false;
            }
          } finally {
            button.disabled = false;
          }
        },
      },
      h('span', { class: 'eyebrow' }, 'Step 1 of 2'),
      h('h1', {}, 'Create your account'),
      h('p', { class: 'muted' }, 'After this step you submit the account for verification. Until a reviewer approves it you can see your status but not the community.'),
      displayName.wrap,
      handle.wrap,
      personId.wrap,
      h('div', { class: 'callout warn' }, h('span', {}, h('strong', {}, 'Demo only. '), 'No real ID or selfie is collected. The identifier is stored as a hash and never shown on your profile.')),
      formError,
      h('div', { class: 'btn-row' }, button, h('a', { class: 'btn quiet', href: '#/recover' }, 'I already have an account'))
    );

    $main.replaceChildren(h('div', { class: 'narrow stack' }, form, extra));
  }

  async function viewStatus() {
    if (!app.me) {
      $main.replaceChildren(signedOutCard('Your status', 'Sign in or create an account to see your verification status.'));
      return;
    }
    await refreshMe();
    const me = app.me;
    if (!me) {
      $main.replaceChildren(signedOutCard('Your status', 'Your session has ended. Sign in again.'));
      return;
    }
    const v = me.verification || {};
    const decision = v.lastDecision || null;

    const head = h(
      'div',
      { class: 'card' },
      h(
        'div',
        { class: 'status-hero' },
        h('span', { class: `status-dot ${me.status}`, 'aria-hidden': 'true' }),
        h('div', {}, h('span', { class: 'eyebrow' }, 'Account status'), h('h1', {}, STATUS_LABELS[me.status] || me.status))
      ),
      h('p', { class: 'muted' }, me.statusMessage),
      identity(me)
    );

    let body;
    if (me.status === 'unverified') {
      body = verificationForm({
        heading: 'Step 2 of 2: submit for verification',
        intro: 'Your account exists, but you need verification to read or post. Submitting puts you in the review queue.',
        buttonLabel: 'Submit for review',
      });
    } else if (me.status === 'pending') {
      body = h(
        'div',
        { class: 'card' },
        h('h2', {}, 'Waiting for a reviewer'),
        h('p', {}, `Submitted ${formatTimeText(v.submittedAt)}. Attempt ${v.attempts || 1}.`),
        v.note ? h('div', { class: 'quote prewrap' }, v.note) : null,
        h('p', { class: 'muted' }, 'Testing tip: open Demo accounts, act as the reviewer, approve this request, then switch back or recover this account with your identifier.'),
        h(
          'div',
          { class: 'btn-row' },
          h('button', { type: 'button', class: 'btn', onClick: () => render() }, 'Check again'),
          h('a', { class: 'btn quiet', href: '#/demo' }, 'Demo accounts')
        )
      );
    } else if (me.status === 'rejected') {
      body = h(
        'div',
        { class: 'stack' },
        h(
          'div',
          { class: 'card danger' },
          h('h2', {}, 'Why it was not approved'),
          h('p', { class: 'prewrap' }, (decision && decision.reason) || 'No reason was recorded.'),
          h('p', { class: 'faint' }, `Reviewed ${formatTimeText(decision && decision.at)}.`),
          h('p', {}, 'If the reason mentions your name or handle, change them on your profile first, then resubmit below.'),
          h('div', { class: 'btn-row' }, h('a', { class: 'btn', href: '#/profile' }, 'Edit name or handle'))
        ),
        verificationForm({
          heading: 'Resubmit or appeal',
          intro: 'Fix what the reviewer pointed out, or explain why the decision should be reconsidered. Either way the request goes back to the queue.',
          buttonLabel: 'Resubmit for review',
          appeal: true,
        })
      );
    } else if (me.status === 'suspended') {
      body = h(
        'div',
        { class: 'stack' },
        h(
          'div',
          { class: 'card danger' },
          h('h2', {}, 'Account suspended'),
          h('p', { class: 'prewrap' }, (me.suspension && me.suspension.reason) || 'No reason was recorded.'),
          h('p', { class: 'faint' }, `Since ${formatTimeText(me.suspension && me.suspension.at)}.`),
          h('p', {}, 'Community access is paused and your posts are hidden, not deleted. A reviewer can reinstate the account, after which everything reappears.'),
          h('div', { class: 'btn-row' }, h('a', { class: 'btn', href: '#/rules' }, 'Community rules'))
        ),
        appealForm(me)
      );
    } else {
      body = h(
        'div',
        { class: 'card success' },
        h('h2', {}, 'You are in'),
        h('p', {}, 'Your account is verified. Welcome to the community.'),
        h('div', { class: 'btn-row' }, h('a', { class: 'btn primary', href: '#/feed' }, 'Open the feed'))
      );
    }

    $main.replaceChildren(h('div', { class: 'narrow stack' }, head, body));
  }

  function appealForm(me) {
    const existing = me.suspension && me.suspension.appeal;
    const textarea = h('textarea', {
      id: 'appeal-message',
      name: 'message',
      maxlength: 1000,
      rows: 4,
      placeholder: 'Explain what happened, what you understand about the rule involved, and what you will do differently.',
    });
    if (existing) textarea.value = existing.message;
    const error = h('div', { class: 'callout error', role: 'alert', hidden: true });
    const button = h('button', { type: 'submit', class: 'btn primary' }, existing ? 'Update appeal' : 'Send appeal');
    return h(
      'form',
      {
        class: 'card',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          error.hidden = true;
          const message = textarea.value.trim();
          if (message.length < 10) {
            error.textContent = 'Write at least ten characters so a reviewer has something to go on.';
            error.hidden = false;
            textarea.focus();
            return;
          }
          button.disabled = true;
          try {
            const data = await api('POST', '/api/suspension/appeal', { message });
            app.me = data.account;
            toast('Appeal sent. A reviewer will see it next to your suspension.', 'success');
            render();
          } catch (err) {
            error.textContent = err.message;
            error.hidden = false;
          } finally {
            button.disabled = false;
          }
        },
      },
      h('h2', {}, existing ? 'Your appeal' : 'Appeal this suspension'),
      existing
        ? h('p', { class: 'faint' }, `Sent ${formatTimeText(existing.at)}. You can update it until a reviewer decides.`)
        : h('p', { class: 'muted' }, 'Reviewers read appeals by hand. There is no deadline in this prototype, and sending one does not guarantee reinstatement.'),
      h('div', { class: 'field' }, h('label', { for: 'appeal-message' }, 'Message to the reviewers'), textarea),
      error,
      h('div', { class: 'btn-row' }, button)
    );
  }

  async function viewProfile() {
    if (!app.me) {
      $main.replaceChildren(signedOutCard('Your profile', 'Sign in to edit your name, handle and bio.'));
      return;
    }
    await refreshMe();
    const me = app.me;
    if (!me) {
      $main.replaceChildren(signedOutCard('Your profile', 'Your session has ended. Sign in again.'));
      return;
    }
    if (me.status === 'suspended') {
      navigate('/status');
      return;
    }
    const displayName = textField({ name: 'displayName', label: 'Display name', hint: '2 to 40 characters.', maxlength: 40, autocomplete: 'nickname' });
    const handle = textField({ name: 'handle', label: 'Handle', hint: 'Lowercase letters, numbers and underscores, 3 to 20 characters. Changing it is allowed; people will see the new one everywhere.', maxlength: 20, autocapitalize: 'none' });
    const bioId = 'field-bio';
    const bio = h('textarea', { id: bioId, name: 'bio', maxlength: 200, rows: 3, placeholder: 'A sentence or two. Optional.' });
    const bioCounter = h('div', { class: 'counter', id: `${bioId}-count` }, `${(me.bio || '').length} / 200`);
    bio.addEventListener('input', () => {
      bioCounter.textContent = `${bio.value.length} / 200`;
    });
    displayName.input.value = me.displayName;
    handle.input.value = me.handle;
    bio.value = me.bio || '';
    const formError = h('div', { class: 'callout error', role: 'alert', hidden: true });
    const button = h('button', { type: 'submit', class: 'btn primary' }, 'Save changes');

    const form = h(
      'form',
      {
        class: 'card',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          formError.hidden = true;
          const values = {
            displayName: displayName.input.value.trim(),
            handle: handle.input.value.trim().replace(/^@/, '').toLowerCase(),
            bio: bio.value.trim(),
          };
          let firstInvalid = null;
          const check = (field, ok, message) => {
            field.setError(ok ? '' : message);
            if (!ok && !firstInvalid) firstInvalid = field;
          };
          check(displayName, values.displayName.length >= 2 && values.displayName.length <= 40, 'Use 2 to 40 characters.');
          check(handle, /^[a-z0-9_]{3,20}$/.test(values.handle), 'Use 3 to 20 lowercase letters, numbers or underscores.');
          if (firstInvalid) {
            firstInvalid.input.focus();
            return;
          }
          const changes = {};
          if (values.displayName !== me.displayName) changes.displayName = values.displayName;
          if (values.handle !== me.handle) changes.handle = values.handle;
          if (values.bio !== (me.bio || '')) changes.bio = values.bio;
          if (!Object.keys(changes).length) {
            toast('Nothing changed.');
            return;
          }
          button.disabled = true;
          try {
            const data = await api('PATCH', '/api/me/profile', changes);
            app.me = data.account;
            toast('Profile saved.', 'success');
            navigate(app.me.canAccessCommunity ? '/feed' : '/status');
          } catch (err) {
            if (err.data && err.data.field === 'handle') {
              handle.setError(err.message);
              handle.input.focus();
            } else if (err.data && err.data.field === 'displayName') {
              displayName.setError(err.message);
              displayName.input.focus();
            } else {
              formError.textContent = err.message;
              formError.hidden = false;
            }
          } finally {
            button.disabled = false;
          }
        },
      },
      h('h1', {}, 'Your profile'),
      h('p', { class: 'muted' }, 'This is what other members see. Your verification status and the demo identifier are not part of it and cannot be changed here.'),
      displayName.wrap,
      handle.wrap,
      h('div', { class: 'field' }, h('label', { for: bioId }, 'Bio'), bio, bioCounter),
      formError,
      h('div', { class: 'btn-row' }, button, h('a', { class: 'btn quiet', href: me.canAccessCommunity ? '#/feed' : '#/status' }, 'Cancel'))
    );

    $main.replaceChildren(
      h(
        'div',
        { class: 'narrow stack' },
        h('div', { class: 'card sunken' }, h('div', { class: 'row between' }, identity(me), statusBadge(me.status))),
        form
      )
    );
  }

  // ---------------------------------------------------------------------
  // Views: feed
  // ---------------------------------------------------------------------

  async function reportTarget(targetType, targetId, label) {
    const result = await confirmDialog({
      title: `Report ${label}`,
      message: 'Reports go to a human reviewer. Use them for harassment, threats, impersonation or spam, not for disagreement.',
      confirmLabel: 'Send report',
      danger: true,
      fields: [
        { name: 'reason', label: 'Reason', type: 'select', options: REPORT_REASONS, required: true },
        { name: 'details', label: 'Details', type: 'textarea', hint: 'Required when the reason is "Something else".', maxlength: 500 },
      ],
    });
    if (!result) return;
    try {
      await api('POST', '/api/reports', { targetType, targetId, reason: result.reason, details: result.details || '' });
      toast('Report sent. A reviewer will look at it.', 'success');
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  async function openProfile(member, reload) {
    let data;
    try {
      data = await api('GET', `/api/members/${encodeURIComponent(member.id)}`);
    } catch (err) {
      toast(err.message, 'error');
      return;
    }
    const m = data.member;
    const rel = data.relationship;
    dialogSeq += 1;
    const titleId = `profile-title-${dialogSeq}`;
    const dialog = h('dialog', { class: 'dialog', 'aria-labelledby': titleId });
    const close = () => dialog.close();
    const actions = [];

    if (!rel.isSelf) {
      actions.push(
        h(
          'button',
          {
            type: 'button',
            class: 'btn',
            onClick: async () => {
              const verb = rel.muted ? 'unmute' : 'mute';
              try {
                await api('POST', `/api/members/${encodeURIComponent(m.id)}/${verb}`, {});
                await refreshMe();
                toast(rel.muted ? `Unmuted @${m.handle}.` : `Muted @${m.handle}. Their posts and comments are hidden from your feed.`, 'success');
                close();
                await reload();
              } catch (err) {
                toast(err.message, 'error');
              }
            },
          },
          rel.muted ? 'Unmute' : 'Mute'
        )
      );
      actions.push(
        h(
          'button',
          {
            type: 'button',
            class: 'btn danger',
            onClick: async () => {
              if (!rel.blocked) {
                const ok = await confirmDialog({
                  title: `Block @${m.handle}?`,
                  message: 'You will not see their content and they will not see yours. You can undo this from the feed sidebar.',
                  confirmLabel: 'Block',
                  danger: true,
                });
                if (!ok) return;
              }
              try {
                await api('POST', `/api/members/${encodeURIComponent(m.id)}/${rel.blocked ? 'unblock' : 'block'}`, {});
                await refreshMe();
                toast(rel.blocked ? `Unblocked @${m.handle}.` : `Blocked @${m.handle}.`, 'success');
                close();
                await reload();
              } catch (err) {
                toast(err.message, 'error');
              }
            },
          },
          rel.blocked ? 'Unblock' : 'Block'
        )
      );
      actions.push(
        h(
          'button',
          {
            type: 'button',
            class: 'btn quiet',
            onClick: () => {
              close();
              reportTarget('member', m.id, `@${m.handle}`);
            },
          },
          'Report member'
        )
      );
    }

    dialog.append(
      h(
        'div',
        { class: 'dialog-form' },
        h(
          'div',
          { class: 'profile-head' },
          avatar(m, 'lg'),
          h(
            'div',
            {},
            h('h2', { id: titleId }, m.displayName),
            h('div', { class: 'row' }, h('span', { class: 'faint' }, `@${m.handle}`), badge(m), m.role === 'reviewer' ? reviewerBadge() : null)
          )
        ),
        m.bio ? h('p', { class: 'prewrap' }, m.bio) : h('p', { class: 'faint' }, 'No bio yet.'),
        h('p', { class: 'faint' }, `Member since ${new Date(m.joinedAt).toLocaleDateString()}. Verification details are private.`),
        rel.blocked ? h('div', { class: 'callout error' }, 'You have blocked this member.') : rel.muted ? h('div', { class: 'callout warn' }, 'You have muted this member.') : null,
        h('div', { class: 'dialog-actions' }, actions, h('button', { type: 'button', class: 'btn quiet', onClick: close }, 'Close'))
      )
    );
    dialog.addEventListener('close', () => dialog.remove());
    document.body.append(dialog);
    dialog.showModal();
  }

  function commentForm(post, onCommented) {
    const id = `comment-${post.id}`;
    const textarea = h('textarea', { id, name: 'body', maxlength: 500, rows: 2, placeholder: 'Add a comment' });
    const error = h('p', { class: 'error', role: 'alert', hidden: true });
    const button = h('button', { type: 'submit', class: 'btn small primary' }, 'Reply');
    const form = h(
      'form',
      {
        class: 'comment-form',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          const body = textarea.value.trim();
          error.hidden = true;
          if (!body) {
            error.textContent = 'Write something first.';
            error.hidden = false;
            textarea.focus();
            return;
          }
          button.disabled = true;
          try {
            const data = await api('POST', `/api/posts/${encodeURIComponent(post.id)}/comments`, { body });
            textarea.value = '';
            toast('Comment added.', 'success');
            onCommented(data.post);
          } catch (err) {
            error.textContent = err.message;
            error.hidden = false;
          } finally {
            button.disabled = false;
          }
        },
      },
      h('label', { for: id, class: 'sr-only' }, `Comment on post by ${post.author.displayName}`),
      textarea,
      h('div', { class: 'row between' }, h('span', { class: 'faint' }, 'Up to 500 characters.'), button),
      error
    );
    return form;
  }

  async function deleteOwn(kind, route, reload) {
    const ok = await confirmDialog({
      title: `Delete your ${kind}?`,
      message: 'It disappears from the feed for everyone. Reviewers can still see that it existed if it was reported.',
      confirmLabel: 'Delete',
      danger: true,
    });
    if (!ok) return;
    try {
      await api('DELETE', route);
      toast(`${kind === 'post' ? 'Post' : 'Comment'} deleted.`, 'success');
      await reload();
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function commentRow(comment, reload, post) {
    const mine = app.me && comment.author.id === app.me.id;
    return h(
      'div',
      { class: 'comment' },
      avatar(comment.author, 'sm'),
      h(
        'div',
        { class: 'grow' },
        h(
          'div',
          { class: 'comment-meta' },
          h('button', { type: 'button', class: 'member-button name', onClick: () => openProfile(comment.author, reload) }, comment.author.displayName),
          badge(comment.author),
          h('span', { class: 'faint' }, `@${comment.author.handle}`),
          h('span', { class: 'faint' }, formatTime(comment.createdAt))
        ),
        h('p', { class: 'comment-body prewrap' }, comment.body),
        mine
          ? h('button', { type: 'button', class: 'btn small quiet', onClick: () => deleteOwn('comment', `/api/posts/${encodeURIComponent(post.id)}/comments/${encodeURIComponent(comment.id)}`, reload) }, 'Delete')
          : h('button', { type: 'button', class: 'btn small quiet', onClick: () => reportTarget('comment', comment.id, `comment by @${comment.author.handle}`) }, 'Report')
      )
    );
  }

  function postCard(post, reload) {
    const commentsWrap = h('div', { class: 'comments' });
    const renderComments = (comments) => {
      const heading = h('h3', { class: 'sr-only' }, 'Comments');
      if (!comments.length) {
        commentsWrap.replaceChildren(heading, h('p', { class: 'faint' }, 'No comments yet.'));
        return;
      }
      commentsWrap.replaceChildren(heading, comments.map((c) => commentRow(c, reload, post)));
    };

    const reactionBar = h('div', { class: 'post-actions', role: 'group', 'aria-label': 'Reactions' });
    const renderReactions = (summary) => {
      reactionBar.replaceChildren(
        ...Object.keys(REACTION_LABELS).map((type) =>
          h(
            'button',
            {
              type: 'button',
              class: 'react',
              'aria-pressed': summary.viewer === type ? 'true' : 'false',
              onClick: async () => {
                try {
                  const data = await api('POST', `/api/posts/${encodeURIComponent(post.id)}/reactions`, { type });
                  renderReactions(data.reactions);
                } catch (err) {
                  toast(err.message, 'error');
                }
              },
            },
            REACTION_LABELS[type],
            h('span', { class: 'count', 'aria-label': `${summary.counts[type] || 0} ${REACTION_LABELS[type].toLowerCase()} reactions` }, String(summary.counts[type] || 0))
          )
        )
      );
    };

    renderReactions(post.reactions);
    renderComments(post.comments);

    const form = commentForm(post, (updated) => renderComments(updated.comments));
    form.hidden = true;
    const toggle = h(
      'button',
      {
        type: 'button',
        class: 'btn small quiet',
        'aria-expanded': 'false',
        onClick: () => {
          form.hidden = !form.hidden;
          toggle.setAttribute('aria-expanded', String(!form.hidden));
          if (!form.hidden) form.querySelector('textarea').focus();
        },
      },
      'Comment'
    );

    const authorId = `post-author-${post.id}`;
    return h(
      'article',
      { class: 'card post', 'aria-labelledby': authorId },
      h(
        'div',
        { class: 'post-head' },
        identity(post.author, { onClick: () => openProfile(post.author, reload), id: authorId }),
        h('span', { class: 'post-time' }, formatTime(post.createdAt))
      ),
      h('p', { class: 'post-body prewrap' }, post.body),
      reactionBar,
      h(
        'div',
        { class: 'row' },
        toggle,
        post.isOwn
          ? [h('span', { class: 'faint' }, 'Your post'), h('button', { type: 'button', class: 'btn small quiet', onClick: () => deleteOwn('post', `/api/posts/${encodeURIComponent(post.id)}`, reload) }, 'Delete')]
          : h('button', { type: 'button', class: 'btn small quiet', onClick: () => reportTarget('post', post.id, `post by @${post.author.handle}`) }, 'Report')
      ),
      form,
      commentsWrap
    );
  }

  function composerCard(onPosted) {
    const textarea = h('textarea', {
      id: 'composer',
      name: 'body',
      maxlength: 1000,
      rows: 3,
      placeholder: 'What is on your mind? Keep it honest and keep it kind.',
      'aria-describedby': 'composer-count',
    });
    const counter = h('div', { class: 'counter', id: 'composer-count' }, '0 / 1000');
    textarea.addEventListener('input', () => {
      counter.textContent = `${textarea.value.length} / 1000`;
    });
    const error = h('p', { class: 'error', role: 'alert', hidden: true });
    const button = h('button', { type: 'submit', class: 'btn primary' }, 'Post');
    return h(
      'form',
      {
        class: 'card composer',
        novalidate: true,
        onSubmit: async (event) => {
          event.preventDefault();
          const body = textarea.value.trim();
          error.hidden = true;
          if (!body) {
            error.textContent = 'Write something first.';
            error.hidden = false;
            textarea.focus();
            return;
          }
          button.disabled = true;
          try {
            await api('POST', '/api/posts', { body });
            textarea.value = '';
            counter.textContent = '0 / 1000';
            toast('Posted.', 'success');
            await onPosted();
          } catch (err) {
            error.textContent = err.message;
            error.hidden = false;
          } finally {
            button.disabled = false;
          }
        },
      },
      h('label', { for: 'composer', class: 'label' }, 'Share with the community'),
      textarea,
      h('div', { class: 'row between' }, counter, button),
      error
    );
  }

  async function hiddenMembersCard(reload) {
    const me = app.me;
    const ids = [...new Set([...me.blocked, ...me.muted])];
    const card = h('div', { class: 'card' }, h('h3', {}, 'Hidden members'));
    if (!ids.length) {
      card.append(h('p', { class: 'faint' }, 'You have not blocked or muted anyone. Open a profile from the feed to do so.'));
      return card;
    }
    const list = h('ul', { class: 'demo-list' });
    card.append(list);
    const results = await Promise.all(
      ids.map((id) => api('GET', `/api/members/${encodeURIComponent(id)}`).catch(() => null))
    );
    for (const data of results) {
      if (!data) continue;
      const m = data.member;
      const labels = [];
      if (me.blocked.includes(m.id)) labels.push('blocked');
      if (me.muted.includes(m.id)) labels.push('muted');
      const undo = async (verb) => {
        try {
          await api('POST', `/api/members/${encodeURIComponent(m.id)}/${verb}`, {});
          await refreshMe();
          toast(`@${m.handle} ${verb === 'unblock' ? 'unblocked' : 'unmuted'}.`, 'success');
          await reload();
        } catch (err) {
          toast(err.message, 'error');
        }
      };
      list.append(
        h(
          'li',
          { class: 'card sunken' },
          avatar(m, 'sm'),
          h('div', { class: 'grow' }, h('div', {}, h('strong', {}, m.displayName)), h('div', { class: 'faint' }, `@${m.handle}: ${labels.join(' and ')}`)),
          me.blocked.includes(m.id) ? h('button', { type: 'button', class: 'btn small', onClick: () => undo('unblock') }, 'Unblock') : null,
          me.muted.includes(m.id) ? h('button', { type: 'button', class: 'btn small', onClick: () => undo('unmute') }, 'Unmute') : null
        )
      );
    }
    return card;
  }

  async function viewFeed() {
    if (!app.me) {
      $main.replaceChildren(signedOutCard('The feed is for verified members', 'Join and get verified, or pick a verified demo account.'));
      return;
    }
    if (!app.me.canAccessCommunity) {
      navigate('/status');
      return;
    }

    const list = h('div', { class: 'feed-list' });
    const side = h('aside', { class: 'feed-side stack', 'aria-label': 'Sidebar' });

    async function reloadAll() {
      await Promise.all([loadPosts(), loadSide()]);
    }

    async function loadPosts() {
      list.replaceChildren(h('div', { class: 'skeleton', 'aria-hidden': 'true' }), h('div', { class: 'skeleton', 'aria-hidden': 'true' }), h('p', { class: 'sr-only' }, 'Loading posts'));
      try {
        const data = await api('GET', '/api/feed');
        if (!data.posts.length) {
          list.replaceChildren(h('div', { class: 'empty' }, 'No posts yet. Be the first to say hello.'));
          return;
        }
        list.replaceChildren(...data.posts.map((post) => postCard(post, reloadAll)));
      } catch (err) {
        if (err.status === 403 || err.status === 401) {
          await refreshMe().catch(() => null);
          navigate('/status');
          return;
        }
        list.replaceChildren(errorCard(err, loadPosts));
      }
    }

    async function loadSide() {
      const me = app.me;
      const profile = h(
        'div',
        { class: 'card' },
        h('h3', {}, 'You'),
        identity(me),
        h('p', { class: 'faint' }, 'Your profile shows your name, handle and bio. Verification details stay private.'),
        h('div', { class: 'row' }, h('a', { class: 'btn small', href: '#/profile' }, 'Edit profile'), h('a', { class: 'btn small quiet', href: '#/status' }, 'Account status'))
      );
      const rules = h(
        'div',
        { class: 'card' },
        h('h3', {}, 'House rules, in short'),
        h('ul', { class: 'small' }, h('li', {}, 'Disagree with ideas, not people.'), h('li', {}, 'No harassment, threats, impersonation or spam.'), h('li', {}, 'Block or mute anyone, no explanation needed.')),
        h('a', { href: '#/rules' }, 'Read the full rules')
      );
      side.replaceChildren(profile, rules, h('p', { class: 'faint' }, 'Loading hidden members…'));
      const hidden = await hiddenMembersCard(reloadAll).catch((err) => errorCard(err, loadSide));
      side.replaceChildren(profile, hidden, rules);
    }

    $main.replaceChildren(
      h(
        'div',
        { class: 'feed-layout' },
        h('div', {}, h('h1', { class: 'sr-only' }, 'Community feed'), composerCard(loadPosts), h('h2', { class: 'sr-only' }, 'Posts'), list),
        side
      )
    );
    await reloadAll();
  }

  // ---------------------------------------------------------------------
  // Views: reviewer dashboard
  // ---------------------------------------------------------------------

  async function act(fn, successMessage, reload) {
    try {
      await fn();
      toast(successMessage, 'success');
      await reload();
    } catch (err) {
      toast(err.message, 'error');
    }
  }

  function queuePanel(queue, reload) {
    if (!queue.length) return h('div', { class: 'empty' }, 'No pending verification requests.');
    return h(
      'div',
      {},
      queue.map((a) =>
        h(
          'div',
          { class: 'card queue-item' },
          h('div', { class: 'row between' }, identity(a), h('span', { class: 'faint' }, `Submitted ${formatTimeText(a.verification.submittedAt)}. Attempt ${a.verification.attempts}.`)),
          a.verification.note ? h('div', { class: 'quote prewrap' }, a.verification.note) : null,
          a.verification.lastDecision && a.verification.lastDecision.outcome === 'rejected'
            ? h('p', { class: 'faint' }, `Previously rejected: ${a.verification.lastDecision.reason}`)
            : null,
          h(
            'div',
            { class: 'btn-row' },
            h(
              'button',
              {
                type: 'button',
                class: 'btn primary',
                onClick: async () => {
                  const ok = await confirmDialog({
                    title: `Approve ${a.displayName}?`,
                    message: `@${a.handle} becomes a verified member with full community access.`,
                    confirmLabel: 'Approve',
                    fields: [{ name: 'note', label: 'Note for the log (optional)', type: 'text', maxlength: 500 }],
                  });
                  if (!ok) return;
                  await act(() => api('POST', `/api/reviewer/verifications/${encodeURIComponent(a.id)}/approve`, { note: ok.note || '' }), `Approved @${a.handle}.`, reload);
                },
              },
              'Approve'
            ),
            h(
              'button',
              {
                type: 'button',
                class: 'btn danger',
                onClick: async () => {
                  const result = await confirmDialog({
                    title: `Reject ${a.displayName}?`,
                    message: 'The reason is shown to the person. They can fix the problem and resubmit, or appeal.',
                    confirmLabel: 'Reject',
                    danger: true,
                    fields: [{ name: 'reason', label: 'Reason (required, shown to the person)', type: 'textarea', required: true, maxlength: 300 }],
                  });
                  if (!result) return;
                  await act(() => api('POST', `/api/reviewer/verifications/${encodeURIComponent(a.id)}/reject`, { reason: result.reason }), `Rejected @${a.handle}.`, reload);
                },
              },
              'Reject'
            )
          )
        )
      )
    );
  }

  function reportCard(report, reload) {
    const target = report.target || {};
    let targetEl;
    if (target.missing) {
      targetEl = h('p', { class: 'faint' }, 'The reported target no longer exists.');
    } else if (target.type === 'member') {
      targetEl = h('div', { class: 'row' }, identity(target.member), statusBadge(target.status));
    } else {
      targetEl = h(
        'div',
        {},
        h('div', { class: 'row' }, h('span', { class: 'faint' }, `${target.type} by`), target.author ? identity(target.author) : null, target.authorStatus ? statusBadge(target.authorStatus) : null),
        h('div', { class: `quote prewrap ${target.removed ? 'removed' : ''}`.trim() }, target.body),
        target.removed ? h('p', { class: 'faint' }, target.removedBy === 'author' ? 'Removed by its author.' : 'Removed by a reviewer.') : null
      );
    }

    const open = report.status === 'open';
    const canRemove = open && target.type !== 'member' && !target.missing && !target.removed;
    const canSuspend = open && !target.missing && (target.type === 'member' ? target.status !== 'suspended' : target.authorStatus !== 'suspended');

    const resolve = (resolution, note) => api('POST', `/api/reviewer/reports/${encodeURIComponent(report.id)}/resolve`, { resolution, note: note || '' });

    const actions = open
      ? h(
          'div',
          { class: 'btn-row' },
          h(
            'button',
            {
              type: 'button',
              class: 'btn',
              onClick: async () => {
                const ok = await confirmDialog({
                  title: 'Dismiss this report?',
                  message: 'No action is taken against the content or the member. The decision is logged.',
                  confirmLabel: 'Dismiss',
                  fields: [{ name: 'note', label: 'Note (optional)', type: 'text', maxlength: 500 }],
                });
                if (!ok) return;
                await act(() => resolve('dismiss', ok.note), 'Report dismissed.', reload);
              },
            },
            'Dismiss'
          ),
          canRemove
            ? h(
                'button',
                {
                  type: 'button',
                  class: 'btn danger',
                  onClick: async () => {
                    const ok = await confirmDialog({
                      title: `Remove this ${target.type}?`,
                      message: 'The content disappears from every feed. The author keeps their account.',
                      confirmLabel: 'Remove content',
                      danger: true,
                      fields: [{ name: 'note', label: 'Reason (required)', type: 'textarea', required: true, maxlength: 500 }],
                    });
                    if (!ok) return;
                    await act(() => resolve('remove_content', ok.note), 'Content removed.', reload);
                  },
                },
                'Remove content'
              )
            : null,
          canSuspend
            ? h(
                'button',
                {
                  type: 'button',
                  class: 'btn danger',
                  onClick: async () => {
                    const ok = await confirmDialog({
                      title: 'Suspend this member?',
                      message: 'They lose community access until a reviewer reinstates them. Their content is hidden while suspended.',
                      confirmLabel: 'Suspend',
                      danger: true,
                      fields: [{ name: 'note', label: 'Reason (required, shown to the member)', type: 'textarea', required: true, maxlength: 300 }],
                    });
                    if (!ok) return;
                    await act(() => resolve('suspend_member', ok.note), 'Member suspended.', reload);
                  },
                },
                'Suspend member'
              )
            : null
        )
      : h(
          'p',
          { class: 'faint' },
          `Resolved: ${RESOLUTION_LABELS[report.resolution && report.resolution.outcome] || 'resolved'}${report.resolution && report.resolution.note ? `. Note: ${report.resolution.note}` : ''}`
        );

    return h(
      'div',
      { class: 'card report-item' },
      h(
        'div',
        { class: 'row between' },
        h('div', { class: 'row' }, h('span', { class: 'badge' }, reasonLabel(report.reason)), h('span', { class: 'faint' }, `Reported ${formatTimeText(report.createdAt)}${report.reporter ? ` by @${report.reporter.handle}` : ''}`)),
        h('span', { class: `badge ${open ? 'pending' : ''}`.trim() }, open ? 'Open' : 'Resolved')
      ),
      report.details ? h('p', { class: 'prewrap' }, report.details) : null,
      targetEl,
      actions
    );
  }

  function reportsPanel(reports, reload) {
    return h(
      'div',
      { class: 'stack' },
      h('h2', {}, 'Open reports'),
      reports.open.length ? reports.open.map((r) => reportCard(r, reload)) : h('div', { class: 'empty' }, 'No open reports.'),
      h('h2', {}, 'Recently resolved'),
      reports.resolved.length ? reports.resolved.map((r) => reportCard(r, reload)) : h('p', { class: 'faint' }, 'Nothing resolved yet.')
    );
  }

  function membersPanel(members, reload) {
    const rows = members.map((m) => {
      let action = null;
      if (m.isSelf) {
        action = h('span', { class: 'faint' }, 'You');
      } else if (m.status === 'suspended') {
        action = h(
          'button',
          {
            type: 'button',
            class: 'btn small',
            onClick: async () => {
              const ok = await confirmDialog({
                title: `Reinstate ${m.displayName}?`,
                message: 'Their previous status is restored and their content becomes visible again.',
                confirmLabel: 'Reinstate',
                fields: [{ name: 'note', label: 'Note (optional)', type: 'text', maxlength: 500 }],
              });
              if (!ok) return;
              await act(() => api('POST', `/api/reviewer/members/${encodeURIComponent(m.id)}/reinstate`, { note: ok.note || '' }), `Reinstated @${m.handle}.`, reload);
            },
          },
          'Reinstate'
        );
      } else {
        action = h(
          'button',
          {
            type: 'button',
            class: 'btn small danger',
            onClick: async () => {
              const ok = await confirmDialog({
                title: `Suspend ${m.displayName}?`,
                message: 'They lose community access until reinstated. Their content is hidden while suspended.',
                confirmLabel: 'Suspend',
                danger: true,
                fields: [{ name: 'reason', label: 'Reason (required, shown to the member)', type: 'textarea', required: true, maxlength: 300 }],
              });
              if (!ok) return;
              await act(() => api('POST', `/api/reviewer/members/${encodeURIComponent(m.id)}/suspend`, { reason: ok.reason }), `Suspended @${m.handle}.`, reload);
            },
          },
          'Suspend'
        );
      }
      const notes = [];
      if (m.status === 'suspended' && m.suspension) {
        notes.push(h('div', { class: 'small prewrap' }, h('strong', {}, 'Suspended: '), m.suspension.reason));
        if (m.suspension.appeal) {
          notes.push(h('div', { class: 'quote prewrap small' }, h('strong', {}, `Appeal (${formatTimeText(m.suspension.appeal.at)}): `), m.suspension.appeal.message));
        }
      }
      if (m.status === 'rejected' && m.verification.lastDecision) {
        notes.push(h('div', { class: 'small prewrap faint' }, h('strong', {}, 'Rejected: '), m.verification.lastDecision.reason));
      }
      if (m.hiddenPostCount) {
        notes.push(h('div', { class: 'faint small' }, `${m.hiddenPostCount} hidden post${m.hiddenPostCount === 1 ? '' : 's'}`));
      }
      return h(
        'tr',
        {},
        h('td', {}, identity(m)),
        h('td', {}, statusBadge(m.status)),
        h('td', {}, m.role === 'reviewer' ? reviewerBadge() : h('span', { class: 'faint' }, 'Member')),
        h('td', {}, String(m.postCount)),
        h('td', { class: 'notes-cell' }, notes.length ? notes : h('span', { class: 'faint' }, '')),
        h('td', {}, action)
      );
    });
    return h(
      'div',
      { class: 'table-wrap' },
      h(
        'table',
        {},
        h('caption', { class: 'sr-only' }, 'All accounts'),
        h('thead', {}, h('tr', {}, h('th', { scope: 'col' }, 'Member'), h('th', { scope: 'col' }, 'Status'), h('th', { scope: 'col' }, 'Role'), h('th', { scope: 'col' }, 'Posts'), h('th', { scope: 'col' }, 'Notes and appeals'), h('th', { scope: 'col' }, 'Action'))),
        h('tbody', {}, rows)
      )
    );
  }

  function historyPanel(actions) {
    if (!actions.length) return h('div', { class: 'empty' }, 'No reviewer actions recorded yet.');
    return h(
      'ul',
      { class: 'history' },
      actions.map((entry) =>
        h(
          'li',
          {},
          h('time', { datetime: entry.at }, new Date(entry.at).toLocaleString()),
          h(
            'div',
            {},
            h('div', {}, h('strong', {}, ACTION_LABELS[entry.type] || entry.type), ' ', h('span', { class: 'faint' }, `by @${entry.reviewerHandle}`)),
            h('div', { class: 'small prewrap' }, entry.targetLabel),
            entry.note ? h('div', { class: 'faint prewrap' }, `Note: ${entry.note}`) : null
          )
        )
      )
    );
  }

  async function viewReviewer() {
    if (!app.me) {
      $main.replaceChildren(signedOutCard('Reviewer dashboard', 'Sign in as a reviewer to moderate the community.'));
      return;
    }
    if (!app.me.isReviewer) {
      $main.replaceChildren(
        h(
          'div',
          { class: 'narrow' },
          h(
            'div',
            { class: 'card danger' },
            h('h1', {}, 'Reviewer access only'),
            h('p', {}, 'Your account does not have reviewer privileges. The server checks this on every reviewer action, so there is nothing to do here.'),
            h('div', { class: 'btn-row' }, h('a', { class: 'btn', href: '#/demo' }, 'Demo accounts'))
          )
        )
      );
      return;
    }

    const container = h('div', { class: 'wide' });
    const TAB_KEYS = ['queue', 'reports', 'members', 'history'];

    async function load() {
      container.replaceChildren(h('p', { class: 'muted' }, 'Loading reviewer data…'));
      let data;
      try {
        data = await api('GET', '/api/reviewer/overview');
      } catch (err) {
        container.replaceChildren(errorCard(err, load));
        return;
      }
      renderOverview(data);
    }

    function renderOverview(data) {
      const tab = TAB_KEYS.includes(app.reviewerTab) ? app.reviewerTab : 'queue';
      const labels = {
        queue: `Queue (${data.queue.length})`,
        reports: `Reports (${data.reports.open.length})`,
        members: `Members (${data.members.length})${data.counts.appeals ? ` · ${data.counts.appeals} appeal${data.counts.appeals === 1 ? '' : 's'}` : ''}`,
        history: 'History',
      };
      const panels = {
        queue: () => queuePanel(data.queue, load),
        reports: () => reportsPanel(data.reports, load),
        members: () => membersPanel(data.members, load),
        history: () => historyPanel(data.actions),
      };
      const stat = (n, label) => h('div', { class: 'card stat' }, h('div', { class: 'n' }, String(n)), h('div', { class: 'l' }, label));

      const tabs = h(
        'div',
        { class: 'tabs', role: 'tablist', 'aria-label': 'Reviewer sections' },
        TAB_KEYS.map((key) =>
          h(
            'button',
            {
              type: 'button',
              role: 'tab',
              id: `tab-${key}`,
              class: 'tab',
              'aria-selected': key === tab ? 'true' : 'false',
              'aria-controls': 'reviewer-panel',
              tabindex: key === tab ? '0' : '-1',
              onClick: () => {
                app.reviewerTab = key;
                renderOverview(data);
                const el = container.querySelector(`#tab-${key}`);
                if (el) el.focus();
              },
            },
            labels[key]
          )
        )
      );
      tabs.addEventListener('keydown', (event) => {
        if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
        event.preventDefault();
        const index = TAB_KEYS.indexOf(tab);
        const next = TAB_KEYS[(index + (event.key === 'ArrowRight' ? 1 : TAB_KEYS.length - 1)) % TAB_KEYS.length];
        app.reviewerTab = next;
        renderOverview(data);
        const el = container.querySelector(`#tab-${next}`);
        if (el) el.focus();
      });

      const panel = h('div', { role: 'tabpanel', id: 'reviewer-panel', 'aria-labelledby': `tab-${tab}`, tabindex: '0' }, panels[tab]());

      container.replaceChildren(
        h(
          'div',
          { class: 'row between' },
          h('div', {}, h('span', { class: 'eyebrow' }, 'Reviewer dashboard'), h('h1', {}, 'Moderation')),
          h('button', { type: 'button', class: 'btn small', onClick: load }, 'Refresh')
        ),
        h(
          'div',
          { class: 'callout warn' },
          h('span', {}, h('strong', {}, 'Demo reviewer. '), 'Selecting this account from Demo accounts is not production authentication, but every action below is authorised on the server and logged in History.')
        ),
        h('div', { class: 'stats' }, stat(data.counts.pending, 'Pending requests'), stat(data.counts.openReports, 'Open reports'), stat(data.counts.appeals || 0, 'Suspension appeals'), stat(data.counts.members, 'Accounts')),
        tabs,
        panel
      );
    }

    $main.replaceChildren(container);
    await load();
  }

  function viewNotFound() {
    $main.replaceChildren(
      h('div', { class: 'narrow' }, h('div', { class: 'card' }, h('h1', {}, 'Page not found'), h('p', { class: 'muted' }, 'That link does not go anywhere in this prototype.'), h('div', { class: 'btn-row' }, h('a', { class: 'btn primary', href: '#/' }, 'Back to the start'))))
    );
  }

  // ---------------------------------------------------------------------
  // Router
  // ---------------------------------------------------------------------

  const views = {
    '/': viewLanding,
    '/join': viewJoin,
    '/recover': viewRecover,
    '/status': viewStatus,
    '/profile': viewProfile,
    '/feed': viewFeed,
    '/rules': viewRules,
    '/reviewer': viewReviewer,
    '/demo': viewDemo,
  };

  let renderSeq = 0;

  async function render() {
    renderSeq += 1;
    const seq = renderSeq;
    const path = currentPath();
    const view = views[path] || viewNotFound;
    renderNav(path);
    try {
      await view();
    } catch (err) {
      if (seq === renderSeq) $main.replaceChildren(errorCard(err, render));
    }
    if (seq !== renderSeq) return;
    renderNav(path);
    if (!app.firstRender) {
      window.scrollTo(0, 0);
      $main.focus({ preventScroll: true });
    }
    app.firstRender = false;
  }

  async function init() {
    try {
      await refreshMe();
    } catch (err) {
      app.me = null;
    }
    window.addEventListener('hashchange', render);
    await render();
  }

  init();
})();
