import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/r25-transfer-candidate.sh"
INCOMING = "/root/autodl-tmp/auralink-core-deploy/incoming/" + "a" * 40 + "-123-1"
FILES = ("auralink-backend-0.0.1-SNAPSHOT.jar", "r25-artifact-verification.json", "core-release-candidate.json")


class CandidateTransferTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.candidate = self.root / "candidate"
        self.remote = self.root / "incoming"
        self.commands = self.root / "commands"
        for folder in (self.candidate, self.remote, self.commands):
            folder.mkdir()
        for index, name in enumerate(FILES):
            (self.candidate / name).write_bytes(bytes([index]) * 30)
        self.counter = self.root / "scp-called"
        self.command("ssh", '''#!/usr/bin/env bash
set -euo pipefail
remote_command="${!#}"
remote_command="${remote_command//"$EXPECTED_INCOMING"/"$REMOTE_FIXTURE"}"
exec bash -c "$remote_command"
''')
        self.command("scp", '''#!/usr/bin/env bash
set -euo pipefail
printf 'copy\n' >> "$COPY_LOG"
for path in candidate/*; do
  cp -p "$path" "$REMOTE_FIXTURE/"
done
if [ "$UPLOAD_OUTCOME" = incomplete ]; then
  printf partial > "$REMOTE_FIXTURE/auralink-backend-0.0.1-SNAPSHOT.jar"
fi
exit 124
''')

    def command(self, name, source):
        path = self.commands / name
        path.write_text(source)
        path.chmod(0o700)

    def transfer(self, outcome="complete"):
        return subprocess.run(
            ["bash", str(SCRIPT), INCOMING, "root@fixture", "22"], cwd=self.root, text=True,
            capture_output=True, timeout=10,
            env={**os.environ, "PATH": f"{self.commands}:{os.environ['PATH']}",
                 "EXPECTED_INCOMING": INCOMING, "REMOTE_FIXTURE": str(self.remote),
                 "COPY_LOG": str(self.counter), "UPLOAD_OUTCOME": outcome},
        )

    def test_exact_incoming_candidate_is_reused_without_scp(self):
        for name in FILES:
            shutil.copyfile(self.candidate / name, self.remote / name)
            (self.remote / name).chmod(0o600)
        result = self.transfer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("CANDIDATE_TRANSFER_REUSED", result.stdout)
        self.assertFalse(self.counter.exists())

    def test_complete_upload_is_usable_when_scp_loses_its_exit_receipt(self):
        result = self.transfer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("CANDIDATE_TRANSFER_VERIFIED_AFTER_SCP_EXIT", result.stdout)
        self.assertEqual(self.counter.read_text(), "copy\n")

    def test_incomplete_upload_still_fails_verification(self):
        result = self.transfer("incomplete")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CANDIDATE_TRANSFER_UNVERIFIED", result.stderr)


if __name__ == "__main__":
    unittest.main()
