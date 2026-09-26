# Installing Stackward on a Pixel (2026)

**Do not rely on Chrome “Open APK → Install”.** On Pixel phones in mid‑2026,
Google’s **Android developer verification** / Play Protect often rejects
sideloaded APKs from unverified signing keys with a useless **"App not installed"**
dialog. That message does **not** mean the APK is corrupt.

The reliable path is **ADB** (USB or wireless). ADB installs bypass that check
and print the real PackageManager error if something is actually wrong.

## Install with wireless ADB (no cable)

On the Pixel:

1. **Settings → About phone → Build number** — tap 7 times.
2. **Settings → System → Developer options**:
   - **USB debugging** → on
   - **Wireless debugging** → on
3. Tap **Wireless debugging** → **Pair device with pairing code**.
   Note the **IP:port** and **pairing code**.

On a computer (Mac/Linux/Windows) with
[platform-tools](https://developer.android.com/tools/releases/platform-tools):

```bash
# 1) Pair (use the pairing port + code from the phone dialog)
adb pair <PHONE_IP>:<PAIRING_PORT>
# enter the pairing code when prompted

# 2) Connect (use the *connection* IP:port shown on the Wireless debugging screen)
adb connect <PHONE_IP>:<CONNECTION_PORT>
adb devices   # should list the phone as "device"

# 3) Download the release APK on the computer, then:
adb install -r app-dogfood.apk
```

Success looks like: `Success`

## Install with USB cable

```bash
adb devices
adb install -r app-dogfood.apk
```

## If `adb install` fails

Paste the **exact** `adb` output. Common codes:

| Code | Fix |
|------|-----|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall any old Stackward / `dev.stackward*` apps first |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free ~200 MB |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | Re-download the APK on the computer (file was truncated) |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | Play Protect — disable scan temporarily, or `adb install -r -t` / `-d` as needed |

Uninstall leftovers:

```bash
adb uninstall dev.stackward.dogfood
adb uninstall dev.stackward.smoke
adb uninstall dev.stackward
```

## Current package IDs

| Build | Package | Use |
|-------|---------|-----|
| dogfood | `dev.stackward.dogfood` | Full phone-test app (label: **Stackward**) |
| smoke | `dev.stackward.smoke` | Install probe only |

## Revoke agent access without the phone (lost device)

If the phone is lost or wiped, revoke Stackward's SSH access from an admin account
on the host (not via the app):

1. SSH to the host as an admin user (root or sudo).
2. Run the panic helper installed by [`scripts/bootstrap_linux.sh`](../scripts/bootstrap_linux.sh):

```bash
sudo /usr/local/sbin/stackward-panic-revoke
```

This clears `~stackward-agent/.ssh/authorized_keys`. Alternatively, edit that file
manually and remove the device's public key line.

3. On Proxmox, revoke the API token if one was issued:

```bash
pveum user token remove stackward-agent@pve stackward
```

Re-onboard a replacement device with a fresh key when ready.

## Chrome sideload (not recommended)

Only try this after ADB works once. Chrome installs of unverified APKs are
commonly blocked in 2026 even when the APK is valid.
