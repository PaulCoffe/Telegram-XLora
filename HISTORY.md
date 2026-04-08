# DEVELOPMENT HISTORY

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
