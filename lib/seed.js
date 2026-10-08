'use strict';

const { createAccount, createPost, createComment, createReport, newId } = require('./models');
const { personHash } = require('./identity');

// Every person, name and post below is fictional. Demo person identifiers are
// synthetic strings so the uniqueness rule can be exercised without real data.
const DEMO_PEOPLE = Object.freeze([
  {
    handle: 'imani',
    displayName: 'Imani Okafor',
    personId: 'DEMO-IMANI-0001',
    status: 'verified',
    role: 'reviewer',
    bio: 'Community reviewer. I read every report slowly and on purpose.',
  },
  {
    handle: 'theo',
    displayName: 'Theo Lindqvist',
    personId: 'DEMO-THEO-0002',
    status: 'verified',
    bio: 'Rooftop beekeeper, amateur radio, chronic over-explainer.',
  },
  {
    handle: 'priya',
    displayName: 'Priya Raman',
    personId: 'DEMO-PRIYA-0003',
    status: 'verified',
    bio: 'Runs the Tuesday book circle. Strong opinions about footnotes.',
  },
  {
    handle: 'mateo',
    displayName: 'Mateo Álvarez',
    personId: 'DEMO-MATEO-0004',
    status: 'verified',
    bio: 'Bike mechanic. Will look at your derailleur for a coffee.',
  },
  {
    handle: 'samw',
    displayName: 'Sam Whitaker',
    personId: 'DEMO-SAM-0005',
    status: 'pending',
    bio: '',
  },
  {
    handle: 'rowan',
    displayName: 'Rowan Vale',
    personId: 'DEMO-ROWAN-0006',
    status: 'rejected',
    bio: '',
  },
  {
    handle: 'dex',
    displayName: 'Dex Marlow',
    personId: 'DEMO-DEX-0007',
    status: 'suspended',
    bio: 'Deals. Deals. Deals.',
  },
]);

function seedState(state) {
  const base = Date.now();
  const ago = (hours) => new Date(base - hours * 60 * 60 * 1000).toISOString();

  const byHandle = {};
  for (const person of DEMO_PEOPLE) {
    const account = createAccount({
      displayName: person.displayName,
      handle: person.handle,
      personHash: personHash(person.personId),
      role: person.role || 'member',
      status: person.status,
      demoLogin: true,
      bio: person.bio,
      createdAt: ago(240),
    });
    byHandle[person.handle] = account;
    state.accounts[account.id] = account;
    state.persons[account.personHash] = account.id;
  }

  const reviewer = byHandle.imani;

  for (const handle of ['imani', 'theo', 'priya', 'mateo', 'dex']) {
    const a = byHandle[handle];
    a.verification.submittedAt = ago(239);
    a.verification.reviewedAt = ago(238);
    a.verification.reviewerId = reviewer.id;
    a.verification.attempts = 1;
    a.verification.lastDecision = { outcome: 'approved', reason: null, at: ago(238) };
  }

  byHandle.samw.verification.submittedAt = ago(5);
  byHandle.samw.verification.attempts = 1;
  byHandle.samw.verification.note = 'Happy to answer any questions about my request.';

  byHandle.rowan.verification.submittedAt = ago(48);
  byHandle.rowan.verification.reviewedAt = ago(40);
  byHandle.rowan.verification.reviewerId = reviewer.id;
  byHandle.rowan.verification.attempts = 1;
  byHandle.rowan.verification.lastDecision = {
    outcome: 'rejected',
    reason:
      'The display name and handle closely match an existing verified member. Choose a handle that is clearly your own and resubmit.',
    at: ago(40),
  };

  byHandle.dex.suspension = {
    reason: 'Repeated unsolicited promotional posts after a warning.',
    at: ago(10),
    reviewerId: reviewer.id,
    previousStatus: 'verified',
  };

  const posts = [];
  const addPost = (handle, hours, body) => {
    const post = createPost({ authorId: byHandle[handle].id, body, createdAt: ago(hours) });
    posts.push(post);
    return post;
  };
  const addComment = (post, handle, hours, body) => {
    const comment = createComment({ authorId: byHandle[handle].id, body, createdAt: ago(hours) });
    post.comments.push(comment);
    return comment;
  };
  const react = (post, handle, type) => {
    post.reactions[byHandle[handle].id] = type;
  };

  const welcome = addPost(
    'imani',
    96,
    'Welcome to Gather. This is a small place on purpose: everyone here is a verified person with one account. Disagree freely, report anything that crosses into harassment, and expect a human to read it.'
  );
  react(welcome, 'theo', 'appreciate');
  react(welcome, 'priya', 'support');
  react(welcome, 'mateo', 'appreciate');
  addComment(welcome, 'priya', 95, 'Glad to be here. Is there a place to propose a reading list?');
  addComment(welcome, 'imani', 94, 'Not yet. Start a post and see who bites.');

  const bees = addPost(
    'theo',
    70,
    'First honey harvest from the rooftop hives: 4.2 kg from two colonies. The bees mostly worked the lime trees along the canal, and you can taste it. Happy to show anyone the setup on a weekend.'
  );
  react(bees, 'priya', 'insightful');
  react(bees, 'mateo', 'appreciate');
  react(bees, 'imani', 'appreciate');
  addComment(bees, 'mateo', 69, 'Four kilos from two hives in a city seems high. What is your winter plan?');
  addComment(bees, 'theo', 68, 'It is on the high side. Leaving them 15 kg each and insulating the roofs.');

  const books = addPost(
    'priya',
    52,
    'Tuesday book circle pick: a short novel this time, under 200 pages. I will argue that the footnotes are the actual story. Come prepared to disagree.'
  );
  react(books, 'theo', 'support');
  addComment(books, 'theo', 50, 'I will be the one arguing the footnotes are a distraction. See you there.');

  const bikes = addPost(
    'mateo',
    30,
    'Free bike check this Saturday at the market square, 10 to 1. Bring the bike, not the excuse. Brakes and gears only; I will not touch carbon frames.'
  );
  react(bikes, 'priya', 'appreciate');
  react(bikes, 'imani', 'support');

  const dexPost = addPost(
    'dex',
    14,
    'DEALS DEALS DEALS. Message me for the best prices in town. Limited time. Tell your friends.'
  );

  const quiet = addPost(
    'theo',
    6,
    'Unpopular opinion: the new bike lanes on the ring road are worse than nothing because they end abruptly at every junction. Change my mind.'
  );
  addComment(quiet, 'mateo', 5, 'Hard disagree. Half a lane is still safer than sharing with buses. The junctions need fixing, not the lanes.');
  addComment(quiet, 'theo', 4, 'Fair. I would take your version over the status quo.');
  react(quiet, 'mateo', 'insightful');

  state.posts.push(...posts);

  const spamReport = createReport({
    reporterId: byHandle.priya.id,
    targetType: 'post',
    targetId: dexPost.id,
    reason: 'spam',
    details: 'Third promotional post this week. Nothing to do with the community.',
    createdAt: ago(12),
  });
  spamReport.status = 'resolved';
  spamReport.resolution = {
    outcome: 'suspend_member',
    note: 'Warned earlier; suspended for repeated spam.',
    reviewerId: reviewer.id,
    at: ago(10),
  };

  const secondOpinion = createReport({
    reporterId: byHandle.priya.id,
    targetType: 'post',
    targetId: quiet.id,
    reason: 'other',
    details: 'Not sure this breaks a rule. It reads as a strong opinion, not an attack. Flagging for a second pair of eyes.',
    createdAt: ago(3),
  });

  state.reports.push(spamReport, secondOpinion);

  const action = (hours, type, targetType, targetId, targetLabel, note) => ({
    id: newId(),
    at: ago(hours),
    reviewerId: reviewer.id,
    reviewerHandle: reviewer.handle,
    type,
    targetType,
    targetId,
    targetLabel,
    note: note || null,
  });

  state.actions.push(
    action(238, 'approve_verification', 'member', byHandle.theo.id, 'Theo Lindqvist (@theo)'),
    action(238, 'approve_verification', 'member', byHandle.priya.id, 'Priya Raman (@priya)'),
    action(238, 'approve_verification', 'member', byHandle.mateo.id, 'Mateo Álvarez (@mateo)'),
    action(238, 'approve_verification', 'member', byHandle.dex.id, 'Dex Marlow (@dex)'),
    action(40, 'reject_verification', 'member', byHandle.rowan.id, 'Rowan Vale (@rowan)', byHandle.rowan.verification.lastDecision.reason),
    action(10, 'suspend_member', 'member', byHandle.dex.id, 'Dex Marlow (@dex)', byHandle.dex.suspension.reason),
    action(10, 'resolve_report', 'report', spamReport.id, 'Report on post by @dex', 'suspend_member')
  );

  state.seededAt = new Date(base).toISOString();
  return state;
}

module.exports = { seedState, DEMO_PEOPLE };
