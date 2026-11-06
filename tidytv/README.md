# tidytv

Clean up an Android TV or Google TV stick over Wi-Fi, from whatever you have to hand: an iPad, a
Windows laptop, a Mac or Linux. One Python file, no dependencies, no Android tools to install.
Built for a Realme 4K Smart Google TV Stick, works on other Google TV devices too.

What it does, in order:

1. **probe** - says what the stick is and which app owns the Home screen.
2. **scan** - lists every package, sorts them into *recommended to disable*, *optional*, *vendor*,
   *yours* and *core*, and writes `plan.txt` for you to edit.
3. **apply** - backs up the current state, disables the packages in your plan with
   `pm disable-user` (nothing is uninstalled), and checks the result. It refuses to disable the Home
   app while no other launcher is installed.
4. **restore** - puts everything back from the backup. **check** tells you what a firmware update
   switched on again. **close** turns ADB off on the stick when you are done.

The idea and the package knowledge come from [CleanYourTV](https://github.com/Gathin23/cleanyourtv)
(MIT), which maintains lists for hundreds of TVs, and from
[CleanMyTV](https://github.com/wingedpanther/cleanmytv), a desktop app built on those lists. tidytv
is a from-scratch, smaller tool whose point is to run where those cannot: it carries its own ADB
client, so an iPad is enough. It does not install a replacement launcher; it tells you when to get
one from the Play Store instead.

## What you can and cannot expect

- Telemetry, feedback uploaders, ad plumbing, the sponsored-app auto-installer and the promo
  screensaver go. That is the "recommended" group, and it is safe on a streaming stick.
- The sponsored rows on the home screen are drawn by Google's own launcher. The only way to lose
  them is another launcher. Install Projectivy Launcher or FLauncher from the Play Store, then let
  tidytv disable the stock one; it will refuse until the new launcher is there.
- Everything is reversible with `restore`, as long as the backup it wrote still exists.
- Disabling a package a service depends on can break that service. The plan keeps anything it
  does not recognise; read the notes before enabling a line.

## Setting up the stick

1. Settings → System → About → select **Android TV OS build** seven times until it says you are a
   developer.
2. Back → System → **Developer options** → turn on **USB debugging**. If there is a
   **Network debugging** or **ADB debugging** toggle too, turn it on. Keep the stick awake.
3. Settings → Network & Internet → note the stick's **IP address** (for example `192.168.1.50`).

If Developer options only offers **Wireless debugging** with a pairing code and port 5555 stays
closed, see *Wireless debugging* below.

## Running it

### From an iPad

tidytv's own ADB client needs only Python and a socket, which the free terminal apps provide.

- **a-Shell** (App Store, free): open it, then

  ```
  curl -L -o tidytv.py https://raw.githubusercontent.com/Yash-kumar4567/Learning/claude/jolly-cerf-5lqzti/tidytv/tidytv.py
  python3 tidytv.py --host 192.168.1.50 probe
  ```

  Or put `tidytv.py` in the a-Shell folder with the Files app and run the same `python3` line.

- **iSH** (App Store, free): `apk add python3`, then the same commands. iSH also has Google's
  real adb (`apk add android-tools`), which is what you need for a stick that insists on pairing;
  see *Wireless debugging*.

The first run creates a key in `~/.tidytv/adbkey.json`; the stick shows **Allow USB debugging?**
once. Tick *Always allow* and accept.

### From Windows (the laptop Codex uses)

Install Python 3 from python.org (tick *Add python.exe to PATH*), then in PowerShell:

```powershell
cd path\to\Learning\tidytv
python tidytv.py --host 192.168.1.50 probe
python tidytv.py --host 192.168.1.50 scan
notepad plan.txt
python tidytv.py --host 192.168.1.50 apply plan.txt
python tidytv.py --host 192.168.1.50 close
```

### The walkthrough

```
python3 tidytv.py --host 192.168.1.50 probe                 # model, Android version, Home owner
python3 tidytv.py --host 192.168.1.50 scan                  # writes plan.txt
# edit plan.txt: lines without '#' will be disabled
python3 tidytv.py --host 192.168.1.50 apply plan.txt --dry-run
python3 tidytv.py --host 192.168.1.50 apply plan.txt        # asks for "yes", backs up, disables
# restart the stick, use it for a day
python3 tidytv.py --host 192.168.1.50 check                 # after a firmware update: what came back
python3 tidytv.py --host 192.168.1.50 restore               # undo everything from the last backup
python3 tidytv.py --host 192.168.1.50 close                 # switch ADB off and confirm the port is shut
```

`lists/google-tv-stick.txt` is a ready-made conservative list if you would rather not read a
plan; `apply` takes it the same way. Lists in the same format from the CleanYourTV `examples/`
folder work too.

## Wireless debugging (pairing code) sticks

Android 11 and newer can offer *Wireless debugging* instead of the classic network ADB. It uses TLS
pairing, which tidytv's built-in client cannot do. Two ways round it:

- Pair once with a real adb (`adb pair <ip>:<pairing port>` with the code on screen, then
  `adb connect <ip>:<port>`), run `adb tcpip 5555`, and use tidytv normally. On an iPad, iSH's
  `apk add android-tools` provides that adb; on Windows, Google's platform-tools.
- Or keep the paired connection and let tidytv drive adb for you:
  `python3 tidytv.py --adb /usr/bin/adb --host <ip> --port <connection port> scan`.

`python3 tidytv.py --host <ip> port` tells you whether 5555 is open without needing a key.

## Safety notes

- Only `pm disable-user --user 0` and `pm enable` are used for packages. No uninstall, no root, no
  system partition changes.
- A short list of core packages (Play Store, Play services, settings, the fallback launcher, the
  remote service) is never disabled whatever a list says.
- The backup written before each apply lives in `~/.tidytv/backups/` (or `$TIDYTV_HOME/backups/`).
- Switch ADB off afterwards with `close`. An open ADB port is an open door on your network.
- Commands run as the shell user on the stick; nothing leaves your Wi-Fi.

## Tests

Offline, with a fake stick on loopback:

```
cd tidytv
python3 -m unittest discover -s tests -v
```

They cover the wire format, RSA key generation, signing and the Android public-key layout, the
handshake with and without authentication (including the first-time key offer), the Wireless
debugging detection, large shell output, plan classification, Home protection and handover, apply
with backup, check and restore.

## Known limitations

- No TLS pairing (see above). No USB transport: Wi-Fi only.
- No launcher is bundled. The home-screen ads need a launcher from the Play Store.
- `scan` knows Google TV packages well and vendor packages only by prefix; a Realme-specific
  package it has not seen is listed for you to look up, not disabled.
- Key generation on first run is pure Python and takes several seconds, longer on an iPad.
