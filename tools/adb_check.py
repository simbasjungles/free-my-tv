#!/usr/bin/env python3
"""Checks from this computer whether a TV's network adb port (5555) answers. It only tries to connect: nothing is sent
to the TV and nothing is changed.

    python3 tools/adb_check.py 192.168.1.50

Exit code: 0 = refused (closed), 1 = open, 2 = no answer (TV off, wrong address, or a firewall dropping it).

Free My TV needs adb on the TV itself while it runs: the app talks to 127.0.0.1:5555 (README, "How it works"), and the
watchdog and Original use it. So the port is normally open while USB debugging is on (some TVs keep it closed until
`adb tcpip 5555` has been run: see the README). This tool tells you what the rest of
your network sees. It cannot tell whether this TV's firmware asks to allow each computer (the *Allow USB debugging?*
prompt). Turning USB debugging off closes the port but also stops Free My TV's own switches until it is on again.
"""
import socket
import sys

OPEN, CLOSED, NO_ANSWER = 'open', 'closed', 'no answer'


def check(host, port=5555, timeout=3.0):
    """'open' when the connection is accepted, 'closed' when it is refused, else 'no answer'."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return OPEN
    except ConnectionRefusedError:
        return CLOSED
    except OSError:
        return NO_ANSWER


def split_host(arg):
    """'192.168.1.50' or '192.168.1.50:5555' -> (host, port)."""
    host, sep, port = arg.rpartition(':')
    if sep and port.isdigit() and ':' not in host:
        return host, int(port)
    return arg, 5555


def main(argv):
    if len(argv) != 2 or argv[1] in ('-h', '--help'):
        print(__doc__)
        return 2
    host, port = split_host(argv[1])
    result = check(host, port)
    print('%s:%d is %s.' % (host, port, result))
    return {OPEN: 1, CLOSED: 0, NO_ANSWER: 2}[result]


if __name__ == '__main__':
    sys.exit(main(sys.argv))
