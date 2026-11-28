#!/usr/bin/env python3
"""Uji verifikasi UID server di klien molina (CLI-PEERCHECK). Hanya stdlib.
Jalankan: python3 scripts/app-tests/test_molina_cli_peer.py"""
import importlib.util
import os
import socket
import struct
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLI = os.path.join(ROOT, "app/src/main/assets/molina/molina-cli.py")
spec = importlib.util.spec_from_file_location("molina_cli_under_test", CLI)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class FakeSock:
    def __init__(self, uid=None, error=None):
        self.uid, self.error = uid, error

    def getsockopt(self, level, opt, size):
        if self.error:
            raise self.error
        return struct.pack("3i", 4242, self.uid, self.uid)


class VerifyPeerTests(unittest.TestCase):
    def test_same_uid_passes(self):
        m.verify_peer(FakeSock(10123), "x", own=10123)

    def test_other_uid_rejected(self):
        with self.assertRaises(PermissionError) as c:
            m.verify_peer(FakeSock(10200), "molina.cmd", own=10123)
        self.assertIn("bukan milik aplikasi Molina", str(c.exception))
        self.assertIn("10200", str(c.exception))

    def test_fake_root_skips(self):
        m.verify_peer(FakeSock(10200), "x", own=0)

    def test_unreadable_own_uid_skips(self):
        old = m.own_uid
        m.own_uid = lambda: None
        try:
            m.verify_peer(FakeSock(10200), "x")
        finally:
            m.own_uid = old

    def test_no_peercred_skips(self):
        m.verify_peer(FakeSock(error=OSError("tidak didukung")), "x", own=10123)
        m.verify_peer(FakeSock(error=AttributeError("SO_PEERCRED")), "x", own=10123)

    def test_own_uid_matches_kernel(self):
        self.assertEqual(m.own_uid(), os.getuid())


class ConnectTests(unittest.TestCase):
    def setUp(self):
        self.name = "molina.peer.test" + str(os.getpid())
        self.srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.srv.bind("\0" + self.name)
        self.srv.listen(2)
        self.old = m.own_uid

    def tearDown(self):
        m.own_uid = self.old
        self.srv.close()

    def test_connect_rejects_foreign_server(self):
        m.own_uid = lambda: os.getuid() + 1
        with self.assertRaises(PermissionError):
            m.connect(self.name, 2.0)

    def test_connect_accepts_same_uid(self):
        m.own_uid = lambda: os.getuid()
        s = m.connect(self.name, 2.0)
        s.close()


if __name__ == "__main__":
    unittest.main()
