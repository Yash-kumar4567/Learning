"""Offline tests for tidytv. Run from the tidytv directory:  python3 -m unittest discover -s tests -v

A fake ADB device runs in a thread on 127.0.0.1 so the real client code is exercised end to end:
handshake, RSA auth (token, signature, public key, acceptance), shell streams, and the commands
built on top. No TV, no network beyond loopback, no adb binary.
"""

import base64
import json
import os
import socket
import struct
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tidytv  # noqa: E402

HEADER = tidytv.HEADER


# ----------------------------------------------------------------------------
# Fake adbd
# ----------------------------------------------------------------------------

class FakeTv(threading.Thread):
    """Answers shell commands from a dict of exact-match responses, with optional RSA auth."""

    def __init__(self, responses, require_auth=False, known_keys=None, stls=False):
        super().__init__(daemon=True)
        self.responses = responses
        self.require_auth = require_auth
        self.known_keys = known_keys if known_keys is not None else set()
        self.stls = stls
        self.commands = []
        self.server = socket.socket()
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(5)
        self.port = self.server.getsockname()[1]
        self.token = b"T" * 20

    def recv_msg(self, conn):
        raw = b""
        while len(raw) < HEADER.size:
            chunk = conn.recv(HEADER.size - len(raw))
            if not chunk:
                return None
            raw += chunk
        command, arg0, arg1, length, _check, _magic = HEADER.unpack(raw)
        data = b""
        while len(data) < length:
            chunk = conn.recv(length - len(data))
            if not chunk:
                return None
            data += chunk
        return command, arg0, arg1, data

    def send(self, conn, command, arg0, arg1, data=b""):
        conn.sendall(tidytv.pack_message(command, arg0, arg1, data))

    def run(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        with conn:
            msg = self.recv_msg(conn)
            if not msg or msg[0] != tidytv.A_CNXN:
                return
            if self.stls:
                self.send(conn, tidytv.A_STLS, 1, 0)
                return
            if self.require_auth:
                self.send(conn, tidytv.A_AUTH, tidytv.AUTH_TOKEN, 0, self.token)
                while True:
                    msg = self.recv_msg(conn)
                    if not msg:
                        return
                    command, arg0, _arg1, data = msg
                    if command == tidytv.A_AUTH and arg0 == tidytv.AUTH_SIGNATURE:
                        if self.signature_ok(data):
                            break
                        self.send(conn, tidytv.A_AUTH, tidytv.AUTH_TOKEN, 0, self.token)
                    elif command == tidytv.A_AUTH and arg0 == tidytv.AUTH_RSAPUBLICKEY:
                        self.known_keys.add(data.rstrip(b"\x00"))
                        self.send(conn, tidytv.A_AUTH, tidytv.AUTH_TOKEN, 0, self.token)
                    else:
                        return
            self.send(conn, tidytv.A_CNXN, tidytv.ADB_VERSION, 4096, b"device::ro.product.name=fake;ro.product.model=Fake Stick;\x00")
            remote_id = 100
            while True:
                msg = self.recv_msg(conn)
                if not msg:
                    return
                command, arg0, arg1, data = msg
                if command == tidytv.A_OPEN:
                    local_id = arg0
                    remote_id += 1
                    shell_cmd = data.rstrip(b"\x00").decode()[len("shell:"):]
                    self.commands.append(shell_cmd)
                    self.send(conn, tidytv.A_OKAY, remote_id, local_id)
                    reply = self.responses.get(shell_cmd, "")
                    if callable(reply):
                        reply = reply(shell_cmd)
                    if reply:
                        payload = reply.encode()
                        for i in range(0, len(payload), 1000):
                            self.send(conn, tidytv.A_WRTE, remote_id, local_id, payload[i:i + 1000])
                            ack = self.recv_msg(conn)
                            if not ack or ack[0] != tidytv.A_OKAY:
                                return
                    self.send(conn, tidytv.A_CLSE, remote_id, local_id)
                elif command == tidytv.A_CLSE:
                    continue
                else:
                    return

    def signature_ok(self, signature):
        for key_bytes in self.known_keys:
            n, e = parse_android_key(key_bytes)
            em = pow(int.from_bytes(signature, "big"), e, n).to_bytes((n.bit_length() + 7) // 8, "big")
            if em.endswith(tidytv.SHA1_DIGEST_INFO + self.token) and em[:2] == b"\x00\x01":
                return True
        return False

    def close(self):
        self.server.close()


def parse_android_key(key_bytes):
    body = base64.b64decode(key_bytes.split(b" ")[0])
    words, _n0inv = struct.unpack("<II", body[:8])
    n = int.from_bytes(body[8:8 + words * 4], "little")
    e = struct.unpack("<I", body[8 + words * 8:12 + words * 8])[0]
    return n, e


def fast_key():
    return tidytv.generate_rsa_key(bits=512)


# ----------------------------------------------------------------------------
# Protocol and crypto
# ----------------------------------------------------------------------------

class ProtocolTests(unittest.TestCase):
    def test_pack_and_unpack_roundtrip(self):
        raw = tidytv.pack_message(tidytv.A_OPEN, 7, 0, b"shell:ls\x00")
        self.assertEqual(len(raw), HEADER.size + 9)
        command, arg0, arg1, length, check = tidytv.unpack_header(raw[:HEADER.size])
        self.assertEqual((command, arg0, arg1, length), (tidytv.A_OPEN, 7, 0, 9))
        self.assertEqual(check, sum(b"shell:ls\x00"))

    def test_bad_magic_is_rejected(self):
        raw = bytearray(tidytv.pack_message(tidytv.A_OKAY, 1, 2))
        raw[-1] ^= 0xFF
        with self.assertRaises(tidytv.TidyError):
            tidytv.unpack_header(bytes(raw))

    def test_checksum_wraps(self):
        self.assertEqual(tidytv.checksum(b"\xff" * 20000), (255 * 20000) & 0xFFFFFFFF)

    def test_parse_list_skips_comments_and_duplicates(self):
        text = "# header\ncom.a.b   # note\n\n# com.c.d\ncom.a.b\ncom.e.f extra words\n"
        self.assertEqual(tidytv.parse_list(text), ["com.a.b", "com.e.f"])


class RsaTests(unittest.TestCase):
    def test_primes(self):
        self.assertTrue(tidytv.is_probable_prime(2 ** 61 - 1))
        self.assertFalse(tidytv.is_probable_prime(2 ** 61 + 1))
        self.assertFalse(tidytv.is_probable_prime(561))  # Carmichael number
        p = tidytv.random_prime(64)
        self.assertEqual(p.bit_length(), 64)
        self.assertTrue(tidytv.is_probable_prime(p))

    def test_keygen_sign_and_verify(self):
        key = fast_key()
        self.assertEqual(key["n"].bit_length(), 512)
        self.assertEqual(pow(pow(42, key["e"], key["n"]), key["d"], key["n"]), 42)
        token = bytes(range(20))
        signature = tidytv.rsa_sign_token(key, token)
        self.assertEqual(len(signature), 64)
        em = pow(int.from_bytes(signature, "big"), key["e"], key["n"]).to_bytes(64, "big")
        self.assertEqual(em[:2], b"\x00\x01")
        self.assertTrue(em.endswith(tidytv.SHA1_DIGEST_INFO + token))
        self.assertEqual(em[2:-len(tidytv.SHA1_DIGEST_INFO) - 21], b"\xff" * (64 - 3 - 35))

    def test_android_public_key_layout(self):
        key = fast_key()
        encoded = tidytv.android_public_key(key, "unit")
        self.assertTrue(encoded.endswith(b" unit"))
        body = base64.b64decode(encoded.split(b" ")[0])
        words, n0inv = struct.unpack("<II", body[:8])
        self.assertEqual(words, 16)
        self.assertEqual(len(body), 8 + 16 * 4 * 2 + 4)
        n = int.from_bytes(body[8:8 + 64], "little")
        rr = int.from_bytes(body[8 + 64:8 + 128], "little")
        e = struct.unpack("<I", body[-4:])[0]
        self.assertEqual(n, key["n"])
        self.assertEqual(e, 65537)
        self.assertEqual(((n & 0xFFFFFFFF) * n0inv) % (1 << 32), (1 << 32) - 1)
        self.assertEqual(rr, pow(2, 2 * 512, n))

    def test_key_file_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "adbkey.json"
            key = tidytv.load_or_create_key(path, bits=512, log=lambda *_: None)
            again = tidytv.load_or_create_key(path, bits=512, log=lambda *_: None)
            self.assertEqual(key, again)
            self.assertEqual(json.loads(path.read_text())["bits"], 512)


# ----------------------------------------------------------------------------
# Client against the fake device
# ----------------------------------------------------------------------------

BASE_RESPONSES = {
    "getprop ro.product.model": "Realme 4K Stick\n",
    "getprop ro.product.brand": "realme\n",
    "getprop ro.product.device": "stick\n",
    "getprop ro.build.version.release": "11\n",
    "getprop ro.build.version.sdk": "30\n",
    "getprop ro.build.display.id": "RTT1.000\n",
    "pm list packages --user 0": "package:com.google.android.feedback\npackage:com.google.android.apps.tv.launcherx\npackage:com.google.android.tvlauncher\npackage:com.android.vending\npackage:com.realme.stickservice\npackage:com.example.mine\npackage:com.google.android.play.games\npackage:com.already.off\n",
    "pm list packages --user 0 -d": "package:com.already.off\n",
    "pm list packages --user 0 -s": "package:com.google.android.feedback\npackage:com.google.android.apps.tv.launcherx\npackage:com.google.android.tvlauncher\npackage:com.android.vending\npackage:com.realme.stickservice\npackage:com.google.android.play.games\npackage:com.already.off\n",
    "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME": "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.google.android.apps.tv.launcherx/.MainActivity\n",
    "cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME": "com.google.android.apps.tv.launcherx/.MainActivity\ncom.google.android.tvlauncher/.MainActivity\ncom.google.android.tungsten.setupwraith/.SetupWraithActivity\n",
}


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["TIDYTV_HOME"] = self.tmp.name
        self.key = fast_key()

    def tearDown(self):
        os.environ.pop("TIDYTV_HOME", None)
        self.tmp.cleanup()

    def start(self, **kwargs):
        responses = dict(BASE_RESPONSES)
        responses.update(kwargs.pop("responses", {}))
        fake = FakeTv(responses, **kwargs)
        fake.start()
        self.addCleanup(fake.close)
        return fake

    def transport(self, fake):
        return tidytv.AdbTcpTransport("127.0.0.1", fake.port, key=self.key, timeout=5, auth_timeout=5, log=lambda *_: None)

    def test_connect_without_auth_and_run_shell(self):
        fake = self.start()
        t = self.transport(fake)
        banner = t.connect()
        self.assertIn("Fake Stick", banner)
        self.assertEqual(t.shell("getprop ro.product.model"), "Realme 4K Stick\n")
        self.assertEqual(t.shell("getprop ro.product.brand"), "realme\n")
        t.close()
        self.assertEqual(fake.commands, ["getprop ro.product.model", "getprop ro.product.brand"])

    def test_large_output_is_reassembled(self):
        big = "x" * 5000 + "\n"
        fake = self.start(responses={"cat big": big})
        t = self.transport(fake)
        t.connect()
        self.assertEqual(t.shell("cat big"), big)
        t.close()

    def test_auth_with_known_key_signs_token(self):
        fake = self.start(require_auth=True, known_keys={tidytv.android_public_key(self.key)})
        t = self.transport(fake)
        t.connect()
        self.assertEqual(t.shell("getprop ro.product.brand"), "realme\n")
        t.close()

    def test_auth_with_unknown_key_offers_public_key_then_signs(self):
        fake = self.start(require_auth=True, known_keys=set())
        t = self.transport(fake)
        t.connect()
        self.assertEqual(len(fake.known_keys), 1)
        self.assertEqual(t.shell("getprop ro.product.brand"), "realme\n")
        t.close()

    def test_wireless_debugging_port_gives_pairing_advice(self):
        fake = self.start(stls=True)
        t = self.transport(fake)
        with self.assertRaises(tidytv.TidyError) as ctx:
            t.connect()
        self.assertIn("Wireless debugging", str(ctx.exception))
        self.assertIn("adb pair", str(ctx.exception))

    def test_refused_port_gives_advice(self):
        probe = socket.socket()
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
        probe.close()
        t = tidytv.AdbTcpTransport("127.0.0.1", port, key=self.key, timeout=2, log=lambda *_: None)
        with self.assertRaises(tidytv.TidyError) as ctx:
            t.connect()
        self.assertIn("refused", str(ctx.exception))


# ----------------------------------------------------------------------------
# Commands and planning
# ----------------------------------------------------------------------------

class PlanningTests(unittest.TestCase):
    states = {
        "all": ["com.a", "com.b", "com.android.vending", "com.google.android.apps.tv.launcherx", "com.off"],
        "disabled": ["com.off"],
        "enabled": ["com.a", "com.b", "com.android.vending", "com.google.android.apps.tv.launcherx"],
        "system": ["com.android.vending", "com.google.android.apps.tv.launcherx"],
    }

    def test_skips_protected_missing_and_disabled(self):
        d = tidytv.plan_apply(["com.a", "com.android.vending", "com.missing", "com.off"], self.states, "com.google.android.apps.tv.launcherx", [])
        self.assertEqual(d["disable"], ["com.a"])
        reasons = dict(d["skip"])
        self.assertIn("protected", reasons["com.android.vending"])
        self.assertIn("not installed", reasons["com.missing"])
        self.assertIn("already", reasons["com.off"])

    def test_home_owner_is_kept_without_another_launcher(self):
        home = "com.google.android.apps.tv.launcherx"
        d = tidytv.plan_apply([home, "com.a"], self.states, home, [home, "com.google.android.tungsten.setupwraith"])
        self.assertEqual(d["disable"], ["com.a"])
        self.assertIn("owns the Home screen", dict(d["skip"])[home])

    def test_setup_wizard_is_held_back_with_the_home_swap(self):
        home = "com.google.android.apps.tv.launcherx"
        states = dict(self.states)
        states["all"] = states["all"] + ["com.google.android.tungsten.setupwraith"]
        d = tidytv.plan_apply([home, "com.google.android.tungsten.setupwraith"], states, home, [home, "com.google.android.tungsten.setupwraith"])
        self.assertEqual(d["disable"], [])
        self.assertIn("same run", dict(d["skip"])["com.google.android.tungsten.setupwraith"])
        d = tidytv.plan_apply([home, "com.google.android.tungsten.setupwraith"], states, home, [home, "com.spocky.projengmenu"])
        self.assertEqual(d["disable"], [home, "com.google.android.tungsten.setupwraith"])

    def test_home_owner_can_go_when_another_launcher_exists(self):
        home = "com.google.android.apps.tv.launcherx"
        d = tidytv.plan_apply([home], self.states, home, [home, "com.spocky.projengmenu"])
        self.assertEqual(d["disable"], [home])
        self.assertEqual(d["other_launchers"], ["com.spocky.projengmenu"])

    def test_force_home_overrides(self):
        home = "com.google.android.apps.tv.launcherx"
        d = tidytv.plan_apply([home], self.states, home, [home], force_home=True)
        self.assertEqual(d["disable"], [home])

    def test_classify(self):
        self.assertEqual(tidytv.classify("com.android.vending", True)[0], "protected")
        self.assertEqual(tidytv.classify("com.google.android.feedback", True)[0], "recommended")
        self.assertEqual(tidytv.classify("com.netflix.ninja", True)[0], "optional")
        self.assertEqual(tidytv.classify("com.realme.whatever", True)[0], "vendor")
        self.assertEqual(tidytv.classify("com.android.something", True)[0], "core")
        self.assertEqual(tidytv.classify("com.google.android.other", True)[0], "google")
        self.assertEqual(tidytv.classify("org.example.app", False)[0], "yours")
        self.assertEqual(tidytv.classify("org.example.sys", True)[0], "unknown")


class CommandTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["TIDYTV_HOME"] = self.tmp.name
        self.key = fast_key()
        self.cwd = os.getcwd()
        os.chdir(self.tmp.name)

    def tearDown(self):
        os.chdir(self.cwd)
        os.environ.pop("TIDYTV_HOME", None)
        self.tmp.cleanup()

    def start(self, extra=None, **kwargs):
        responses = dict(BASE_RESPONSES)
        responses.update(extra or {})
        fake = FakeTv(responses, **kwargs)
        fake.start()
        self.addCleanup(fake.close)
        return fake

    def run_main(self, fake, *argv):
        import contextlib
        import io
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = tidytv.main(["--host", "127.0.0.1", "--port", str(fake.port), "--timeout", "5", *argv])
        return code, out.getvalue(), err.getvalue()

    def seed_key(self):
        path = Path(self.tmp.name) / "adbkey.json"
        path.write_text(json.dumps({"n": f"{self.key['n']:x}", "e": self.key["e"], "d": f"{self.key['d']:x}", "bits": 512}))

    def test_probe(self):
        self.seed_key()
        fake = self.start()
        code, out, _ = self.run_main(fake, "probe")
        self.assertEqual(code, 0, out)
        self.assertIn("realme Realme 4K Stick", out)
        self.assertIn("Home:     com.google.android.apps.tv.launcherx", out)
        self.assertIn("1 installed by you", out)

    def test_scan_writes_plan_with_right_sections(self):
        self.seed_key()
        fake = self.start()
        code, out, _ = self.run_main(fake, "scan", "-o", "plan.txt")
        self.assertEqual(code, 0, out)
        plan = Path("plan.txt").read_text()
        self.assertRegex(plan, r"(?m)^com\.google\.android\.feedback ")
        self.assertRegex(plan, r"(?m)^# com\.google\.android\.play\.games ")
        self.assertRegex(plan, r"(?m)^# com\.realme\.stickservice ")
        self.assertRegex(plan, r"(?m)^# com\.example\.mine ")
        self.assertRegex(plan, r"(?m)^# com\.already\.off ")
        self.assertNotRegex(plan, r"(?m)^com\.android\.vending")
        self.assertEqual(tidytv.parse_list(plan), ["com.google.android.feedback"])
        self.assertIn("install another launcher", out)
        self.assertIn(f"--host 127.0.0.1 --port {fake.port} apply plan.txt", out)

    def test_connection_flags_keep_adb_and_port(self):
        import argparse
        args = argparse.Namespace(host="10.0.0.5", port=39553, adb="adb")
        self.assertEqual(tidytv.connection_flags(args), "--adb adb --host 10.0.0.5 --port 39553")
        args = argparse.Namespace(host="10.0.0.5", port=5555, adb=None)
        self.assertEqual(tidytv.connection_flags(args), "--host 10.0.0.5")

    def test_apply_dry_run_changes_nothing(self):
        self.seed_key()
        fake = self.start()
        Path("list.txt").write_text("com.google.android.feedback\ncom.android.vending\ncom.not.installed\ncom.already.off\n")
        code, out, _ = self.run_main(fake, "apply", "list.txt", "--dry-run")
        self.assertEqual(code, 0, out)
        self.assertIn("Dry run", out)
        self.assertIn("skip     com.android.vending: protected", out)
        self.assertIn("skip     com.not.installed: not installed", out)
        self.assertIn("skip     com.already.off: already disabled", out)
        self.assertIn("1 package(s) will be disabled", out)
        self.assertFalse(any(c.startswith("pm disable") for c in fake.commands))
        self.assertEqual(list(tidytv.backups_dir().glob("*.json")), [])

    def test_apply_backs_up_disables_and_restore_undoes(self):
        self.seed_key()
        disabled = []

        def disable(cmd):
            disabled.append(cmd.split()[-1])
            return f"Package {cmd.split()[-1]} new state: disabled-user\n"

        def list_disabled(_cmd):
            return "".join(f"package:{p}\n" for p in ["com.already.off"] + disabled)

        extra = {
            "pm disable-user --user 0 com.google.android.feedback": disable,
            "pm disable-user --user 0 com.google.android.play.games": disable,
            "pm list packages --user 0 -d": list_disabled,
            "pm enable --user 0 com.google.android.feedback": "Package com.google.android.feedback new state: enabled\n",
            "pm enable --user 0 com.google.android.play.games": "Package com.google.android.play.games new state: enabled\n",
        }
        fake = self.start(extra)
        Path("list.txt").write_text("com.google.android.feedback\ncom.google.android.play.games\n")
        code, out, _ = self.run_main(fake, "apply", "list.txt", "--yes")
        self.assertEqual(code, 0, out)
        self.assertEqual(disabled, ["com.google.android.feedback", "com.google.android.play.games"])
        backups = list(tidytv.backups_dir().glob("*.json"))
        self.assertEqual(len(backups), 1)
        backup = json.loads(backups[0].read_text())
        self.assertEqual(backup["applied"], ["com.google.android.feedback", "com.google.android.play.games"])
        self.assertIn("com.google.android.feedback", backup["enabled"])
        self.assertEqual(backup["home"], "com.google.android.apps.tv.launcherx")
        self.assertIn("2 disabled, 0 still enabled", out)

        code, out, _ = self.run_main(fake, "check")
        self.assertEqual(code, 0, out)
        self.assertIn("still disabled", out)

        code, out, _ = self.run_main(fake, "restore")
        self.assertEqual(code, 0, out)
        enables = [c for c in fake.commands if c.startswith("pm enable")]
        self.assertEqual(sorted(enables), sorted(["pm enable --user 0 com.google.android.feedback", "pm enable --user 0 com.google.android.play.games"]))
        self.assertFalse(any("pm enable --user 0 com.already.off" == c for c in fake.commands), "packages disabled before the backup stay disabled")

    def test_apply_refuses_home_without_other_launcher(self):
        self.seed_key()
        extra = {
            "cmd package query-activities --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME": "com.google.android.apps.tv.launcherx/.MainActivity\ncom.google.android.tungsten.setupwraith/.SetupWraithActivity\n",
        }
        fake = self.start(extra)
        Path("list.txt").write_text("com.google.android.apps.tv.launcherx\n")
        code, out, _ = self.run_main(fake, "apply", "list.txt", "--yes")
        self.assertEqual(code, 0, out)
        self.assertIn("owns the Home screen", out)
        self.assertIn("Nothing to disable", out)
        self.assertFalse(any(c.startswith("pm disable") for c in fake.commands))

    def test_apply_hands_home_over_when_setup_wizard_grabs_it(self):
        self.seed_key()
        home_now = {"owner": "com.google.android.apps.tv.launcherx"}

        def resolve(_cmd):
            return f"{home_now['owner']}/.Main\n"

        def disable(cmd):
            home_now["owner"] = "com.google.android.tungsten.setupwraith"
            return "Package x new state: disabled-user\n"

        def set_home(_cmd):
            home_now["owner"] = "com.google.android.tvlauncher"
            return ""

        extra = {
            "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME": resolve,
            "pm disable-user --user 0 com.google.android.apps.tv.launcherx": disable,
            "pm list packages --user 0 -d": lambda _c: "package:com.already.off\npackage:com.google.android.apps.tv.launcherx\n" if home_now["owner"] != "com.google.android.apps.tv.launcherx" else "package:com.already.off\n",
            "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME com.google.android.tvlauncher": "com.google.android.tvlauncher/.MainActivity\n",
            "cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity": set_home,
        }
        fake = self.start(extra)
        Path("list.txt").write_text("com.google.android.apps.tv.launcherx\n")
        code, out, _ = self.run_main(fake, "apply", "list.txt", "--yes")
        self.assertEqual(code, 0, out)
        self.assertIn("Home handed to com.google.android.tvlauncher", out)
        self.assertIn("Home is now com.google.android.tvlauncher", out)

    def test_shell_passthrough(self):
        self.seed_key()
        fake = self.start({"echo hi there": "hi there\n"})
        code, out, _ = self.run_main(fake, "shell", "echo", "hi", "there")
        self.assertEqual(code, 0)
        self.assertEqual(out.strip().splitlines()[-1], "hi there")

    def test_missing_host_is_an_error(self):
        import contextlib
        import io
        err = io.StringIO()
        os.environ.pop("TIDYTV_HOST", None)
        with contextlib.redirect_stderr(err):
            code = tidytv.main(["probe"])
        self.assertEqual(code, 2)
        self.assertIn("--host", err.getvalue())


if __name__ == "__main__":
    unittest.main()
