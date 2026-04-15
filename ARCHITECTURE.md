# Telegram-XLora Architecture & MeshCore Integration

> Last updated: 2026-04-14 — Protocol alignment v1.12.0+ | Final Rebranding v17 (Telemetry Integration v16)

## 1. Overview
Telegram-XLora is a custom Android client based on Forkgram that integrates **MeshCore LoRa** networking via Bluetooth LE. Users can communicate without internet using BLE-connected LoRa hardware (Heltec T114, LilyGO, etc.).

**Protocol Reference**:
- [MeshCore companion_protocol.md v1.12.0+](https://github.com/meshcore-dev/MeshCore/blob/main/docs/companion_protocol.md)
- [MeshCore Open Source Reference (zjs81/meshcore-open)](https://github.com/zjs81/meshcore-open)

---

## 2. BLE Connection Architecture

### Service UUIDs (Nordic UART Service — NUS)
| UUID | Role |
|------|------|
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | Service |
| `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | RX — App → Device |
| `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | TX — Device → App (notify) |

### Connection Sequence (per companion_protocol.md §Connection Steps)
1. Scan for device advertising the Service UUID
2. Connect GATT
3. Discover services & characteristics
4. Request MTU 512
5. Enable notifications on TX characteristic (descriptor 0x2902)
6. **Wait for BOND_BONDED** (via `BroadcastReceiver`) before GATT setup to prevent connection drops.
7. Send `CMD_APP_START (0x01)` → await `PACKET_SELF_INFO (0x05)`
7. Send `CMD_DEVICE_QUERY (0x16 0x03)` → await `PACKET_DEVICE_INFO (0x0D)`
8. Send `CMD_SET_DEVICE_TIME (0x09)`
9. Send `CMD_GET_CHANNEL (0x1F)` × 8 (slots 0-7) → collect `PACKET_CHANNEL_INFO (0x12)`
10. Send `CMD_SYNC_NEXT_MESSAGE (0x0A)` until `PACKET_NO_MORE_MSGS (0x0A)`

---

## 3. Core Components

### `MeshManager` (`org.telegram.messenger.mesh`)
**Role**: Hardware abstraction — BLE scanning, GATT lifecycle, command queue, response dispatcher.

**Key design choices**:
- `WriteQueue` — serialized command execution; only one command in-flight at a time with 5s timeout
- All listener callbacks dispatched on the **main thread** via `handler.post()`
- MTU 512 requested after service discovery
- **Bonding-first sequence**: GATT connection is deferred until the system confirms the device is bonded to ensure encrypted characteristic access.

**Listener interface** (`MeshManagerListener`):
```java
void onDevicesUpdated();
void onConnectionStateChanged(boolean connected);
default void onChannelMessage(int channelIndex, String senderInfo, String text, long ts, int snr, int hops) {}
default void onContactMessage(String pubKeyHex, String text, long ts, int snr, int hops) {}
default void onChannelLoaded(int slotIndex, String name, String secretHex, boolean isPublic) {}
default void onSelfInfoLoaded(String pubKeyHex, String name, long freqHz, float bwKHz, int sf, int cr) {}
```

**Supported commands**:
| Method | CMD byte | Description |
|--------|----------|-------------|
| `sendAppStart()` | `0x01` | Init handshake (sends proto_ver=1) |
| `sendDeviceQuery()` | `0x16 0x03` | Fetch device info |
| `sendGetChannel(idx)` | `0x1F` | Fetch channel slot |
| `sendSetChannel(idx, name, secret)` | `0x20` | Create/update channel |
| `sendChannelMessage(idx, text)` | `0x03 0x00` | Send to channel slot |
| `sendContactMessage(pubkey, text)` | `0x02` | Send DM (with txt_type and attempt bytes) |
| `sendGetMessage()` | `0x0A` | Poll queued messages |
| `sendGetBattery()` | `0x14` | Battery + storage |
| `sendRadioConfig(freq, bw, sf, cr)` | `0x0B` | Set radio params (Little-Endian uint32) |

---

### `MeshStorage` (`org.telegram.messenger.mesh`)
**Role**: SQLite persistence. DB name: `mesh_data.db`, current version: **3**.

**Schema v3**:
```sql
-- Per-node metadata (one row per discovered LoRa node)
CREATE TABLE nodes (
    pubkey TEXT PRIMARY KEY,    -- full or prefix pubkey hex
    nickname TEXT,
    tg_user_id INTEGER DEFAULT 0, -- 0 = unlinked
    last_seen INTEGER,
    last_rssi INTEGER,
    last_hops INTEGER
);

-- Message history (chat history for channel + DM)
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dialog_id INTEGER,          -- synthetic negative long
    sender_pubkey TEXT,         -- pubkey prefix (empty = outgoing)
    text TEXT,
    date INTEGER,               -- Unix seconds
    is_out INTEGER DEFAULT 0,
    mesh_msg_id INTEGER DEFAULT 0, -- deterministic token/random_id for ACK matching
    status INTEGER DEFAULT 0,      -- 0=Pending, 1=Sent to LoRa, 2=Delivered, 3=Failed
    snr REAL DEFAULT 0,            -- telemetry collected on arrival
    hops INTEGER DEFAULT 0         -- telemetry collected on arrival
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_dedup ON messages(dialog_id, mesh_msg_id) WHERE mesh_msg_id != 0;

-- LoRa channel slots (per MeshCore spec, slots 0-7)
CREATE TABLE lora_channels (
    slot_index INTEGER PRIMARY KEY, -- 0..7
    name TEXT NOT NULL DEFAULT '',
    secret_hex TEXT,                -- 16-byte hex; NULL only during migration
    is_public INTEGER DEFAULT 0     -- 1 for slot 0 / hashtag channels
);

-- BLE pairing PINs per device address
CREATE TABLE device_pins (
    address TEXT PRIMARY KEY,
    pin INTEGER
);
```

**Telemetry Retrieval Methods (v5)**:
- `getLastMessageSnr(long dialogId)`: Queries the `messages` table for the most recent message's SNR value.
- `getLastMessageHops(long dialogId)`: Queries the `messages` table for the most recent message's hop count.

**Channel constants**:
- **Public channel key** (slot 0): `8b3387e9c5cdea6ac9e5edbaa115cd72` (official MeshCore)
- **Hashtag channel key**: first 16 bytes of `SHA256("#channelname")`
- **Private channel**: CSPRNG 16-byte secret

**Synthetic Dialog IDs** (negative longs, no collision with Telegram IDs):
- Channel slot N: `-(2_000_000_000 + N)`
- Contact (pubkey-derived): `-(3_000_000_000 + abs(pubkey.hashCode()) % 1_000_000_000)`

---

### `MeshTransportManager` (`org.telegram.messenger.mesh`)
**Role**: Business logic coordinator. Implements `MeshManagerListener`.

**Responsibilities**:
- User-facing Mesh state: enabled/disabled, selected BLE device, radio config (persisted via SharedPreferences `mesh_config`)
- On `onSelfInfoLoaded`: sync radio config from device to local prefs
- On `onChannelLoaded`: notify `NotificationCenter.didMeshChannelsUpdated`
- On `onChannelMessage(slot, ...)`: persist via `MeshStorage.saveChannelMessage()`, post `NC.didReceiveMeshChannelMessage`
- On `onContactMessage(pubkey, ...)`:
  - If pubkey is TG-linked → TODO Phase 2: inject into real Telegram chat
  - Always: persist via `MeshStorage.saveContactMessage()`, post `NC.didReceiveMeshContactMessage`
- Radio presets: 18 Russian city presets + user-defined custom presets (JSON in SharedPreferences)

---

### `MeshProtocol` (`org.telegram.messenger.mesh`)
**Role**: Legacy framing constants (kept for reference). Binary constants only; no routing logic.

> **Note**: Active message parsing now happens entirely in `MeshManager.processResponse()` per companion_protocol.md. `MeshProtocol.Packet.deserialize()` is no longer used.

---

## 4. NotificationCenter Events

| Event | Args | Description |
|-------|------|-------------|
| `didUpdateMeshNodes` | — | Node list changed (RSSI, hops, new node) |
| `didRequestMeshPairing` | `String address, int pin` | BLE bonding PIN required |
| `didReceiveMeshChannelMessage` | `int channelIndex, String text, String channelName` | New channel message |
| `didReceiveMeshContactMessage` | `String pubKeyHex, String text` | New contact DM |
| `didMeshChannelsUpdated` | — | Channel slot list changed |

---

## 5. Hybrid Chat Logic & UI Integration

### 5.1 Synthetic ID Routing
The `MessagesController` intercepts `loadMessagesInternal()` for dialog IDs < -2,000,000,000. It redirects history fetching to `MeshStorage.getMessages()`, wrapping the results into standard `MessageObject` instances with `isMesh = true` and storing them in the consistently named `dialogMessages` plural field. This standardized naming convention ensures seamless coexistence with Telegram chats and prevents compilation mismatches across the codebase.

### 5.2 Message Delivery Lifecycle (3 Stages)
We map the `status` column from `MeshStorage` to standard Telegram status icons in `ChatMessageCell.createStatusDrawableParams()`:
- **0 (Pending)**: Renders the **Clock** icon. Message is waiting for BLE connection or queue flush.
- **1 (Sent to LoRa)**: Renders a **Single Check**. The LoRa device has accepted the packet for radio transmission.
- **2 (Delivered)**: Renders **Double Checks**. A delivery ACK was received from the mesh network.
- **3 (Failed)**: Red exclamation/error state.

### 5.3 Deterministic ACK Matching
To match hardware ACKs to database records, `MeshManager` generates a 4-byte token derived from the command packet (e.g., `[CMD] [0x00] [Slot] [TS_Low]`). This token is stored as `mesh_msg_id` and matched against the `PACKET_ACK` payload (echo of the first 4 bytes of the command).

### 5.4 UI Telemetry
- **DialogCell**: Renders an orange telemetry line below the last message (SNR: {x} | Path: {y} hops).
- **ChatMessageCell**: Appends ` | Mesh H{n}` to the timestamp footer in chat bubbles.
- **Error Handling**: `SendMessagesHelper` shows a `Bulletin` notification if sending is attempted while the BLE device is disconnected, confirming the message is queued.

---

### 6. Mesh Folders (Virtual Dialog Folders)
- **Mesh Channels (1493)**: Populated via `MeshStorage.getLoraChannels()`.
  - Slot 0 (Public) is always shown.
  - Slots 1-7 are shown only if they have a non-empty, user-defined name.
- **Mesh Contacts (1494)**: Populated via `MeshStorage.getMeshContacts()`.
  - Shows all discovered LoRa nodes as virtual "contacts".
- **Action Redirection**: The Floating Action Button (FAB) in these folders is redirected to `MeshSettingsActivity` for node/channel management.
- Each active LoRa channel slot → one synthetic \"chat\" entry
- Each known unlinked contact → one synthetic \"DM\" entry
- Filter type: `DialogFilter` with `isMesh = true`

---

### 7. Security
- **BLE Authentication**: Standard Bluetooth LE Secure Connections (OOB, Numeric Comparison, or Passkey Entry). The app listens for `ACTION_PAIRING_REQUEST`. 
  - Dynamic passkey requests (where the MeshCore OLED shows a 6-digit PIN) display the native Android system prompt for user input.
  - Consent/Numeric Comparison flows are auto-confirmed.
- **LoRa channel encryption**: MeshCore firmware handles payload encryption using the 16-byte channel secret
- **Key storage**: Channel secrets stored in `lora_channels.secret_hex`; never logged

---

### 8. Threading Model
| Thread | Responsibility |
|--------|----------------|
| Main (UI) | All listener callbacks, NotificationCenter posts, UI updates |
| GATT callback thread | BLE read/write; immediately queued via handler |
| `MeshStorageQueue` | All SQLite writes and reads that could block |

**Rule**: Never perform SQLite reads/writes on the GATT callback or main thread directly.
**Stability**: `MeshManager` uses `CopyOnWriteArrayList` for internal collections (listeners, discovered devices) to ensure thread-safety during asynchronous GATT events.

---

### 9. Message Encoding (UCF) [v13]
To maximize character capacity for Cyrillic text over LoRa (which has a strict ~133-byte packet limit), the client implements **UCF (Unicode Cyrillic Fast)** encoding:
- **Identifier**: `txt_type = 0x14`.
- **Logic**: 
  - Standard UTF-8 is used if the message fits within 133 bytes.
  - UCF is used for longer messages if they only contain ASCII (0x00-0x7F) and Cyrillic (0x0400-0x047F) characters.
  - UCF maps Cyrillic characters to a single byte (0x80-0xFF), effectively doubling capacity from 66 to 120-130 characters.
- **UI Constraints**: A global limit of **120 characters** is enforced in the `ChatActivityEnterView` for all Mesh dialogs to guarantee delivery in a single LoRa packet.

---

## 10. Change History

| Date | Version | Change |
|------|---------|--------|
| 2026-04-06 | v1 | Initial MeshCore integration (scanning, connection) |
| 2026-04-07 | v2 | BLE stability fixes: WriteQueue, MTU 512, GATT_ERROR handling |
| 2026-04-08 | v3 | MeshStorage v2: device_pins table; radio config persistence |
| 2026-04-09 | v4 | MeshForegroundService; MeshSettingsActivity; channel presets |
| 2026-04-10 | **v5** | **Protocol alignment phase 1**: typed callbacks, DB v3, lora_channels, public channel key seeding |
| 2026-04-10 | **v6** | **Protocol alignment phase 2**: CMD_SET_RADIO_PARAMS(0x0B), CMD_SEND_TXT_MSG(0x02) packet mapping, and BLE Passkey Entry native UI fixes. |
| 2026-04-10 | **v7** | **Connection Hardening & DM Routing**: Transitioned to direct-connect peer mapping, bypass auto-scan, restricted allowed BLE device names, and replaced channel 0 stub with true sendContactMessage logic. |
| 2026-04-13 | **v8** | **Final Stabilization**: Implemented BOND_STATE_CHANGED handshake to fix GATT_ERROR 133, added UI telemetry (SNR/Hops) in chat bubbles, and performed final log pruning. |
| 2026-04-13 | **v9** | **UI & Lifecycle Completion**: Implemented 3-stage delivery status (Pending/Sent/Delivered), synthetic history interception in `MessagesController`, deterministic ACK tokens, and network-unreachable error UI. |
| 2026-04-13 | **v10** | **High-Fidelity UI & Folder Cleanup**: Fixed binary name corruption (null-terminator parser), refined Channels/Contacts folder filtering, and implemented contextual FAB actions. |
| 2026-04-13 | **v11** | **Real-time Discovery (ADVERT)**: Implemented 0x80 packet parsing for asynchronous node announcements, enabling automatic directory updates without manual sync. |
| 2026-04-14 | **v12** | **Build Stabilization & Hardening**: Fixed JNI configurations, resolved naming conflicts in `MessagesController`, and implemented thread-safe collections in `MeshManager`. |
| 2026-04-14 | **v13** | **Global Standardization**: Systematically renamed `dialogMessage` to `dialogMessages` across the entire project (Storage, Controllers, UI) to eliminate build mismatches. |
| 2026-04-14 | **v14** | **UI/UX Finalization**: Implemented 'Manual Add Node', stabilized multi-line input expansion, redesigned tech-footer for chat bubbles, and pruned redundant folder menus. |
| 2026-04-14 | **v15** | **Rebranding & CI/CD Restoration**: Finalized app renaming to **Telegram-XLora** across all localizations and fixed critical build failure in `DialogCell.java`. |
| 2026-04-14 | **v16** | **Telemetry Integration & Structural Repair**: Resolved structural brace imbalances in `DialogCell.java` and `MeshStorage.java`, integrated SNR/Hops list rendering. |
| 2026-04-15 | **v17** | **CI Compile Fix & Workflow Simplification**: Fixed Mesh avatar scope bug in `DialogCell.java` and removed unused NDK r23c setup from GitHub Actions. |
| 2026-04-15 | **v18** | **Security Hardening**: Disabled cleartext traffic, restricted providers/FileProvider paths, and removed sensitive token/key logging. |


---

## 10. Project Rules (from AGENTS.md)
- **Language**: Russian for chat, English for code/git/docs
- **Build target**: Android 16, Target SDK 35
- **Hardware**: Heltec T114, LilyGO running MeshCore firmware ≥ v1.12.0
## 11. Maintenance Touchpoints (Integrated Hooks)

To ensure smooth upgrades when the upstream Telegram project changes, the following files must be verified as they contain critical XLora integration "hooks":

### Core Logic Hooks
- **`TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java`**:
  - Intercepts `loadMessagesInternal()` for dialogs < -2B.
  - Implements synthetic dialog filtering in `getMeshDialogs()`.
  - **Standard**: Uses `dialogMessages` (plural).

- **`TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java`**:
  - Intercepts `sendMessage()` to route data through `MeshManager` when the destination is a synthetic dialog.

- **`TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`**:
  - Initializes `MeshManager.getInstance()` on app start.
  - Manages the lifecycle of `MeshForegroundService`.

### UI & Presentation Hooks
- **`TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java`**:
  - Customizes folder rendering for "Mesh Channels" and "Mesh Contacts".
  - Redirects FAB clicks to `MeshSettingsActivity`.

- **`TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java`**:
  - Renders custom status icons for LoRa message delivery (Stage 0-3).
  - Appends SNR and Hops telemetry to message bubbles.

- **`TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java`**:
  - Renders the Mesh radio icon and telemetry preview in the chat list.

### Internal Module Hooks
- **`TMessagesProj/src/main/java/org/telegram/messenger/mesh/*`**:
  - **Standalone Core**: Keep this directory as decoupled as possible from the Rest of Telegram to facilitate updates.
