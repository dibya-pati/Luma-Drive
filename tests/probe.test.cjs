// SPDX-License-Identifier: GPL-3.0-or-later
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function fixture(protocol = 'http:') {
  let now = 0, nextTimer = 0;
  const timers = new Map(), sockets = [], listeners = {};
  const nodes = {state: {}, count: {}, retry: {}, stop: {}};
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; sockets.push(this); }
    send(text) { this.sent.push(text); }
    close() { this.readyState = 3; this.onclose?.(); }
    open() { this.readyState = 1; this.onopen?.(); }
    pong() { this.onmessage?.({data: 'pong'}); }
  }
  vm.runInNewContext(fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/probe/probe.js'), 'utf8'), {
    document: {getElementById: id => nodes[id]},
    window: {addEventListener: (type, cb) => { listeners[type] = cb; }},
    location: {protocol, host: 'probe.invalid:9093'}, WebSocket: Socket,
    Date: {now: () => now}, setInterval: cb => { timers.set(++nextTimer, cb); return nextTimer; },
    clearInterval: id => timers.delete(id)
  });
  return {nodes, sockets, timers, listeners, tick(ms) { now += ms; [...timers.values()].forEach(cb => cb()); }};
}
test('requires an echo, not just a socket open, before reporting success', () => {
  const f = fixture(); const s = f.sockets[0]; s.open();
  assert.deepEqual(s.sent, ['ping']); assert.doesNotMatch(f.nodes.state.textContent, /works/);
  s.pong(); assert.match(f.nodes.state.textContent, /works/); assert.equal(f.nodes.count.textContent, 'Echoes: 1');
  f.tick(1000); assert.equal(s.sent.length, 2);
});
test('missing replies become visible even while socket stays open', () => {
  const f = fixture(); f.sockets[0].open(); f.tick(6000);
  assert.match(f.nodes.state.textContent, /No echo reply/);
});
test('retry replaces the old timer and ignores stale events', () => {
  const f = fixture(); const old = f.sockets[0]; old.open(); f.nodes.retry.onclick();
  old.onopen?.(); old.pong(); assert.equal(f.timers.size, 0);
  const next = f.sockets[1]; next.open(); next.pong();
  assert.equal(f.timers.size, 1); assert.equal(old.readyState, 3);
  next.close(); assert.equal(f.timers.size, 0); assert.match(f.nodes.state.textContent, /closed/);
});
test('secure origin uses WSS and leaving stops the heartbeat', () => {
  const f = fixture('https:'); const s = f.sockets[0];
  assert.equal(s.url, 'wss://probe.invalid:9093/echo'); s.open();
  f.listeners.pagehide(); assert.equal(f.timers.size, 0); assert.equal(s.readyState, 3);
});

test('Stop checking closes the socket and heartbeat until an explicit retry',()=>{const f=fixture();const s=f.sockets[0];s.open();s.pong();f.nodes.stop.onclick();f.tick(6000);assert.equal(s.readyState,3);assert.equal(f.timers.size,0);assert.match(f.nodes.state.textContent,/stopped/);s.pong();assert.match(f.nodes.state.textContent,/stopped/);f.nodes.retry.onclick();assert.equal(f.sockets.length,2);});
