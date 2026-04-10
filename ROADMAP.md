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
- [x] PACKET_ACK (0x82) — parse token + log
- [x] Auto-reconnect with exponential backoff
- [x] GATT_ERROR (133/132) detection + tryClearBond() anti-loop
- [x] MeshForegroundService — keeps BLE alive in background
- [x] BLE PIN auto-confirm (stored in device_pins table)

---

## Phase 2 — Storage & Data Model ✅ (Complete)

- [x] MeshStorage DB v4 — `mesh_data.db`
- [x] `nodes` table — pubkey, nickname, tg_user_id, last_rssi, last_hops
- [x] `lora_channels` table — slot_index (0-7), name, secret_hex, is_public
- [x] `messages` table — dialog_id, sender_pubkey, text, date, is_out, mesh_msg_id
- [x] `device_pins` table — address → PIN mapping
- [x] Seed slot 0 with official public key `8b3387e9c5cdea6ac9e5edbaa115cd72`
- [x] DB migration v2 → v3 → v4 (preserve existing data)
- [x] In-memory node cache (ConcurrentHashMap) — O(1) lookups
- [x] In-memory channel slot cache
- [x] Synthetic dialog IDs (channel: `channelDialogId(slot)` → -(2B+slot), contact: -(3B+hash))
- [x] Typed API: `saveChannelMessage()`, `saveContactMessage()` with dedup overloads
- [x] Deduplication: UNIQUE INDEX on (dialog_id, mesh_msg_id) + 5s fuzzy fallback

---

## Phase 3 — Transport & Routing ✅ (Complete)

- [x] Typed `MeshManagerListener` interface (default methods)
- [x] `onSelfInfoLoaded` — sync radio config from device to SharedPrefs
- [x] `onChannelLoaded` — persist channel slot, notify UI
- [x] `onChannelMessage` → MeshStorage + NC.didReceiveMeshChannelMessage
- [x] `onContactMessage` → MeshStorage + NC.didReceiveMeshContactMessage
- [x] NotificationCenter events: `didReceiveMeshChannelMessage`, `didReceiveMeshContactMessage`, `didMeshChannelsUpdated`, `didUpdateMeshNodes`
- [x] MeshTransportManager — radio preset system (18 city presets + custom, persist to SharedPrefs)
- [x] Offline outgoing queue (`pendingOutbox`) — buffer messages when BLE disconnected
- [x] `flushPendingOutbox()` — drain queue immediately after handshake completes

---

## Phase 4 — UI: Mesh Folder & Chat ✅ (Complete)

- [x] `MessagesController.getMeshDialogs()` / `checkMeshFilter()` — queries `MeshStorage.getLoraChannels()`, injects synthetic dialogs
- [x] `DialogsActivity.getDialogsArray()` — Mesh filter branch renders `LoraChannel` list via `channelDialogId()`
- [x] `MeshChatActivity.java` — universal chat screen (channel + DM modes)
- [x] `MeshChannelManagerActivity.java` — channel slot manager (list 0-7, create, delete)
- [x] `MeshSettingsActivity` — device info (name, pubkey, battery, fw) + Disconnect button
- [x] `MeshSettingsActivity` — Node Options dialog (link contact to TG user)
- [ ] Unread badge on Mesh folder icon

---

## Phase 5 — Settings ✅ (Complete)

- [x] Preset selection (18 city presets + custom save/delete/rename)
- [x] Manual radio config — frequency, BW, SF, CR
- [x] `CMD_RADIO_CONFIG` applied to device on change
- [ ] Manual config validation sliders with min/max guards
- [ ] Channel secret export/import via QR code

---

## Phase 6 — Hybrid Mode (TG ↔ LoRa) ✅ (Complete)

- [x] `MeshStorage.linkNodeToUser(pubkey, tgUserId)` — persist mapping
- [x] `MeshTransportManager.onContactMessage` — inject into real TG chat via `updateInterfaceWithMessages` (prefixed "📡 LoRa:")
- [x] `SendMessagesHelper` hook — relay outgoing TG message to LoRa ch.0 when offline
- [ ] "via Mesh" badge in ChatMessageCell (hop count + SNR)
- [ ] Outgoing TG message → route to linked LoRa contact (direct DM, not channel broadcast)

---

## Phase 7 — Reliability ✅ (Complete)

- [x] Message deduplication — UNIQUE INDEX (mesh_msg_id) + 5s fuzzy fallback
- [x] PACKET_ACK (0x82) handler — parse token + log
- [x] Offline outgoing queue — buffer when BLE disconnected, flush on reconnect
- [x] Sync on reconnect — poll CMD_GET_MSG until PACKET_NO_MORE_MSGS
- [x] Anti-loop: 3 GATT_ERROR → `tryClearBond()` + 8s delay + backoff reset
- [x] MeshForegroundService — prevents BLE service from being killed in background

---

## Phase 8 — Audit & Final Cleanup ✅ (Complete)

- [x] Removed `MeshProtocol.java` (legacy, unused)
- [x] Removed `MeshFragmenter.java` (legacy, replaced by inline MTU chunking in MeshManager)
- [x] Migrated `DialogsActivity` from deprecated `MeshChannel/getChannels()` to `LoraChannel/getLoraChannels()/channelDialogId()`
- [x] Migrated `SendMessagesHelper` from `sendData(byte[])` to `sendChannelMessage(0, text)` directly
- [x] Updated HISTORY.md, ARCHITECTURE.md, ROADMAP.md
- [x] Clean git commit on `mesh-dev` branch

---

## Phase 9 — QA & Hardware Testing

- [ ] End-to-end test on Heltec T114 (fw ≥ 1.12.0):
  - [ ] Handshake (SELF_INFO roundtrip)
  - [ ] Channel list load (all 8 slots)
  - [ ] Send message → PACKET_MSG_SENT + ACK
  - [ ] Receive message (channel + DM)
  - [ ] Offline queue: send while disconnected, flush on reconnect
  - [ ] Reconnect after 30s idle
- [ ] Logcat validation — no GATT_ERROR spam, no ANR
- [ ] Unread badge on Mesh folder icon
- [ ] "via Mesh" badge in message bubbles (SNR + hops)

---

## Backlog / Future Ideas

- [ ] Location beaconing — broadcast GPS via MeshCore ADVERT packets
- [ ] Group DM (private channel per group)
- [ ] Encrypted message storage (SQLCipher for mesh_data.db)
- [ ] Multi-device support (multiple BLE devices connected simultaneously)
- [ ] Push relay: when internet available, re-send received LoRa msgs to TG cloud
- [ ] Channel secret export/import via QR code

---

## Current Status

| Component | Status |
|-----------|--------|
| BLE / GATT stack | ✅ Stable |
| MeshStorage v4 | ✅ Stable |
| MeshTransportManager | ✅ Stable |
| Virtual Mesh folder (UI) | ✅ Working |
| Hybrid mode (TG ↔ LoRa) | ✅ Working |
| Offline queue | ✅ Working |
| CI Build (`mesh-dev`) | ✅ Triggered |
| Hardware QA (Heltec T114) | ⏳ Pending |
