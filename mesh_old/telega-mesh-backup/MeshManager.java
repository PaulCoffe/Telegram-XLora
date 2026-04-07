package org.thunderdog.challegram.mesh;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MeshManager is responsible for BLE communication with MeshCore-enabled LoRa modules.
 * It manages discovery, connection, and data exchange.
 */
public class MeshManager {
    private static final String TAG = "MeshManager";

    // Standard Nordic UART Service (often used by MeshCore/Meshtastic for simple serial)
    // We should verify the exact UUIDs for MeshCore
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    // MeshCore AES-256 PSK (32 bytes). This should be user-configurable.
    private static final byte[] DEFAULT_PSK = new byte[] {
        (byte)0x4d, (byte)0x65, (byte)0x73, (byte)0x68, (byte)0x43, (byte)0x6f, (byte)0x72, (byte)0x65, // MeshCore
        (byte)0x53, (byte)0x65, (byte)0x63, (byte)0x75, (byte)0x72, (byte)0x69, (byte)0x74, (byte)0x79, // Security
        (byte)0x32, (byte)0x35, (byte)0x36, (byte)0x42, (byte)0x69, (byte)0x74, (byte)0x4b, (byte)0x65, // 256BitKe
        (byte)0x79, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f, (byte)0x5f  // y_______
    };

    private final Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;

    public interface MeshReceivedListener {
        void onPacketReceived(byte[] data);
    }

    private MeshReceivedListener meshReceivedListener;
    private Tdlib tdlib;
    private final MeshFragmenter fragmenter;

    public MeshManager(Context context, Tdlib tdlib) {
        this.context = context;
        this.tdlib = tdlib;
        this.fragmenter = new MeshFragmenter();
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    public void setMeshReceivedListener(MeshReceivedListener listener) {
        this.meshReceivedListener = listener;
    }

    @SuppressLint("MissingPermission")
    public void connect(String deviceAddress) {
        if (bluetoothAdapter == null || deviceAddress == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    txCharacteristic = service.getCharacteristic(TX_CHARACTERISTIC_UUID);
                    rxCharacteristic = service.getCharacteristic(RX_CHARACTERISTIC_UUID);
                    gatt.setCharacteristicNotification(txCharacteristic, true);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (TX_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                processIncomingPacket(data, tdlib);
            }
        }
    };

    @SuppressLint("MissingPermission")
    public void sendData(byte[] data) {
        if (bluetoothGatt != null && rxCharacteristic != null) {
            try {
                byte[] encrypted = encrypt(data);
                rxCharacteristic.setValue(encrypted);
                bluetoothGatt.writeCharacteristic(rxCharacteristic);
            } catch (Exception e) {
                Log.e(TAG, "Encryption failed", e);
            }
        }
    }

    /**
     * Called when a packet is received from the mesh.
     * If the node is ONLINE, it acts as a Gateway and forwards the message to Telegram.
     */
    public void processIncomingPacket(byte[] data, Tdlib tdlib) {
        try {
            byte[] decrypted = decrypt(data);
            if (decrypted == null) return;

            byte[] assembled = fragmenter.onFragmentReceived(decrypted);
            if (assembled != null) {
                // Check if we have internet and should act as a gateway
                TdApi.NetworkType networkType = tdlib.getNetworkType();
                if (networkType != null && networkType.getConstructor() != TdApi.NetworkTypeNone.CONSTRUCTOR) {
                    // Gateway Mode: Relay to Telegram
                    String messageText = new String(assembled);
                    Log.i(TAG, "Gateway Mode: Relaying message to Telegram: " + messageText);
                    // In a real scenario, we'd need to know the destination chatId from the packet metadata.
                } else {
                    // Client Mode: Just show the packet
                    if (meshReceivedListener != null) {
                        meshReceivedListener.onPacketReceived(assembled);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
        }
    }

    private byte[] encrypt(byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        SecretKeySpec keySpec = new SecretKeySpec(DEFAULT_PSK, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] ciphertext = cipher.doFinal(data);
        
        // Return IV + Ciphertext
        ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
        buffer.put(iv);
        buffer.put(ciphertext);
        return buffer.array();
    }

    private byte[] decrypt(byte[] data) throws Exception {
        if (data.length < 16) return null;
        
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] iv = new byte[16];
        buffer.get(iv);
        byte[] ciphertext = new byte[data.length - 16];
        buffer.get(ciphertext);
        
        SecretKeySpec keySpec = new SecretKeySpec(DEFAULT_PSK, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(ciphertext);
    }
}
