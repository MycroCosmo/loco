// Testcontainers preload only. Delegate unchanged, expose timings and the actual limiter key.
if (!process.argv[1]?.endsWith('start-v2.cjs')) return;
const { AsyncLocalStorage } = require('node:async_hooks');
const { Server } = require('node:http');
const context = new AsyncLocalStorage();
const emit = Server.prototype.emit;
Server.prototype.emit = function (event, ...args) {
  if (event !== 'request') return emit.call(this, event, ...args);
  const state = { imageNanos: 0n };
  const response = args[1], writeHead = response.writeHead;
  response.writeHead = function (...headers) {
    this.setHeader('X-Harness-Image-Nanos', state.imageNanos.toString());
    return writeHead.apply(this, headers);
  };
  return context.run(state, () => emit.call(this, event, ...args));
};
const { UploadService } = require('./dist/upload/upload.service');
const save = UploadService.prototype.saveAsWebP;
UploadService.prototype.saveAsWebP = async function (...args) {
  const start = process.hrtime.bigint();
  try { return await save.apply(this, args); }
  finally { const state = context.getStore(); if (state) state.imageNanos += process.hrtime.bigint() - start; }
};
const { ThrottlerGuard } = require('@nestjs/throttler');
const generateKey = ThrottlerGuard.prototype.generateKey;
ThrottlerGuard.prototype.generateKey = function (ctx, suffix, name) {
  const { req, res } = this.getRequestResponse(ctx);
  res.setHeader('X-Harness-Handler', `${ctx.getClass().name}-${ctx.getHandler().name}-${name}`);
  res.setHeader('X-Harness-Route', req.route.path);
  return generateKey.call(this, ctx, suffix, name);
};
