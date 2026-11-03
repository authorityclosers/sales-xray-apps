import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

// Native feel in the shell (orchestrator, 3 Nov 2026; docs/product/research-hybrid-native-feel-20261103.md
// items 5, 6, 8 and 11 and native-feel-spec-20261103.md section 4 in the platform repo).
const main = new URL("../apps/phone/android/app/src/main/", import.meta.url);
const read = (path) => readFileSync(new URL(path, main), "utf8");
const java = (name) => read(`java/com/authorityclosers/salesxray/${name}.java`);
const body = (source, signature) => {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, signature);
  const rest = source.slice(start);
  return rest.slice(0, rest.indexOf("\n    }\n"));
};

// sx-phone.js's pull check, run against a small fake page.
function pullCheck(page) {
  const script = read("assets/sx-phone.js");
  const from = script.indexOf("  var pullScreens = ");
  const to = script.indexOf('  document.addEventListener(\n    "touchstart"');
  assert.ok(from > 0 && to > from, "sx-phone.js has the pull check");
  const context = vm.createContext({
    location: { pathname: page.path },
    document: {
      scrollingElement: { scrollTop: page.pageScroll ?? 0 },
      activeElement: page.focused ?? null,
      querySelector: () => (page.modalOpen ? {} : null),
    },
    window: {
      getComputedStyle: (node) => ({ position: node.position ?? "static" }),
    },
  });
  vm.runInContext(script.slice(from, to), context);
  return context.mayPull(page.target);
}
function element(parent, options = {}) {
  return {
    nodeType: 1,
    parentElement: parent ?? null,
    scrollTop: options.scrollTop ?? 0,
    position: options.position,
    getAttribute: (name) => (options.attributes ?? {})[name] ?? null,
    matches: () => options.field === true,
  };
}

test("a pull starts only on a list screen's top, outside sheets, fields and anything scrolled", () => {
  const html = element(null);
  const list = element(html);
  const row = element(list);
  assert.equal(pullCheck({ path: "/leads", target: row }), true);
  assert.equal(pullCheck({ path: "/dashboard/", target: row }), true);
  assert.equal(pullCheck({ path: "/analysis/calls", target: row }), true);
  // A lead page, Settings or a Report never reload under the finger.
  assert.equal(pullCheck({ path: "/settings", target: row }), false);
  assert.equal(pullCheck({ path: "/review/abc", target: row }), false);
  // The page or the list under the finger is scrolled down: the touch scrolls it.
  assert.equal(pullCheck({ path: "/leads", target: row, pageScroll: 40 }), false);
  const scrolled = element(element(html, { scrollTop: 120 }));
  assert.equal(pullCheck({ path: "/leads", target: scrolled }), false);
  // A sheet, a dialog or anything fixed (a sheet's backdrop, the bars), a focused field, an opt-out.
  assert.equal(pullCheck({ path: "/leads", target: row, modalOpen: true }), false);
  assert.equal(pullCheck({ path: "/leads", target: element(element(html, { position: "fixed" })) }), false);
  assert.equal(pullCheck({ path: "/leads", target: row, focused: element(html, { field: true }) }), false);
  const optedOut = element(element(html, { attributes: { "data-sx-pull": "off" } }));
  assert.equal(pullCheck({ path: "/leads", target: optedOut }), false);
});

test("sx-phone.js tells the app about every touch and asks the page to refresh before loading it again", () => {
  const script = read("assets/sx-phone.js");
  assert.match(script, /addEventListener\(\s*"touchstart",[\s\S]*method: "pull", args: \{ ok: ok \}[\s\S]*\{ capture: true, passive: true \}/);
  assert.match(script, /pullRefresh: function \(\) \{[\s\S]*new CustomEvent\("sx-refresh", \{\s*cancelable: true,[\s\S]*call\("refreshed", \{\}\)[\s\S]*if \(asked\.defaultPrevented\) return "page";\s*location\.reload\(\);\s*return "reload";/);
  assert.match(script, /meta\[name="sx-safe-area"\]/);
  assert.match(script, /call\("safeArea", \{\s*page: !!safeArea && safeArea\.getAttribute\("content"\) === "page",/);
});

test("the page sits in a pull layout that the page decides for, and a pull's paint or answer ends it", () => {
  const activity = java("MainActivity");
  const create = body(activity, "public void onCreate(Bundle savedInstanceState)");
  assert.match(create, /pull = PullRefresh\.around\(webView\);/);
  assert.match(create, /safeArea = SafeArea\.apply\(this, webView, PhoneBridge\.HOSTS, insets -> pull\.setTopInset\(insets\[1\]\)\);/);
  assert.match(body(activity, "public void onPageCommitVisible(WebView view, String url)"), /pull\.done\(\);/);
  const pull = java("PullRefresh");
  assert.match(pull, /class PullRefresh extends SwipeRefreshLayout/);
  assert.match(body(pull, "public boolean canChildScrollUp()"), /return !touchMayPull \|\| page\.getScrollY\(\) > 0;/);
  assert.match(body(pull, "public boolean onInterceptTouchEvent(MotionEvent event)"), /touchMayPull = false;/);
  // The hidden sign-in window sits beside the pull layout, which lays out one child only.
  assert.match(activity, /\(\(ViewGroup\) pull\.getParent\(\)\)\.addView\(window, new ViewGroup\.LayoutParams\(1, 1\)\)/);
  const bridge = java("PhoneBridge");
  for (const method of ["backDepth", "pull", "refreshed", "safeArea"]) assert.match(bridge, new RegExp(`case "${method}":`));
});

test("Back listens only while the page can go back, and the screen opts in to predictive back", () => {
  const activity = java("MainActivity");
  assert.doesNotMatch(activity, /new OnBackPressedCallback\(true\)/);
  assert.match(body(activity, "public void onCreate(Bundle savedInstanceState)"), /back = new WebBack\(this, webView\);/);
  assert.match(body(activity, "public void doUpdateVisitedHistory(WebView view, String url, boolean isReload)"), /back\.historyChanged\(\);/);
  assert.match(body(activity, "public void onPageStarted(WebView view, String url, Bitmap favicon)"), /back\.pageStarted\(\);/);
  const back = java("WebBack");
  assert.match(back, /handler\.setEnabled\(BackRules\.appHandles\(Build\.VERSION\.SDK_INT, depth, page\.canGoBack\(\)\)\)/);
  const manifest = read("AndroidManifest.xml");
  const screen = manifest.slice(manifest.indexOf('android:name=".MainActivity"') - 300, manifest.indexOf('android:name=".MainActivity"') + 400);
  assert.match(screen, /android:enableOnBackInvokedCallback="true"/);
});

test("the start: a 2 s splash cap, a connection ahead of the page, and a renderer that dies never closes the app", () => {
  const activity = java("MainActivity");
  assert.match(activity, /private static final long SPLASH_MAX_MS = 2_000;/);
  // Both WebViews share one renderer: both clients must answer true, or Android closes the app.
  const gone = activity.match(/public boolean onRenderProcessGone\(WebView view, RenderProcessGoneDetail detail\) \{[\s\S]*?\n {20}\}|public boolean onRenderProcessGone\(WebView view, RenderProcessGoneDetail detail\) \{[\s\S]*?\n {16}\}/g);
  assert.equal(gone?.length, 2);
  for (const handler of gone) assert.match(handler, /return true;/);
  assert.match(body(activity, "private void renderGone(WebView view, boolean crashed)"), /recreate\(\);/);
  assert.match(body(activity, "public void onSaveInstanceState(@NonNull Bundle outState)"), /if \(page != null && !renderCrashed\) outState\.putString\(PageState\.KEY, page\.getUrl\(\)\);/);
  const start = java("WebViewStart");
  assert.match(start, /profile\.enqueuePreconnect\(site\);/);
  assert.match(start, /getUiThreadBlockingStartUpLocations\(\)/);
  // No WebView is made only to be thrown away: the one other WebView is the sign-in window, made on demand.
  assert.equal((activity.match(/new WebView\(/g) ?? []).length, 1);
  assert.match(body(activity, "private WebView openSignInWindow(WebView opener)"), /new WebView\(this\)/);
});

test("Android never darkens the app by itself, and its night mode is the page's theme key", () => {
  const styles = read("res/values/styles.xml");
  for (const theme of ["AppTheme", "AppTheme.NoActionBar", "AppTheme.NoActionBarLaunch"]) {
    const start = styles.indexOf(`<style name="${theme}"`);
    const block = styles.slice(start, styles.indexOf("</style>", start));
    assert.match(block, /<item name="android:forceDarkAllowed" tools:targetApi="29">false<\/item>/, theme);
  }
  assert.match(java("NightMode"), /getString\(MODE, "light"\)/);
  assert.match(java("ThemeRules"), /return "dark"\.equals\(mode\) \? "dark" : "light";/);
});
