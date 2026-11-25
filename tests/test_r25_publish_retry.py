import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "r25-publish-retry.py"
BUSY = '{"outcome":"REFUSED","failure_class":"Refused","reason":"shared_publication_busy"}'
COMMAND = ["ssh", "production", "publish"]


def load_runner():
    spec = importlib.util.spec_from_file_location("r25_publish_retry", SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner


class ReleaseLeaseTest(unittest.TestCase):
    def test_reuses_the_exact_command_after_the_shared_lock_is_released(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "shared.lock"
            lock.touch()
            # A different file description must conflict even in this process.
            import fcntl

            with lock.open("r+") as holder:
                fcntl.flock(holder, fcntl.LOCK_EX)
                command = [
                    sys.executable, "-c",
                    "import fcntl,sys\n"
                    "with open(sys.argv[1]) as lock:\n"
                    "    try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)\n"
                    "    except BlockingIOError: print(sys.argv[2], file=sys.stderr); sys.exit(65)\n"
                    "    print('candidate-published')\n",
                    str(lock), BUSY,
                ]
                check = Mock()
                called = []

                def invoke(argv):
                    called.append(argv)
                    return subprocess.run(argv, capture_output=True, text=True, check=False)

                result = load_runner().run_with_lease_retry(
                    command, 10, 1, check_release=check, invoke=invoke,
                    sleep=lambda _: fcntl.flock(holder, fcntl.LOCK_UN),
                    note=Mock(),
                )
                self.assertEqual((result.returncode, result.stdout.strip()), (0, "candidate-published"))
                self.assertEqual(called, [command, command])
                self.assertEqual(check.call_count, 2)

    def test_other_failures_and_lost_ssh_results_do_not_retry(self):
        for code, error in ((65, '{"outcome":"REFUSED","reason":"candidate_mismatch"}'),
                            (70, '{"outcome":"PUBLICATION_FAILED","restored":true}'),
                            (124, ""), (255, BUSY), (65, BUSY + '\n' + BUSY)):
            with self.subTest(code=code, error=error):
                result = subprocess.CompletedProcess(COMMAND, code, "", error)
                invoke = Mock(return_value=result)
                sleep = Mock()
                self.assertIs(load_runner().run_with_lease_retry(
                    COMMAND, 5, 1, check_release=Mock(), invoke=invoke,
                    sleep=sleep, note=Mock(),
                ), result)
                invoke.assert_called_once_with(COMMAND)
                sleep.assert_not_called()

    def test_rechecks_release_before_retrying_and_stops_when_it_has_advanced(self):
        check = Mock(side_effect=[None, ValueError("release_advanced")])
        invoke = Mock(return_value=subprocess.CompletedProcess(COMMAND, 65, "", BUSY))
        with self.assertRaisesRegex(ValueError, "release_advanced"):
            load_runner().run_with_lease_retry(
                COMMAND, 5, 1, check_release=check, invoke=invoke,
                sleep=Mock(), note=Mock(),
            )
        invoke.assert_called_once_with(COMMAND)

    def test_wait_budget_stops_retries(self):
        runner = load_runner()
        failure = subprocess.CompletedProcess(COMMAND, 65, "", BUSY)
        invoke = Mock(return_value=failure)
        sleep = Mock()
        self.assertIs(runner.run_with_lease_retry(
            COMMAND, 5, 2, check_release=Mock(), invoke=invoke, sleep=sleep,
            clock=Mock(side_effect=[0, 2, 2, 4, 4, 5]), note=Mock(),
        ), failure)
        self.assertEqual(invoke.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [2, 2, 1])

    def test_receipt_must_commit_the_candidate_artifact(self):
        runner = load_runner()
        commit = "a" * 40
        digest = "b" * 64
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "candidate.json"
            manifest.write_text(json.dumps({"commit_sha": commit, "artifact": {"sha256": digest}}))
            release = {"commit_sha": commit, "artifact_sha256": digest}
            receipt = {"outcome": "COMMITTED", "transaction_id": "c" * 32, "release": release}
            runner.verify_receipt(receipt, commit, manifest)
            for update in ({"outcome": "RESTORED"},
                           {"transaction_id": None},
                           {"release": {**release, "artifact_sha256": "d" * 64}}):
                with self.subTest(update=update), self.assertRaisesRegex(ValueError, "RECEIPT_MISMATCH"):
                    runner.verify_receipt({**receipt, **update}, commit, manifest)


if __name__ == "__main__":
    unittest.main()
