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
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.List;
import java.util.UUID;

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
    private static final UUID TX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    // MeshCore AES-256 PSK (32 bytes).
    private static final byte[] MESH_PSK = new byte[] {
        (byte)0x4d, (byte)0x65, (byte)0x73, (byte)0x68, (byte)0x43, (byte)0x6f, (byte)0x72, (byte)0x65, // MeshCore
        (byte)0x53, (byte)0x65, (byte)0x63, (byte)0x75, (byte)0x72, (byte)0x69, (byte)0x74, (byte)0x79, // Security
        (byte)0x32, (byte)0x35, (byte)0x36, (byte)0x42, (byte)0x69, (byte)0x74, (byte)0x4b, (byte)0x65, // 256BitKe
        (byte)0x79, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f  // y_______
    };
    
    private final MeshFragmenter fragmenter = new MeshFragmenter();
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private boolean isScanning = false;
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

    public void startScanning() {
        if (isScanning) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;

        final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;

        isScanning = true;
        foundDevices.clear();
        scanner.startScan(scanCallback);
        
        // Stop scanning after 30 seconds
        handler.postDelayed(() -> stopScanning(), 30000);
    }

    public void stopScanning() {
        if (!isScanning) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
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
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UART_SERVICE_UUID);
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
                    BluetoothGattCharacteristic rx = service.getCharacteristic(RX_CHARACTERISTIC_UUID);
                    gatt.setCharacteristicNotification(rx, true);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic.getUuid().equals(RX_CHARACTERISTIC_UUID)) {
                processIncomingPacket(characteristic.getValue());
            }
        }
    };

    private void processIncomingPacket(byte[] encryptedData) {
        try {
            byte[] decrypted = decrypt(encryptedData);
            byte[] assembled = fragmenter.onFragmentReceived(decrypted);
            if (assembled != null) {
                String messageText = new String(assembled);
                handleMeshMessage(messageText);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void onNetworkStatusChanged(boolean online) {
        if (online) {
            FileLog.d("MeshManager: Network is online, starting gateway checks.");
            // Here we would scan for pending messages in a mesh-specific queue
        } else {
            FileLog.d("MeshManager: Network is offline, relying on mesh transport.");
        }
    }

    private void handleMeshMessage(String text) {
        // Simple protocol check: if text starts with "RELAY:", it's for someone else.
        if (text.startsWith("RELAY:")) {
            if (ApplicationLoader.isNetworkOnline()) {
                String payload = text.substring(6);
                FileLog.d("MeshManager: Acting as Gateway. Relaying: " + payload);
                // In a future version, payload would contain destination info.
            } else {
                FileLog.d("MeshManager: Received relay request but we are offline. Dropping or re-broadcast.");
            }
        } else {
            // It's a message for us.
            FileLog.d("MeshManager: Received Direct Message: " + text);
            // TODO: Inject into MessagesController or show as a pseudo-notification.
        }
    }

    public void sendData(byte[] data) {
        if (txCharacteristic == null || bluetoothGatt == null) return;

        try {
            byte[] encrypted = encrypt(data);
            List<MeshFragmenter.Fragment> fragments = fragmenter.fragment(encrypted, (int) System.currentTimeMillis());
            for (MeshFragmenter.Fragment f : fragments) {
                txCharacteristic.setValue(f.serialize());
                bluetoothGatt.writeCharacteristic(txCharacteristic);
                // BLE write is usually async, in production we should wait for onCharacteristicWrite
                Thread.sleep(50); 
            }
        } catch (Exception e) {
            FileLog.e(e);
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
