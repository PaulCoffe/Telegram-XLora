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
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MeshManager handles BLE communication with LoRa modules (Nordic UART Service).
 * It manages encryption, fragmentation, and relay logic.
 */
public class MeshManager {
    private static final String TAG = "MeshManager";
    private static volatile MeshManager Instance;

    // Nordic UART Service UUIDs
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // Write
    private static final UUID TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // Notify
    // Standard BLE Client Characteristic Configuration Descriptor (CCCD)
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // MeshCore AES-256 PSK (32 bytes).
    private static final byte[] MESH_PSK = new byte[] {
        (byte)0x4d, (byte)0x65, (byte)0x73, (byte)0x68, (byte)0x43, (byte)0x6f, (byte)0x72, (byte)0x65, // MeshCore
        (byte)0x53, (byte)0x65, (byte)0x63, (byte)0x75, (byte)0x72, (byte)0x69, (byte)0x74, (byte)0x79, // Security
        (byte)0x32, (byte)0x35, (byte)0x36, (byte)0x42, (byte)0x69, (byte)0x74, (byte)0x4b, (byte)0x65, // 256BitKe
        (byte)0x79, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f  // y_______
    };

    // MeshCore Protocol Commands
    private static final byte CMD_GET_CONFIG = 0x02;
    private static final byte CMD_GET_CONTACTS = 0x03;
    private static final byte CMD_APP_START = 0x04;
    private static final byte CMD_FETCH_HISTORY = 0x01;
    
    private boolean isHandshakeComplete = false;
    
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int type = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
                if (device != null && device.getAddress().equals(currentDeviceAddress)) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didRequestMeshPairing, device, type);
                }
            }
        }
    };

    private String currentDeviceAddress;

    // FIX #3: Reconnect with exponential backoff on GATT error
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long[] RECONNECT_DELAYS_MS = {2000L, 5000L, 15000L};
    
    private final MeshFragmenter fragmenter = new MeshFragmenter();
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic; // Phone writes to this
    private boolean isScanning = false;
    private boolean isConnected = false;
    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean isWriting = false;
    public interface MeshManagerListener {
        void onDevicesUpdated();
        void onConnectionStateChanged(boolean connected);
        void onMessageReceived(byte[] data, int rssi, int hops);
    }

    private final ArrayList<MeshManagerListener> listeners = new ArrayList<>();

    public void addListener(MeshManagerListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(MeshManagerListener listener) {
        listeners.remove(listener);
    }

    private final java.util.ArrayList<BluetoothDevice> foundDevices = new java.util.ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public static MeshManager getInstance() {
        MeshManager localInstance = Instance;
        if (localInstance == null) {
            synchronized (MeshManager.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MeshManager();
                }
            }
        }
        return localInstance;
    }

    private MeshManager() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        ApplicationLoader.applicationContext.registerReceiver(pairingReceiver, filter);
    }

    public boolean isScanning() {
        return isScanning;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isHandshakeComplete() {
        return isHandshakeComplete;
    }

    public void startScanningIfPermissionsGranted() {
        if (ApplicationLoader.applicationContext != null) {
            boolean granted;
            if (Build.VERSION.SDK_INT >= 31) {
                granted = ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                          ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            } else {
                granted = ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                startScanning();
            }
        }
    }

    public void startScanning() {
        if (isScanning) {
            FileLog.d(TAG + ": Scan already in progress");
            return;
        }
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            FileLog.e(TAG + ": Bluetooth not available or disabled");
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, "Bluetooth выключен", Toast.LENGTH_SHORT).show());
            return;
        }

        // Check Location services (required for BLE scanning)
        android.location.LocationManager lm = (android.location.LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, "Включите Геолокацию для поиска устройств", Toast.LENGTH_LONG).show());
        }

        // Permission check for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                FileLog.e(TAG + ": Missing BLUETOOTH_SCAN permission");
                return;
            }
        }

        final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            FileLog.e(TAG + ": Failed to get LE Scanner");
            return;
        }

        try {
            isScanning = true;
            foundDevices.clear();
            
            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
                
            java.util.List<android.bluetooth.le.ScanFilter> filters = new java.util.ArrayList<>();
            filters.add(new android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(new android.os.ParcelUuid(UART_SERVICE_UUID))
                .build());

            scanner.startScan(filters, settings, scanCallback);
            FileLog.d(TAG + ": Scan started with filters and low latency");
            handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, "Поиск устройств MeshCore...", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            FileLog.e(TAG + ": Exception starting scan", e);
            isScanning = false;
            return;
        }
        
        // Stop scanning after 30 seconds if not found target yet
        handler.removeCallbacks(stopScanRunnable);
        handler.postDelayed(stopScanRunnable, 30000);
    }

    private final Runnable stopScanRunnable = this::stopScanning;

    public void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        handler.removeCallbacks(stopScanRunnable);
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getBluetoothLeScanner() != null) {
            try {
                // Permission check for Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                }
                adapter.getBluetoothLeScanner().stopScan(scanCallback);
                FileLog.d(TAG + ": Scan stopped manually");
            } catch (Exception e) {
                FileLog.e(TAG + ": Error stopping scan", e);
            }
        }
        
        handler.post(() -> {
            for (MeshManagerListener l : listeners) {
                l.onDevicesUpdated();
            }
        });
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null) {
                String name = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        name = device.getName();
                    }
                } else {
                    name = device.getName();
                }
                
                if (name == null) name = "Mesh Device";

                boolean exists = false;
                for (BluetoothDevice d : foundDevices) {
                    if (d.getAddress().equals(device.getAddress())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    foundDevices.add(device);
                    for (MeshManagerListener l : listeners) {
                        l.onDevicesUpdated();
                    }
                }
            }
            
            String targetAddress = MeshTransportManager.getInstance().getSelectedDeviceAddress();
            if (targetAddress != null && targetAddress.equals(device.getAddress())) {
                stopScanning();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            FileLog.e(TAG + ": Scan failed with error: " + errorCode);
            isScanning = false;
        }
    };

    // FIX #4: Guard getName() with BLUETOOTH_CONNECT permission on Android 12+
    public java.util.ArrayList<String> getFoundDevices() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (BluetoothDevice d : foundDevices) {
            String name = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext,
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    name = d.getName();
                }
            } else {
                name = d.getName();
            }
            if (name == null || name.isEmpty()) name = "Mesh Device";
            names.add(name + "\n" + d.getAddress());
        }
        return names;
    }

    public void connect(String addressWithInfo) {
        String address = addressWithInfo;
        if (addressWithInfo.contains("\n")) {
            address = addressWithInfo.split("\n")[1];
        }
        for (BluetoothDevice d : foundDevices) {
            if (d.getAddress().equals(address)) {
                connectToDevice(d);
                MeshTransportManager.getInstance().setSelectedDeviceAddress(address);
                break;
            }
        }
    }

    public void confirmPairing(String pin) {
        if (currentDeviceAddress == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;
        BluetoothDevice device = adapter.getRemoteDevice(currentDeviceAddress);
        if (device != null) {
            byte[] pinBytes = pin.getBytes();
            device.setPin(pinBytes);
            device.setPairingConfirmation(true);
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        if (device == null) return;
        currentDeviceAddress = device.getAddress();
        // Close any existing stale GATT connection before opening a new one
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        FileLog.d(TAG + ": Connecting to device (" + currentDeviceAddress + ")");
        bluetoothGatt = device.connectGatt(ApplicationLoader.applicationContext, false, gattCallback);
    }

    /**
     * FIX #3: Schedules a reconnect attempt with exponential backoff.
     * After MAX_RECONNECT_ATTEMPTS failures, falls back to a new BLE scan.
     */
    private void scheduleReconnect() {
        if (currentDeviceAddress == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            FileLog.e(TAG + ": Max reconnect attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached. Restarting scan.");
            reconnectAttempts = 0;
            handler.post(this::startScanningIfPermissionsGranted);
            return;
        }
        long delay = RECONNECT_DELAYS_MS[reconnectAttempts];
        reconnectAttempts++;
        FileLog.d(TAG + ": Scheduling reconnect attempt " + reconnectAttempts + " in " + delay + "ms");
        handler.postDelayed(() -> {
            if (!isConnected && currentDeviceAddress != null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    BluetoothDevice device = adapter.getRemoteDevice(currentDeviceAddress);
                    connectToDevice(device);
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

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                FileLog.d(TAG + ": GATT Connected [status=" + status + "], discovering services...");
                isConnected = true;
                reconnectAttempts = 0; // FIX #3: reset counter on successful connect
                // MintyLinux sequence Step 1: discoverServices first
                gatt.discoverServices();
                handler.post(() -> {
                    for (MeshManagerListener l : listeners) {
                        l.onConnectionStateChanged(true);
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                FileLog.d(TAG + ": GATT Disconnected [status=" + status + "]");
                isConnected = false;
                isHandshakeComplete = false;
                isWriting = false;
                writeQueue.clear();
                rxCharacteristic = null;
                gatt.close(); // Always close after disconnect to free resources
                if (bluetoothGatt == gatt) bluetoothGatt = null;
                handler.post(() -> {
                    for (MeshManagerListener l : listeners) {
                        l.onConnectionStateChanged(false);
                    }
                });
                // FIX #3: Auto-reconnect on unexpected disconnect (status != 0 means GATT error)
                if (status != 0) {
                    FileLog.e(TAG + ": GATT error on disconnect (status=" + status + "), scheduling reconnect...");
                    scheduleReconnect();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UART_SERVICE_UUID);
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(RX_CHARACTERISTIC_UUID);
                    // MintyLinux sequence Step 2: request MTU after services are discovered
                    FileLog.d(TAG + ": Services discovered. Requesting MTU 512...");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        gatt.requestMtu(512);
                    } else {
                        // Fallback for old Android: enable notifications directly
                        enableNotificationsOnTx(gatt, service);
                    }
                } else {
                    FileLog.e(TAG + ": UART Service not found on device!");
                }
            } else {
                FileLog.e(TAG + ": Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            // MintyLinux sequence Step 3: enable notifications via CCCD descriptor 0x2902
            FileLog.d(TAG + ": MTU changed to " + mtu + " [status=" + status + "]");
            BluetoothGattService service = gatt.getService(UART_SERVICE_UUID);
            if (service != null) {
                enableNotificationsOnTx(gatt, service);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    FileLog.d(TAG + ": CCCD descriptor written. Notifications enabled. Starting handshake...");
                    isHandshakeComplete = false;
                    // MintyLinux sequence Step 4: start handshake only after descriptor confirmed
                    handler.postDelayed(() -> sendHandshakeCommand(CMD_APP_START), 200);
                } else {
                    FileLog.e(TAG + ": Failed to write CCCD descriptor, status: " + status);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(TX_CHARACTERISTIC_UUID)) {
                // getValue() on GATT thread is safe; processIncomingPacket handles its own threading
                byte[] data = characteristic.getValue();
                processIncomingPacket(data);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            isWriting = false;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] val = characteristic.getValue();
                if (val != null && val.length == 1) {
                    final byte lastCmd = val[0];
                    if (lastCmd == CMD_APP_START) {
                        FileLog.d(TAG + ": Handshake Step 1 complete. Fetching config...");
                        handler.postDelayed(() -> sendHandshakeCommand(CMD_GET_CONFIG), 150);
                    } else if (lastCmd == CMD_GET_CONFIG) {
                        FileLog.d(TAG + ": Handshake Step 2 complete. Fetching contacts...");
                        handler.postDelayed(() -> sendHandshakeCommand(CMD_GET_CONTACTS), 150);
                    } else if (lastCmd == CMD_GET_CONTACTS) {
                        isHandshakeComplete = true;
                        FileLog.d(TAG + ": Handshake complete. Mesh node ready.");
                        handler.post(() -> {
                            for (MeshManagerListener l : listeners) {
                                l.onConnectionStateChanged(true);
                            }
                        });
                    }
                }
            } else {
                FileLog.e(TAG + ": GATT Write failed with status: " + status);
            }

            processWriteQueue();
        }
    };

    /**
     * Enables BLE notifications on the TX characteristic by writing to the CCCD
     * descriptor (0x2902). This is required by the BLE spec and the MintyLinux
     * reference implementation to guarantee notifications are delivered.
     */
    private void enableNotificationsOnTx(BluetoothGatt gatt, BluetoothGattService service) {
        BluetoothGattCharacteristic tx = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
        if (tx == null) {
            FileLog.e(TAG + ": TX characteristic not found!");
            return;
        }
        // Step 1: register with the local Android BLE stack
        gatt.setCharacteristicNotification(tx, true);
        // Step 2: write CCCD descriptor on the remote device (mandatory for hardware ACK)
        BluetoothGattDescriptor descriptor = tx.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean written = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    written = gatt.writeDescriptor(descriptor);
                }
            } else {
                written = gatt.writeDescriptor(descriptor);
            }
            FileLog.d(TAG + ": Writing CCCD descriptor (0x2902): " + written);
        } else {
            FileLog.e(TAG + ": CCCD descriptor (0x2902) not found! Proceeding without it (hardware may not send notifications).");
            // Fallback: start handshake anyway
            isHandshakeComplete = false;
            handler.postDelayed(() -> sendHandshakeCommand(CMD_APP_START), 200);
        }
    }

    private synchronized void processWriteQueue() {
        if (isWriting || writeQueue.isEmpty() || rxCharacteristic == null || bluetoothGatt == null) {
            return;
        }
        byte[] nextPacket = writeQueue.poll();
        if (nextPacket != null) {
            isWriting = true;
            try {
                rxCharacteristic.setValue(nextPacket);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt.writeCharacteristic(rxCharacteristic);
                    } else {
                        isWriting = false;
                    }
                } else {
                    bluetoothGatt.writeCharacteristic(rxCharacteristic);
                }
            } catch (Exception e) {
                FileLog.e(TAG + ": Error writing to characteristic", e);
                isWriting = false;
                processWriteQueue();
            }
        }
    }

    public void sendData(byte[] data) {
        sendData(data, 0);
    }

    public void sendData(byte[] data, int targetNodeHash) {
        if (rxCharacteristic == null || bluetoothGatt == null) return;

        try {
            int[] path = (targetNodeHash != 0) ? new int[]{targetNodeHash} : null;
            byte type = (targetNodeHash != 0) ? MeshProtocol.TYPE_TXT_MSG : MeshProtocol.TYPE_GRP_TXT;
            
            MeshProtocol.Packet packet = new MeshProtocol.Packet(type, path, data);
            byte[] rawPacket = packet.serialize();
            
            byte[] encrypted = encrypt(rawPacket);
            List<MeshFragmenter.Fragment> fragments = fragmenter.fragment(encrypted, (int) (System.currentTimeMillis() / 1000));

            for (MeshFragmenter.Fragment frag : fragments) {
                writeQueue.add(frag.serialize());
            }

            if (!isWriting) {
                processWriteQueue();
            }
            
            // Save to local history (using 0 as default channel/chat ID for broadcast)
            MeshStorage.getInstance().saveMessage(0, targetNodeHash, new String(data), true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void processIncomingPacket(byte[] rawData) {
        if (rawData == null || rawData.length == 0) return;

        // Command responses (Local UART commands 0x01-0x0F) are usually unencrypted
        if (rawData.length > 0 && rawData[0] < 0x10) {
            handleLocalCommandResponse(rawData);
            return;
        }

        try {
            byte[] decrypted = decrypt(rawData);
            if (decrypted == null) return;

            byte[] assembled = fragmenter.onFragmentReceived(decrypted);
            if (assembled != null) {
                MeshProtocol.Packet packet = MeshProtocol.Packet.deserialize(assembled);
                if (packet == null) return;

                // Notify listeners with raw data and metadata
                int hops = (packet.path != null) ? packet.path.length : 0;
                for (MeshManagerListener l : listeners) {
                    l.onMessageReceived(assembled, 0, hops); // RSSI 0 for now
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void handleLocalCommandResponse(byte[] data) {
        byte cmd = data[0];
        if (cmd == CMD_GET_CONFIG) {
            // Parse config (Example: [CMD(1)] [PubKey(32)] [Nick(...)]
            if (data.length >= 33) {
                byte[] pubKey = new byte[32];
                System.arraycopy(data, 1, pubKey, 0, 32);
                String nick = (data.length > 33) ? new String(data, 33, data.length - 33) : "Unknown";
                FileLog.d(TAG + ": Local config received: Nick=" + nick);
                // Update storage self-identity
                MeshStorage.getInstance().updateNode(org.telegram.messenger.Utilities.bytesToHex(pubKey), nick, 0, 0);
            }
        } else if (cmd == CMD_GET_CONTACTS) {
            // Parse contacts list and sync with internal DB
            FileLog.d(TAG + ": Contacts list received from node");
        }
    }

    private void handleMeshMessage(String text) {
        FileLog.d(TAG + ": Received Mesh Message: " + text);
        
        // Scenario 2: If it's a message from a node, we should notify the UI
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didReceiveSmsCode, text); // Placeholder for generic data broadcast
        
        // Relay logic for Gateway Mode
        if (ApplicationLoader.isNetworkOnline()) {
             // In Gateway Mode, if we receive a message intended for high-level relay, we send it to TG servers
        }
    }

    public void syncHistory() {
        if (!isConnected) return;
        FileLog.d(TAG + ": MeshManager: Starting history sync from node...");
        // Send a request to pull history from node buffer
        // MeshCore CMD_FETCH_HISTORY (Example: 0x01 command byte)
        byte[] fetchCmd = new byte[] { 0x01 }; 
        sendData(fetchCmd);
    }

    public void sendRadioConfig(long freq, float bw, int sf, int cr) {
        if (bluetoothGatt == null) return;
        
        // CMD_SET_CHANNEL_CONFIG = 32
        // [CMD(1)] [Index(1)] [Freq(4 LE)] [BW_idx(1)] [SF(1)] [CR(1)] [Power(1)]
        byte[] packet = new byte[10];
        packet[0] = 32; // CMD_SET_CHANNEL_CONFIG
        packet[1] = 0;  // Channel Index
        
        // Frequency (Hz) LE
        packet[2] = (byte) (freq & 0xFF);
        packet[3] = (byte) ((freq >> 8) & 0xFF);
        packet[4] = (byte) ((freq >> 16) & 0xFF);
        packet[5] = (byte) ((freq >> 24) & 0xFF);
        
        // Bandwidth index mapping
        byte bwIdx = 7; // Default 125kHz
        if (bw <= 7.8f) bwIdx = 0;
        else if (bw <= 10.4f) bwIdx = 1;
        else if (bw <= 15.6f) bwIdx = 2;
        else if (bw <= 20.8f) bwIdx = 3;
        else if (bw <= 31.25f) bwIdx = 4;
        else if (bw <= 41.7f) bwIdx = 5;
        else if (bw <= 62.5f) bwIdx = 6;
        else if (bw <= 125f) bwIdx = 7;
        else if (bw <= 250f) bwIdx = 8;
        else bwIdx = 9;
        
        packet[6] = bwIdx;
        packet[7] = (byte) sf;
        packet[8] = (byte) cr;
        packet[9] = 20; // Default Power 20dBm
        
        sendPacket(packet);
        handler.post(() -> Toast.makeText(ApplicationLoader.applicationContext, "Настройки радио отправлены", Toast.LENGTH_SHORT).show());
    }

    private void sendHandshakeCommand(byte cmd) {
        if (rxCharacteristic == null || bluetoothGatt == null) return;
        byte[] packet = new byte[] { cmd };
        writeQueue.add(packet);
        if (!isWriting) {
            processWriteQueue();
        }
    }

    private void sendPacket(byte[] data) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(UART_SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(RX_CHARACTERISTIC_UUID);
            if (characteristic != null) {
                characteristic.setValue(data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                }
                bluetoothGatt.writeCharacteristic(characteristic);
            }
        }
    }

    public void onNetworkStatusChanged(boolean online) {
        FileLog.d(TAG + ": Network status changed: " + (online ? "online" : "offline"));
        if (online) {
            // Logic to handle reconnection to mesh if needed
        }
    }

    private byte[] encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] iv = new byte[16];
        new java.security.SecureRandom().nextBytes(iv);
        SecretKeySpec keySpec = new SecretKeySpec(MESH_PSK, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] ciphertext = cipher.doFinal(data);
        
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(iv.length + ciphertext.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        return buffer.array();
    }

    private byte[] decrypt(byte[] data) throws Exception {
        if (data.length < 16) return null;
        
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
        byte[] iv = new byte[16];
        buffer.get(iv);
        byte[] ciphertext = new byte[data.length - 16];
        buffer.get(ciphertext);
        
        SecretKeySpec keySpec = new SecretKeySpec(MESH_PSK, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(ciphertext);
    }
}
