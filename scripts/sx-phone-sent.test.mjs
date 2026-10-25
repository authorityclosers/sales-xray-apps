import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

// "Calls on this phone" says Sent only once the page really uploaded the recording
// (demo matrix, 25 Oct 2026: it said Sent for a recording only added to New analysis).
const source = readFileSync(
  new URL("../apps/phone/android/app/src/main/assets/sx-phone.js", import.meta.url),
  "utf8",
);
const UPLOAD = "/v1/conversation/acquisition/submissions/3f6c/source";

// A page with just enough for sx-phone.js to load, and a fetch that answers `answer`.
function page(answer) {
  const store = new Map();
  const fetched = [];
  // Any DOM node or method: every property is another one, and calling one returns one.
  const element = () => {
    const values = {};
    return new Proxy(function () {}, {
      get: (target, key) => (key in values ? values[key] : element()),
      set: (target, key, value) => {
        values[key] = value;
        return true;
      },
      apply: () => element(),
    });
  };
  const sandbox = {
    SXPhone: { postMessage() {}, onmessage: null },
    localStorage: {
      getItem: (key) => (store.has(key) ? store.get(key) : null),
      setItem: (key, value) => store.set(key, String(value)),
      removeItem: (key) => store.delete(key),
    },
    sessionStorage: { getItem: () => null, setItem() {} },
    document: element(),
    location: { pathname: "/dashboard", assign() {} },
    history: { pushState() {}, replaceState() {} },
    matchMedia: () => ({ matches: false, addEventListener() {} }),
    addEventListener() {},
    dispatchEvent() {},
    MutationObserver: class {
      observe() {}
    },
    setTimeout: () => 0,
    clearTimeout() {},
    setInterval: () => 0,
    fetch: (...args) => {
      fetched.push(args);
      return Promise.resolve(answer);
    },
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox);
  const read = (key) => JSON.parse(store.get(key) ?? "{}");
  return { sandbox, store, fetched, read };
}

const added = (store) =>
  store.set(
    "sx.phone.added",
    JSON.stringify({ "content://call-1": { at: 1, name: "call-1.mp3", size: 2048 } }),
  );
const flush = () => new Promise((resolve) => setImmediate(resolve));

test("an added recording becomes Sent once its upload went through", async () => {
  const answer = { ok: true };
  const { sandbox, store, fetched, read } = page(answer);
  added(store);
  const init = { method: "PUT", body: { name: "call-1.mp3", size: 2048 } };
  const response = await sandbox.fetch(UPLOAD, init);
  await flush();
  // The page's call passes through untouched.
  assert.equal(response, answer);
  assert.deepEqual(fetched, [[UPLOAD, init]]);
  assert.ok(read("sx.phone.sent")["content://call-1"]);
  assert.deepEqual(read("sx.phone.added"), {});
});

test("a failed upload, a playback read or another file leaves it Ready to analyse", async () => {
  const failed = page({ ok: false });
  added(failed.store);
  await failed.sandbox.fetch(UPLOAD, { method: "PUT", body: { name: "call-1.mp3", size: 2048 } });
  await flush();
  assert.deepEqual(failed.read("sx.phone.sent"), {});

  const { sandbox, store, read } = page({ ok: true });
  added(store);
  await sandbox.fetch(UPLOAD, { headers: { Range: "bytes=0-" } });
  await sandbox.fetch(UPLOAD, { method: "PUT", body: { name: "other.mp3", size: 2048 } });
  await sandbox.fetch(UPLOAD, { method: "PUT", body: { name: "call-1.mp3", size: 99 } });
  await sandbox.fetch("/v1/conversation/acquisition/submissions/3f6c/plan", {
    method: "PUT",
    body: { name: "call-1.mp3", size: 2048 },
  });
  await flush();
  assert.deepEqual(read("sx.phone.sent"), {});
  assert.ok(read("sx.phone.added")["content://call-1"]);
});
