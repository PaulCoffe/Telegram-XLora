package org.telegram.messenger.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
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

    // MeshCore AES-256 PSK (32 bytes).
    private static final byte[] MESH_PSK = new byte[] {
        (byte)0x4d, (byte)0x65, (byte)0x73, (byte)0x68, (byte)0x43, (byte)0x6f, (byte)0x72, (byte)0x65, // MeshCore
        (byte)0x53, (byte)0x65, (byte)0x63, (byte)0x75, (byte)0x72, (byte)0x69, (byte)0x74, (byte)0x79, // Security
        (byte)0x32, (byte)0x35, (byte)0x36, (byte)0x42, (byte)0x69, (byte)0x74, (byte)0x4b, (byte)0x65, // 256BitKe
        (byte)0x79, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f  // y_______
    };
    
    private final MeshFragmenter fragmenter = new MeshFragmenter();
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic; // Phone writes to this
    private boolean isScanning = false;
    private boolean isConnected = false;
    private final ConcurrentLinkedQueue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private boolean isWriting = false;
    public interface MeshManagerListener {
        void onDevicesUpdated();
        void onConnectionStateChanged(boolean connected);
        void onMessageReceived(byte[] data);
    }

    private MeshManagerListener listener;

    public void setListener(MeshManagerListener listener) {
        this.listener = listener;
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

    private MeshManager() {}

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
        if (isScanning) return;
        
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            FileLog.e(TAG + ": Bluetooth not available or disabled");
            return;
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
            scanner.startScan(scanCallback);
            FileLog.d(TAG + ": Scan started successfully");
        } catch (Exception e) {
            FileLog.e(TAG + ": Exception starting scan: " + e.getMessage());
            isScanning = false;
            return;
        }
        
        // Stop scanning after 30 seconds
        handler.postDelayed(() -> stopScanning(), 30000);
    }

    public void stopScanning() {
        if (!isScanning) return;
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
            } catch (Exception e) {
                FileLog.e(TAG + ": Error stopping scan: " + e.getMessage());
            }
        }
        isScanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null && device.getName() != null) {
                boolean exists = false;
                for (BluetoothDevice d : foundDevices) {
                    if (d.getAddress().equals(device.getAddress())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    foundDevices.add(device);
                    if (listener != null) {
                        handler.post(() -> listener.onDevicesUpdated());
                    }
                }
            }
            
            String targetAddress = MeshTransportManager.getInstance().getSelectedDeviceAddress();
            if (targetAddress != null && targetAddress.equals(device.getAddress())) {
                stopScanning();
                connectToDevice(device);
            }
        }
    };

    public java.util.ArrayList<String> getFoundDevices() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (BluetoothDevice d : foundDevices) {
            names.add(d.getName() + "\n" + d.getAddress());
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

    private void connectToDevice(BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(ApplicationLoader.applicationContext, false, gattCallback);
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
                FileLog.d(TAG + ": GATT Connected, discovering services...");
                isConnected = true;
                if (listener != null) {
                    handler.post(() -> listener.onConnectionStateChanged(true));
                }
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                FileLog.d(TAG + ": GATT Disconnected");
                isConnected = false;
                if (listener != null) {
                    handler.post(() -> listener.onConnectionStateChanged(false));
                }
                rxCharacteristic = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UART_SERVICE_UUID);
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(RX_CHARACTERISTIC_UUID);
                    BluetoothGattCharacteristic tx = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
                    if (tx != null) {
                        gatt.setCharacteristicNotification(tx, true);
                        FileLog.d(TAG + ": UART Service configured, RX/TX ready");
                    }
                }
            } else {
                FileLog.e(TAG + ": Service discovery failed with status: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(TX_CHARACTERISTIC_UUID)) {
                byte[] data = characteristic.getValue();
                if (listener != null) {
                    handler.post(() -> listener.onMessageReceived(data));
                }
                processIncomingPacket(data);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            isWriting = false;
            processWriteQueue();
        }
    };

    private synchronized void processWriteQueue() {
        if (isWriting || writeQueue.isEmpty() || rxCharacteristic == null || bluetoothGatt == null) {
            return;
        }
        byte[] nextPacket = writeQueue.poll();
        if (nextPacket != null) {
            isWriting = true;
            rxCharacteristic.setValue(nextPacket);
            bluetoothGatt.writeCharacteristic(rxCharacteristic);
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

    private void processIncomingPacket(byte[] encryptedData) {
        try {
            byte[] decrypted = decrypt(encryptedData);
            if (decrypted == null) return;

            byte[] assembled = fragmenter.onFragmentReceived(decrypted);
            if (assembled != null) {
                MeshProtocol.Packet packet = MeshProtocol.Packet.deserialize(assembled);
                if (packet == null) return;

                if (packet.type == MeshProtocol.TYPE_TXT_MSG || packet.type == MeshProtocol.TYPE_GRP_TXT) {
                    String messageText = new String(packet.payload);
                    int senderHash = (packet.path != null && packet.path.length > 0) ? packet.path[packet.path.length - 1] : 0;
                    
                    // Save to local storage
                    MeshStorage.getInstance().saveMessage(0, senderHash, messageText, false);
                    
                    handleMeshMessage(messageText);
                } else if (packet.type == MeshProtocol.TYPE_REQ) {
                    // Handle history requests or node info requests
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
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
