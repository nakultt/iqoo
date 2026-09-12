# Wireless ADB for VeriTransit (`tools/adbw`)

Deploy and debug VeriTransit on a **physical phone with no USB cable**.

Android 11+ has *Wireless debugging* built in, so you do **not** need a cable to
turn ADB on. You enable it on the phone's touchscreen, pair once, and from then
on `tools/adbw` builds, installs, launches and tails logs over Wi-Fi.

> **One thing to be clear about up front:** no app can switch wireless debugging
> on for you. `adbd` is a system daemon behind a user-controlled security
> toggle — that is Android's security boundary. The phone has to be unlocked and
> the toggle flipped by hand. `adbw` makes everything *after* that one command.

---

## Quick start

```bash
# 0. once per Mac: make sure a supported JDK is on JAVA_HOME (see "JDK" below)
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

tools/adbw doctor          # preflight — run this first, always
tools/adbw pair            # pair (needs the 6-digit code on the phone)
tools/adbw deploy --logcat # build -> install -> launch -> logs
```

### Enable wireless debugging on the phone (one time)

1. **Settings → About phone → tap "Build number" 7 times** → unlocks Developer options
2. **Settings → System → Developer options → Wireless debugging → ON**
3. Tap **"Pair device with pairing code"** — a 6-digit code + `IP:Port` appear
4. On the Mac: `tools/adbw pair` then type the code

Leave the pair dialog open while pairing: **the pairing port only exists while
that dialog is on screen**, and it changes every time you open it. The
*connection* port shown on the main Wireless-debugging screen is stable.

---

## Commands

| Command | What it does |
| --- | --- |
| `doctor` | Preflight: adb, mDNS, JDK, APK artifacts, network, devices, services |
| `pair [CODE] [HOST:PORT]` | Pair; auto-discovers the pairing port, or takes the dialog's address |
| `connect [HOST:PORT]` | Connect on the persistent debugging port |
| `disconnect [ADDR\|--all]` | Drop wireless connections — **never** touches emulators/USB |
| `status` | Connected devices + services currently advertised |
| `deploy` | **build → install → launch** (the main command) |
| `install` / `launch` | Install only / launch only |
| `logs [--clear]` | Stream app logcat (filtered to the app's pid) |
| `reverse [L[:R]]` | List or create reverse tunnels (`--remove-all` to clear) |
| `shell <cmd...>` | Run a shell command on the device |
| `probe HOST:PORT` | TCP reachability test — diagnoses the network, not adb |

**Options:** `-s/--serial SERIAL` target one device · `--wireless` only
wireless devices · `--release` release build · `--no-build` skip Gradle ·
`--logcat` tail after deploy.

### Typical session

```bash
tools/adbw doctor                       # is everything sane?
tools/adbw pair                         # first time only
tools/adbw deploy --logcat              # inner dev loop
tools/adbw shell getprop ro.product.model
tools/adbw logs --clear
tools/adbw reverse 8080                 # device can reach this Mac at 127.0.0.1:8080
tools/adbw disconnect
```

`deploy` self-heals the common local case: if a build signed with a different
key is already on the device, it says so, uninstalls the old build and retries
(app data is lost — that is what the warning is about).

> **`disconnect` only ever drops wireless devices.** `adb disconnect` with no
> target also tears down emulator transports, so `--all` instead enumerates the
> `ip:port` devices and disconnects just those. Passing a non-wireless serial is
> refused rather than silently killing a working emulator.

---

## ⚠️ Shared / office networks

**`adbw` will refuse to guess when more than one device is advertising ADB.**

This matters here: on the office network several colleagues' phones were
visible at once —

```
adb-10BFAX1BNR0010U-E49N5C   _adb-tls-connect._tcp   172.26.252.176:39935
adb-10BFAT1RTA000XP-ByoS6S   _adb-tls-connect._tcp   172.26.252.117:45189
adb-10BFCG0ZPC00204-HOU19T   _adb-tls-connect._tcp   172.26.252.101:35863
```

Blindly connecting to "the first device found" could target **someone else's
phone**. So `connect` requires one of:

- a previous successful `pair` (it remembers *your* phone's IP), or
- an explicit address: `tools/adbw connect 172.26.252.98:37099`

`pair` is the clean way to disambiguate: the code on your screen proves which
device is yours.

---

## Troubleshooting

Run `tools/adbw doctor` first — it names the failing stage rather than hanging.

### "no wireless debugging service discovered"

Wireless debugging is either off, or the phone is not advertising. Check in
this order:

1. **Is Wireless debugging actually ON?** Toggling it off/on on the phone
   regenerates the port, so re-pair afterwards.
2. **Use the manual address.** mDNS discovery is genuinely flaky — during
   testing this Mac alternated between seeing three devices and seeing none
   while the phones sat untouched. Read `IP address & Port` straight off the
   phone's Wireless-debugging screen and pass it explicitly:
   ```bash
   tools/adbw connect 172.26.252.98:37099
   ```
3. **Wi-Fi client isolation.** Common on office, guest and hotspot networks:
   both devices are on the same SSID but the AP forbids client-to-client
   traffic. Same network is *not* enough. Test with
   `tools/adbw probe <phone-ip>:<port>` — a timeout (rather than "refused")
   is the signature of isolation.
4. **macOS Local Network permission.** System Settings → Privacy & Security →
   **Local Network** → enable your terminal. Without it, mDNS silently returns
   nothing. (On this Mac it was already granted — discovery worked.)
5. **Different band/VLAN.** Phone on 5 GHz and Mac on 2.4 GHz, or separate
   guest VLANs, will not see each other.

### "could not connect to <ip:port>"

Wireless debugging was toggled off, or the port changed. Re-pair — the port is
not stable across toggles. `adbw` remembers your phone's **IP** (stable) and
re-discovers the port.

### "pairing failed"

The 6-digit code expires quickly and the pairing port exists only while the
dialog is open. Reopen the dialog, then run `tools/adbw pair` immediately.

### "JAVA_HOME is set to a missing directory"

`doctor` reports this because it breaks every `./gradlew` call from your shell.
On this Mac `JAVA_HOME` pointed at
`/Applications/Android Studio.app/Contents/jbr/Contents/Home` — **Android
Studio is not installed**, so the directory does not exist. Fix it in
`~/.zshrc`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

`adbw` resolves a working JDK itself, so it keeps working even when your shell's
`JAVA_HOME` is broken — but your own `./gradlew` invocations will not.

---

## JDK requirement

Gradle **8.14.3** with AGP **8.13.2** supports **Java 17–24**. On this Mac:

| JDK | Path | Usable? |
| --- | --- | --- |
| 21 (Homebrew) | `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` | ✅ **preferred** |
| 25 (Oracle) | `/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home` | ❌ too new for Gradle 8.14.3 |
| 11 (Corretto) | `/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home` | ❌ below AGP's minimum |

`adbw` picks the best available JDK for each build, preferring 21. Override with
`ADBW_JAVA_HOME=/path/to/jdk`.

---

## How it works

- All state lives in `tools/.state/wireless.env` (gitignored): the last
  connected address and, critically, **your phone's IP**, so later commands
  never target a different device.
- `pair` uses the *pairing* service (`_adb-tls-pairing._tcp`); `connect` uses the
  *connection* service (`_adb-tls-connect._tcp`).
- Everything shells out to `adb` — no reimplementation of the ADB protocol, and
  no extra dependencies.

### Scope and honest limits

`adbw` replaces the **deploy/debug loop** only. It does not enable developer
options, cannot grant root, and does not run as a background service. Shell
commands run with the app's own privileges, which is normal for non-root adb.

**Verified** on this machine (22/22 automated checks passing): `doctor`, JDK
resolution, build (debug **and** release), install, auto-recovery from
signature conflict, launch, `logs`, `reverse` (add/list/remove), `shell`,
`status`, `probe`, `disconnect`, plus error paths (`unknown command`, missing
arguments, unreachable host, refused non-wireless disconnect) — all against the
`qwentest` emulator.

**Not verifiable without your phone in hand:** the actual radio pairing
(`pair`/`connect`). That step needs the live 6-digit code, which exists only on
the phone's screen. Every other stage of the path is proven working.

---

*There is a second, unrelated adb server bundled inside vivo PC Suite
(`/Applications/pcsuite.app/Contents/Resources/adb/adb`, v31.0.3). `adbw` uses
the Homebrew `adb` 37.0.0 on your `PATH`; having two servers is fine as long as
both use port 5037 — only one can bind it, and pcsuite's yields to the
already-running one.*
