import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read = p => readFileSync(new URL('../' + p, import.meta.url), 'utf8');
test('staging inherits Play overlay and keeps native sources shared', () => {
  const gradle = read('apps/phone/android/app/build.gradle');
  assert.match(gradle, /manifest.srcFile 'src\/play\/AndroidManifest.xml'/);
  assert.match(gradle, /applicationIdSuffix "\.staging"/);
  assert.match(gradle, /appLabel: "Sales X-Ray Staging"/);
  assert.match(gradle, /sx_self_package", "com.authorityclosers.salesxray.staging"/);
  assert.match(gradle, /appIcon: "@drawable\/staging_icon"/);
});
test('staging config isolates both Capacitor and native API calls', () => {
  const config = JSON.parse(read('apps/phone/android/app/src/staging/assets/capacitor.config.json'));
  assert.equal(config.appId, 'com.authorityclosers.salesxray.staging');
  assert.equal(config.server.url, 'https://salesxray-staging.authorityclosers.com');
  assert.equal(config.server.cleartext, false);
  assert.equal(config.android.allowMixedContent, false);
  assert.match(read('apps/phone/android/app/src/staging/res/drawable/staging_icon.xml'), /#6536B3/);
});
test('QA workflow requires exact staging web ref and cannot publish', () => {
  const workflow = read('.github/workflows/staging-android.yml');
  assert.match(workflow, /\^\[0-9a-f\]\{40\}\$/);
  assert.match(workflow, /web salesxray-staging.authorityclosers.com/);
  assert.match(workflow, /secrets.SX_ANDROID_SIGNING_STORE_BASE64/);
  assert.doesNotMatch(workflow, /play-upload|gh release|bundlePlayRelease|contents: write/);
  assert.match(workflow, /SX_STAGING_TESTLAB_ENABLED == 'true'/);
});

test('staging shares Play Java but cannot launch its store flows', () => {
  const gradle = read('apps/phone/android/app/build.gradle');
  assert.match(gradle, /java.srcDir 'src\/play\/java'/);
  for (const name of ['StoreUpdates', 'StoreReview']) {
    const source = read(`apps/phone/android/app/src/play/java/com/authorityclosers/salesxray/${name}.java`);
    assert.match(source, /!"com.authorityclosers.salesxray".equals\(activity.getPackageName\(\)\)/);
  }
});
