'use strict';

const crypto = require('node:crypto');

const STATUSES = Object.freeze(['unverified', 'pending', 'verified', 'rejected', 'suspended']);
const ROLES = Object.freeze(['member', 'reviewer']);
const REACTIONS = Object.freeze(['appreciate', 'insightful', 'support']);
const REPORT_REASONS = Object.freeze(['harassment', 'threat', 'impersonation', 'spam', 'other']);
const REPORT_TARGETS = Object.freeze(['post', 'comment', 'member']);
const RESOLUTIONS = Object.freeze(['dismiss', 'remove_content', 'suspend_member']);

function now() {
  return new Date().toISOString();
}

function newId() {
  return crypto.randomUUID();
}

function createAccount(fields) {
  return {
    id: fields.id || newId(),
    displayName: fields.displayName,
    handle: fields.handle,
    personHash: fields.personHash,
    role: fields.role || 'member',
    status: fields.status || 'unverified',
    demoLogin: Boolean(fields.demoLogin),
    bio: fields.bio || '',
    createdAt: fields.createdAt || now(),
    verification: {
      submittedAt: null,
      reviewedAt: null,
      reviewerId: null,
      note: null,
      attempts: 0,
      lastDecision: null,
    },
    suspension: null,
    blocked: [],
    muted: [],
  };
}

function createPost(fields) {
  return {
    id: fields.id || newId(),
    authorId: fields.authorId,
    body: fields.body,
    createdAt: fields.createdAt || now(),
    removed: false,
    removal: null,
    reactions: {},
    comments: [],
  };
}

function createComment(fields) {
  return {
    id: fields.id || newId(),
    authorId: fields.authorId,
    body: fields.body,
    createdAt: fields.createdAt || now(),
    removed: false,
    removal: null,
  };
}

function createReport(fields) {
  return {
    id: fields.id || newId(),
    reporterId: fields.reporterId,
    targetType: fields.targetType,
    targetId: fields.targetId,
    postId: fields.postId || null,
    reason: fields.reason,
    details: fields.details || '',
    status: 'open',
    createdAt: fields.createdAt || now(),
    resolution: null,
  };
}

module.exports = {
  STATUSES,
  ROLES,
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
};
