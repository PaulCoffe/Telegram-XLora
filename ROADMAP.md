# Telegram-XLora — Project Roadmap

> Last updated: 2026-04-14
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

- [x] End-to-end test on Heltec T114 (fw ≥ 1.12.0):
  - [x] Handshake (SELF_INFO roundtrip)
  - [x] Channel list load (all 8 slots)
  - [x] Send message → PACKET_MSG_SENT + ACK
  - [x] Receive message (channel + DM)
  - [x] Offline queue: send while disconnected, flush on reconnect
  - [x] Reconnect after 30s idle
- [x] Logcat validation — no GATT_ERROR spam, no ANR
- [x] Unread badge on Mesh folder icon
- [x] \"via Mesh\" badge in message bubbles (SNR + hops)
- [x] 3-stage delivery status (Clock / Single Check / Double Check)

---

## Backlog / Future Ideas

- [ ] Location beaconing — broadcast GPS via MeshCore ADVERT packets
- [ ] Group DM (private channel per group)
- [ ] Encrypted message storage (SQLCipher for mesh_data.db)
- [ ] Multi-device support (multiple BLE devices connected simultaneously)
- [ ] Push relay: when internet available, re-send received LoRa msgs to TG cloud
- [ ] Channel secret export/import via QR code

---

## Phase 10 — UX Purification & Purity ✅ (Complete)

- [x] Fixed binary name corruption (null-terminator parser in `MeshManager`)
- [x] Refined folder filtering: `Mesh Channels` (Public only by default)
- [x] Refined folder filtering: `Mesh Contacts` (Discovered nodes only)
- [x] Redirected folder FAB click to `MeshSettingsActivity`
- [x] Synchronization of project documentation (Architecture, Roadmap, History)

---

## Phase 11 — Real-time Node Discovery (ADVERT 0x80) ✅ (Complete)

- [x] Implemented `parseAdvertisement(0x80)` in `MeshManager.java`
- [x] Extracted RSSI/Hops telemetry from asynchronous broadcast packets
- [x] Automated directory updates for new nodes appearing in the air
- [x] Result: Immediate appearance of newly heard nodes in the "Mesh Contacts" folder.

---

## Phase 12 — Build Reliability & Thread-Safety ✅ (Complete)

- [x] Restored correct `jniLibs` configuration in Gradle
- [x] Implemented `packagingOptions` to exclude CVS metadata and fix APK merge conflicts
- [x] Normalized `dialogMessages` (plural) naming across `MessagesController` and `TranslateController`
- [x] Migrated `MeshManager` to `CopyOnWriteArrayList` for thread-safe listener/device management
- [x] Hardened GATT callbacks with exhaustive null-safety

---

## Phase 13 — UCF Encoding & Message Capacity Expansion ✅ (Complete)

- [x] Implemented **UCF (Unicode Cyrillic Fast)** encoding in `MeshManager.java`
- [x] Doubled Cyrillic message capacity (70 -> 120-130 characters) using 1-byte mapping
- [x] Implemented smart fallback: UTF-8 for small messages, UCF for long Cyrillic
- [x] Enforced hard 120-symbol limit in `ChatActivityEnterView` for Mesh dialogs
- [x] Standardized `dialogMessages` naming across `MessagesController` and `TranslateController` to fix build failures

---

## Phase 14 — Global Naming Standardization & Final Build Stabilization ✅ (Complete)

- [x] Systematically renamed singular `dialogMessage` to `dialogMessages` in all storage and controller references.
- [x] Updated method signatures in `MessagesStorage` for consistency.
- [x] Resolved internal naming mismatches in `MessagesController` (`resetDialogs` / `processLoadedDialogs`).
- [x] Verified zero remaining singular field references via global static analysis.
- [x] Stabilized CI/CD pipeline on `mesh-dev` branch.


---

## Phase 15 — Maintenance Protocol & Documentation Enforcement ✅ (Complete)

- [x] Established mandatory documentation sync rule in `AGENTS.md` (v7.4)
- [x] Created `MAINTENANCE.md` guide for long-term project support
- [x] Identified and documented integration "Hooks" in `ARCHITECTURE.md`
- [x] Institutionalized documentation updates before every commit

---

## Phase 16 — UI/UX Finalization & Node Management ✅ (Complete)

- [x] **Input UX Stabilization**: Enabled fluid multi-line input expansion and enforced 120-symbol limit.
- [x] **Bubble Redesign**: Dedicated telemetry footer (Hops/SNR) with tech-aesthetic styling; corrected message alignment.
- [x] **Node Management**: Implemented "Set Nickname" and "Link to TG Contact" workflow in settings.
- [x] **Manual Node Discovery**: Added "Добавить узел вручную" (Manual Add) button for out-of-band contact sharing.
- [x] **UI Purification**: Pruned redundant dot-menus in system-managed Mesh folders to reduce UI cognitive load.
- [x] **Persistence**: Real-time DB synchronization for node metadata (RSSI/Hops) from ADVERT packets.

---

## Phase 17 — Rebranding & CI/CD Restoration ✅ (Complete)

- [x] Fixed critical syntax error in `DialogCell.java` (misplaced method and braces)
- [x] Renamed `AppName` to **Telegram-XLora** in English (`values`)
- [x] Renamed `AppName` to **Telegram-XLora** in Russian (`values-ru`)
- [x] Renamed `AppName` to **Telegram-XLora** in Ukrainian (`values-uk`)
- [x] Renamed `AppName` to **Telegram-XLora** in German (`values-de`)
- [x] Renamed `AppName` to **Telegram-XLora** in Spanish (`values-es`)
- [x] Renamed `AppName` to **Telegram-XLora** in Italian (`values-it`)
- [x] Renamed `AppName` to **Telegram-XLora** in Portuguese (`values-pt-rBR`)
- [x] Renamed `AppName` to **Telegram-XLora** in Dutch (`values-nl`)
- [x] Triggered automated build on `mesh-dev`

---

## Phase 18 — Urgent Build Stabilization & Telemetry Integration ✅ (Complete)

- [x] Fixed structural brace imbalance in `DialogCell.java`
- [x] Resolved missing import for `MeshStorage` in `DialogCell.java`
- [x] Fixed syntax error (missing brace) in `MeshStorage.java`
- [x] Implemented telemetry helpers: `getLastMessageSnr()`, `getLastMessageHops()`
- [x] Fully integrated real-time SNR/Hops display in chat list (`DialogCell`)
- [x] CI compile fix: removed out-of-scope Mesh avatar reference in `DialogCell.java`
- [x] GitHub Actions hardening: removed unused NDK r23c setup (r21e only)
- [x] Security hardening: disable cleartext traffic, restrict providers and FileProvider paths, redact sensitive logs

---

## Phase 19 — Final UI/UX Purification & Consistency ✅ (Complete)

- [x] **Naming Fallback**: Implemented robust `Node-[short_pk]` resolution for empty nicknames in `DialogCell`.
- [x] **Avatar Logic**: Standardized Mesh contact avatars to initials/themed colors; removed random Telegram icons.
- [x] **Clean Telemetry**: Consolidated SNR/Hops display into `drawClockOrErrorLayout` with native Telegram styling.
- [x] **UI De-Cluttering**: Removed legacy "neon" UI artifacts and tech-aesthetic borders from chat bubbles.
- [x] **Adaptive Input**: Enforced 120-symbol limit and enabled fluid multi-line expansion (max 10 lines) for Mesh dialogs.
- [x] **Message Alignment**: Verified and enforced Right (Out) / Left (In) alignment for Mesh messages.

---


---

## Phase 23 — Mesh Navigation Re-engineering ✅ (Complete)

## Phase 24 — Telemetry Persistence & Delivery Hardening ✅ (Complete)

- [x] Implemented `tg_message_tracker` table in `MeshStorage` (DB v6).
- [x] Persistent delivery status recovery (ACK matching) after app restarts.
- [x] Standardized SNR/Hops telemetry rendering in chat list (`DialogCell`).
- [x] Unified and polished telemetry footer in message bubbles (`ChatMessageCell`).
- [x] Verified zero "stuck status" messages via persistent token recovery logic.

## Current Status (2026-04-15)

| Component | Status |
|-----------|--------|
| UI/UX Consistency | ✅ Finalized & Polished |
| Connectivity | ✅ Persistent Auto-Reconnect |
| Pairing UX | ✅ Automated PIN Entry |
| Mesh Navigation | ✅ Dedicated Bottom Tab (5 tabs) |
| Delivery Reliability| ✅ Persistent ACK Matching (DB v6) |
| CI Build (`mesh-dev`) | ✅ Stable |

