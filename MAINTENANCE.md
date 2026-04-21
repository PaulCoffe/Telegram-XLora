# Telegram-XLora Maintenance & Upgrades Guide

This document serves as the primary manual for maintaining and upgrading the Telegram-XLora project across its three core dependencies: upstream Telegram (Forkgram), MeshCore libraries, and the bridge integration.

## 1. Upstream Tracking (Remotes)

To keep the project updated with the base code, maintain the following Git remotes:

| Remote | URL | Role |
|--------|-----|------|
| `origin` | `https://github.com/PaulCoffe/Telegram-XLora.git` | Primary development repository |
| `upstream` | `https://github.com/forkgram/TelegramAndroid.git` | Base Telegram client (Forkgram) |
| `meshcore` | `https://github.com/mintylinux/Meshcore-Wardrive-Android.git` | MeshCore LoRa functionality |

### Update Telegram Base
```bash
git fetch upstream
git merge upstream/master  # or rebase for cleaner history
```
**Warning**: Telegram updates often involve massive refactors (e.g., renaming `dialogMessage` to `dialogMessages`). Always run the **Hooks Verification** after a merge.

## 2. Maintenance Touchpoints (The "Hooks")

When updating the base Telegram code, these files are the most likely to cause conflicts or breakages. Always verify them manually or via grep.

| File | Integration Role |
|------|------------------|
| `MessagesController.java` | Intercepts history and dialog filtering |
| `SendMessagesHelper.java` | Intercepts sending flow and redirects to LoRa when offline |
| `DialogsActivity.java` | Renders Mesh folders and virtual entries |
| `MainTabsActivity.java` | Orchestrates the 5-tab navigation system |
| `ChatMessageCell.java` | Renders Mesh-specific status icons and telemetry |
| `ApplicationLoader.java` | Initializes `MeshManager` and background service |

## 3. Submodule Management

The MeshCore libraries are linked via Git submodules. To update them:
```bash
git submodule update --remote --recursive
```
Check the `companion_protocol.md` version in the submodule to ensure binary compatibility with the `MeshManager.java` constants.

## 4. Conflict Resolution Strategy

### Naming Standardization
If the build fails with "cannot find symbol" for fields like `dialogMessages`, it usually means Telegram has performed a global rename.
- **Rule**: Follow Telegram's new naming convention. Do not revert to old names; instead, update the Mesh glue code to match.

### CI/CD Validation
The project uses GitHub Actions for verification. After any merge:
1. Push to a development branch (e.g., `mesh-dev`).
2. **IMPORTANT**: Builds are ALWAYS performed on GitHub by committing to the `mesh-dev` branch. Local builds are for syntax and import checks only.
3. Monitor `gh run list`.
4. If the build fails, use `gh run view --log` to identify the broken hook.

**Workflow note**: GitHub Actions is pinned to **NDK r21e** (`ndkVersion 21.4.7075529`). Avoid adding extra NDK installs unless explicitly required, as they increase disk usage and flakiness on hosted runners.

**tde2e note**: `TMessagesProj/jni/tde2e/build-tdlib.sh` defaults to `ANDROID_NDK_VERSION=23.2.8568313`. Our native pipeline must explicitly pass the configured `android.ndkDirectory` version to avoid accidental dependence on a runner-preinstalled NDK.

## 4.1 Security Baselines (Do not regress)
- **Cleartext traffic**: keep `android:usesCleartextTraffic="false"` and prefer a strict `network_security_config`.
- **Providers**: avoid `android:exported="true"` providers unless protected by permission + caller verification.
- **FileProvider**: keep `provider_paths.xml` narrow (no `root-path` to `/storage`).
- **Logging**: never log tokens/keys/secrets (push auth key, login tokens, mesh secrets/pins).

## 6. Manual Verification Protocol

To ensure high-stakes delivery logic works correctly, perform the following tests:

### 6.1 Persistent Delivery Recovery
1. Start the app and connect to a LoRa device.
2. Send a message to a Mesh contact.
3. While the status is "Pending" (Clock icon), **Force Stop** the app.
4. Restart the app.
5. **Verification**: 
   - The message should immediately show "Pending" in the chat list.
   - Once the BLE connection re-establishes, the message should transition to "Sent" (Single Check) or "Delivered" (Double Check) automatically without user intervention.
   - Check `MeshStorage` logs via `adb logcat | grep MeshStorage` to confirm `tg_message_tracker` was loaded on startup.

### 6.2 Telemetry Refresh
1. Receive a message via LoRa.
2. Observe the SNR/Hops in the chat list and the message bubble.
3. **Verification**: SNR should match the hardware report (usually -20 to +15 dB). Hops should reflect the path correctly (0 = Direct).

---

## 7. Deployment
Always use `./gradlew assembleRelease` to confirm that the native JNI libraries (FFmpeg, MeshCore) are correctly bundled for all architectures (`arm64-v8a` is the primary target).
