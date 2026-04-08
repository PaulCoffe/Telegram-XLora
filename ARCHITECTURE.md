# ARCHITECTURE

## System Foundation
- **Base Project**: forkgram/TelegramAndroid (Official Forkgram).
- **Language**: Java / C++.
- **Platform**: Android.
- **Core Modules**:
  - `:TMessagesProj`: The main Telegram logic and JNI.
  - `:TMessagesProj_App`: The primary application module for assembly.
  *(All other app variations have been removed for simplicity).*

## CI/CD Pipeline (GitHub Actions)
- **Goal**: Automated Assembly of the Release APK.
- **Environment**: Ubuntu Latest, JDK 17, NDK r21e + r23c.
- **Native Build**: FFmpeg, VPX, BoringSSL within `TMessagesProj/jni` (built via shell scripts before APK assembly).
- **Secrets Management**:
  - `API_ID`: GitHub Secret (fallback to dummy '12345').
  - `API_HASH`: GitHub Secret (fallback to dummy 'abcdef0123456789').

## LoRa Mesh Integration (MeshCore)
The mesh system is integrated as a transport layer below `SendMessagesHelper`.

### Core Components
- **MeshManager.java**: Manages BLE life cycle, GATT connection to Nordic UART Service (NUS), and async write queue.
- **MeshTransportManager.java**: Persists mesh settings (enabled/disabled, device address) and acts as a gateway bridge.
- **MeshFragmenter.java**: Handles packet fragmentation (200 bytes MTU) and reassembly with AES-256 CTR encryption.
- **MeshSettingsActivity.java**: UI for device discovery and permission handling (Android 12+).

### Message Flow
1. **Outgoing**: `SendMessagesHelper` checks network status. If offline and Mesh is enabled, it sends bytes via `MeshManager.sendData()`.
2. **Incoming**: `MeshManager` receives GATT notification -> Decrypt -> Reassemble -> `handleMeshMessage()`.
3. **Gateway**: If a device receives a packet starting with `RELAY:` and it has internet, it forwards the payload via standard Telegram API.

### Security
- **Algorithm**: AES-256 CTR.
- **Key**: Static 32-byte MeshCore PSK (defined in `MeshManager`).
- **Transport**: BLE UART (encrypted).
