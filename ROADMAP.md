# Telegram-XLora — Project Roadmap

> Last updated: 2026-04-10
> Branch: `mesh-dev` | Protocol: MeshCore companion_protocol.md v1.12.0+

---

## Legend
- `[x]` Done
- `[~]` In progress / partial
- `[ ]` Not started
- `[!]` Blocker / needs decision

---

## Phase 1 — Foundation & Protocol ✅ (Complete)

- [x] Fork Forkgram / Telegram Android clean base
- [x] BLE scanning (NUS UUIDs: 6E400001...)
- [x] GATT connect → MTU 512 → enable notifications (descriptor 0x2902)
- [x] WriteQueue — serialized command execution, 5s timeout
- [x] CMD_APP_START (0x01) → parse PACKET_SELF_INFO (0x05)
- [x] CMD_DEVICE_QUERY (0x16 0x03) → parse PACKET_DEVICE_INFO (0x0D)
- [x] CMD_GET_CHANNEL (0x1F) → parse PACKET_CHANNEL_INFO (0x12) with 16-byte secret
- [x] CMD_SEND_CHANNEL_MSG (0x03) → parse PACKET_MSG_SENT (0x06)
- [x] CMD_GET_MSG (0x0A) → parse PACKET_CHANNEL_MSG_RECV (0x08) + V3 (0x11)
- [x] CMD_GET_MSG (0x0A) → parse PACKET_CONTACT_MSG_RECV (0x07) + V3 (0x10)
- [x] CMD_SET_CHANNEL (0x20) — create/update channel slots
- [x] CMD_GET_BATTERY (0x14) → parse PACKET_BATTERY (0x0C)
- [x] PACKET_ADVERTISEMENT (0x80) handling
- [x] PACKET_MESSAGES_WAITING (0x83) — push notification from device
- [x] Auto-reconnect with exponential backoff
- [x] GATT_ERROR (133/132) detection + retry logic
- [x] MeshForegroundService — keeps BLE alive in background
- [x] BLE PIN auto-confirm (stored in device_pins table)

---

## Phase 2 — Storage & Data Model ✅ (Complete)

- [x] MeshStorage DB v3 — `mesh_data.db`
- [x] `nodes` table — pubkey, nickname, tg_user_id, last_rssi, last_hops
- [x] `lora_channels` table — slot_index (0-7), name, secret_hex, is_public
- [x] `messages` table — dialog_id, sender_pubkey, text, date, is_out
- [x] `device_pins` table — address → PIN mapping
- [x] Seed slot 0 with official public key `8b3387e9c5cdea6ac9e5edbaa115cd72`
- [x] DB migration v2 → v3 (preserve existing data)
- [x] In-memory node cache (ConcurrentHashMap) — O(1) lookups
- [x] In-memory channel slot cache
- [x] Synthetic dialog IDs (channel: -2B-N, contact: -3B-hash)
- [x] Typed API: `saveChannelMessage()`, `saveContactMessage()`

---

## Phase 3 — Transport & Routing ✅ (Complete)

- [x] Typed `MeshManagerListener` interface (default methods)
- [x] `onSelfInfoLoaded` — sync radio config from device to SharedPrefs
- [x] `onChannelLoaded` — persist channel slot, notify UI
- [x] `onChannelMessage` → MeshStorage + NC.didReceiveMeshChannelMessage
- [x] `onContactMessage` → MeshStorage + NC.didReceiveMeshContactMessage
- [x] NotificationCenter events: `didReceiveMeshChannelMessage`, `didReceiveMeshContactMessage`, `didMeshChannelsUpdated`
- [x] MeshTransportManager — radio preset system (18 city presets + custom)

---

## Phase 4 — UI: Mesh Folder & Chat List 🔄 (Next)

### 4.1 Mesh Folder — Dialog List
- [ ] `MessagesController.checkMeshFilter()` — query `MeshStorage.getLoraChannels()` and inject synthetic dialogs
- [ ] DialogFilter with `isMesh = true` — shows only lora_channels + known contacts
- [ ] Unread badge on Mesh folder icon when new message arrives (subscribe to `NC.didReceiveMeshChannelMessage`)
- [ ] Channel entry shows: slot name, last message preview, timestamp
- [ ] Contact entry shows: pubkey prefix (first 6 hex), last message, RSSI

### 4.2 Mesh Channel Chat Screen
- [ ] Open synthetic dialog → show `MeshStorage.getMessages(dialogId, 50)`
- [ ] Message input bar → `MeshManager.sendChannelMessage(slotIndex, text)`
- [ ] Message length guard: warn at 133 chars (MeshCore spec limit)
- [ ] Auto-split long messages: `[1/N] ... [N/N]` chunks
- [ ] "via Mesh" badge on each bubble (SNR + hops indicator)
- [ ] Incoming messages realtime via `NC.didReceiveMeshChannelMessage`

### 4.3 Mesh Contact DM Screen
- [ ] Open synthetic contact dialog → show `MeshStorage.getMessages(contactDialogId, 50)`
- [ ] Direct message send (contact pubkey known → CMD 0x02 or 0x03 DM command per protocol)
- [ ] Incoming messages via `NC.didReceiveMeshContactMessage`

---

## Phase 5 — Settings & Channel Management

### 5.1 MeshSettingsActivity improvements
- [ ] Show connected device name + model (from PACKET_DEVICE_INFO)
- [ ] Show battery % + storage KB/total (from PACKET_BATTERY)
- [ ] Show device public key (first 12 hex chars)
- [ ] Show firmware version + build string
- [ ] "Disconnect" button

### 5.2 Channel Slot Manager
- [ ] List all 8 slots → name, type (public/hashtag/private), used/empty
- [ ] Create private channel: generate CSPRNG 16-byte secret → `sendSetChannel(idx, name, secret)`
- [ ] Create hashtag channel: `SHA256("#name")[0:16]` → `sendSetChannel(idx, name, key)`
- [ ] Delete channel: `sendSetChannel(idx, "", zeroes)` 
- [ ] Export channel secret (QR code for sharing with contacts)
- [ ] Import channel secret (scan QR)

### 5.3 Radio Config UI
- [x] Preset selection (18 city presets + custom)
- [ ] Manual config sliders (freq, BW, SF, CR) with validation
- [ ] "Apply" sends `CMD_RADIO_CONFIG` and confirms round-trip

---

## Phase 6 — Hybrid Mode (TG ↔ LoRa)

- [ ] Node linking UI: tap a Mesh node → "Link to Telegram contact"
- [ ] `MeshStorage.linkNodeToUser(pubkey, tgUserId)` — persist mapping
- [ ] On `onContactMessage`: if TG-linked → inject into real Telegram chat via `processLoadedMessages`
- [ ] Embed Mesh metadata in `TLRPC.Message.custom_params` (magic `0x4D455348` + hops)
- [ ] `ChatMessageCell` renders "via Mesh 🌐" badge + hop count for linked messages
- [ ] Outgoing TG message → relay to LoRa if contact is Mesh-linked and internet unavailable

---

## Phase 7 — Reliability & Edge Cases

- [ ] Message deduplication (device may resend on reconnect)
- [ ] PACKET_ACK (0x82) handling — mark sent message as delivered
- [ ] Offline message queue — buffer outgoing messages when BLE disconnected
- [ ] Message sync on reconnect: poll `CMD_GET_MSG` until `PACKET_NO_MORE_MSGS`
- [ ] Handle PACKET_ERROR (0x01) codes 0x00-0x09 with user-friendly alerts
- [ ] Anti-loop: 3 consecutive GATT_ERROR → switch bond mode + notify user
- [ ] Private channel secret backup (encrypted SharedPreferences export)

---

## Phase 8 — QA & Build

- [ ] End-to-end test on Heltec T114 (fw ≥ 1.12.0):
  - [ ] Handshake (SELF_INFO roundtrip)
  - [ ] Channel list load (all 8 slots)
  - [ ] Send message → PACKET_MSG_SENT
  - [ ] Receive message (channel + DM)
  - [ ] Reconnect after 30s idle
- [ ] Logcat validation — no GATT_ERROR spam, no ANR
- [ ] GitHub Actions: stable `assembleDebug` on `mesh-dev`
- [ ] APK smoke test on physical device (Android 13+)

---

## Backlog / Future Ideas

- [ ] Location beaconing — broadcast GPS via MeshCore ADVERT packets
- [ ] Group DM (private channel per group)
- [ ] Web companion app (meshcore.js integration)
- [ ] Encrypted message storage (SQLCipher for mesh_data.db)
- [ ] Multi-device support (multiple BLE devices connected simultaneously)
- [ ] Push relay: when internet available, re-send received LoRa msgs to TG cloud

---

## Current Blockers

| # | Blocker | Owner | Status |
|---|---------|-------|--------|
| 1 | `MessagesController.checkMeshFilter()` stub — Mesh folder not populating | Dev | **Next task** |
| 2 | GitHub Actions build — awaiting result | CI | ⏳ In progress |
| 3 | No physical device for end-to-end test | QA | Waiting |
