# Installing Stackward dogfood APKs (Pixel)

Chrome's **"App not installed"** message hides the real PackageManager error.
In August 2026 Google is also rolling out **Android developer verification**, which
can block sideloads from unverified signing keys unless you use ADB or the
advanced power-user flow.

## Preferred: install with ADB

1. On the Pixel: **Settings → About phone → Build number** (tap 7×) to enable Developer options.
2. **Settings → System → Developer options → USB debugging** → on.
3. Plug into a computer with [`platform-tools`](https://developer.android.com/tools/releases/platform-tools) / `adb`.
4. Download the APK from the GitHub Release, then:

```bash
adb devices          # phone should show as "device"
adb install -r app-dogfood.apk
```

If it fails, `adb` prints the **real** reason, e.g.:

| Code | Meaning |
|------|---------|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Old Stackward still installed with a different key — uninstall it first |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free more space |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | Truncated / corrupt download |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | Play Protect / developer verification — see below |

## Sideload checklist (if you cannot use ADB)

1. Download in **Chrome** (not the GitHub app). Confirm the file size matches the release notes.
2. **Settings → Apps → Special app access → Install unknown apps → Chrome → Allow**.
3. Play Store → profile → **Play Protect → Settings** → turn off **Scan apps with Play Protect** temporarily.
4. Open the APK from **Files → Downloads** and install.
5. If Android blocks an **unverified developer**, use ADB (above) or Google's advanced sideload flow in Developer options (may require a waiting period).

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

## Smoke vs dogfood

| Build | Package ID | Contents |
|-------|------------|----------|
| `smoke` | `dev.stackward.smoke` | Tiny APK, **no** native MediaPipe libs — install probe only |
| `dogfood` | `dev.stackward.dogfood` | Full app (arm64), on-device LLM libs included |

If **smoke** installs but **dogfood** does not, the failure is in native-lib packaging.
If **neither** installs, the failure is device policy / Play Protect / developer verification — use ADB.
