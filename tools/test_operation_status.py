import unittest
from unittest.mock import patch
from types import SimpleNamespace
from operation_status import parse_status, StatusWatcher, read_device_elapsed_ms, read_device_boot_id

class StatusCaptureTest(unittest.TestCase):
    def test_waits_for_new_session_and_not_stale_terminal(self):
        old = parse_status('{"schema":1,"session_id":"old","phase":"terminal","status":"Transfer complete","summary":{"found":2,"downloaded":2,"skipped":0,"failed":0},"recorded_at_ms":1}')
        running = parse_status('{"schema":1,"session_id":"new","phase":"running","status":"Finding glasses","summary":null,"recorded_at_ms":2}')
        done = parse_status('{"schema":1,"session_id":"new","phase":"terminal","status":"Transfer complete","summary":{"found":16,"downloaded":2,"skipped":14,"failed":0},"recorded_at_ms":3}')
        watcher = StatusWatcher(old, started_ms=2)
        self.assertIsNone(watcher.consume(old))
        self.assertIsNone(watcher.consume(done))  # terminal alone does not prove a watched start
        self.assertIsNone(watcher.consume(running))
        self.assertEqual(done, watcher.consume(done))

    def test_rejects_delayed_older_session_and_wrong_terminal(self):
        baseline = parse_status('{"schema":1,"session_id":"baseline","phase":"terminal","status":"Cancelled","summary":null,"recorded_at_ms":5}')
        stale = parse_status('{"schema":1,"session_id":"older","phase":"running","status":"Finding glasses","summary":null,"recorded_at_ms":7}')
        running = parse_status('{"schema":1,"session_id":"target","phase":"running","status":"Downloading","summary":null,"recorded_at_ms":11}')
        wrong = parse_status('{"schema":1,"session_id":"older","phase":"terminal","status":"Cancelled","summary":null,"recorded_at_ms":12}')
        done = parse_status('{"schema":1,"session_id":"target","phase":"terminal","status":"Transfer complete","summary":{"found":1,"downloaded":1,"skipped":0,"failed":0},"recorded_at_ms":13}')
        watcher = StatusWatcher(baseline, started_ms=10)
        self.assertIsNone(watcher.consume(stale))
        self.assertIsNone(watcher.consume(wrong))
        self.assertIsNone(watcher.consume(running))
        self.assertIsNone(watcher.consume(wrong))
        self.assertEqual(done, watcher.consume(done))

    def test_fast_terminal_requires_durable_start_after_watcher_not_a_polled_running_state(self):
        baseline = parse_status('{"schema":1,"session_id":"baseline","phase":"terminal","status":"Cancelled","summary":null,"recorded_at_ms":5}')
        old = parse_status('{"schema":1,"session_id":"old","phase":"terminal","status":"Transfer complete","summary":{"found":1,"downloaded":1,"skipped":0,"failed":0},"started_at_ms":9,"recorded_at_ms":13}')
        fast = parse_status('{"schema":1,"session_id":"fast","phase":"terminal","status":"Needs attention","summary":null,"started_at_ms":11,"recorded_at_ms":12}')
        watcher = StatusWatcher(baseline, started_ms=10)
        self.assertIsNone(watcher.consume(old))
        self.assertEqual(fast, watcher.consume(fast))

    def test_rejects_invalid_start_order(self):
        with self.assertRaises(ValueError):
            parse_status('{"schema":1,"session_id":"bad","phase":"terminal","status":"Cancelled","summary":null,"started_at_ms":12,"recorded_at_ms":11}')

    def test_rejects_boolean_recorded_timestamp(self):
        with self.assertRaises(ValueError):
            parse_status('{"schema":1,"session_id":"bad","phase":"terminal","status":"Cancelled","summary":null,"recorded_at_ms":true}')

    def test_device_elapsed_start_survives_wall_clock_skew(self):
        stale = parse_status('{"schema":1,"session_id":"stale","phase":"terminal","status":"Cancelled","summary":null,"started_at_ms":500,"started_elapsed_ms":8,"recorded_at_ms":520}')
        fresh = parse_status('{"schema":1,"session_id":"fresh","phase":"terminal","status":"Needs attention","summary":null,"started_at_ms":501,"started_elapsed_ms":12,"recorded_at_ms":521}')
        watcher = StatusWatcher(None, started_ms=100000, started_elapsed_ms=10)
        self.assertIsNone(watcher.consume(stale))
        self.assertEqual(fresh, watcher.consume(fresh))

    def test_device_reboot_aborts_instead_of_silently_waiting_for_old_uptime(self):
        after_reboot = parse_status('{"schema":1,"session_id":"fresh","phase":"terminal","status":"Needs attention","summary":null,"started_at_ms":101,"started_elapsed_ms":5,"recorded_at_ms":102}')
        watcher = StatusWatcher(None, started_ms=100, started_elapsed_ms=1000)
        with self.assertRaisesRegex(RuntimeError, 'reboot'):
            watcher.consume(after_reboot, current_elapsed_ms=10)

    def test_reboot_after_uptime_catches_up_still_aborts(self):
        after_reboot = parse_status('{"schema":1,"session_id":"fresh","phase":"terminal","status":"Transfer complete","summary":{"found":1,"downloaded":1,"skipped":0,"failed":0},"started_at_ms":101,"started_elapsed_ms":6,"recorded_at_ms":102}')
        watcher = StatusWatcher(None, started_ms=100, started_elapsed_ms=5, boot_id='50745f19-575e-4204-820f-55c54da3172d')
        with self.assertRaisesRegex(RuntimeError, 'reboot'):
            watcher.consume(after_reboot, current_elapsed_ms=7, current_boot_id='a33ee811-a721-4c04-a64e-f02a34999bdf')

    def test_boot_id_probe_uses_explicit_serial_and_validates_uuid(self):
        boot = '50745f19-575e-4204-820f-55c54da3172d'
        with patch('operation_status.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout=boot+'\n', stderr='')) as run:
            self.assertEqual(boot, read_device_boot_id('emulator-5554'))
            self.assertEqual(['-s', 'emulator-5554'], run.call_args.args[0][1:3])
        with patch('operation_status.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout='not-a-boot-id\n', stderr='')):
            with self.assertRaisesRegex(RuntimeError, 'boot identifier'):
                read_device_boot_id('emulator-5554')

    def test_device_uptime_probe_is_explicit_and_reports_malformed_response(self):
        with patch('operation_status.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout='123.45 678.90\n', stderr='')) as run:
            self.assertEqual(123450, read_device_elapsed_ms('emulator-5554'))
            self.assertEqual(['-s', 'emulator-5554'], run.call_args.args[0][1:3])
        with patch('operation_status.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout='', stderr='')):
            with self.assertRaisesRegex(RuntimeError, 'uptime'):
                read_device_elapsed_ms('emulator-5554')
        for response in ('-1.00 20.00\n', 'NaN 20.00\n', 'Infinity 20.00\n'):
            with patch('operation_status.subprocess.run', return_value=SimpleNamespace(returncode=0, stdout=response, stderr='')):
                with self.assertRaisesRegex(RuntimeError, 'uptime'):
                    read_device_elapsed_ms('emulator-5554')

    def test_rejects_false_transfer_completion_without_full_summary(self):
        with self.assertRaises(ValueError):
            parse_status('{"schema":1,"session_id":"new","phase":"terminal","status":"Transfer complete","summary":null,"recorded_at_ms":3}')
        with self.assertRaises(ValueError):
            parse_status('{"schema":1,"session_id":"new","phase":"terminal","status":"Transfer complete","summary":{"found":16,"downloaded":2,"skipped":13,"failed":0},"recorded_at_ms":3}')

if __name__ == '__main__': unittest.main()
