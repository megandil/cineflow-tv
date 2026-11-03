// Build app/ first, then run: node tools/window-test.mjs
// Checks the real window controller and maximize helpers without opening desktop windows.
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
function load(file, mocks, platform) {
  const filename = join(root, file);
  const exports = {};
  const require = createRequire(filename);
  vm.runInNewContext(readFileSync(filename, "utf8"), {
    exports, require: (name) => name in mocks ? mocks[name] : require(name),
    process: { platform }, setInterval: () => 1, clearInterval() {},
  }, { filename });
  return exports;
}

class Window extends EventEmitter {
  constructor(resizable) {
    super();
    this.resizable = resizable;
    this.nativeChanges = [];
    this.bounds = { x: 150, y: 100, width: 1200, height: 800 };
    this.fullScreen = false;
    this.maximized = false;
    this.webContents = { send() {} };
  }
  getBounds() { return { ...this.bounds }; }
  setBounds(bounds) { this.bounds = { ...bounds }; }
  getMinimumSize() { return this.resizable ? [0, 0] : [this.bounds.width, this.bounds.height]; }
  isDestroyed() { return false; }
  isFullScreen() { return this.fullScreen; }
  isMaximized() { return this.maximized; }
  setResizable(on) { this.resizable = on; this.nativeChanges.push(on); }
  maximize() { this.maximized = true; this.emit("maximize"); }
  unmaximize() { this.maximized = false; this.emit("unmaximize"); }
  setFullScreen(on) { this.fullScreen = on; this.emit(on ? "enter-full-screen" : "leave-full-screen"); }
}

function setup(platform, nativeResizeEnabled, transparent) {
  const win = new Window(nativeResizeEnabled);
  const ipcMain = new EventEmitter();
  ipcMain.handle = () => {};
  let cursor = { x: 1000, y: 500 };
  const screen = {
    getDisplayMatching: () => ({
      bounds: { x: 0, y: 0, width: 1920, height: 1080 },
      workArea: { x: 0, y: 0, width: 1920, height: 1040 },
    }),
    getCursorScreenPoint: () => cursor,
  };
  const maximize = load("app/dist/core/Maximize.js", { electron: { screen } }, platform);
  maximize.useManualMaximize(win, transparent);
  load("app/dist/controllers/windowController.js", {
    electron: { ipcMain, screen }, "../main": { mainWindow: win }, "../core/Maximize": maximize,
  }, platform).setupWindowControls(nativeResizeEnabled);
  const { IPC_CHANNELS } = createRequire(join(root, "app/package.json"))("./dist/constants");
  return { win, maximize, setCursor: (value) => { cursor = value; },
    input: (channel, ...args) => ipcMain.emit(IPC_CHANNELS[channel], {}, ...args) };
}

const { win, maximize, input, setCursor } = setup("win32", false, true);
for (let i = 0; i < 3; i++) {
  const normal = win.getBounds();
  input("MAXIMIZE_WINDOW");
  assert.equal(maximize.isMaximized(win), true);
  input("MAXIMIZE_WINDOW");
  assert.equal(maximize.isMaximized(win), false);
  assert.deepEqual(win.getBounds(), normal);
  assert.equal(win.resizable, false, "restoring a transparent window must not enable native resizing");
  maximize.setFullScreen(win, true);
  maximize.setFullScreen(win, false);
  assert.deepEqual(win.getBounds(), normal);
  assert.equal(win.resizable, false, "leaving fullscreen must not enable native resizing");
}
assert.ok(win.nativeChanges.every((on) => !on));
console.log("PASS transparent Windows windows keep native resizing off through maximize and fullscreen cycles");

// The custom controls call setBounds rather than Windows' native resizing.
const beforeDrag = win.getBounds();
input("WINDOW_DRAG", "start", 500, 120);
input("WINDOW_DRAG", "move", 540, 145);
input("WINDOW_DRAG", "end", 540, 145);
assert.equal(win.bounds.x, beforeDrag.x + 40);
assert.equal(win.bounds.y, beforeDrag.y + 25);
const beforeResize = win.getBounds();
input("WINDOW_RESIZE", "start", "e");
setCursor({ x: 1090, y: 500 });
input("WINDOW_RESIZE", "end", "e");
assert.equal(win.bounds.width, beforeResize.width + 90);
input("WINDOW_RESIZE", "start", "e");
setCursor({ x: 1000, y: 500 });
input("WINDOW_RESIZE", "end", "e");
assert.equal(win.bounds.width, beforeResize.width, "custom edges must shrink past the native fixed-size minimum");
assert.equal(win.resizable, false);
console.log("PASS custom dragging and edge resizing work with native resizing off");

// IPC may arrive after a quick drag has already moved the system cursor to its end point.
const beforeQuickResize = win.getBounds();
setCursor({ x: 1090, y: 500 });
input("WINDOW_RESIZE", "start", "e", 1000, 500);
input("WINDOW_RESIZE", "end", "e");
assert.equal(win.bounds.width, beforeQuickResize.width + 90, "quick resize must use the pointer-down coordinates");
console.log("PASS quick edge drags retain the pointer-down position across IPC");

const beforeFull = win.getBounds();
input("MAXIMIZE_WINDOW");
maximize.setFullScreen(win, true);
maximize.setFullScreen(win, false);
assert.equal(maximize.isMaximized(win), true);
assert.equal(win.resizable, false);
input("MAXIMIZE_WINDOW");
assert.deepEqual(win.getBounds(), beforeFull);
console.log("PASS fullscreen from maximized returns to maximized, then restores the normal bounds");

const opaque = setup("win32", true, false);
opaque.input("MAXIMIZE_WINDOW");
assert.equal(opaque.win.resizable, false);
opaque.input("MAXIMIZE_WINDOW");
assert.equal(opaque.win.resizable, true);
opaque.maximize.setFullScreen(opaque.win, true);
opaque.maximize.setFullScreen(opaque.win, false);
assert.equal(opaque.win.resizable, true);
for (const platform of ["darwin", "linux"]) {
  const other = setup(platform, true, true);
  other.input("MAXIMIZE_WINDOW");
  other.input("MAXIMIZE_WINDOW");
  other.maximize.setFullScreen(other.win, true);
  other.maximize.setFullScreen(other.win, false);
  assert.equal(other.win.resizable, true);
  assert.deepEqual(other.win.nativeChanges, []);
}
console.log("PASS opaque Windows windows and non-Windows controls retain their resizing behavior");
