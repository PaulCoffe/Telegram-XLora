package org.telegram.messenger.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import android.util.Base64;

/**
 * MeshTransportManager maintains the global state of the Mesh transport.
 */
public class MeshTransportManager implements MeshManager.MeshManagerListener {
    private static volatile MeshTransportManager Instance;
    private boolean meshEnabled;
    private String selectedDeviceAddress;

    public static MeshTransportManager getInstance() {
        MeshTransportManager localInstance = Instance;
        if (localInstance == null) {
            synchronized (MeshTransportManager.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MeshTransportManager();
                }
            }
        }
        return localInstance;
    }

    private long frequency;
    private float bandwidth;
    private int spreadingFactor;
    private int codingRate;

    private MeshTransportManager() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE);
        meshEnabled = preferences.getBoolean("mesh_enabled", false);
        selectedDeviceAddress = preferences.getString("mesh_device_address", null);
        
        frequency = preferences.getLong("radio_freq", 868731018L);
        bandwidth = preferences.getFloat("radio_bw", 62.5f);
        spreadingFactor = preferences.getInt("radio_sf", 7);
        codingRate = preferences.getInt("radio_cr", 7);

        MeshManager.getInstance().addListener(this);
    }

    public long getFrequency() { return frequency; }
    public float getBandwidth() { return bandwidth; }
    public int getSpreadingFactor() { return spreadingFactor; }
    public int getCodingRate() { return codingRate; }

    public void setRadioConfig(long freq, float bw, int sf, int cr) {
        this.frequency = freq;
        this.bandwidth = bw;
        this.spreadingFactor = sf;
        this.codingRate = cr;
        
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putLong("radio_freq", freq);
        editor.putFloat("radio_bw", bw);
        editor.putInt("radio_sf", sf);
        editor.putInt("radio_cr", cr);
        editor.apply();
        
        MeshManager.getInstance().sendRadioConfig(freq, bw, sf, cr);
    }

    public boolean isMeshEnabled() {
        return meshEnabled;
    }

    public void setMeshEnabled(boolean enabled) {
        this.meshEnabled = enabled;
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putBoolean("mesh_enabled", enabled);
        editor.apply();
        
        org.telegram.messenger.MessagesController.getInstance(org.telegram.messenger.UserConfig.selectedAccount).checkMeshFilter();

        if (enabled) {
            MeshManager.getInstance().startScanning();
        } else {
            MeshManager.getInstance().stopAll();
        }
    }

    public String getSelectedDeviceAddress() {
        return selectedDeviceAddress;
    }

    public void setSelectedDeviceAddress(String address) {
        this.selectedDeviceAddress = address;
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putString("mesh_device_address", address);
        editor.apply();
    }

    @Override
    public void onDevicesUpdated() {}

    @Override
    public void onConnectionStateChanged(boolean connected) {}

    @Override
    public void onMessageReceived(byte[] data, int rssi, int hops) {
        MeshProtocol.Packet packet = MeshProtocol.Packet.deserialize(data);
        if (packet == null) return;

        // In MeshCore protocol, first node in path is often the direct sender hash if we don't have pubkey
        String senderId = (packet.path != null && packet.path.length > 0) ? String.valueOf(packet.path[0]) : "unknown";
        
        // Update storage with discovered node
        MeshStorage.getInstance().updateNode(senderId, null, rssi, hops);
        
        long tgUserId = MeshStorage.getInstance().getTgUserIdForNode(senderId);
        if (tgUserId != 0) {
            routeToTelegramChat(tgUserId, packet, rssi, hops);
        } else {
            routeToMeshPureChat(senderId, packet, rssi, hops);
        }
    }

    private void routeToTelegramChat(long userId, MeshProtocol.Packet packet, int rssi, int hops) {
        if (packet.type != MeshProtocol.TYPE_TXT_MSG) return;
        String text = new String(packet.payload);
        
        // Simulate incoming message for user
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = text;
        message.date = (int) (System.currentTimeMillis() / 1000);
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = userId;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = userId;
        message.out = false;
        message.unread = true;
        
        // Add custom params for Mesh indicator
        message.custom_params = new org.telegram.tgnet.NativeByteBuffer(8);
        message.custom_params.writeInt32(0x4D455348); // "MESH" magic
        message.custom_params.writeInt32(hops);
        
        // Pack into TLRPC container
        TLRPC.TL_messages_messages messagesRes = new TLRPC.TL_messages_messages();
        messagesRes.messages.add(message);
        
        // Execute on UI thread to prevent crashes and ensure UI update
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            MessagesController.getInstance(UserConfig.selectedAccount).processLoadedMessages(
                messagesRes, 1, userId, 0, 1, 0, 0, false, 0, 
                0, 0, 0, 0, 1, false, 0, 0, 0, false, 0, true, false, null
            );
        });
    }

    private void routeToMeshPureChat(String senderId, MeshProtocol.Packet packet, int rssi, int hops) {
        // Handle pure mesh chat (virtual dialogs logic)
        // ... (Similar to above but with Mesh-prefixed IDs)
    }
}
