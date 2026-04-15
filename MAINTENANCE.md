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
| `SendMessagesHelper.java` | Routes outgoing messages to LoRa when offline |
| `DialogsActivity.java` | Renders Mesh folders and virtual entries |
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

## 5. Deployment
Always use `./gradlew assembleRelease` to confirm that the native JNI libraries (FFmpeg, MeshCore) are correctly bundled for all architectures (`arm64-v8a` is the primary target).
