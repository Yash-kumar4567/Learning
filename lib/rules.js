'use strict';

// Community rules shown in the app and returned by GET /api/rules.
const RULES = Object.freeze([
  {
    id: 'one-person',
    title: 'One person, one account',
    summary: 'Every member is a verified person with a single active account.',
    allowed: ['Changing your display name or handle.', 'Recovering access to your existing account.'],
    notAllowed: ['Creating a second account.', 'Lending your account to someone else.'],
  },
  {
    id: 'disagree-well',
    title: 'Disagree with ideas, not with people',
    summary: 'Strong opinions and ordinary disagreement are welcome. Attacks on people are not.',
    allowed: ['Saying a take is wrong and explaining why.', 'Pushing back firmly on a popular view.'],
    notAllowed: ['Insults, slurs or dogpiling.', 'Repeatedly targeting one member across threads.'],
  },
  {
    id: 'no-harassment',
    title: 'No harassment or threats',
    summary: 'Threats of harm, intimidation and sustained unwanted contact lead to removal.',
    allowed: ['Blocking or muting anyone, for any reason, without explanation.'],
    notAllowed: ['Threats, even as a joke.', 'Contacting someone who has asked you to stop.'],
  },
  {
    id: 'no-impersonation',
    title: 'Be yourself',
    summary: 'Do not pretend to be another person, organisation or reviewer.',
    allowed: ['Pseudonymous display names, as long as they are not deceptive.'],
    notAllowed: ['Copying another member\'s name and avatar.', 'Claiming to speak for Gather.'],
  },
  {
    id: 'no-spam',
    title: 'No spam',
    summary: 'Repeated promotion, bulk posting and link farming are removed.',
    allowed: ['Sharing your own work occasionally, in context.'],
    notAllowed: ['Posting the same promotion across threads.', 'Automated posting.'],
  },
  {
    id: 'privacy',
    title: 'Respect privacy',
    summary: 'Do not share another person\'s private information or verification details.',
    allowed: ['Talking about your own experiences.'],
    notAllowed: ['Posting addresses, phone numbers or documents of others.'],
  },
  {
    id: 'moderation',
    title: 'How moderation works',
    summary:
      'People review reports. Nothing here detects negativity automatically. Reviewers can remove content, suspend accounts and reinstate them, and every action is logged.',
    allowed: ['Reporting anything you think breaks these rules.', 'Appealing a rejected verification.'],
    notAllowed: ['Filing reports to silence disagreement.'],
  },
]);

module.exports = { RULES };
