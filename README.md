# 🛰 Telegram-XLora

**Telegram-XLora** — это мощный пользовательский клиент Telegram для Android, объединяющий надежность официального приложения с уникальными возможностями связи через LoRa-сети (MeshCore). Этот проект создан для обеспечения приватности и автономности связи даже в условиях полного отсутствия интернета.

---

## 🇷🇺 Описание проекта (Russian)

### Обзор
Telegram-XLora базируется на стабильном форке **Forkgram**, дополняя его полноценным стеком протоколов **LoRa Mesh**. Проект позволяет использовать Android-смартфон в качестве терминала для общения в децентрализованных сетях MeshCore через Bluetooth-подключение к LoRa-модулям.

### Ключевые особенности Mesh-связи:
- **Автономность**: Отправка и получение сообщений через LoRa-радиоканал, когда мобильная сеть или Wi-Fi недоступны.
*   **Интеграция в интерфейс**: Выделенная папка (вкладка) **Mesh** в списке чатов для быстрого доступа к оффлайн-диалогам.
*   **MeshCore Protocol**: Полная поддержка протокола MeshCore для передачи текстовых сообщений.
*   **BLE Сканирование**: Быстрый поиск и подключение к MeshCore-устройствам через Bluetooth с контролем прав доступа и состояния GPS.

### Настройка радио (LoRa):
Приложение предоставляет полный контроль над аппаратными параметрами LoRa-модуля:
- **Частота**: Ручной ввод или использование пресетов.
- **Полоса пропускания (BW)**: От 7.8 кГц до 500 кГц.
- **Коэффициент расширения (SF)**: Настройка от SF7 до SF12.
- **Кодирование (CR)**: Выбор параметров коррекции ошибок.
- **Пресет «Москва»**: Быстрая настройка на оптимальные параметры для московского региона (**868.731 МГц**, 62.5 кГц, SF7, CR 4/7).

### Дополнительные возможности (база Forkgram):
- Удаление сообщений у всех участников по умолчанию.
- Отсутствие «плавающей» кнопки карандаша для чистоты интерфейса.
- Отображение оригинальной даты пересланных сообщений.
- Скрытая строка «Подключение к прокси...».
- Безлимитные закрепленные чаты (Pinned chats).
- Тонкая настройка приватности (скрытие аватаров, имен и т.д.).

---

## 🇺🇸 Project Overview (English)

**Telegram-XLora** is a high-performance custom Telegram client for Android that merges the reliability of the official app with unique LoRa Mesh (MeshCore) communication capabilities. Built for privacy and resilience, it ensures you stay connected even when the internet is down.

### Key Features:
- **LoRa Mesh Connectivity**: Seamlessly swap between Telegram servers and LoRa radio channels for messaging in offline environments.
- **Native UI Integration**: A dedicated **Mesh** folder/tab for managing hardware-based dialogues directly in your chat list.
- **MeshCore Protocol**: Full implementation of the MeshCore protocol for binary and text data transmission.
- **Advanced BLE Management**: Robust Bluetooth scanning and connection stability for MeshCore hardware modules.

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