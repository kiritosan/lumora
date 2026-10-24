"""Offline signing regressions. Uses temporary directories, never a device or user key.

python -m unittest discover -s android/ci -p test_signing.py -v
On Windows, Git Bash and PowerShell are used when available.
"""
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

CI = Path(__file__).resolve().parent
BASH = shutil.which('bash') if os.name != 'nt' else next(
    (str(p) for p in [Path('C:/Program Files/Git/bin/bash.exe')] if p.exists()), None)
PWSH = shutil.which('pwsh')
VARIABLES = ('LUMORA_KEYSTORE_B64', 'LUMORA_STORE_PASSWORD', 'LUMORA_KEY_ALIAS',
             'LUMORA_KEY_PASSWORD', 'LUMORA_DEBUG_KEYSTORE_B64')


@unittest.skipUnless(BASH, 'Bash is required')
class SigningModeTest(unittest.TestCase):
    def run_mode(self, values, fail_after=False):
        env = os.environ.copy()
        for name in VARIABLES:
            env.pop(name, None)
        env.update(values)
        with tempfile.TemporaryDirectory(prefix='lumora-sign-test-') as folder:
            helper = Path(folder, 'prepare-signing.sh')
            shutil.copyfile(CI / 'prepare-signing.sh', helper)
            script = 'source ./prepare-signing.sh\nprintf "MODE=%s TASKS=%s\\n" "$variant" "${tasks[*]}"\n'
            # Verify the exact decoded bytes before the EXIT trap deletes the transient key.
            script += 'test "$(cat "$KEYSTORE_FILE")" = "test-keystore"\n'
            if fail_after:
                script += 'exit 37\n'
            result = subprocess.run([BASH, '-c', script], cwd=folder, env=env,
                                    capture_output=True, text=True, encoding='utf-8', timeout=30)
            self.assertFalse(Path(folder, 'android/keystore/cnb-debug.p12').exists())
            self.assertFalse(Path(folder, 'android/keystore/cnb-release.p12').exists())
            return result

    def test_no_key_fails_instead_of_generating_ephemeral_apk(self):
        result = self.run_mode({})
        self.assertNotEqual(0, result.returncode)
        self.assertIn('LUMORA_DEBUG_KEYSTORE_B64', result.stderr)

    def test_fixed_debug_key_selects_debug_tasks_and_is_cleaned(self):
        result = self.run_mode({'LUMORA_DEBUG_KEYSTORE_B64': base64.b64encode(b'test-keystore').decode()})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('MODE=debug TASKS=assembleDebug lintDebug assembleDebugAndroidTest', result.stdout)

    def test_partial_release_never_falls_back_even_with_debug_key(self):
        for missing in VARIABLES[:4]:
            with self.subTest(missing=missing):
                values = {name: 'configured' for name in VARIABLES}
                del values[missing]
                result = self.run_mode(values)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(missing, result.stderr)

    def test_complete_release_takes_priority_and_cleans_key(self):
        values = {name: 'configured' for name in VARIABLES}
        values['LUMORA_KEYSTORE_B64'] = base64.b64encode(b'test-keystore').decode()
        result = self.run_mode(values)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('MODE=release TASKS=assembleRelease lintRelease assembleDebugAndroidTest', result.stdout)

    def test_bad_base64_fails_and_removes_partial_key(self):
        result = self.run_mode({'LUMORA_DEBUG_KEYSTORE_B64': 'invalid%%%BASE64'})
        self.assertNotEqual(0, result.returncode)

    def test_empty_decoding_fails(self):
        result = self.run_mode({'LUMORA_DEBUG_KEYSTORE_B64': '\n'})
        self.assertNotEqual(0, result.returncode)

    def test_later_build_failure_also_cleans_key(self):
        result = self.run_mode({'LUMORA_DEBUG_KEYSTORE_B64': base64.b64encode(b'test-keystore').decode()}, True)
        self.assertEqual(37, result.returncode)


@unittest.skipUnless(PWSH, 'PowerShell is required')
class PrepareDebugKeyTest(unittest.TestCase):
    def prepare(self, root, *args):
        script = root / 'android/ci/prepare-debug-signing.ps1'
        script.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(CI / script.name, script)
        return subprocess.run([PWSH, '-NoProfile', '-File', str(script), *args],
                              capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)

    def test_repeat_reuses_key_and_import_preserves_certificate(self):
        with tempfile.TemporaryDirectory(prefix='lumora-key-test-') as folder:
            root = Path(folder)
            first = root / 'first'
            result = self.prepare(first)
            self.assertEqual(0, result.returncode, result.stderr)
            key = first / 'android/keystore/lumora-preview.p12'
            digest = hashlib.sha256(key.read_bytes()).digest()
            result = self.prepare(first)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(digest, hashlib.sha256(key.read_bytes()).digest())
            yaml = (first / 'android/keystore/lumora-debug-secrets.yml').read_text(encoding='utf-8')
            self.assertIn(base64.b64encode(key.read_bytes()).decode(), yaml)
            self.assertNotIn(base64.b64encode(key.read_bytes()).decode(), result.stdout)
            # Re-importing an existing preview key must preserve its certificate, not generate another key.
            imported = self.prepare(root / 'imported', '-SourceKeystore', str(key))
            self.assertEqual(0, imported.returncode, imported.stderr)
            fingerprint = next(line for line in result.stdout.splitlines() if 'SHA' in line)
            self.assertIn(fingerprint.strip(), imported.stdout)
            rejected = self.prepare(first, '-SourceKeystore', str(key))
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(digest, hashlib.sha256(key.read_bytes()).digest())


if __name__ == '__main__':
    unittest.main()
