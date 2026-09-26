# Stackward ADB monitor backlog

Issues below are **new actionable** Errors/Warnings captured from device logcat.
Resolve or dismiss intentionally; do not delete history without reason.

## Open

### [2026-08-08 13:10] W — Keystore Ed25519 falls back to software key
- **Signature:** `AgentKeyManager|Keystore Ed25519 failed, using software key|InvalidAlgorithmParameterException|biometric must be enrolled`
- **First seen:** `08-08 13:10:05.389`
- **Count (session):** 1
- **Why it matters:** SSH key generation cannot use Android Keystore on this emulator because no biometric is enrolled; app falls back to software keys (weaker binding).
- **Sample:**
  ```
  W/AgentKeyManager: Keystore Ed25519 failed, using software key
  W/AgentKeyManager: java.security.InvalidAlgorithmParameterException: java.lang.IllegalStateException: At least one biometric must be enrolled to create keys requiring user authentication for every use
  	at android.security.keystore2.AndroidKeyStoreKeyPairGeneratorSpi.initialize(...)
  	at dev.stackward.crypto.AgentKeyManager.generateKeystoreKeypair(AgentKeyManager.kt:132)
  	at dev.stackward.crypto.AgentKeyManager.generateKeypair(AgentKeyManager.kt:42)
  	at dev.stackward.ui.onboarding.OnboardingViewModel$generateSshKey$1$publicKey$1.invokeSuspend(OnboardingViewModel.kt:292)
  ```
- **Status:** open

### [2026-08-08 13:10] E — Proxmox bootstrap failed (shell-escape + invalid priv)
- **Signature:** `Stackward|startBootstrap: failed|SshException|Proxmox bootstrap failed|privs invalid privilege VM.Monitor`
- **First seen:** `08-08 13:10:50.935`
- **Count (session):** 2
- **Why it matters:** Onboarding bootstrap aborts: sudo/password appears to be executed as a shell command (quoting/escaping bug), and Proxmox rejects privilege `VM.Monitor` as invalid format.
- **Sample:**
  ```
  E/Stackward: startBootstrap: failed
  E/Stackward: dev.stackward.connection.SshException: Proxmox bootstrap failed: bash: line 1: <REDACTED_PASSWORD>: command not found
  400 Parameter verification failed.
  privs: invalid format - invalid privilege 'VM.Monitor'
  
  pveum role modify <roleid> [OPTIONS]
  	at dev.stackward.onboarding.OnboardingFlow.start(OnboardingFlow.kt:152)
  	at dev.stackward.ui.onboarding.OnboardingViewModel$startBootstrap$1$result$1.invokeSuspend(OnboardingViewModel.kt:474)
  ```
- **Status:** open

<!-- new entries from monitoring-adb-logs appear above -->

## Resolved / ignored
<!-- move entries here when fixed or confirmed noise -->
