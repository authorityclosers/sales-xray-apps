#!/usr/bin/env python3
"""Read-only screen evidence on a USB phone. Python 3 standard library only."""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile

PACKAGE = 'com.authorityclosers.salesxray.staging'
HOST = 'salesxray-staging.authorityclosers.com'
REPO = 'authorityclosers/sales-xray-apps'


def screens(lead, report):
    # IDs come from this staging workspace, never production. No calls or writes.
    return [('Home', '/dashboard/'), ('Leads', '/leads/'),
            ('Lead', '/leads/?lead=' + lead), ('Calls', '/analysis/calls/'),
            ('Report', '/analysis/calls/' + report + '/'), ('Chats', '/chats/'),
            ('Organisation', '/organisation/'), ('Settings', '/settings/')]


@contextlib.contextmanager
def phone_lock(directory, serial):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / (serial + '.lock')
    # O_EXCL is atomic on macOS, Linux and Windows. Existing locks are never stolen.
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, 'w') as out:
            json.dump({'pid': os.getpid(), 'serial': serial, 'started': time.time()}, out)
        yield path
    finally:
        path.unlink(missing_ok=True)


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, timeout=120, **kwargs)


def aapt_tool(explicit):
    if explicit:
        return explicit
    found = shutil.which('aapt')
    if found:
        return found
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    choices = sorted(Path(sdk).glob('build-tools/*/aapt*')) if sdk else []
    choices = [p for p in choices if p.name in ('aapt', 'aapt.exe')]
    if not choices:
        raise ValueError('aapt required to verify the APK package before install; set --aapt or ANDROID_HOME')
    return str(choices[-1])


def verify_apk(apk, aapt):
    badging = run([aapt, 'dump', 'badging', apk], capture_output=True, text=True).stdout
    match = re.search(r"^package: name='([^']+)'", badging, re.M)
    if not match or match[1] != PACKAGE:
        raise ValueError('Refusing APK: package must be exactly ' + PACKAGE)
    with zipfile.ZipFile(apk) as z:
        config = json.loads(z.read('assets/capacitor.config.json'))
        bundle = json.loads(z.read('assets/web/sx-web.json'))
        if config['server']['url'].rstrip('/') != 'https://' + HOST or bundle['host'] != HOST:
            raise ValueError('Refusing APK: Capacitor and bundled web must both target staging')
        if not re.fullmatch('[0-9a-f]{40}', bundle['sha']):
            raise ValueError('Refusing APK: missing exact bundled platform commit')
    return bundle


def download_apk(out):
    env = dict(os.environ)
    result = run(['gh', 'run', 'list', '-R', REPO, '--workflow', 'staging-android.yml',
                  '--branch', 'main', '--status', 'success', '--limit', '1', '--json', 'databaseId,headSha,url'],
                 capture_output=True, text=True, env=env)
    runs = json.loads(result.stdout)
    if not runs:
        raise ValueError('No successful staging QA build on main; pass --apk explicitly')
    build = runs[0]
    run(['gh', 'run', 'download', str(build['databaseId']), '-R', REPO,
         '--pattern', 'sales-xray-staging-*', '--dir', out / 'artifact'], env=env)
    apks = list((out / 'artifact').rglob('*.apk'))
    if len(apks) != 1:
        raise ValueError('Expected exactly one staging APK in artifact')
    return apks[0], build


def check(args):
    routes = screens(args.lead, args.report)
    adb = ['adb', '-s', args.serial]
    if args.dry_run:
        print('DRY RUN: no phone commands, downloads, locks or installs')
        print('Lock:', args.lock_dir / (args.serial + '.lock'))
        print('Verify APK package, Capacitor staging URL and S9 host/commit before install')
        print(shlex.join(adb + ['install', '-r', str(args.apk or '<latest signed staging artifact>')]))
        for name, path in routes:
            print(name + ': ' + shlex.join(adb + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.authorityclosers.salesxray.MainActivity', '-a', 'android.intent.action.VIEW', '-d', PACKAGE + ':' + path]))
        return 0
    with phone_lock(args.lock_dir, args.serial):
        args.out.mkdir(parents=True, exist_ok=False)
        os.chmod(args.out, 0o700)
        apk, build = (args.apk.resolve(), None) if args.apk else download_apk(args.out)
        bundle = verify_apk(apk, aapt_tool(args.aapt))
        run(adb + ['get-state'], capture_output=True)
        # No uninstall/downgrade/data-clear fallback: a signature mismatch needs the operator.
        run(adb + ['install', '-r', apk], capture_output=True)
        packages = run(adb + ['shell', 'cmd', 'package', 'list', 'packages', '-U', PACKAGE], capture_output=True, text=True).stdout
        uid_match = re.search(r'^package:' + re.escape(PACKAGE) + r' uid:(\d+)\s*$', packages, re.M)
        if not uid_match:
            raise ValueError('Cannot isolate staging logcat UID; no logs collected')
        rows = []
        failed = False
        log_path = args.out / 'logcat.txt'
        with log_path.open('wb') as log:
            # Do not clear the phone's shared logcat buffers. Start with fresh messages only.
            logger = subprocess.Popen(adb + ['logcat', '--uid=' + uid_match[1], '-T', '1', '-v', 'threadtime'], stdout=log, stderr=subprocess.STDOUT)
            try:
                time.sleep(.5)
                for name, path in routes:
                    run(adb + ['shell', 'am', 'force-stop', PACKAGE])
                    log_offset = log_path.stat().st_size
                    start = time.monotonic()
                    launch = run(adb + ['shell', 'am', 'start', '-W', '-n', PACKAGE + '/com.authorityclosers.salesxray.MainActivity',
                                        '-a', 'android.intent.action.VIEW', '-d', PACKAGE + ':' + path], capture_output=True, text=True)
                    (args.out / (name.lower() + '-launch.txt')).write_text(launch.stdout + launch.stderr)
                    record = None
                    # Each screen has a fresh process and document: FCP is meaningful, not an SPA estimate.
                    deadline = start + args.timeout
                    while time.monotonic() < deadline:
                        if logger.poll() is not None:
                            raise ValueError('logcat stopped; check USB connection')
                        text = log_path.read_bytes()[log_offset:].decode('utf-8', errors='replace')
                        pattern = r'SXQA ' + re.escape(json.dumps(path)) + r' (\{[^\n]+\})'
                        matches = re.findall(pattern, text)
                        if matches:
                            record = json.loads(matches[-1])
                            break
                        time.sleep(.25)
                    # Give the hydrated page time to settle for the visual evidence, without calling it FCP.
                    time.sleep(args.settle)
                    shot = name.lower() + '.png'
                    with (args.out / shot).open('wb') as image:
                        run(adb + ['exec-out', 'screencap', '-p'], stdout=image)
                    actual = record['url'] if record else 'No FCP signal (timeout)'
                    expected = 'https://' + HOST + path.split('?')[0]
                    ok = bool(record and actual.rstrip('/') == expected.rstrip('/'))
                    failed |= not ok
                    rows.append({'screen': name, 'path': path, 'fcp_ms': record['ms'] if record else None,
                                 'launch_to_screenshot_ms': round((time.monotonic() - start) * 1000),
                                 'actual': actual, 'status': 'paint observed; inspect screenshot' if ok else 'FAIL: timeout or redirect', 'screenshot': shot})
            finally:
                logger.terminate()
                try:
                    logger.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    logger.kill()
                    logger.wait()
                # Always retain a partial report when adb or navigation fails.
                text = log_path.read_text(errors='replace')
                errors = [line for line in text.splitlines() if re.search(r'FATAL EXCEPTION|AndroidRuntime|SXQAConsole|[Ee]rror.*(?:bridge|Capacitor)|(?:bridge|Capacitor).*([Ee]rror|[Ee]xception)|chromium.*CONSOLE.*[Ee]rror', line)]
                (args.out / 'errors.txt').write_text('\n'.join(errors))
                evidence = {'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'build': build,
                            'platform': bundle['sha'], 'serial': args.serial, 'screens': rows, 'error_lines': len(errors)}
                (args.out / 'report.json').write_text(json.dumps(evidence, indent=2))
                md = ['# Staging real-app check', '', 'Platform: `' + bundle['sha'] + '`', '',
                      'Cold document FCP is measured from Android process start; Android launch output is also kept. Screenshots require visual review. Lead query state and report contents require signed-in review; a paint alone does not prove them.', '',
                      '| Screen | FCP ms | Result / final URL | Screenshot |', '|---|---:|---|---|']
                for row in rows:
                    md.append(f"| {row['screen']} | {row['fcp_ms'] if row['fcp_ms'] is not None else 'unavailable'} | {row['status']}: {row['actual']} | [image]({row['screenshot']}) |")
                md += ['', f"Captured {len(errors)} error candidates: [errors](errors.txt), [full logcat](logcat.txt).", '',
                       'Logs and screenshots may contain staging account data. Keep this folder private.']
                (args.out / 'REPORT.md').write_text('\n'.join(md) + '\n')
        print(args.out / 'REPORT.md')
        return 1 if failed or errors else 0


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial', help='USB adb serial; always scoped with adb -s')
    p.add_argument('--apk', type=Path, help='Signed staging APK; defaults to latest successful main staging build via gh')
    p.add_argument('--lead', required=True, help='Existing staging lead ID')
    p.add_argument('--report', required=True, help='Existing staging report ID')
    p.add_argument('--aapt', help='Android SDK build-tools aapt executable')
    p.add_argument('--out', type=Path, default=Path('real-app-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')))
    p.add_argument('--lock-dir', type=Path, default=Path(os.environ.get('SX_PHONE_LOCK_DIR', str(Path.home() / '.cache/sales-xray/phone-locks'))))
    p.add_argument('--timeout', type=float, default=30)
    p.add_argument('--settle', type=float, default=3)
    p.add_argument('--dry-run', action='store_true')
    args = p.parse_args()
    # Colons in wireless serials are normalized consistently for cross-platform filenames.
    if not re.fullmatch(r'[A-Za-z0-9_.:-]+', args.serial) or args.serial.startswith('-'):
        p.error('invalid serial')
    if ':' in args.serial:
        p.error('USB serial required; wireless serials are not supported')
    for value in (args.lead, args.report):
        if not re.fullmatch(r'[A-Za-z0-9_-]+', value):
            p.error('lead/report must be staging IDs (letters, digits, underscore, hyphen)')
    if args.timeout <= 0 or args.settle < 0:
        p.error('timeout must be positive; settle must be nonnegative')
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        return check(args)
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, KeyError) as error:
        print('real-app-check: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
