package org.telegram.messenger.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.BroadcastReceiver;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MeshManager handles BLE communication with MeshCore devices (Heltec T114, LilyGO etc.)
 * using the official MeshCore Companion Protocol v1.12.0+.
 *
 * Connection sequence (per spec):
 *   Connect → Discover Services → Request MTU(512) → Enable TX notifications (CCCD 0x2902)
 *   → CMD_APP_START → CMD_DEVICE_QUERY → CMD_SET_DEVICE_TIME → CMD_GET_CONTACTS → CMD_GET_CHANNEL (x8) → CMD_SYNC_NEXT_MESSAGE
 *
 * IMPORTANT: BLE commands are sent in PLAINTEXT. AES encryption is applied at the LoRa radio
 * layer by the device firmware — not at the BLE transport layer.
 */
public class MeshManager {
    private static final String TAG = "MeshManager";
    private static volatile MeshManager Instance;

    // ---- Nordic UART Service UUIDs (official MeshCore BLE transport) ----
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_CHAR_UUID      = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // App → Device (Write)
    private static final UUID TX_CHAR_UUID      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // Device → App (Notify)
    private static final UUID CCCD_UUID         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ---- MeshCore Companion Protocol commands (companion_protocol.md) ----
    // NOTE: These are BLE-layer commands sent PLAINTEXT to the device firmware.
    private static final byte CMD_APP_START        = 0x01; // was 0x04 — FIX: must be 0x01 per spec
    private static final byte CMD_GET_MSG          = 0x0A; // Sync next queued message
    private static final byte CMD_GET_BATTERY      = 0x14; // Battery + storage
    // 2-byte commands (sent as full byte arrays, not using the byte constant directly):
    //   CMD_DEVICE_QUERY  = { 0x16, 0x03 }
    //   CMD_SET_DEVICE_TIME_PREFIX = { 0x09, ... } (4 bytes timestamp)
    //   CMD_GET_CHANNEL   = { 0x1F, idx }
    //   CMD_SEND_CHANNEL_MSG starts with 0x03
    //
    // Send messages use a full-byte array builder (see buildSendChannelMessage).

    // ---- Response packet types (firmware → app) ----
    static final byte PACKET_OK                    = 0x00;
    static final byte PACKET_ERROR                 = 0x01;
    static final byte PACKET_CONTACT_START         = 0x02;
    static final byte PACKET_CONTACT               = 0x03;
    static final byte PACKET_CONTACT_END           = 0x04;
    static final byte PACKET_SELF_INFO             = 0x05;
    static final byte PACKET_MSG_SENT              = 0x06;
    static final byte PACKET_CONTACT_MSG_RECV      = 0x07;
    static final byte PACKET_CHANNEL_MSG_RECV      = 0x08;
    static final byte PACKET_NO_MORE_MSGS          = 0x0A;
    static final byte PACKET_BATTERY               = 0x0C;
    static final byte PACKET_DEVICE_INFO           = 0x0D;
    static final byte PACKET_CONTACT_MSG_RECV_V3   = 0x10;
    static final byte PACKET_CHANNEL_MSG_RECV_V3   = 0x11;
    static final byte PACKET_CHANNEL_INFO          = 0x12;
    static final byte PACKET_ADVERTISEMENT         = (byte) 0x80;
    static final byte PACKET_ACK                   = (byte) 0x82;
    static final byte PACKET_MESSAGES_WAITING      = (byte) 0x83;
    static final byte PACKET_LOG_DATA              = (byte) 0x88;

    // ---- BLE state ----
    private BluetoothGatt         bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;

    private boolean isScanning    = false;
    private boolean isConnecting  = false;
    private boolean isConnected   = false;
    private boolean isHandshakeComplete = false;

    // Write queue: one command at a time per spec ("Send one command, wait for response")
    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isWriting = false;

    // ---- Reconnect backoff ----
    private int  reconnectAttempts = 0;
    private int  gattErrorStreak  = 0;   // Phase 7.5: consecutive GATT errors
    private static final int  MAX_RECONNECT_ATTEMPTS = 3;
    private static final int  GATT_ANTI_LOOP_THRESHOLD = 3;  // trigger bond-clear after N errors
    private static final long[] RECONNECT_DELAYS_MS = {2000L, 5000L, 15000L};

    /**
     * Phase 7.2: Offline outgoing queue.
     * Items are stored as raw byte packets; they are flushed after handshake completes.
     */
    private static final class PendingMsg {
        final byte[] packet;   // ready-to-send BLE payload
        final Runnable onSave; // saves to MeshStorage (called before/after flush)
        PendingMsg(byte[] p, Runnable save) { packet = p; onSave = save; }
    }
    private final ConcurrentLinkedQueue<PendingMsg> pendingOutbox = new ConcurrentLinkedQueue<>();

    private String currentDeviceAddress;

    private final ArrayList<BluetoothDevice>        foundDevices = new ArrayList<>();
    private final ArrayList<MeshManagerListener>    listeners    = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---- Listener ----
    public interface MeshManagerListener {
        void onDevicesUpdated();
        void onConnectionStateChanged(boolean connected);

        /**
         * Called when a LoRa channel message is received.
         * @param channelIndex slot index (0-7)
         * @param senderInfo   pubkey-prefix or node name (may be empty for channel 0)
         * @param text         message text
         * @param timestampSec Unix timestamp in seconds
         * @param snr          SNR value (0 if not V3)
         * @param hops         path length / hop count
         */
        default void onChannelMessage(int channelIndex, String senderInfo, String text,
                                      long timestampSec, int snr, int hops) {}

        /**
         * Called when a direct contact message is received.
         * @param pubKeyHex    hex pubkey prefix of sender
         * @param text         message text
         * @param timestampSec Unix timestamp
         * @param snr          SNR (0 if not V3 packet)
         * @param hops         path length
         */
        default void onContactMessage(String pubKeyHex, String text,
                                      long timestampSec, int snr, int hops) {}

        /**
         * Called when a LoRa channel slot info is loaded from device.
         * @param slotIndex 0-7
         * @param name      channel name (empty = slot unused)
         * @param secretHex 16-byte hex secret (MeshCore public key for slot 0)
         * @param isPublic  true for slot 0 or hashtag-derived channels
         */
        default void onChannelLoaded(int slotIndex, String name, String secretHex, boolean isPublic) {}

        /**
         * Called when PACKET_SELF_INFO is parsed (response to CMD_APP_START).
         */
        default void onSelfInfoLoaded(String pubKeyHex, String name,
                                      long freqHz, float bwKHz, int sf, int cr) {}
    }

    public void addListener(MeshManagerListener l)    { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(MeshManagerListener l) { listeners.remove(l); }

    // ---- Singleton ----
    public static MeshManager getInstance() {
        MeshManager local = Instance;
        if (local == null) {
            synchronized (MeshManager.class) {
                local = Instance;
                if (local == null) Instance = local = new MeshManager();
            }
        }
        return local;
    }

    private MeshManager() {
        // Listen for Android BLE pairing requests so we can auto-confirm with device PIN
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        ApplicationLoader.applicationContext.registerReceiver(pairingReceiver, f);
    }

    // ---- BLE Pairing (Bonding) ----
    // MeshCore firmware (ESP32/nRF52) may require bonding. The device PIN is embedded
    // in PACKET_DEVICE_INFO response (bytes 4-7, little-endian). Before that arrives,
    // we auto-confirm Numeric Comparison and try "123456" as default PIN.
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(currentDeviceAddress)) return;

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            FileLog.d(TAG + ": Pairing request received, variant=" + variant);

            if (variant == BluetoothDevice.PAIRING_VARIANT_PIN || variant == 1 /* PAIRING_VARIANT_PASSKEY */) {
                // DO NOT auto-inject "123456" and abort broadcast.
                // We must let the Android system show the native PIN entry dialog
                // so the user can enter the dynamic PIN from the device screen.
                FileLog.d(TAG + ": PIN/Passkey requested. Waiting for user input via system dialog.");
            } else if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ||
                       variant == 3 /* PAIRING_VARIANT_CONSENT — hidden API, value = 3 */) {
                device.setPairingConfirmation(true);
                abortBroadcast();
                FileLog.d(TAG + ": Auto-confirmed numeric comparison / consent pairing");
            }

            // Also notify the UI in case the user wants to enter a custom PIN
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.didRequestMeshPairing, device, variant);
        }
    };

    // ---- Public: confirm pairing with a custom PIN (from UI) ----
    public void confirmPairing(String pin) {
        if (currentDeviceAddress == null) return;
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(currentDeviceAddress);
        if (device != null) {
            device.setPin(pin.getBytes(StandardCharsets.UTF_8));
            device.setPairingConfirmation(true);
        }
    }

    // ---- State getters ----
    public boolean isScanning()         { return isScanning; }
    public boolean isConnecting()       { return isConnecting; }
    public boolean isConnected()        { return isConnected; }
    public boolean isHandshakeComplete(){ return isHandshakeComplete; }

    // ============================================================
    // SCANNING
    // ============================================================

    public void startScanningIfPermissionsGranted() {
        if (ApplicationLoader.applicationContext == null) return;
        boolean granted = (Build.VERSION.SDK_INT >= 31)
                ? ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_SCAN)  == PackageManager.PERMISSION_GRANTED
               && ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                : ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (granted) startScanning();
    }

    public void startScanning() {
        if (isScanning) return;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    "Bluetooth выключен", Toast.LENGTH_SHORT).show());
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            FileLog.e(TAG + ": Missing BLUETOOTH_SCAN permission");
            return;
        }

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { FileLog.e(TAG + ": LE scanner unavailable"); return; }

        try {
            isScanning = true;
            foundDevices.clear();

            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            // NOTE: We intentionally scan WITHOUT a UUID filter.
            // Many MeshCore devices (Heltec T114, LilyGO T3S3) do NOT include the NUS service
            // UUID in their advertising packet — they only expose it after GATT connection.
            // Filtering by UUID here would silently drop those devices.
            // We instead filter by device name in onScanResult (see MESHCORE_NAME_KEYWORDS).
            scanner.startScan(null, settings, scanCallback);
            FileLog.d(TAG + ": BLE scan started (no UUID filter — name filter applied in callback)");
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    "Поиск MeshCore устройств...", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            FileLog.e(TAG + ": Failed to start scan", e);
            isScanning = false;
            return;
        }

        handler.removeCallbacks(stopScanRunnable);
        handler.postDelayed(stopScanRunnable, 30_000);
    }

    private final Runnable stopScanRunnable = this::stopScanning;

    public void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        handler.removeCallbacks(stopScanRunnable);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getBluetoothLeScanner() != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                adapter.getBluetoothLeScanner().stopScan(scanCallback);
                FileLog.d(TAG + ": BLE scan stopped");
            } catch (Exception e) {
                FileLog.e(TAG + ": Error stopping scan", e);
            }
        }
        handler.post(() -> { for (MeshManagerListener l : listeners) l.onDevicesUpdated(); });
    }

    /**
     * Keywords used to identify MeshCore-compatible devices by name.
     * Devices are matched if their name contains any of these (case-insensitive).
     * Devices with no name (null/empty) are also shown so completely unnamed devices
     * can still be connected to by MAC address.
     */
    private static final String[] MESHCORE_NAME_KEYWORDS = {
        "meshcore", "mesh", "heltec", "lilygo", "lora", "t114", "t3s3", "meshtastic"
    };

    private boolean isMeshCoreDevice(String name) {
        if (name == null || name.isEmpty() || name.equals("Mesh Device")) return false;
        String lower = name.toLowerCase();
        for (String kw : MESHCORE_NAME_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = getDeviceName(device);

            // Accept devices whose name matches MeshCore keywords OR that have no name
            // (unnamed devices may still be MeshCore — user can try connecting by address)
            if (!isMeshCoreDevice(name) && name != null && !name.isEmpty() && !name.equals("Mesh Device")) {
                return; // skip non-MeshCore named devices
            }

            boolean exists = false;
            for (BluetoothDevice d : foundDevices) {
                if (d.getAddress().equals(device.getAddress())) { exists = true; break; }
            }
            if (!exists) {
                foundDevices.add(device);
                FileLog.d(TAG + ": Found MeshCore candidate: " + name + " [" + device.getAddress() + "]");
                handler.post(() -> { for (MeshManagerListener l : listeners) l.onDevicesUpdated(); });
            }

            // Auto-connect if this is the saved preferred device
            String targetAddress = MeshTransportManager.getInstance().getSelectedDeviceAddress();
            if (targetAddress != null && targetAddress.equals(device.getAddress())) {
                stopScanning();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            FileLog.e(TAG + ": Scan failed, error code: " + errorCode);
            isScanning = false;
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext,
                    "Ошибка BLE сканирования (код: " + errorCode + ")", Toast.LENGTH_LONG).show());
        }
    };

    /** Returns device name with BLUETOOTH_CONNECT guard for API 31+ */
    private String getDeviceName(BluetoothDevice device) {
        String name = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                name = device.getName();
            }
        } else {
            name = device.getName();
        }
        return (name != null && !name.isEmpty()) ? name : "Mesh Device";
    }

    public ArrayList<String> getFoundDevices() {
        ArrayList<String> result = new ArrayList<>();
        for (BluetoothDevice d : foundDevices) {
            result.add(getDeviceName(d) + "\n" + d.getAddress());
        }
        return result;
    }

    // ============================================================
    // CONNECTION
    // ============================================================

    public void connect(String addressWithInfo) {
        String address = addressWithInfo.contains("\n")
                ? addressWithInfo.split("\n")[1] : addressWithInfo;
        address = address.trim();
        for (BluetoothDevice d : foundDevices) {
            if (d.getAddress() != null && d.getAddress().trim().equalsIgnoreCase(address)) {
                MeshTransportManager.getInstance().setSelectedDeviceAddress(address);
                connectToDevice(d);
                break;
            }
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (device == null) return;
        currentDeviceAddress = device.getAddress();

        stopScanning(); // Highly recommended before connecting

        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        writeQueue.clear();
        isWriting = false;
        isHandshakeComplete = false;
        isConnecting = true;

        FileLog.d(TAG + ": Connecting to " + currentDeviceAddress + "...");
        handler.post(() -> { for (MeshManagerListener l : listeners) l.onConnectionStateChanged(false); });
        
        try {
            // autoConnect=false for reliable first-time connection
            bluetoothGatt = device.connectGatt(ApplicationLoader.applicationContext, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            FileLog.e(TAG + ": SecurityException allocating GATT. Missing permissions?", e);
            isConnecting = false;
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, "Ошибка доступа к Bluetooth (SecurityException)", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            FileLog.e(TAG + ": Unknown exception calling connectGatt", e);
            isConnecting = false;
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, "Неизвестная ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void scheduleReconnect() {
        if (currentDeviceAddress == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            FileLog.e(TAG + ": Max reconnect attempts reached. Restarting scan.");
            reconnectAttempts = 0;
            handler.post(this::startScanningIfPermissionsGranted);
            return;
        }
        long delay = RECONNECT_DELAYS_MS[reconnectAttempts++];
        FileLog.d(TAG + ": Reconnect attempt " + reconnectAttempts + " in " + delay + "ms");
        handler.postDelayed(() -> {
            if (!isConnected && currentDeviceAddress != null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    connectToDevice(adapter.getRemoteDevice(currentDeviceAddress));
                }
            }
        }, delay);
    }

    public void stopAll() {
        stopScanning();
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    // ============================================================
    // GATT CALLBACKS
    // ============================================================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                FileLog.d(TAG + ": GATT connected [status=" + status + "], discovering services...");
                isConnected = true;
                isConnecting = false;
                reconnectAttempts = 0;
                gattErrorStreak = 0; // Phase 7.5: reset error streak on successful connect
                // Step 1: Discover services
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    FileLog.e(TAG + ": No permission for discoverServices", e);
                }
                handler.post(() -> { for (MeshManagerListener l : listeners) l.onConnectionStateChanged(true); });

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                FileLog.d(TAG + ": GATT disconnected [status=" + status + "]");
                isConnected = false;
                isConnecting = false;
                isHandshakeComplete = false;
                isWriting = false;
                writeQueue.clear();
                rxCharacteristic = null;
                gatt.close();
                if (bluetoothGatt == gatt) bluetoothGatt = null;
                handler.post(() -> { for (MeshManagerListener l : listeners) l.onConnectionStateChanged(false); });

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gattErrorStreak++;
                    FileLog.e(TAG + ": GATT error (status=" + status + "), streak=" + gattErrorStreak);
                    if (gattErrorStreak >= GATT_ANTI_LOOP_THRESHOLD) {
                        // Phase 7.5: anti-loop — clear bond and re-pair from scratch
                        FileLog.e(TAG + ": Anti-loop triggered! Clearing BLE bond for " + currentDeviceAddress);
                        gattErrorStreak = 0;
                        tryClearBond(gatt.getDevice());
                        // After bond clear, wait longer before reconnect
                        handler.postDelayed(() -> {
                            if (!isConnected && currentDeviceAddress != null) {
                                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                                if (adapter != null && adapter.isEnabled()) {
                                    reconnectAttempts = 0; // reset backoff after bond clear
                                    connectToDevice(adapter.getRemoteDevice(currentDeviceAddress));
                                }
                            }
                        }, 8000L);
                    } else {
                        scheduleReconnect();
                    }
                } else {
                    gattErrorStreak = 0; // clean disconnect — reset streak
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                FileLog.e(TAG + ": Service discovery failed, status=" + status);
                return;
            }
            BluetoothGattService service = gatt.getService(UART_SERVICE_UUID);
            if (service == null) {
                FileLog.e(TAG + ": NUS/UART service not found! Device may not be MeshCore.");
                return;
            }
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID);
            if (rxCharacteristic == null) {
                FileLog.e(TAG + ": RX characteristic not found!");
                return;
            }
            // Step 2: Request MTU 512 before enabling notifications
            FileLog.d(TAG + ": Services discovered, requesting MTU 512...");
            gatt.requestMtu(512);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            FileLog.d(TAG + ": MTU negotiated to " + mtu + " [status=" + status + "]");
            // Step 3: Enable TX notifications via CCCD descriptor
            BluetoothGattService service = gatt.getService(UART_SERVICE_UUID);
            if (service != null) enableTxNotifications(gatt, service);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                FileLog.d(TAG + ": TX notifications enabled. Starting handshake...");
                // Step 4: Send CMD_APP_START — MUST be 8+ bytes: [0x01, 0x00 x7, app_name_UTF8]
                handler.postDelayed(MeshManager.this::sendAppStart, 200);
            } else {
                FileLog.e(TAG + ": Failed to write CCCD, status=" + status + ". Trying without descriptor...");
                // Fallback: some devices don't need CCCD write but still work
                handler.postDelayed(MeshManager.this::sendAppStart, 200);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            isWriting = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                FileLog.e(TAG + ": Write failed, status=" + status);
            }
            // Drain queue: next command will be sent when response arrives (command-response pattern)
            // For commands that don't have a guaranteed response (e.g. battery), drain immediately
            processWriteQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (!TX_CHAR_UUID.equals(characteristic.getUuid())) return;
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;
            processResponse(data);
        }

        // API 33+: override for new callback signature
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (!TX_CHAR_UUID.equals(characteristic.getUuid())) return;
            if (value == null || value.length == 0) return;
            processResponse(value);
        }
    };

    // ============================================================
    // HANDSHAKE SEQUENCE (per companion_protocol.md)
    // ============================================================

    /**
     * Step 4: CMD_APP_START (0x01)
     * Format: [0x01] [0x00 x7] [app_name_UTF8 optional]
     * Response: PACKET_SELF_INFO (0x05)
     */
    private void sendAppStart() {
        byte[] appName = "Telegram-XLora".getBytes(StandardCharsets.UTF_8);
        // Protocol: [CMD=0x01][proto_ver=0x01][reserved x6][app_name_UTF8]
        // proto_ver MUST be 0x01 — device validates this in the handshake.
        // Sending 0x00 here causes the device to silently discard the packet.
        byte[] packet = new byte[8 + appName.length];
        packet[0] = CMD_APP_START; // 0x01 = command ID
        packet[1] = 0x01;          // protocol version = 1  ← CRITICAL: was missing
        // bytes 2-7: reserved, stay 0x00
        System.arraycopy(appName, 0, packet, 8, appName.length);
        FileLog.d(TAG + ": Sending CMD_APP_START proto_ver=1 app='Telegram-XLora' (" + packet.length + " bytes)");
        enqueueWrite(packet);
    }

    /**
     * Step 5: CMD_DEVICE_QUERY (0x16 0x03)
     * Response: PACKET_DEVICE_INFO (0x0D)
     */
    private void sendDeviceQuery() {
        FileLog.d(TAG + ": Sending CMD_DEVICE_QUERY");
        enqueueWrite(new byte[]{0x16, 0x03});
    }

    /**
     * Step 6: CMD_SET_DEVICE_TIME (0x09 + 4-byte timestamp LE)
     * Syncs device clock to current Unix time.
     */
    private void sendDeviceTime() {
        int ts = (int) (System.currentTimeMillis() / 1000L);
        byte[] packet = new byte[5];
        packet[0] = 0x09;
        packet[1] = (byte) (ts & 0xFF);
        packet[2] = (byte) ((ts >> 8) & 0xFF);
        packet[3] = (byte) ((ts >> 16) & 0xFF);
        packet[4] = (byte) ((ts >> 24) & 0xFF);
        FileLog.d(TAG + ": Sending CMD_SET_DEVICE_TIME ts=" + ts);
        enqueueWrite(packet);
    }

    /**
     * Step 7: CMD_GET_CONTACTS (0x03 0x00 = send to contacts, but actually GET is 0x02 0x00...)
     * Per spec: contacts are returned via PACKET_CONTACT_START/CONTACT/CONTACT_END sequence.
     * The actual GET_CONTACTS command starts the contact list dump.
     * Looking at the spec carefully: contacts returned by CMD_GET_CONTACTS
     */
    private void sendGetContacts() {
        // CMD_GET_CONTACTS: per spec, the command byte for listing contacts.
        // From companion_protocol.md table: PACKET_CONTACT_START=0x02, PACKET_CONTACT=0x03, PACKET_CONTACT_END=0x04
        // The command to request contacts is byte 0x02 (triggers PACKET_CONTACT_START response)
        FileLog.d(TAG + ": Sending CMD_GET_CONTACTS");
        enqueueWrite(new byte[]{0x02});
    }

    /**
     * Step 8: CMD_GET_CHANNEL for all 8 slots (0x1F, channel_index)
     */
    private void sendGetChannel(int index) {
        FileLog.d(TAG + ": Sending CMD_GET_CHANNEL[" + index + "]");
        enqueueWrite(new byte[]{0x1F, (byte) index});
    }

    /**
     * Step 9: CMD_SYNC_NEXT_MESSAGE (0x0A) — flush buffered messages
     */
    private void sendSyncNextMessage() {
        FileLog.d(TAG + ": Sending CMD_SYNC_NEXT_MESSAGE");
        enqueueWrite(new byte[]{CMD_GET_MSG});
    }

    // Tracks handshake progress
    private int channelSyncIndex = 0;
    private static final int TOTAL_CHANNEL_SLOTS = 8;

    // ============================================================
    // RESPONSE PARSER
    // ============================================================

    private void processResponse(byte[] data) {
        if (data == null || data.length == 0) return;
        byte packetType = data[0];

        switch (packetType) {
            case PACKET_SELF_INFO:
                // Response to CMD_APP_START — parse device self-info
                parseSelfInfo(data);
                // Next handshake step: CMD_DEVICE_QUERY
                handler.postDelayed(this::sendDeviceQuery, 100);
                break;

            case PACKET_DEVICE_INFO:
                // Response to CMD_DEVICE_QUERY — parse device info (includes BLE PIN)
                parseDeviceInfo(data);
                // Next: set device time
                handler.postDelayed(this::sendDeviceTime, 100);
                break;

            case PACKET_OK:
                // Generic OK (e.g. from SET_DEVICE_TIME) — advance handshake
                if (!isHandshakeComplete) {
                    advanceHandshakeAfterOk();
                }
                break;

            case PACKET_CONTACT_START:
                FileLog.d(TAG + ": Contact list start");
                break;
            case PACKET_CONTACT:
                parseContact(data);
                break;
            case PACKET_CONTACT_END:
                FileLog.d(TAG + ": Contact list complete. Fetching channels...");
                channelSyncIndex = 0;
                handler.postDelayed(() -> sendGetChannel(channelSyncIndex), 100);
                break;

            case PACKET_CHANNEL_INFO:
                parseChannelInfo(data);
                channelSyncIndex++;
                if (channelSyncIndex < TOTAL_CHANNEL_SLOTS) {
                    handler.postDelayed(() -> sendGetChannel(channelSyncIndex), 100);
                } else {
                    // All channels fetched — handshake complete
                    handler.postDelayed(this::sendSyncNextMessage, 100);
                }
                break;

            case PACKET_NO_MORE_MSGS:
                if (!isHandshakeComplete) {
                    isHandshakeComplete = true;
                    FileLog.d(TAG + ": Handshake complete. MeshCore node ready.");
                    handler.post(() -> {
                        for (MeshManagerListener l : listeners) l.onConnectionStateChanged(true);
                        Toast.makeText(ApplicationLoader.applicationContext,
                                "MeshCore подключён", Toast.LENGTH_SHORT).show();
                        // Phase 7.2: flush offline outgoing queue
                        flushPendingOutbox();
                    });
                } else {
                    // Phase 7.3: no more messages in device queue — nothing to do
                    FileLog.d(TAG + ": PACKET_NO_MORE_MSGS — queue exhausted");
                }
                break;

            case PACKET_MESSAGES_WAITING:
                // Device has queued messages — poll them
                FileLog.d(TAG + ": Messages waiting on device. Polling...");
                handler.post(this::sendSyncNextMessage);
                break;

            case PACKET_CONTACT_MSG_RECV:
            case PACKET_CONTACT_MSG_RECV_V3:
                parseContactMessage(data);
                // ACK and request next
                handler.postDelayed(this::sendSyncNextMessage, 100);
                break;

            case PACKET_CHANNEL_MSG_RECV:
            case PACKET_CHANNEL_MSG_RECV_V3:
                parseChannelMessage(data);
                // ACK and request next
                handler.postDelayed(this::sendSyncNextMessage, 100);
                break;

            case PACKET_ERROR:
                int errCode = (data.length > 1) ? (data[1] & 0xFF) : 0;
                FileLog.e(TAG + ": PACKET_ERROR received, code=" + errCode);
                break;

            case PACKET_BATTERY:
                parseBattery(data);
                break;

            case PACKET_LOG_DATA:
                // RF log — silently ignore to reduce log spam
                break;

            case PACKET_ADVERTISEMENT:
                FileLog.d(TAG + ": Advertisement packet received (0x80)");
                break;

            case PACKET_ACK:
                // ACK payload (per companion_protocol.md):
                //   Byte 0: 0x82
                //   Bytes 1-4: echo of the first 4 bytes of the command that was ACK'd
                //              (or random_id of the sent message when present)
                if (data.length >= 5) {
                    long ackToken = ((long)(data[1] & 0xFF))
                            | ((long)(data[2] & 0xFF) << 8)
                            | ((long)(data[3] & 0xFF) << 16)
                            | ((long)(data[4] & 0xFF) << 24);
                    FileLog.d(TAG + ": PACKET_ACK token=0x" + String.format("%08X", ackToken)
                            + " — message delivered to device radio");
                    // TODO Phase 2: mark outbox message as ACK'd in MeshStorage
                } else {
                    FileLog.d(TAG + ": PACKET_ACK (no payload)");
                }
                break;

            case PACKET_MSG_SENT:
                // Device accepted the message for transmission over LoRa radio.
                // No further handshake action needed; the write queue drains automatically.
                FileLog.d(TAG + ": PACKET_MSG_SENT — message accepted by radio");
                break;

            default:
                FileLog.d(TAG + ": Unknown packet type: 0x" + String.format("%02X", packetType)
                        + " len=" + data.length);
                break;
        }
    }

    /** After a generic PACKET_OK, advance to the next handshake step */
    private void advanceHandshakeAfterOk() {
        // SET_DEVICE_TIME returns OK → get contacts
        FileLog.d(TAG + ": PACKET_OK during handshake, getting contacts...");
        handler.postDelayed(this::sendGetContacts, 100);
    }

    // ============================================================
    // PACKET PARSERS
    // ============================================================

    private void parseSelfInfo(byte[] data) {
        // PACKET_SELF_INFO (0x05) per companion_protocol.md:
        // Byte 0: type
        // Byte 1: adv_type, Byte 2: tx_power, Byte 3: max_tx_power
        // Bytes 4-35: public key (32 bytes)
        // Bytes 36-39: lat, 40-43: lon (int32 LE / 1e6)
        // Byte 44: multi_acks, 45: adv_loc_policy, 46: telemetry_mode, 47: manual_add_contacts
        // Bytes 48-51: freq (Hz, LE uint32), 52-55: bw (Hz / 1000.0), 56: SF, 57: CR
        // Bytes 58+: device name (UTF-8, no null terminator)
        if (data.length < 36) { FileLog.e(TAG + ": PACKET_SELF_INFO too short (" + data.length + ")"); return; }

        byte[] pubKey = Arrays.copyOfRange(data, 4, 36);
        String pubKeyHex = bytesToHex(pubKey);

        String name = "Unknown";
        long freqHz = 0;
        float bwKHz = 0;
        int sf = 0, cr = 0;

        if (data.length >= 58) {
            freqHz = byteArrayToInt(data, 48) & 0xFFFFFFFFL;
            bwKHz  = (byteArrayToInt(data, 52) & 0xFFFFFFFFL) / 1000.0f;
            sf     = data[56] & 0xFF;
            cr     = data[57] & 0xFF;
        }
        if (data.length > 58) {
            name = new String(data, 58, data.length - 58, StandardCharsets.UTF_8)
                    .replaceAll("\u0000", "").trim();
        }

        FileLog.d(TAG + ": SELF_INFO name='" + name + "' pubkey=" + pubKeyHex.substring(0, Math.min(12, pubKeyHex.length())) + "... freq=" + freqHz + " bw=" + bwKHz + " sf=" + sf + " cr=" + cr);

        final String finalPubKey = pubKeyHex;
        final String finalName   = name;
        final long   finalFreq   = freqHz;
        final float  finalBw     = bwKHz;
        final int    finalSf     = sf;
        final int    finalCr     = cr;

        MeshStorage.getInstance().updateNode(pubKeyHex, name, 0, 0);

        handler.post(() -> {
            for (MeshManagerListener l : listeners) {
                l.onSelfInfoLoaded(finalPubKey, finalName, finalFreq, finalBw, finalSf, finalCr);
            }
        });
    }

    private void parseDeviceInfo(byte[] data) {
        // PACKET_DEVICE_INFO (0x0D): [0] type, [1] fw_ver, [2] max_contacts, [3] max_channels,
        // [4-7] BLE PIN (LE uint32), [8-19] fw_build, [20-59] model, [60-79] version
        if (data.length < 2) return;
        int fwVer = data[1] & 0xFF;
        FileLog.d(TAG + ": PACKET_DEVICE_INFO fw_ver=" + fwVer);

        if (fwVer >= 3 && data.length >= 8) {
            // BLE PIN for pairing — update our stored PIN if it differs from default
            long blePin = byteArrayToInt(data, 4) & 0xFFFFFFFFL;
            if (blePin != 0 && blePin != 123456L) {
                FileLog.d(TAG + ": Device has custom BLE PIN=" + blePin + ". Storing for next bond.");
                // Store for use in confirmPairing if re-bond is needed
                MeshStorage.getInstance().saveDevicePin(currentDeviceAddress, (int) blePin);
            }
            if (data.length >= 80) {
                String model = new String(data, 20, 40, StandardCharsets.UTF_8)
                        .replaceAll("\u0000", "").trim();
                String version = new String(data, 60, 20, StandardCharsets.UTF_8)
                        .replaceAll("\u0000", "").trim();
                FileLog.d(TAG + ": Device model=" + model + " version=" + version);
            }
        }
    }

    private void parseContact(byte[] data) {
        // PACKET_CONTACT (0x03) per companion_protocol.md:
        // Byte 0: 0x03 (type)
        // Bytes 1-6: Public Key Prefix (6 bytes)
        // Byte 7: Flags / adv_type
        // Bytes 8+: Node name (UTF-8)
        if (data.length < 7) return;
        String pubKeyHex = bytesToHex(Arrays.copyOfRange(data, 1, 7));
        String name = "";
        if (data.length > 8) {
            name = new String(data, 8, data.length - 8, StandardCharsets.UTF_8)
                    .replaceAll("\u0000", "").trim();
        }
        FileLog.d(TAG + ": Contact pubkey=" + pubKeyHex + " name='" + name + "'");
        MeshStorage.getInstance().updateNode(pubKeyHex, name.isEmpty() ? null : name, 0, 0);
    }

    private void parseChannelInfo(byte[] data) {
        // PACKET_CHANNEL_INFO (0x12) per companion_protocol.md:
        // Byte 0: 0x12
        // Byte 1: Channel Index (0-7)
        // Bytes 2-33: Channel Name (32 bytes, null-padded)
        // Bytes 34-49: Secret (16 bytes)
        // Total: 50 bytes minimum
        if (data.length < 34) {
            FileLog.e(TAG + ": PACKET_CHANNEL_INFO too short (" + data.length + ")");
            return;
        }
        int idx  = data[1] & 0xFF;
        String name = new String(data, 2, 32, StandardCharsets.UTF_8)
                .replaceAll("\u0000", "").trim();

        // Parse 16-byte secret (if present)
        String secretHex = MeshStorage.PUBLIC_CHANNEL_KEY_HEX; // default for slot 0
        if (data.length >= 50) {
            secretHex = bytesToHex(Arrays.copyOfRange(data, 34, 50));
        }

        // Slot 0 with all-zero secret → firmware uses public key; override with official key
        boolean allZeroSecret = secretHex.matches("0{32}");
        boolean isPublic = (idx == 0) || allZeroSecret;
        if (idx == 0 && allZeroSecret) {
            secretHex = MeshStorage.PUBLIC_CHANNEL_KEY_HEX;
        }

        FileLog.d(TAG + ": PACKET_CHANNEL_INFO slot[" + idx + "] name='" + name + "' public=" + isPublic);

        // Persist to storage (async via storageQueue)
        MeshStorage.getInstance().saveLoraChannel(idx, name, secretHex, isPublic);

        final int    finalIdx      = idx;
        final String finalName     = name;
        final String finalSecret   = secretHex;
        final boolean finalPublic  = isPublic;
        handler.post(() -> {
            for (MeshManagerListener l : listeners) {
                l.onChannelLoaded(finalIdx, finalName, finalSecret, finalPublic);
            }
        });
    }

    private void parseContactMessage(byte[] data) {
        // PACKET_CONTACT_MSG_RECV (0x07) or V3 (0x10) per companion_protocol.md
        boolean isV3 = (data[0] == PACKET_CONTACT_MSG_RECV_V3);
        int offset = 1;
        int snr = 0;
        if (isV3) {
            // V3: Byte 1 = SNR (signed, multiply by 4), Bytes 2-3 = reserved
            int snrRaw = data[offset] & 0xFF;
            snr = (snrRaw < 128 ? snrRaw : snrRaw - 256); // sign-extend
            offset += 3;
        }

        // Minimum: pubkey(6) + pathLen(1) + txtType(1) + timestamp(4) = 12
        if (data.length < offset + 12) {
            FileLog.e(TAG + ": PACKET_CONTACT_MSG_RECV too short");
            return;
        }

        String pubKeyPrefix = bytesToHex(Arrays.copyOfRange(data, offset, offset + 6));
        offset += 6;
        int pathLen = data[offset] & 0xFF;
        int txtType = data[offset + 1] & 0xFF;
        offset += 2;
        long timestamp = byteArrayToInt(data, offset) & 0xFFFFFFFFL;
        offset += 4;
        if (txtType == 2) offset += 4; // skip 4-byte signature
        if (offset >= data.length) return;

        String text = new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
        FileLog.d(TAG + ": ContactMsg from=" + pubKeyPrefix + " hops=" + pathLen + " snr=" + snr + " text='" + text + "'");

        final String finalPubKey = pubKeyPrefix;
        final String finalText   = text;
        final long   finalTs     = timestamp;
        final int    finalSnr    = snr;
        final int    finalHops   = pathLen;
        handler.post(() -> {
            for (MeshManagerListener l : listeners) {
                l.onContactMessage(finalPubKey, finalText, finalTs, finalSnr, finalHops);
            }
        });
    }

    private void parseChannelMessage(byte[] data) {
        // PACKET_CHANNEL_MSG_RECV (0x08) or V3 (0x11) per companion_protocol.md:
        // Standard (0x08): [type][ch_idx][path_len][txt_type][ts LE4][text...]
        // V3     (0x11): [type][snr][res][res][ch_idx][path_len][txt_type][ts LE4][text...]
        boolean isV3 = (data[0] == PACKET_CHANNEL_MSG_RECV_V3);
        int offset = 1;
        int snr = 0;
        if (isV3) {
            int snrRaw = data[offset] & 0xFF;
            snr = (snrRaw < 128 ? snrRaw : snrRaw - 256);
            offset += 3; // snr + 2 reserved
        }

        // Need: ch_idx(1) + path_len(1) + txt_type(1) + timestamp(4) = 7
        if (data.length < offset + 7) {
            FileLog.e(TAG + ": PACKET_CHANNEL_MSG_RECV too short");
            return;
        }

        int  channelIdx = data[offset]     & 0xFF;
        int  pathLen    = data[offset + 1] & 0xFF;
        int  txtType    = data[offset + 2] & 0xFF;
        long timestamp  = byteArrayToInt(data, offset + 3) & 0xFFFFFFFFL;
        offset += 7;
        if (offset >= data.length) return;

        String text = new String(data, offset, data.length - offset, StandardCharsets.UTF_8);
        FileLog.d(TAG + ": ChannelMsg ch=" + channelIdx + " hops=" + pathLen + " snr=" + snr + " text='" + text + "'");

        final int    finalCh   = channelIdx;
        final String finalText = text;
        final long   finalTs   = timestamp;
        final int    finalSnr  = snr;
        final int    finalHops = pathLen;
        handler.post(() -> {
            for (MeshManagerListener l : listeners) {
                l.onChannelMessage(finalCh, "", finalText, finalTs, finalSnr, finalHops);
            }
        });
    }

    private void parseBattery(byte[] data) {
        if (data.length < 3) return;
        int mv = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        FileLog.d(TAG + ": Battery=" + mv + "mV");
    }

    // ============================================================
    // SEND MESSAGES (public API)
    // ============================================================

    /**
     * Sends a text message to a LoRa channel.
     * If the device is offline, the message is queued in {@link #pendingOutbox} and
     * flushed automatically when the handshake completes (Phase 7.2).
     *
     * Format: [0x03] [0x00] [channel_idx] [ts_LE 4 bytes] [text_UTF8]
     */
    public void sendChannelMessage(int channelIndex, String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > 133) {
            FileLog.e(TAG + ": Message too long (" + textBytes.length + " > 133)");
            return;
        }
        int ts = (int) (System.currentTimeMillis() / 1000L);
        byte[] packet = new byte[7 + textBytes.length];
        packet[0] = 0x03;
        packet[1] = 0x00;
        packet[2] = (byte) (channelIndex & 0xFF);
        packet[3] = (byte) (ts & 0xFF);
        packet[4] = (byte) ((ts >> 8) & 0xFF);
        packet[5] = (byte) ((ts >> 16) & 0xFF);
        packet[6] = (byte) ((ts >> 24) & 0xFF);
        System.arraycopy(textBytes, 0, packet, 7, textBytes.length);

        Runnable saveToHistory = () ->
                MeshStorage.getInstance().saveChannelMessage(channelIndex, null, text, true);

        if (isHandshakeComplete) {
            enqueueWrite(packet);
            saveToHistory.run();
        } else {
            // Offline — queue for later
            pendingOutbox.add(new PendingMsg(packet, saveToHistory));
            FileLog.d(TAG + ": Channel msg queued offline (outbox size=" + pendingOutbox.size() + ")");
        }
    }

    /**
     * Sends a direct (DM) message to a Mesh contact.
     * Format: [0x02] [pubkey 6 bytes] [ts_LE 4 bytes] [text_UTF8]
     * Queued offline if device not connected (Phase 7.2).
     *
     * @param pubKeyHex  hex pubkey prefix of recipient (at least 12 hex chars = 6 bytes)
     * @param text       message text
     */
    public void sendContactMessage(String pubKeyHex, String text) {
        if (pubKeyHex == null || pubKeyHex.length() < 12) {
            FileLog.e(TAG + ": Invalid pubkey for DM: " + pubKeyHex);
            return;
        }
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > 133) {
            FileLog.e(TAG + ": DM too long (" + textBytes.length + " > 133)");
            return;
        }
        // Parse 6-byte pubkey prefix from hex
        byte[] pubBytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            pubBytes[i] = (byte) Integer.parseInt(pubKeyHex.substring(i * 2, i * 2 + 2), 16);
        }
        int ts = (int) (System.currentTimeMillis() / 1000L);
        // Header size: 1(CMD) + 1(txt_type) + 1(attempt) + 4(ts) + 6(pubkey) = 13 bytes
        byte[] packet = new byte[13 + textBytes.length];
        packet[0] = 0x02;  // CMD_SEND_MSG
        packet[1] = 0x00;  // txt_type (0 = Plain text)
        packet[2] = 0x00;  // attempt (retry count)
        
        // Timestamp (4 bytes Little-Endian)
        packet[3]  = (byte) (ts & 0xFF);
        packet[4]  = (byte) ((ts >> 8) & 0xFF);
        packet[5]  = (byte) ((ts >> 16) & 0xFF);
        packet[6]  = (byte) ((ts >> 24) & 0xFF);
        
        // PubKey Prefix (6 bytes)
        System.arraycopy(pubBytes, 0, packet, 7, 6);
        
        // Text payload
        System.arraycopy(textBytes, 0, packet, 13, textBytes.length);

        final String finalPub = pubKeyHex;
        Runnable saveToHistory = () ->
                MeshStorage.getInstance().saveContactMessage(finalPub, text, true);

        if (isHandshakeComplete) {
            enqueueWrite(packet);
            saveToHistory.run();
        } else {
            pendingOutbox.add(new PendingMsg(packet, saveToHistory));
            FileLog.d(TAG + ": DM queued offline for " + pubKeyHex.substring(0, 8)
                    + " (outbox size=" + pendingOutbox.size() + ")");
        }
    }

    /**
     * Phase 7.2: Flushes offline outgoing queue.
     * Called on main thread right after handshake completes.
     */
    private void flushPendingOutbox() {
        if (pendingOutbox.isEmpty()) return;
        FileLog.d(TAG + ": Flushing " + pendingOutbox.size() + " queued outgoing messages");
        PendingMsg msg;
        while ((msg = pendingOutbox.poll()) != null) {
            enqueueWrite(msg.packet);
            if (msg.onSave != null) msg.onSave.run();
        }
    }

    /**
     * Phase 7.5: Attempts to remove the BLE bond (via reflection) to force re-pairing.
     * Needed when GATT 133 repeats 3 times — the bond cache gets stale.
     */
    private void tryClearBond(BluetoothDevice device) {
        if (device == null) return;
        try {
            java.lang.reflect.Method removeBond =
                    BluetoothDevice.class.getMethod("removeBond");
            boolean result = (boolean) removeBond.invoke(device);
            FileLog.d(TAG + ": removeBond() result=" + result + " for " + device.getAddress());
        } catch (Exception e) {
            FileLog.e(TAG + ": removeBond() reflection failed", e);
        }
    }

    /**
     * Creates or updates a LoRa channel slot on the device.
     * CMD_SET_CHANNEL (0x20) per companion_protocol.md:
     *   Byte 0: 0x20
     *   Byte 1: channel index (0-7)
     *   Bytes 2-33: channel name (32 bytes, UTF-8, null-padded)
     *   Bytes 34-49: secret (16 bytes, all-zero for public)
     * Total: 50 bytes
     *
     * @param channelIndex 0 = public, 1-7 = private
     * @param name         channel name (max 32 bytes UTF-8)
     * @param secret16     16-byte secret, or null to use all-zero (public channel)
     */
    public void sendSetChannel(int channelIndex, String name, byte[] secret16) {
        if (!isHandshakeComplete) {
            FileLog.e(TAG + ": Cannot set channel — handshake not complete");
            return;
        }
        if (channelIndex < 0 || channelIndex > 7) {
            FileLog.e(TAG + ": Invalid channel index: " + channelIndex);
            return;
        }
        byte[] packet = new byte[50];
        packet[0] = 0x20;  // CMD_SET_CHANNEL
        packet[1] = (byte) (channelIndex & 0xFF);

        // Channel name: 32 bytes, null-padded
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int nameLen = Math.min(nameBytes.length, 32);
        System.arraycopy(nameBytes, 0, packet, 2, nameLen);
        // bytes 2+nameLen .. 33 remain 0x00 (null-padding)

        // Secret: 16 bytes
        if (secret16 != null && secret16.length >= 16) {
            System.arraycopy(secret16, 0, packet, 34, 16);
        }
        // else all-zero = public channel

        FileLog.d(TAG + ": Sending CMD_SET_CHANNEL slot=" + channelIndex + " name='" + name + "'");
        enqueueWrite(packet);
    }

    /** Legacy sendData compat shim — routes to channel 0 */
    public void sendData(byte[] data) {
        sendChannelMessage(0, new String(data, StandardCharsets.UTF_8));
    }

    /** Legacy sendData with targetNode — send to channel 0 for now (direct contact msg TBD) */
    public void sendData(byte[] data, int targetNodeHash) {
        sendData(data);
    }

    /** Sync history: poll CMD_GET_MSG if connected */
    public void syncHistory() {
        if (isConnected && isHandshakeComplete) sendSyncNextMessage();
    }

    /**
     * Sends radio configuration to device — CMD_SET_RADIO_PARAMS (0x0B).
     * Format (Little-Endian):
     *   [0x0B][freq 4 bytes LE][bw 4 bytes LE][sf 1 byte][cr 1 byte]
     *
     * @param freq   frequency in Hz (e.g. 868731018)
     * @param bwKHz  bandwidth in kHz (e.g. 62.5). Converted to Hz internally.
     * @param sf     Spreading Factor (7-12)
     * @param cr     Coding Rate (5-8, meaning 4/5 to 4/8)
     */
    public void sendRadioConfig(long freq, float bwKHz, int sf, int cr) {
        if (!isConnected || rxCharacteristic == null) {
            FileLog.e(TAG + ": Cannot send radio config — not connected");
            return;
        }
        // Convert bandwidth from kHz to Hz (device expects Hz)
        long bwHz = (long)(bwKHz * 1000.0f);

        byte[] packet = new byte[11];
        packet[0]  = 0x0B;  // CMD_SET_RADIO_PARAMS
        // Frequency (Little-Endian uint32)
        packet[1]  = (byte)(freq & 0xFF);
        packet[2]  = (byte)((freq >> 8) & 0xFF);
        packet[3]  = (byte)((freq >> 16) & 0xFF);
        packet[4]  = (byte)((freq >> 24) & 0xFF);
        // Bandwidth in Hz (Little-Endian uint32)
        packet[5]  = (byte)(bwHz & 0xFF);
        packet[6]  = (byte)((bwHz >> 8) & 0xFF);
        packet[7]  = (byte)((bwHz >> 16) & 0xFF);
        packet[8]  = (byte)((bwHz >> 24) & 0xFF);
        // Spreading Factor
        packet[9]  = (byte)(sf & 0xFF);
        // Coding Rate
        packet[10] = (byte)(cr & 0xFF);

        FileLog.d(TAG + ": Sending CMD_SET_RADIO_PARAMS freq=" + freq
                + " bwHz=" + bwHz + " sf=" + sf + " cr=" + cr);
        enqueueWrite(packet);
    }

    // ============================================================
    // WRITE QUEUE (one command at a time per spec)
    // ============================================================

    private void enqueueWrite(byte[] packet) {
        writeQueue.add(packet);
        if (!isWriting) processWriteQueue();
    }

    private synchronized void processWriteQueue() {
        if (isWriting || writeQueue.isEmpty() || rxCharacteristic == null || bluetoothGatt == null) return;

        byte[] packet = writeQueue.poll();
        if (packet == null) return;

        isWriting = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: new writeCharacteristic API
                if (checkBleConnectPermission()) {
                    bluetoothGatt.writeCharacteristic(rxCharacteristic, packet,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } else {
                    isWriting = false;
                }
            } else {
                rxCharacteristic.setValue(packet);
                rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (checkBleConnectPermission()) {
                    bluetoothGatt.writeCharacteristic(rxCharacteristic);
                } else {
                    isWriting = false;
                }
            }
        } catch (Exception e) {
            FileLog.e(TAG + ": Write exception", e);
            isWriting = false;
            processWriteQueue();
        }
    }

    // ============================================================
    // ENABLE TX NOTIFICATIONS
    // ============================================================

    private void enableTxNotifications(BluetoothGatt gatt, BluetoothGattService service) {
        BluetoothGattCharacteristic tx = service.getCharacteristic(TX_CHAR_UUID);
        if (tx == null) { FileLog.e(TAG + ": TX characteristic not found!"); return; }

        if (!checkBleConnectPermission()) { FileLog.e(TAG + ": No BLUETOOTH_CONNECT permission"); return; }

        gatt.setCharacteristicNotification(tx, true);

        BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            FileLog.e(TAG + ": CCCD descriptor not found, proceeding anyway");
            handler.postDelayed(this::sendAppStart, 300);
            return;
        }

        boolean written;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+
            written = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothGatt.GATT_SUCCESS;
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            written = gatt.writeDescriptor(cccd);
        }
        FileLog.d(TAG + ": Writing CCCD descriptor: " + written);
    }

    // ============================================================
    // UTILITIES
    // ============================================================

    private boolean checkBleConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static int byteArrayToInt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public boolean isHandshakeCompleted() { return isHandshakeComplete; }
    public void onNetworkStatusChanged(boolean online) {}
}
