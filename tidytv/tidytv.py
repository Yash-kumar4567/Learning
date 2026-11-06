#!/usr/bin/env python3
"""tidytv: clean up an Android TV / Google TV stick over Wi-Fi, from anything that runs Python.

Single file, standard library only. It speaks the ADB network protocol itself, so it runs on an
iPad (a-Shell or iSH), Windows, macOS or Linux without installing Android tools. Nothing is
uninstalled: packages are only disabled for the main user with `pm disable-user`, and a backup is
written before anything changes so `restore` can put it all back.

  python3 tidytv.py --host 192.168.1.50 probe
  python3 tidytv.py --host 192.168.1.50 scan            # writes plan.txt for you to edit
  python3 tidytv.py --host 192.168.1.50 apply plan.txt  # backs up, disables, verifies
  python3 tidytv.py --host 192.168.1.50 restore         # undo the last apply
  python3 tidytv.py --host 192.168.1.50 close           # switch ADB off on the TV

If your stick only offers "Wireless debugging" with a pairing code (Android 11+), pair once with
a real adb (`adb pair`, `adb connect`), then either run `adb tcpip 5555` or pass `--adb /path/to/adb`
so tidytv talks through that paired connection. See README.md.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import socket
import struct
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

__version__ = "0.1.0"

# ----------------------------------------------------------------------------
# ADB wire protocol
# ----------------------------------------------------------------------------

A_SYNC = 0x434E5953
A_CNXN = 0x4E584E43
A_AUTH = 0x48545541
A_OPEN = 0x4E45504F
A_OKAY = 0x59414B4F
A_CLSE = 0x45534C43
A_WRTE = 0x45545257
A_STLS = 0x534C5453

AUTH_TOKEN = 1
AUTH_SIGNATURE = 2
AUTH_RSAPUBLICKEY = 3

ADB_VERSION = 0x01000000
MAX_PAYLOAD = 256 * 1024
HEADER = struct.Struct("<6I")
COMMAND_NAMES = {A_SYNC: "SYNC", A_CNXN: "CNXN", A_AUTH: "AUTH", A_OPEN: "OPEN", A_OKAY: "OKAY", A_CLSE: "CLSE", A_WRTE: "WRTE", A_STLS: "STLS"}


class TidyError(Exception):
    """A problem worth showing the user as a sentence, without a traceback."""


def checksum(data: bytes) -> int:
    return sum(data) & 0xFFFFFFFF


def pack_message(command: int, arg0: int, arg1: int, data: bytes = b"") -> bytes:
    return HEADER.pack(command, arg0, arg1, len(data), checksum(data), command ^ 0xFFFFFFFF) + data


def unpack_header(raw: bytes):
    command, arg0, arg1, length, check, magic = HEADER.unpack(raw)
    if magic != (command ^ 0xFFFFFFFF):
        raise TidyError("The device sent a corrupt ADB header. Is this really an ADB port?")
    return command, arg0, arg1, length, check


# ----------------------------------------------------------------------------
# RSA key for ADB authentication (pure Python)
# ----------------------------------------------------------------------------

SHA1_DIGEST_INFO = bytes.fromhex("3021300906052b0e03021a05000414")
SMALL_PRIMES = [p for p in range(3, 2000, 2) if all(p % q for q in range(3, int(p ** 0.5) + 1, 2))]


def is_probable_prime(n: int, rounds: int = 32) -> bool:
    if n < 2:
        return False
    for p in SMALL_PRIMES:
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(rounds):
        a = 2 + secrets.randbelow(n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def random_prime(bits: int) -> int:
    while True:
        candidate = secrets.randbits(bits) | (1 << (bits - 1)) | 1
        if is_probable_prime(candidate):
            return candidate


def generate_rsa_key(bits: int = 2048, exponent: int = 65537) -> dict:
    """Return {'n', 'e', 'd', 'bits'} as ints. 2048 bits takes a few seconds in pure Python."""
    while True:
        p = random_prime(bits // 2)
        q = random_prime(bits // 2)
        if p == q:
            continue
        n = p * q
        if n.bit_length() != bits:
            continue
        phi = (p - 1) * (q - 1)
        try:
            d = pow(exponent, -1, phi)
        except ValueError:
            continue
        return {"n": n, "e": exponent, "d": d, "bits": bits}


def rsa_sign_token(key: dict, token: bytes) -> bytes:
    """PKCS#1 v1.5 signature of an ADB auth token. adbd treats the 20-byte token as a SHA-1 digest."""
    k = key["bits"] // 8
    digest_info = SHA1_DIGEST_INFO + token
    padding_length = k - len(digest_info) - 3
    if padding_length < 8:
        raise TidyError("RSA key too small for the ADB token.")
    em = b"\x00\x01" + b"\xff" * padding_length + b"\x00" + digest_info
    signature = pow(int.from_bytes(em, "big"), key["d"], key["n"])
    return signature.to_bytes(k, "big")


def android_public_key(key: dict, name: str = "tidytv") -> bytes:
    """Encode the public key the way adbd expects: base64 of Android's RSAPublicKey struct, a space and a name."""
    words = key["bits"] // 32
    n = key["n"]
    r = 1 << (32 * words)
    n0inv = (-pow(n & 0xFFFFFFFF, -1, 1 << 32)) & 0xFFFFFFFF
    rr = (r * r) % n
    body = struct.pack("<II", words, n0inv)
    body += n.to_bytes(words * 4, "little")
    body += rr.to_bytes(words * 4, "little")
    body += struct.pack("<I", key["e"])
    return base64.b64encode(body) + b" " + name.encode("ascii")


def key_file_path() -> Path:
    override = os.environ.get("TIDYTV_HOME")
    base = Path(override) if override else Path.home() / ".tidytv"
    return base / "adbkey.json"


def load_or_create_key(path: Path | None = None, bits: int = 2048, log=print) -> dict:
    path = path or key_file_path()
    if path.exists():
        data = json.loads(path.read_text("utf-8"))
        return {"n": int(data["n"], 16), "e": int(data["e"]), "d": int(data["d"], 16), "bits": int(data["bits"])}
    log(f"Creating a {bits}-bit ADB key at {path} (first run only, this can take a little while)...")
    key = generate_rsa_key(bits)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"n": f"{key['n']:x}", "e": key["e"], "d": f"{key['d']:x}", "bits": key["bits"]}), "utf-8")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    return key


# ----------------------------------------------------------------------------
# Transports: our own TCP client, or a real adb binary for paired connections
# ----------------------------------------------------------------------------

class AdbTcpTransport:
    """Minimal ADB client over TCP: connect, authenticate, run shell commands."""

    def __init__(self, host: str, port: int = 5555, key: dict | None = None, timeout: float = 10.0, auth_timeout: float = 120.0, log=print):
        self.host = host
        self.port = port
        self.key = key
        self.timeout = timeout
        self.auth_timeout = auth_timeout
        self.log = log
        self.sock: socket.socket | None = None
        self.device_banner = ""
        self.max_payload = MAX_PAYLOAD
        self._next_local_id = 1

    # --- low level ---------------------------------------------------------

    def _send(self, command: int, arg0: int, arg1: int, data: bytes = b"") -> None:
        assert self.sock is not None
        self.sock.sendall(pack_message(command, arg0, arg1, data))

    def _recv_exact(self, length: int) -> bytes:
        assert self.sock is not None
        chunks = []
        remaining = length
        while remaining:
            chunk = self.sock.recv(remaining)
            if not chunk:
                raise TidyError("The TV closed the ADB connection.")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def _recv(self):
        command, arg0, arg1, length, check = unpack_header(self._recv_exact(HEADER.size))
        data = self._recv_exact(length) if length else b""
        if length and check != checksum(data) and check != 0:
            raise TidyError("The TV sent a message with a bad checksum.")
        return command, arg0, arg1, data

    # --- connection --------------------------------------------------------

    def connect(self) -> str:
        try:
            self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except ConnectionRefusedError:
            raise TidyError(
                f"{self.host}:{self.port} refused the connection: nothing listens for ADB there.\n"
                "On the TV, check Developer options: USB debugging on, and Network/ADB debugging on if there is such a toggle.\n"
                "If the TV only offers 'Wireless debugging' with a pairing code, see README.md: pair with a real adb first."
            )
        except socket.timeout:
            raise TidyError(f"Could not reach {self.host}:{self.port} (timed out). Is the TV on, awake, and on the same Wi-Fi?")
        except OSError as err:
            raise TidyError(f"Could not reach {self.host}:{self.port}: {err}")

        self.sock.settimeout(self.timeout)
        self._send(A_CNXN, ADB_VERSION, MAX_PAYLOAD, b"host::tidytv\x00")
        tokens_seen = 0
        deadline = time.monotonic() + self.auth_timeout
        while True:
            try:
                command, arg0, arg1, data = self._recv()
            except socket.timeout:
                if time.monotonic() > deadline:
                    raise TidyError("Timed out waiting for the TV to accept the connection. Look for an 'Allow USB debugging?' prompt on the screen.")
                continue
            if command == A_CNXN:
                self.device_banner = data.rstrip(b"\x00").decode("utf-8", "replace")
                self.max_payload = max(4096, min(arg1, MAX_PAYLOAD))
                return self.device_banner
            if command == A_STLS:
                raise TidyError(
                    f"{self.host}:{self.port} is a 'Wireless debugging' port: it asks for TLS pairing, which this client cannot do.\n"
                    "Pair once with a real adb (adb pair, then adb connect), then either run 'adb tcpip 5555' and retry,\n"
                    "or pass --adb /path/to/adb --port <connection port> so tidytv talks through that paired connection."
                )
            if command == A_AUTH and arg0 == AUTH_TOKEN:
                if self.key is None:
                    raise TidyError("The TV asks for authentication but no key is loaded.")
                tokens_seen += 1
                if tokens_seen == 2:
                    # Our signature was refused: the TV does not know this key yet. Offer it and wait for the person.
                    self.log("The TV does not know this computer yet. On the TV, accept 'Allow USB debugging?' (tick 'Always allow').")
                    self._send(A_AUTH, AUTH_RSAPUBLICKEY, 0, android_public_key(self.key) + b"\x00")
                    self.sock.settimeout(self.auth_timeout)
                else:
                    # First token, or a fresh token after the key was accepted: sign it.
                    self._send(A_AUTH, AUTH_SIGNATURE, 0, rsa_sign_token(self.key, data))
                continue
            raise TidyError(f"Unexpected ADB message during connect: {COMMAND_NAMES.get(command, hex(command))}")

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            finally:
                self.sock = None

    # --- shell -------------------------------------------------------------

    def shell(self, command: str, timeout: float | None = None) -> str:
        if self.sock is None:
            self.connect()
        assert self.sock is not None
        self.sock.settimeout(timeout or max(self.timeout, 30.0))
        local_id = self._next_local_id
        self._next_local_id += 1
        self._send(A_OPEN, local_id, 0, b"shell:" + command.encode("utf-8") + b"\x00")
        remote_id = None
        output = bytearray()
        while True:
            cmd, arg0, arg1, data = self._recv()
            if cmd == A_OKAY:
                remote_id = arg0
            elif cmd == A_WRTE:
                output += data
                self._send(A_OKAY, local_id, arg0)
            elif cmd == A_CLSE:
                if remote_id is not None:
                    self._send(A_CLSE, local_id, remote_id)
                break
            elif cmd == A_AUTH:
                raise TidyError("The TV dropped the authorisation. Reconnect and accept the prompt again.")
            else:
                raise TidyError(f"Unexpected ADB message during shell: {COMMAND_NAMES.get(cmd, hex(cmd))}")
        return output.decode("utf-8", "replace")


class AdbBinaryTransport:
    """Run commands through a real adb that is already connected (for paired Wireless debugging)."""

    def __init__(self, binary: str, host: str, port: int, timeout: float = 60.0, log=print):
        self.binary = binary
        self.serial = f"{host}:{port}"
        self.timeout = timeout
        self.log = log
        self.device_banner = f"via {binary}"

    def connect(self) -> str:
        result = subprocess.run([self.binary, "connect", self.serial], capture_output=True, text=True, timeout=self.timeout)
        text = (result.stdout + result.stderr).strip()
        if "connected" not in text and "already connected" not in text:
            raise TidyError(f"'{self.binary} connect {self.serial}' answered: {text or 'nothing'}")
        return text

    def close(self) -> None:
        return None

    def shell(self, command: str, timeout: float | None = None) -> str:
        result = subprocess.run([self.binary, "-s", self.serial, "shell", command], capture_output=True, text=True, timeout=timeout or self.timeout)
        if result.returncode != 0 and "error:" in result.stderr:
            raise TidyError(f"adb shell failed: {result.stderr.strip()}")
        return result.stdout


# ----------------------------------------------------------------------------
# Package knowledge for Google TV / Android TV sticks
# ----------------------------------------------------------------------------

# Disabled by the large majority of community Google TV lists; safe on a streaming stick.
RECOMMENDED = {
    "com.google.android.feedback": "Google feedback and crash reporting",
    "com.android.tv.feedbackconsent": "feedback consent prompts",
    "com.google.android.partnersetup": "partner setup: pushes OEM/partner customisations",
    "android.autoinstalls.config.google.gtvpai": "auto-installs sponsored apps after setup",
    "com.android.adservices.api": "Privacy Sandbox ad services",
    "com.android.federatedcompute.services": "federated compute for on-device personalisation",
    "com.android.ondevicepersonalization.services": "on-device personalisation (ads)",
    "com.google.android.apps.tv.dreamx": "Google TV ambient screensaver with promo content (a plain screensaver remains)",
    "com.google.android.onetimeinitializer": "one-time initialiser, nothing after first boot",
    "com.android.printspooler": "print spooler, useless on a TV",
    "com.google.android.syncadapters.calendar": "calendar sync adapter",
    "com.google.android.tv.bugreportsender": "bug report uploader",
}

# Preinstalled apps and services people may or may not use. Listed commented out in the plan.
OPTIONAL = {
    "com.google.android.play.games": "Play Games",
    "com.google.android.videos": "Google TV Movies & TV (keep if you buy or rent films there)",
    "com.google.android.youtube.tvmusic": "YouTube Music",
    "com.google.android.katniss": "Google Assistant voice search (keep if you use the remote's mic)",
    "com.google.android.apps.mediashell": "Chromecast built-in (keep if you cast from your phone)",
    "com.google.android.marvin.talkback": "TalkBack screen reader (keep if anyone needs it)",
    "com.netflix.ninja": "Netflix",
    "com.amazon.amazonvideo.livingroom": "Prime Video",
    "com.google.android.tungsten.setupwraith": "setup wizard; stops 'finish setting up' nags, but run setup fully first",
    "com.google.android.apps.tv.launcherx": "stock Google TV home with sponsored rows: ONLY after installing another launcher (Projectivy, FLauncher...)",
}

# Never disabled by this tool, whatever a list says.
PROTECTED = {
    "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings", "com.android.shell",
    "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.google.android.ext.services",
    "com.google.android.ext.shared", "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
    "com.android.bluetooth", "com.android.providers.settings", "com.google.android.tv.remote.service",
    "com.google.android.inputmethod.latin", "com.google.android.tv.frameworkpackagestubs", "com.android.vpndialogs",
    "com.google.android.webview", "com.android.webview", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    "com.android.tv.settings.intent", "com.google.android.tts",
}

# Packages that answer HOME but are not launchers.
NOT_A_LAUNCHER = {"com.google.android.tungsten.setupwraith", "com.android.tv.settings", "com.android.settings", "com.google.android.tv.frameworkpackagestubs"}

VENDOR_PREFIXES = ("com.realme.", "com.oppo.", "com.oplus.", "com.skyworth.", "com.amlogic.", "com.droidlogic.", "com.mediatek.", "com.tcl.", "com.hisense.", "com.xiaomi.", "com.mitv.", "com.sony.", "com.philips.", "com.tpv.", "com.vestel.")
USER_KEY = "--user 0"


def classify(package: str, system: bool) -> tuple[str, str]:
    """Return (category, note) for a package name."""
    if package in PROTECTED:
        return "protected", "core system, never touched"
    if package in RECOMMENDED:
        return "recommended", RECOMMENDED[package]
    if package in OPTIONAL:
        return "optional", OPTIONAL[package]
    if not system:
        return "yours", "installed by you; uninstall it from Settings if you do not want it"
    if package.startswith(VENDOR_PREFIXES):
        return "vendor", "vendor package; could be hardware support or could be bloat, look it up before disabling"
    if package.startswith(("com.android.", "android.")):
        return "core", "AOSP component; leave it"
    if package.startswith("com.google.android."):
        return "google", "Google component not in the lists; look it up before disabling"
    return "unknown", "not in the lists; look it up before disabling"


# ----------------------------------------------------------------------------
# List files (same format as cleanyourtv: one package per line, # comments)
# ----------------------------------------------------------------------------

def parse_list(text: str) -> list[str]:
    packages = []
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        package = line.split()[0]
        if package not in packages:
            packages.append(package)
    return packages


def write_plan(path: Path, device: dict, groups: dict[str, list[tuple[str, str]]]) -> None:
    lines = [
        f"# tidytv plan for {device.get('model', 'unknown model')} ({device.get('brand', '?')}), Android {device.get('android', '?')}, written {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
        "# One package per line. Lines without '#' will be DISABLED (never uninstalled). Add '#' in front to keep one.",
        "# Apply with:  python3 tidytv.py --host <tv ip> apply plan.txt     Undo with:  python3 tidytv.py --host <tv ip> restore",
        "",
        "# --- Recommended: ads, telemetry and feeders. Disabled unless you comment them out.",
    ]
    lines += [f"{p:<52} # {note}" for p, note in groups.get("recommended", [])]
    lines += ["", "# --- Optional: preinstalled apps. Remove the leading '# ' to disable one."]
    lines += [f"# {p:<50} # {note}" for p, note in groups.get("optional", [])]
    lines += ["", "# --- Vendor and other system packages found on this TV. Kept. Look each up before enabling a line."]
    lines += [f"# {p:<50} # {note}" for p, note in groups.get("vendor", []) + groups.get("google", []) + groups.get("unknown", [])]
    lines += ["", "# --- Apps you installed yourself (listed for information; uninstall from Settings instead)."]
    lines += [f"# {p:<50} # {note}" for p, note in groups.get("yours", [])]
    lines += ["", "# --- Already disabled on this TV when the plan was written."]
    lines += [f"# {p:<50} # already disabled" for p, _ in groups.get("disabled", [])]
    path.write_text("\n".join(lines) + "\n", "utf-8")


# ----------------------------------------------------------------------------
# TV operations
# ----------------------------------------------------------------------------

class Tv:
    def __init__(self, transport, log=print):
        self.t = transport
        self.log = log

    def sh(self, command: str, timeout: float | None = None) -> str:
        return self.t.shell(command, timeout=timeout)

    def prop(self, name: str) -> str:
        return self.sh(f"getprop {name}").strip()

    def probe(self) -> dict:
        return {
            "model": self.prop("ro.product.model"),
            "brand": self.prop("ro.product.brand"),
            "device": self.prop("ro.product.device"),
            "android": self.prop("ro.build.version.release"),
            "sdk": self.prop("ro.build.version.sdk"),
            "build": self.prop("ro.build.display.id"),
            "home": self.home_owner(),
        }

    def packages(self, flag: str = "") -> list[str]:
        out = self.sh(f"pm list packages {USER_KEY} {flag}".strip())
        return sorted(line.split(":", 1)[1].strip() for line in out.splitlines() if line.startswith("package:"))

    def package_states(self) -> dict:
        all_packages = set(self.packages())
        disabled = set(self.packages("-d"))
        system = set(self.packages("-s"))
        return {"all": sorted(all_packages), "disabled": sorted(disabled), "enabled": sorted(all_packages - disabled), "system": sorted(system)}

    def home_owner(self) -> str:
        out = self.sh(f"cmd package resolve-activity --brief {USER_KEY} -a android.intent.action.MAIN -c android.intent.category.HOME")
        lines = [l.strip() for l in out.splitlines() if "/" in l]
        return lines[-1].split("/")[0] if lines else ""

    def home_candidates(self) -> list[str]:
        out = self.sh(f"cmd package query-activities --brief {USER_KEY} -a android.intent.action.MAIN -c android.intent.category.HOME")
        found = []
        for line in out.splitlines():
            line = line.strip()
            if "/" in line and not line.startswith("No "):
                package = line.split("/")[0]
                if package not in found:
                    found.append(package)
        return found

    def home_activity(self, package: str) -> str:
        out = self.sh(f"cmd package resolve-activity --brief {USER_KEY} -a android.intent.action.MAIN -c android.intent.category.HOME {package}")
        lines = [l.strip() for l in out.splitlines() if "/" in l]
        return lines[-1] if lines else ""

    def set_home(self, package: str) -> bool:
        activity = self.home_activity(package)
        if not activity:
            return False
        self.sh(f"cmd package set-home-activity {USER_KEY} {activity}")
        return self.home_owner() == package

    def disable(self, package: str) -> str:
        return self.sh(f"pm disable-user {USER_KEY} {package}").strip()

    def enable(self, package: str) -> str:
        return self.sh(f"pm enable {USER_KEY} {package}").strip()


def backups_dir() -> Path:
    override = os.environ.get("TIDYTV_HOME")
    base = Path(override) if override else Path.home() / ".tidytv"
    path = base / "backups"
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_backup(device: dict, states: dict, home: str, applied: list[str]) -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    path = backups_dir() / f"{stamp}-{device.get('device') or 'tv'}.json"
    path.write_text(json.dumps({"at": stamp, "device": device, "home": home, "enabled": states["enabled"], "disabled": states["disabled"], "applied": applied}, indent=2), "utf-8")
    return path


def latest_backup() -> Path | None:
    files = sorted(backups_dir().glob("*.json"))
    return files[-1] if files else None


def plan_apply(packages: list[str], states: dict, home: str, home_candidates: list[str], force_home: bool = False) -> dict:
    """Decide what to do with each package before touching the TV. Pure function, unit-tested."""
    present = set(states["all"])
    disabled = set(states["disabled"])
    other_launchers = [p for p in home_candidates if p != home and p not in NOT_A_LAUNCHER and p not in packages]
    decision = {"disable": [], "skip": [], "other_launchers": other_launchers}
    for package in packages:
        if package in PROTECTED:
            decision["skip"].append((package, "protected core package"))
        elif package not in present:
            decision["skip"].append((package, "not installed on this TV"))
        elif package in disabled:
            decision["skip"].append((package, "already disabled"))
        elif package == home and not force_home and not other_launchers:
            decision["skip"].append((package, "it owns the Home screen and no other launcher is installed; install one first (or --force-home)"))
        else:
            decision["disable"].append(package)
    return decision


# ----------------------------------------------------------------------------
# Commands
# ----------------------------------------------------------------------------

def cmd_probe(tv: Tv, args) -> int:
    info = tv.probe()
    states = tv.package_states()
    print(f"Model:    {info['brand']} {info['model']} ({info['device']})")
    print(f"Android:  {info['android']} (SDK {info['sdk']}), build {info['build']}")
    print(f"Home:     {info['home'] or 'unknown'}")
    print(f"Packages: {len(states['all'])} installed, {len(states['disabled'])} disabled, {len(states['all']) - len(states['system'])} installed by you")
    return 0


def connection_flags(args) -> str:
    """The flags needed to reach this TV again, for hints printed to the user."""
    flags = f"--host {args.host}"
    if args.adb:
        flags = f"--adb {args.adb} " + flags
    if args.port != 5555 or args.adb:
        flags += f" --port {args.port}"
    return flags


def cmd_scan(tv: Tv, args) -> int:
    info = tv.probe()
    states = tv.package_states()
    system = set(states["system"])
    disabled = set(states["disabled"])
    groups: dict[str, list[tuple[str, str]]] = {}
    for package in states["all"]:
        if package in disabled:
            groups.setdefault("disabled", []).append((package, "already disabled"))
            continue
        category, note = classify(package, package in system)
        groups.setdefault(category, []).append((package, note))
    out = Path(args.output)
    write_plan(out, info, groups)
    counts = {k: len(v) for k, v in groups.items()}
    print(f"Scanned {len(states['all'])} packages on {info['brand']} {info['model']}.")
    for key in ("recommended", "optional", "vendor", "google", "unknown", "yours", "core", "protected", "disabled"):
        if key in counts:
            print(f"  {key:<12} {counts[key]}")
    print(f"\nPlan written to {out}. Open it, keep or remove lines, then run:\n  python3 tidytv.py {connection_flags(args)} apply {out}")
    if info["home"] == "com.google.android.apps.tv.launcherx":
        print("\nThe sponsored rows on the home screen come from the stock launcher itself. To lose them, install another launcher\n(Projectivy Launcher or FLauncher from the Play Store), then uncomment its line in the plan and apply again.")
    return 0


def cmd_apply(tv: Tv, args) -> int:
    packages = parse_list(Path(args.list).read_text("utf-8"))
    if not packages:
        raise TidyError(f"{args.list} has no active lines; nothing to do.")
    info = tv.probe()
    states = tv.package_states()
    home = info["home"]
    decision = plan_apply(packages, states, home, tv.home_candidates(), force_home=args.force_home)
    for package, why in decision["skip"]:
        print(f"skip     {package}: {why}")
    if not decision["disable"]:
        print("Nothing to disable.")
        return 0
    print(f"\n{len(decision['disable'])} package(s) will be disabled for the main user (not uninstalled):")
    for package in decision["disable"]:
        print(f"  {package}")
    if args.dry_run:
        print("\nDry run: nothing changed.")
        return 0
    if not args.yes:
        answer = input("\nContinue? Type yes to go ahead: ").strip().lower()
        if answer != "yes":
            print("Cancelled. Nothing changed.")
            return 1
    backup = write_backup(info, states, home, decision["disable"])
    print(f"\nBackup written to {backup}")
    failed = []
    for package in decision["disable"]:
        result = tv.disable(package)
        ok = "disabled-user" in result or "new state: disabled" in result
        print(f"{'ok  ' if ok else 'FAIL'}     {package}" + ("" if ok else f": {result}"))
        if not ok:
            failed.append(package)
    after = set(tv.package_states()["disabled"])
    not_disabled = [p for p in decision["disable"] if p not in after]
    new_home = tv.home_owner()
    if home in decision["disable"] and (not new_home or new_home in NOT_A_LAUNCHER) and decision["other_launchers"]:
        # The setup wizard answers HOME ahead of a launcher that was never made Home; hand it over explicitly.
        for candidate in decision["other_launchers"]:
            if tv.set_home(candidate):
                new_home = candidate
                print(f"Home handed to {candidate}.")
                break
    print(f"\nDone. {len(decision['disable']) - len(not_disabled)} disabled, {len(not_disabled)} still enabled. Home is now {new_home or 'unknown'}.")
    if home and (not new_home or new_home in NOT_A_LAUNCHER):
        print(f"WARNING: no launcher answers Home. Run restore now:\n  python3 tidytv.py {connection_flags(args)} restore")
        return 2
    if not_disabled:
        print("Still enabled (the firmware protects them): " + ", ".join(not_disabled))
    print(f"Restart the TV once, then check that everything you use still works. Undo with:\n  python3 tidytv.py {connection_flags(args)} restore")
    return 1 if failed else 0


def cmd_restore(tv: Tv, args) -> int:
    path = Path(args.backup) if args.backup else latest_backup()
    if not path or not path.exists():
        raise TidyError("No backup found. Nothing to restore from.")
    backup = json.loads(path.read_text("utf-8"))
    states = tv.package_states()
    now_disabled = set(states["disabled"])
    to_enable = [p for p in backup["enabled"] if p in now_disabled]
    if args.only_applied:
        to_enable = [p for p in to_enable if p in backup.get("applied", [])]
    print(f"Restoring from {path.name}: {len(to_enable)} package(s) to re-enable.")
    if args.dry_run:
        for package in to_enable:
            print(f"  would enable {package}")
        return 0
    for package in to_enable:
        result = tv.enable(package)
        print(f"{'ok  ' if 'enabled' in result else 'FAIL'}     {package}" + ("" if "enabled" in result else f": {result}"))
    wanted_home = backup.get("home")
    if wanted_home and tv.home_owner() != wanted_home and wanted_home in set(tv.packages()):
        tv.set_home(wanted_home)
    print(f"Home is now {tv.home_owner() or 'unknown'}. Restart the TV once.")
    return 0


def cmd_check(tv: Tv, args) -> int:
    path = Path(args.backup) if args.backup else latest_backup()
    if not path or not path.exists():
        raise TidyError("No backup found; apply a plan first.")
    backup = json.loads(path.read_text("utf-8"))
    applied = backup.get("applied", [])
    enabled_now = set(tv.package_states()["enabled"])
    came_back = [p for p in applied if p in enabled_now]
    if not came_back:
        print(f"All {len(applied)} packages from {path.name} are still disabled.")
        return 0
    print(f"{len(came_back)} package(s) are enabled again (a firmware update, or a restore):")
    for package in came_back:
        print(f"  {package}")
    if args.fix:
        for package in came_back:
            result = tv.disable(package)
            print(f"{'ok  ' if 'disabled' in result else 'FAIL'}     {package}")
    else:
        print("Run with --fix to disable them again.")
    return 0


def cmd_shell(tv: Tv, args) -> int:
    sys.stdout.write(tv.sh(" ".join(args.command)))
    return 0


def cmd_close(tv: Tv, args) -> int:
    print("Switching ADB off on the TV (settings put global adb_enabled 0)...")
    try:
        tv.sh("settings put global adb_enabled 0")
    except (TidyError, OSError):
        pass  # the TV drops the connection as the daemon stops
    tv.t.close()
    time.sleep(2)
    try:
        with socket.create_connection((args.host, args.port), timeout=5):
            print(f"{args.host}:{args.port} still accepts connections. Turn USB debugging off in Developer options and restart the TV.")
            return 1
    except OSError:
        print(f"{args.host}:{args.port} now refuses connections. ADB is closed.")
        return 0


def cmd_port(args) -> int:
    try:
        with socket.create_connection((args.host, args.port), timeout=5):
            print(f"{args.host}:{args.port} is open.")
            return 1
    except ConnectionRefusedError:
        print(f"{args.host}:{args.port} refused: ADB is closed.")
        return 0
    except OSError as err:
        print(f"could not check {args.host}:{args.port}: {err}")
        return 2


# ----------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="tidytv", description="Clean up an Android TV / Google TV stick over Wi-Fi. Disables, never uninstalls.")
    parser.add_argument("--host", default=os.environ.get("TIDYTV_HOST"), help="TV IP address (or TIDYTV_HOST)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("TIDYTV_PORT", "5555")), help="ADB port, default 5555")
    parser.add_argument("--adb", default=os.environ.get("TIDYTV_ADB"), help="path to a real adb to use instead of the built-in client (for paired Wireless debugging)")
    parser.add_argument("--timeout", type=float, default=10.0, help="network timeout in seconds")
    parser.add_argument("--version", action="version", version=f"tidytv {__version__}")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("probe", help="show model, Android version and what owns Home")
    p = sub.add_parser("scan", help="list packages, classify them and write a plan file")
    p.add_argument("-o", "--output", default="plan.txt")
    p = sub.add_parser("apply", help="disable the packages in a plan or list file (backs up first)")
    p.add_argument("list")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    p.add_argument("--force-home", action="store_true", help="allow disabling the current Home app even with no other launcher (not recommended)")
    p = sub.add_parser("restore", help="re-enable what the last apply disabled (or everything in a backup)")
    p.add_argument("backup", nargs="?")
    p.add_argument("--only-applied", action="store_true", help="re-enable only packages that apply disabled, not others")
    p.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("check", help="see which applied packages a firmware update switched back on")
    p.add_argument("backup", nargs="?")
    p.add_argument("--fix", action="store_true")
    p = sub.add_parser("shell", help="run one shell command on the TV")
    p.add_argument("command", nargs=argparse.REMAINDER)
    sub.add_parser("close", help="switch ADB off on the TV and check the port is closed")
    sub.add_parser("port", help="check whether the ADB port is open (no key needed)")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.host:
        print("Give the TV's IP address with --host (Settings > Network on the TV shows it).", file=sys.stderr)
        return 2
    if args.cmd == "port":
        return cmd_port(args)
    try:
        if args.adb:
            transport = AdbBinaryTransport(args.adb, args.host, args.port)
        else:
            transport = AdbTcpTransport(args.host, args.port, key=load_or_create_key(), timeout=args.timeout)
        banner = transport.connect()
        props = dict(kv.split("=", 1) for kv in banner.split("::", 1)[-1].split(";") if "=" in kv) if "::" in banner else {}
        print(f"Connected to {props.get('ro.product.model') or f'{args.host}:{args.port}'}.")
        tv = Tv(transport)
        handler = {"probe": cmd_probe, "scan": cmd_scan, "apply": cmd_apply, "restore": cmd_restore, "check": cmd_check, "shell": cmd_shell, "close": cmd_close}[args.cmd]
        try:
            return handler(tv, args)
        finally:
            transport.close()
    except TidyError as err:
        print(f"\n{err}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nInterrupted. If an apply was running, use restore to put things back.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
