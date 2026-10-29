import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('check', ROOT / 'scripts/real-app-check.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class RealAppTests(unittest.TestCase):
    def test_lock_refuses_existing_and_releases_only_own(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            with check.phone_lock(directory, 'USB1') as path:
                with self.assertRaises(FileExistsError):
                    with check.phone_lock(directory, 'USB1'):
                        self.fail('second driver acquired lock')
                self.assertTrue(path.exists())
            self.assertFalse(path.exists())
            with self.assertRaisesRegex(RuntimeError, 'failure'):
                with check.phone_lock(directory, 'USB1'):
                    raise RuntimeError('failure')
            self.assertFalse(path.exists())

    def test_dry_run_needs_no_tools_and_has_no_side_effects(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, locks = Path(tmp) / 'out', Path(tmp) / 'locks'
            result = subprocess.run(['bash', str(ROOT / 'scripts/real-app-check.sh'), 'USB123', '--lead', 'qa-lead',
                                     '--report', 'qa-report', '--out', str(out), '--lock-dir', str(locks), '--dry-run'],
                                    capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.count('android.intent.action.VIEW'), 8)
            self.assertIn('com.authorityclosers.salesxray.staging', result.stdout)
            self.assertNotIn('market:', result.stdout)
            self.assertFalse(out.exists())
            self.assertFalse(locks.exists())

    def test_refuse_play_apk_before_adb(self):
        with patch.object(check, 'run', return_value=subprocess.CompletedProcess([], 0, "package: name='com.authorityclosers.salesxray'")):
            with self.assertRaisesRegex(ValueError, 'package must be exactly'):
                check.verify_apk(Path('play.apk'), 'aapt')

    def test_refuse_wrong_web_host_and_accept_exact_staging(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / 'staging.apk'
            def write(host):
                with zipfile.ZipFile(apk, 'w') as z:
                    z.writestr('assets/capacitor.config.json', json.dumps({'server': {'url': 'https://' + host}}))
                    z.writestr('assets/web/sx-web.json', json.dumps({'host': host, 'sha': 'a' * 40}))
            with patch.object(check, 'run', return_value=subprocess.CompletedProcess([], 0, "package: name='" + check.PACKAGE + "'")):
                write('salesxray.authorityclosers.com')
                with self.assertRaisesRegex(ValueError, 'both target staging'):
                    check.verify_apk(apk, 'aapt')
                write(check.HOST)
                self.assertEqual(check.verify_apk(apk, 'aapt')['sha'], 'a' * 40)

    def test_ids_cannot_inject_shell_or_route(self):
        result = subprocess.run(['python3', str(ROOT / 'scripts/real-app-check.py'), 'USB1', '--lead', 'x;am start evil',
                                 '--report', 'qa', '--dry-run'], capture_output=True)
        self.assertEqual(result.returncode, 2)


if __name__ == '__main__':
    unittest.main()
