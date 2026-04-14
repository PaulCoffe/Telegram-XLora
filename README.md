# 🛰 Telegram-XLora: The Hybrid Mesh Messenger

**Telegram-XLora** is the next generation of resilient communication. It combines the power of the **Telegram** cloud with the decentralization of **LoRa Mesh** networking, ensuring you stay connected even when the world goes offline.

Built on the high-performance **Forkgram** core and integrated with the **MeshCore** protocol, this client transforms your smartphone into a tactical communication hub.

---

## 💎 Why Telegram-XLora?

| Feature | Description |
| :--- | :--- |
| **🌐 Hybrid Connectivity** | Seamlessly switch between standard Telegram (Internet) and LoRa Radio (No Internet). |
| **🔗 Node-to-User Linking** | Link physical LoRa nodes to your Telegram contacts. Messages arrive in the standard chat window. |
| **📡 Tactical Telemetry** | real-time SNR, RSSI, and Hop counts displayed directly in message bubbles. |
| **⚡ UCF Core** | **Unicode Cyrillic Fast** encoding doubles your message capacity (up to 120 chars) for radio traffic. |
| **🟢 Smart Delivery** | 3-stage status indicators: **Pending (Clock)** ➔ **Sent to Radio (Check)** ➔ **Delivered (Double Check)**. |

---

## 🇷🇺 Основные возможности (Russian)

### 1. 📡 LoRa Mesh: Связь без границ
*   **Полная автономность**: Отправляйте сообщения через модули LilyGO T-Echo, Heltec T114 и др. без Wi-Fi и сотовой связи.
*   **Mesh-сети**: Ваши сообщения ретранслируются другими участниками сети, значительно увеличивая дальность покрытия.
*   **Приватные каналы**: 8 программируемых слотов с AES-256 шифрованием для группового общения.
*   **Прямые сообщения (DM)**: Безопасная связь «точка-точка» по уникальному Public Key.

### 2. 🧬 Гибридный интеллект
*   **Умная маршрутизация**: Если вы оффлайн, Telegram-XLora может автоматически перенаправить сообщение через Mesh-сеть.
*   **Единый интерфейс**: Радио-сообщения отображаются в привычных чатах с пометкой `📡 LoRa` и расширенной телеметрией.
*   **Оптимизация UCF**: Специальный алгоритм сжатия позволяет упаковать в один радио-пакет в 2 раза больше кириллических символов.

### 3. 🎨 Премиальный UI и UX
*   **Папки Mesh**: Отдельные вкладки для «Mesh Каналов» и «Mesh Контактов» для идеального порядка.
*   **Neon Aesthetics**: Современный технический стиль с индикаторами качества сигнала и количеством «прыжков» (Hops).
*   **Авто-коннект**: Интеллектуальное управление Bluetooth — приложение само найдет и подключит ваш трансивер.

---

## 🚀 Quick Start / Быстрый запуск

1.  **Hardware**: Возьмите любое устройство с поддержкой **MeshCore** (Heltec, LilyGO).
2.  **Pairing**: В настройках выберите основной регион (например, **Москва 868 МГц**) и подключитесь к устройству по BLE.
3.  **Chat**: Откройте вкладку **Mesh Channels**, выберите **Slot 0 (Public)** и отправьте свой первый "Hello World" в эфир!

---

## 🛠 Technical Excellence

*   **Platform**: Android 12+ (Target API 35).
*   **Engine**: Forkgram (v10+ Telegram Core) for maximum speed and privacy.
*   **Transport**: Hardened BLE stack with `BOND_BONDED` handshake logic and thread-safe execution.
*   **Persistence**: `MeshStorage v5` with deterministic token matching for hardware ACKs.

---

## 📦 Build & Support

**Building from source**:
Requires Android NDK (r23c+) and Gradle 8.0+.
```bash
./gradlew assembleAfatFd_v8aRelease
```

---

> [!IMPORTANT]
> **Telegram-XLora** is open-source software provided "as is". Use of LoRa features requires compatible hardware running **MeshCore** firmware v1.12.0 or higher.

> [!TIP]
> Use the **Moscow** preset for instant 868.731 MHz mesh network access in the RU region.

---
*Developed with 💚 for the Mesh Community.*