# Telegram-XLora Architecture & MeshCore Integration

> Last updated: 2026-04-13 — Protocol alignment v1.12.0+ | Final Stabilization v8

## 1. Overview
Telegram-XLora is a custom Android client based on Forkgram that integrates **MeshCore LoRa** networking via Bluetooth LE. Users can communicate without internet using BLE-connected LoRa hardware (Heltec T114, LilyGO, etc.).

**Protocol Reference**: [MeshCore companion_protocol.md v1.12.0+](https://github.com/meshcore-dev/MeshCore/blob/main/docs/companion_protocol.md)

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

-- Message history (channel + DM)
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dialog_id INTEGER,          -- synthetic negative long
    sender_pubkey TEXT,         -- pubkey prefix (empty = outgoing)
    text TEXT,
    date INTEGER,               -- Unix seconds
    is_out INTEGER DEFAULT 0
);

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

## 5. Hybrid Chat Logic (Phase 2 — Planned)
- **Identity Linking**: Users link a Mesh pubkey to a Telegram User ID via `MeshStorage.linkNodeToUser()`
- **Metadata Persistence**: Mesh-specific data (hops, SNR) will be embedded in `TLRPC.Message.custom_params` using magic `0x4D455348` ("MESH")
- **UI Rendering**: `ChatMessageCell` detects magic header and renders technical aesthetic (Neon Green, corner markers) + "via Mesh" indicator + hop count & SNR telemetry footer.

---

## 6. Mesh Folder (Virtual Dialog Folder)
- `MessagesController.checkMeshFilter()` populates a virtual folder with synthetic dialogs
- Each active LoRa channel slot → one synthetic "chat" entry
- Each known unlinked contact → one synthetic "DM" entry
- Filter type: `DialogFilter` with `isMesh = true`

---

## 7. Security
- **BLE Authentication**: Standard Bluetooth LE Secure Connections (OOB, Numeric Comparison, or Passkey Entry). The app listens for `ACTION_PAIRING_REQUEST`. 
  - Dynamic passkey requests (where the MeshCore OLED shows a 6-digit PIN) display the native Android system prompt for user input.
  - Consent/Numeric Comparison flows are auto-confirmed.
- **LoRa channel encryption**: MeshCore firmware handles payload encryption using the 16-byte channel secret
- **Key storage**: Channel secrets stored in `lora_channels.secret_hex`; never logged

---

## 8. Threading Model
| Thread | Responsibility |
|--------|----------------|
| Main (UI) | All listener callbacks, NotificationCenter posts, UI updates |
| GATT callback thread | BLE read/write; immediately queued via handler |
| `MeshStorageQueue` | All SQLite writes and reads that could block |

**Rule**: Never perform SQLite reads/writes on the GATT callback or main thread directly.

---

## 9. Change History

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

---

## 10. Project Rules (from AGENTS.md)
- **Language**: Russian for chat, English for code/git/docs
- **Build target**: Android 16, Target SDK 35
- **Hardware**: Heltec T114, LilyGO running MeshCore firmware ≥ v1.12.0
- **Zero Trust**: Specific dependency versions pinned; no hardcoded secrets
