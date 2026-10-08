#!/usr/bin/env node
'use strict';

// Gather demo server. Plain Node.js, no dependencies.
//   node server.js            -> http://127.0.0.1:3000
//   GATHER_PORT=4000 node server.js
//   GATHER_DATA_DIR=./somewhere node server.js
// Binds to loopback by default. Set GATHER_HOST to change it deliberately.

const path = require('node:path');
const { createApp } = require('./lib/app');

function main() {
  const host = process.env.GATHER_HOST || '127.0.0.1';
  const port = Number(process.env.GATHER_PORT || 3000);
  const dataDir = process.env.GATHER_DATA_DIR || path.join(__dirname, 'data');

  const app = createApp({ dataDir });
  app
    .listen(port, host)
    .then((address) => {
      console.log(`Gather demo is running at http://${address.address}:${address.port}`);
      console.log(`State file: ${app.store.file}`);
      console.log('Demo: simulated verification. Not for production use.');
    })
    .catch((err) => {
      console.error('Could not start the server:', err.message);
      process.exit(1);
    });

  let closing = false;
  const shutdown = () => {
    if (closing) return;
    closing = true;
    console.log('\nShutting down. State is saved to disk.');
    app.close().then(() => process.exit(0));
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (require.main === module) {
  main();
}

module.exports = { createApp };
