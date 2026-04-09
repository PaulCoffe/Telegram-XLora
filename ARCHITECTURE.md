# Telegram-XLora Architecture & MeshCore Integration

## 1. Overview
Telegram-XLora is a custom Android client based on Forkgram that integrates **MeshCore LoRa** networking. It allows users to communicate without internet via Bluetooth-connected LoRa hardware (e.g., LilyGO T114).

## 2. Core Components

### MeshManager (`org.telegram.messenger.mesh`)
- **Role**: Hardware abstraction layer (BLE).
- **Functionality**: Handles Bluetooth scanning, GATT connection (Nordic UART Service), Pairing/PIN bonding, and raw packet fragmentation/de-fragmentation.
- **Listeners**: Supports multiple `MeshManagerListener` instances for decoupled UI and transport logic.

### MeshProtocol
- **Role**: Framing and Serialization.
- **Protocol**: Custom MeshCore binary protocol with header `[Version][Type][Flags]`, routing path (node hashes), and payload.
- **Types**: `TXT_MSG`, `GRP_TXT`, `ADVERT`, `ACK`, `REQ`, `RESPONSE`.

### MeshTransportManager
- **Role**: Intermediate routing and business logic.
- **Hybrid Routing**: Maps incoming Mesh packets to Telegram contacts using `MeshStorage`. 
- **Inbound Routing**: If a node is linked to a Telegram User ID, the message is injected into the standard Telegram dialog using `MessagesController.processLoadedMessages`.
- **Gateway Mode**: (Planned) Relaying Mesh messages to official Telegram servers when internet is available.

### MeshStorage
- **Role**: Persistent local database (`mesh_data.db`).
- **Tables**:
    - `nodes`: `pubkey` (PK), `nickname`, `tg_user_id` (FKey mapping), `last_seen`, `last_rssi`, `last_hops`.
    - `messages`: Message history for pure Mesh chats and metadata tracking.

## 3. Hybrid Chat Logic
The "Hybrid" feature allows a seamless communication channel:
- **Identity Linking**: Users manually link a discovered Mesh Node ID to a Telegram Contact.
- **Metadata Persistence**: Mesh-specific data (Hops, RSSI) is stored in Telegram's `TLRPC.Message.custom_params` blob using a magic magic header (`0x4D455348`).
- **UI Rendering**: `ChatMessageCell` detects the magic header in `MessageObject` and draws the "Mesh" indicator and hop count.

## 4. UI/UX Features
- **Mesh Folder**: Custom dialog filter for Mesh-related conversations.
- **Node Discoverability**: A list in Settings -> MeshCore showing all nearby LoRa nodes.
- **Status Indicators**: "via Mesh" indicator in chat bubbles.

## 5. Security & Encryption
- **Transport**: Standard Bluetooth Security (Bonding/PIN).
- **Payload**: MeshCore payload encryption (implemented in `MeshManager.encrypt/decrypt`).

## 6. Project Rules (AGENTS.md)
- **Language**: Russian for chat, English for code/git.
- **Infrastructure**: Non-root Docker (planned for backend components), Healthchecks (planned).
- **Zero Trust**: Specific versions for dependencies.
