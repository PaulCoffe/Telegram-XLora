# DEVELOPMENT HISTORY

## [2026-04-10] Phase 9: Wireless Protocol Alignment & Dynamic Pairing (mesh-dev)

### Objective
Achieve 100% byte-for-byte protocol compatibility with the official `MeshCore` firmware and fix the Bluetooth Secure Connections (Passkey) pairing flow.

### Changes
1. **Bluetooth Pairing Flow Fixed**:
   - Removed the hardcoded `123456` PIN injection and `abortBroadcast()` for `PAIRING_VARIANT_PIN` & `PAIRING_VARIANT_PASSKEY`.
   - Android will now correctly display the native system popup prompting the user to enter the 6-digit dynamic physical passkey displayed on the MeshCore device OLED screen.
2. **Handshake Verification**:
   - Added the missing `protocol_version` byte (`0x01`) to `CMD_APP_START (0x01)`, allowing the firmware to properly accept the handshake sequence.
3. **Private Messages Payload Structure**:
   - Rewrote `sendContactMessage` (`CMD_SEND_TXT_MSG - 0x02`) payload structure.
   - Injected missing `txt_type (0x00)` and `attempt (0x00)` bytes to conform to the 13-byte header requirement of `companion_protocol.md`.
4. **Radio Configuration Implemented**:
   - Replaced placeholder stub in `sendRadioConfig` with actual `CMD_SET_RADIO_PARAMS (0x0B)` execution. 
   - Uses Little-Endian 4-byte integers for Frequency Hz and Bandwidth Hz.
5. **Write Queue Stability**:
   - Added handling for `PACKET_MSG_SENT (0x06)` in `processResponse()` so the command queue drains immediately after radio acceptance.

---

## [2026-04-10] Phase 8: Final Audit & Codebase Cleanup (mesh-dev)

### Objective
Remove all legacy dead code, migrate deprecated API callers, and prepare the project for a stable CI release build.

### Changes
1. **Deleted legacy files** (zero external references confirmed):
   - `MeshProtocol.java` — legacy packet constants, superseded by inline constants in `MeshManager.java`.
   - `MeshFragmenter.java` — legacy MTU fragmentation utility, superseded by inline chunking in `MeshManager.sendChannelMessage()`.

2. **DialogsActivity → LoraChannel API migration**:
   - Replaced `MeshStorage.getChannels()` (`@Deprecated`) + `MeshChannel.hash` with `MeshStorage.getLoraChannels()` + `LoraChannel.slotIndex`.
   - Replaced `getLastMessage(hash)` (non-existent method) with `getLastChannelMessageText(slotIndex)`.
   - Dialog IDs now computed via `MeshStorage.channelDialogId(slotIndex)` (consistent with MeshStorage schema v4).
   - Added `channel.name.isEmpty()` guard to skip uninitialized slots.

3. **SendMessagesHelper → direct sendChannelMessage() API**:
   - Replaced `MeshManager.getInstance().sendData(msg.getBytes())` with `sendChannelMessage(0, text)`.
   - Eliminates the `sendData()` byte-array shim hop; message now goes directly to the offline queue implementation.

### Result
- Zero dead code remaining in `org.telegram.messenger.mesh.*`.
- All callers aligned with MeshStorage v4 schema.
- Deprecated `@Deprecated` methods in `MeshStorage` retained for API surface compatibility but no longer called from production code paths.

---

## [2026-04-08] Phase: Build Speed Optimization
Focused on reducing iteration time for rapid prototyping on Samsung S25 (Android 16).

### Improvements
1. **Parallelism Restored**: Removed `-j1` and `org.gradle.workers.max=1` limitations. Enabled `org.gradle.parallel=true`.
2. **Deep Caching**:
   - Implemented `actions/cache` for pre-built Native Libraries (FFmpeg, libvpx, BoringSSL).
   - Enabled Gradle caching in `setup-java`.
3. **Architecture Targeting**:
   - Native scripts now only build for `arm64-v8a`.
   - Gradle task switched to `:TMessagesProj_App:assembleAfatFd_v8aRelease`.
   - Result: Much smaller APK and significantly faster CI builds (~4x speedup expected).

---

## [2026-04-08] Phase: LoRa Mesh Stabilization (mesh-dev)

### Context & Problem
The initial MeshCore implementation caused the app to crash on start on Android 12+ due to missing BLE permissions during auto-scan. Additionally, the BLE write operations were blocking the UI thread, and device discovery didn't refresh the UI correctly.

### Major Changes
1. **Async BLE Architecture**:
   - Refactored `MeshManager` to use a `ConcurrentLinkedQueue` for GATT writes.
   - Implemented `onCharacteristicWrite` to process the queue sequentially, preventing GATT congestion.
   - All listeners now receive events via `Handler(Looper.getMainLooper())`.

2. **Android 12+ Permission Fixes**:
   - Implemented `onRequestPermissionsResultFragment` in `MeshSettingsActivity`.
   - Added `startScanningIfPermissionsGranted()` to allow safe background scanning without crashes.
   - Updated `ApplicationLoader` to use safe scan entry point.

3. **Transport Reliability**:
   - Added null checks in `MeshFragmenter` and `MeshManager` to handle fragmentary or malformed packets.
   - Fixed Nordic UART UUIDs for better hardware compatibility (Heltec T114).

### Status
- **Branch**: `mesh-dev`
- **Build**: Automated APK build triggered on GitHub.
- **Stability**: High (No known boot-time crashes).

---
## [2026-04-07] Phase: Mesh Core Integration
- Initial hook of `MeshTransportManager` into `SendMessagesHelper`.
- Basic AES-256 CTR implementation.
- Discovery UI added to `SettingsActivity`.
