// Build app/ first (npm run dist), then:
// node tools/volume-test.mjs [path/to/mpv]
// Exercises the shell's startup options, the page proxy and per-viewer restoration.
// Supplying mpv also checks the actual native volume limit without playing audio.
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { connect } from "node:net";
import { dirname, join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const log = { info() {}, warn() {}, error() {} };
function load(file, mocks, globals = {}) {
  const filename = join(root, file);
  const exports = {};
  const require = createRequire(filename);
  vm.runInNewContext(readFileSync(filename, "utf8"), {
    exports, require: (name) => name in mocks ? mocks[name] : require(name),
    process, Buffer, console, __dirname: dirname(filename), ...globals,
  }, { filename });
  return exports;
}

let startup;
const shell = load("app/dist/core/MpvShell.js", {
  electron: { app: { getVersion: () => "test", getPath: () => root } },
  child_process: { spawn: (_exe, args) => { startup = args; return new EventEmitter(); } },
  "../utils/logger": { getLogger: () => log },
  "./Maximize": {},
}).default;
shell.mpvPath = () => "mpv";
shell.videoWindow = { getNativeWindowHandle: () => Buffer.alloc(8) };
shell.connect = () => {};
shell.startMpv();
assert.ok(startup.includes("--volume-max=200"), "native limit must match Stremio's 200% controls");
console.log("PASS native startup allows 200% volume");

let pageFunction;
load("app/dist/preload/mpvShell.js", {
  electron: {
    ipcRenderer: { on() {} },
    contextBridge: {
      exposeInMainWorld() {},
      executeInMainWorld: ({ func }) => { pageFunction = func.toString(); },
    },
  },
}, { EventTarget, Event }).installMpvShell();

let proxy, receive;
const sent = [];
const hooks = {};
const container = { querySelector: () => null, prepend(v) { proxy = v; v.parentElement = this; } };
const document = {
  querySelector: () => container,
  createElement() {
    return Object.assign(new EventTarget(), {
      style: {}, setAttribute() {}, remove() {},
      classList: { contains: (name) => name === "dx-mpv-video" },
    });
  },
};
const window = { __dxbiShellBridge: {
  post: (s) => sent.push(JSON.parse(s).args),
  listen: (fn) => { receive = fn; },
} };
vm.runInNewContext(`(${pageFunction})()`, {
  window, document, location: { hash: "#/player/test" }, Event, performance,
  addEventListener: (name, fn) => { hooks[name] = fn; }, setInterval() {},
});
hooks.hashchange();
function nativeProp(name, data) {
  receive(JSON.stringify({ type: 1, args: ["mpv-prop-change", { name, data }] }));
}
for (const percent of [0, 50, 100, 130, 180, 200]) {
  nativeProp("volume", percent);
  assert.equal(proxy.volume, percent / 100, `proxy must preserve ${percent}%`);
  proxy.volume = percent / 100;
  assert.deepEqual(sent.at(-1), ["mpv-set-prop", ["volume", percent]]);
}
proxy.volume = 3;
assert.equal(sent.at(-1)[1][1], 200);
proxy.volume = -1;
assert.equal(sent.at(-1)[1][1], 0);
const count = sent.length;
proxy.volume = NaN;
assert.equal(sent.length, count);
console.log("PASS proxy reads and writes boosted volume, clamps bounds and ignores NaN");

const plugin = readFileSync(join(root, "dxbi.plugin.js"), "utf8");
const playbackSource = plugin.slice(plugin.indexOf("  const playback = {"), plugin.indexOf("  const SHORTCUTS = ["));
let saved = { volume: 1.8, muted: true, rate: 1.25 };
let clock = 2000;
const playback = vm.runInNewContext(`${playbackSource}\nplayback`, {
  document: { querySelector: () => proxy }, location: { hash: "#/player/test" }, SPEEDS: [1, 1.25],
  settings: { rememberPlayback: true }, currentRoute: () => "player",
  viewers: { key: (k) => k }, watchParty: {},
  store: { get: () => saved, set: (_key, value) => { saved = value; } },
  performance: { now: () => clock }, clearTimeout() {}, setTimeout: (fn) => fn(),
});
playback.ensure();
sent.length = 0;
playback.handle("playing");
assert.ok(sent.some(([method, args]) => method === "mpv-set-prop" && args[0] === "volume" && args[1] === 180), "restore must preserve 180% volume");
nativeProp("volume", 200);
clock = 4000;
playback.handle("volumechange");
assert.equal(saved.volume, 2, "saved volume must retain 200%");
playback.applied = null;
sent.length = 0;
playback.handle("playing");
assert.ok(sent.some(([method, args]) => method === "mpv-set-prop" && args[0] === "volume" && args[1] === 200), "restore must preserve 200% volume");
const browserVideo = Object.assign(new EventTarget(), { classList: { contains: () => false }, volume: 0 });
playback.video = browserVideo;
playback.applied = null;
playback.handle("playing");
assert.equal(browserVideo.volume, 1, "browser playback must stay within HTMLMediaElement bounds");
console.log("PASS per-viewer boost survives save/restore; browser fallback stays within 100%");

if (process.argv[2]) {
  const pipe = process.platform === "win32" ? `\\\\.\\pipe\\dxbi-volume-test-${process.pid}` : `/tmp/dxbi-volume-test-${process.pid}.sock`;
  const args = startup.filter((s) => !/^--(wid|input-ipc-server|log-file|vo|force-window)=/.test(s));
  const child = spawn(process.argv[2], [...args, `--input-ipc-server=${pipe}`, "--vo=null", "--ao=null", "--force-window=no"], { windowsHide: true, stdio: "ignore" });
  let spawnError;
  child.on("error", (err) => { spawnError = err; });
  let socket;
  try {
    for (let i = 0; i < 100 && !socket; i++) {
      if (spawnError) throw spawnError;
      socket = await new Promise((done) => {
        const s = connect(pipe);
        s.once("connect", () => done(s));
        s.once("error", () => { s.destroy(); done(null); });
      });
      if (!socket) await new Promise((r) => setTimeout(r, 50));
    }
    assert.ok(socket, "mpv IPC must connect");
    let id = 0, buffer = "";
    const pending = new Map();
    socket.on("data", (chunk) => {
      buffer += chunk;
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const message = JSON.parse(buffer.slice(0, nl));
        buffer = buffer.slice(nl + 1);
        const resolve = pending.get(message.request_id);
        if (resolve) { pending.delete(message.request_id); resolve(message); }
      }
    });
    const command = (command) => new Promise((done, reject) => {
      const request_id = ++id;
      const timeout = setTimeout(() => {
        pending.delete(request_id);
        reject(new Error(`mpv timed out: ${JSON.stringify(command)}`));
      }, 5000);
      pending.set(request_id, (message) => { clearTimeout(timeout); done(message); });
      socket.write(JSON.stringify({ command, request_id }) + "\n");
    });
    assert.equal((await command(["get_property", "volume-max"])).data, 200);
    for (const percent of [50, 100, 130, 180, 200, 80]) {
      assert.equal((await command(["set_property", "volume", percent])).error, "success");
      assert.equal((await command(["get_property", "volume"])).data, percent);
    }
    console.log("PASS real mpv accepts and retains 180% and 200%, and returns to normal volume");
  } finally {
    socket?.destroy();
    child.kill();
  }
}
