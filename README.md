# 🛰 Telegram-XLora

**Telegram-XLora** — это инновационный кросс-платформенный клиент Telegram для Android, построенный на базе форка **Forkgram** и глубоко интегрированный с протоколом **MeshCore LoRa**. 

Проект превращает обычный мессенджер в мощный инструмент автономной связи, позволяя обмениваться сообщениями через децентрализованные радиосети в условиях отсутствия сотовой связи, интернета или при необходимости полной радиомолчания.

---

## 🇷🇺 Полный функционал приложения (Russian)

### 1. 🌐 MeshCore LoRa (Новое)
Telegram-XLora расширяет границы общения, добавляя поддержку LoRa-модулей (LilyGO T-Echo, Heltec T114 и др.) по Bluetooth.

*   **Автономная связь (Mesh-сети)**:
    *   Отправка и получение текстовых сообщений полностью без интернета.
    *   Работа в режиме Mesh: сообщения ретранслируются другими узлами, расширяя дальность покрытия.
    *   **Поддержка 8 каналов**: 8 независимых слотов для каналов (публичные — слот 0, и приватные с ключами шифрования).
    *   **Direct Messages (DMs)**: Личные сообщения напрямую конкретному пользователю по его уникальному публичному ключу (PubKey).

*   **Гибридный режим (Hybrid Messaging)**:
    *   **Привязка к контакту**: Вы можете привязать узел LoRa (PubKey) к существующему пользователю Telegram.
    *   **Единый чат**: Сообщения, пришедшие через радиоканал от привязанного контакта, отображаются прямо в его стандартном чате Telegram с пометкой `📡 LoRa`.
    *   **Оффлайн-очередь**: Если интернет появится, переписка останется доступной локально.

*   **Интерфейс и UI/UX**:
    *   **Папка Mesh**: Автоматически создаваемая вкладка в списке чатов, где собраны все ваши радио-диалоги.
    *   **Телеметрия в чате**: Каждое Mesh-сообщение отображает данные о качестве связи: количество прыжков (`Hops`) и уровень шума (`SNR`).
    *   **Статусы доставки**: Визуальная индикация «Отправка...», «Доставлено» и «Ошибка» для радио-сообщений.

*   **Управление оборудованием**:
    *   **Поиск (BLE Scanner)**: Умный поиск MeshCore-устройств с фильтрацией по ключевым словам.
    *   **Авто-подключение**: Приложение запоминает ваше устройство и автоматически восстанавливает соединение при запуске, не разряжая батарею лишним сканированием.
    *   **Настройка радио**: Полный контроль над параметрами:
        *   Частота (с поддержкой пресетов для городов РФ: Москва, Липецк, Бийск и др.).
        *   Bandwidth (BW), Spreading Factor (SF), Coding Rate (CR).
        *   Мониторинг заряда аккумулятора и памяти внешнего модуля.

### 2. ⚡ Улучшения Forkgram (База)
Telegram-XLora наследует лучшие функции Forkgram, делая использование Telegram более продуктивным и приватным.

*   **Приватность и безопасность**:
    *   **Удаление у всех по умолчанию**: При удалении сообщения галочка «Удалить у [имя]» включена автоматически.
    *   **Секретные функции**: Скрытие статуса «Подключение к прокси...» для чистого интерфейса.
    *   **Оригинальные даты**: При пересылке сообщений отображается реальное время их создания.
    *   **Скрытие аватаров и имен**: Расширенные настройки конфиденциальности в списке чатов.

*   **Продуктивность интерфейса**:
    *   **Безлимитные закрепленные чаты**: Закрепляйте столько диалогов, сколько вам нужно (в стандартном клиенте лимит — 5).
    *   **Чистый экран**: Удалена плавающая кнопка карандаша (FAB), освобождая место для контента.
    *   **Вкладки и категории**: Удобная навигация по папкам в нижней или верхней части экрана.
    *   **Улучшенный выбор медиа**: Быстрый доступ к галерее и файлам.

---

## 🇺🇸 Full Project Overview (English)

**Telegram-XLora** is an advanced custom Telegram client for Android. It combines the productivity of the **Forkgram** fork with deep **MeshCore LoRa** integration, allowing for resilient communication without internet.

### 1. 🌐 MeshCore LoRa Features
*   **Offline Connectivity**: Send and receive texts via LoRa radio modules (LilyGO, Heltec) over BLE.
*   **8-Slot Channel Management**: Support for public (Slot 0) and private encrypted channels.
*   **P2P Direct Messages**: Secure messaging to specific nodes using Public Key addressing.
*   **Hybrid Chat**: Link LoRa nodes to Telegram contacts. Messages received via radio appear in standard Telegram chats with a `📡 LoRa` prefix.
*   **Telemetry & Status**: See real-time message stats like Hops and SNR. Visual delivery indicators for all radio traffic.
*   **Hardware Control**: Fine-tune Spreading Factor, Bandwidth, and Frequency. Includes built-in regional presets.

### 2. ⚡ Forkgram Enhancements
*   **Enhanced Privacy**: "Delete for everyone" by default; original timestamps on forwarded messages; stealth proxy status.
*   **UI Productivity**: Unlimited pinned chats; removed floating action button for a cleaner UI; custom drawer and tab management.
*   **Core Improvements**: Faster media loading and advanced privacy settings inherited from the Forkgram core.

---

## 🛠 Технические требования / Technical Info

*   **Оборудование**: Модули LoRa (nRF52840, ESP32) с прошивкой **MeshCore** (v1.12.0+).
*   **Сборка**:
    *   Android SDK 35 (Target).
    *   NDK r23c (для нативных библиотек).
    *   Gradle 8.0+.
*   **Лицензия**: GNU GPL v2.

---

> [!IMPORTANT]
> Это программное обеспечение предоставляется «как есть». Для использования функций LoRa необходимо физическое устройство-трансивер, подключенное по Bluetooth.
luetooth scanning and connection stability for MeshCore hardware modules.

### Radio & Hardware Configuration:
Take full control over your LoRa hardware with integrated configuration tools:
- **Frequency Control**: Granular frequency settings with support for regional presets.
- **Fine-tuning**: Adjust Bandwidth (BW), Spreading Factor (SF), and Coding Rate (CR) to optimize range and speed.
- **"Moscow" Preset**: Instant one-tap configuration for the 868.731 MHz mesh network.

### Privacy & UI Enhancements (inherits from Forkgram):
- `Delete for everyone` enabled by default for all chats.
- Clean UI: Removed the floating pencil icon and streamlined the side drawer.
- Original timestamps for forwarded messages.
- Hidden "Connecting to proxy..." status for enhanced stealth.
- Unlimited pinned chats and enhanced avatar privacy options.

---

## 🛠 Техническая информация / Technical Info

### Сборка / Building
Проект собирается с помощью Gradle. Для успешной компиляции нативного кода (C++) требуется Android NDK (рекомендуется r21e/r23c).
```bash
./gradlew assembleRelease
```

### Лицензия / License
Проект распространяется по лицензии GNU GPL v2 (на базе исходного кода Telegram Android).

---

> [!IMPORTANT]
> Для работы LoRa-функций требуется совместимое оборудование (модули на базе nRF52/ESP32 с поддержкой LoRa) и прошивка MeshCore.