"""Run with: python3 tests/adb_check_test.py (from anywhere)."""
import os
import socket
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
import adb_check as ac  # noqa: E402


class AdbCheckTest(unittest.TestCase):
    def test_open_port(self):
        with socket.socket() as srv:
            srv.bind(('127.0.0.1', 0))
            srv.listen(1)
            self.assertEqual(ac.check('127.0.0.1', srv.getsockname()[1], 2), ac.OPEN)

    def test_refused_port_is_closed(self):
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            port = s.getsockname()[1]
        self.assertEqual(ac.check('127.0.0.1', port, 2), ac.CLOSED)

    def test_unresolvable_host_is_no_answer(self):
        self.assertEqual(ac.check('no-such-host.invalid', 5555, 2), ac.NO_ANSWER)

    def test_exit_codes(self):
        self.assertEqual(ac.main(['adb_check.py']), 2)


if __name__ == '__main__':
    unittest.main()
