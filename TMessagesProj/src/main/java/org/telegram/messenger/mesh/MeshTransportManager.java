package org.telegram.messenger.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.List;
import android.util.Base64;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

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

    public static class MeshPreset {
        public String name;
        public long frequency;
        public float bandwidth;
        public int spreadingFactor;
        public int codingRate;
        public boolean isSystem;

        public MeshPreset(String name, long frequency, float bandwidth, int spreadingFactor, int codingRate, boolean isSystem) {
            this.name = name;
            this.frequency = frequency;
            this.bandwidth = bandwidth;
            this.spreadingFactor = spreadingFactor;
            this.codingRate = codingRate;
            this.isSystem = isSystem;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MeshPreset)) return false;
            MeshPreset other = (MeshPreset) obj;
            return frequency == other.frequency && bandwidth == other.bandwidth && 
                   spreadingFactor == other.spreadingFactor && codingRate == other.codingRate;
        }
    }

    private static final List<MeshPreset> SYSTEM_PRESETS = new ArrayList<>();
    static {
        SYSTEM_PRESETS.add(new MeshPreset("Москва (MOW)", 868731018L, 62.5f, 7, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Липецк (LPK)", 868950012L, 62.5f, 9, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Бийск (BSK)", 869000000L, 62.5f, 8, 5, true));
        SYSTEM_PRESETS.add(new MeshPreset("Иркутск (IKT)", 868731018L, 62.5f, 7, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Иваново (IWA)", 868731018L, 62.5f, 8, 8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Хабаровск (KHV)", 864281250L, 62.5f, 8, 6, true));
        SYSTEM_PRESETS.add(new MeshPreset("Тверь (KLD)", 869169000L, 62.5f, 8, 8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Калуга (KLF)", 868731018L, 62.5f, 7, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Киров (KVX)", 868731018L, 62.5f, 8, 8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Казань (KZN)", 868731018L, 62.5f, 8, 6, true));
        SYSTEM_PRESETS.add(new MeshPreset("Новосибирск (OVB)", 869000000L, 62.5f, 9, 8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Ростов-на-Дону (ROV)", 868731018L, 62.5f, 9, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Рязань (RZN)", 868880000L, 62.5f, 9, 5, true));
        SYSTEM_PRESETS.add(new MeshPreset("Екатеринбург (SVX)", 869047000L, 62.5f, 7, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Тамбов (TBW)", 868950000L, 125.0f, 10, 5, true));
        SYSTEM_PRESETS.add(new MeshPreset("Тула (TYA)", 868731018L, 62.5f, 7, 8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Волгоград (VOG)", 869525000L, 62.5f, 7, 7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Владивосток (VVO)", 864281250L, 62.5f, 8, 6, true));
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

    public List<MeshPreset> getPresets() {
        List<MeshPreset> all = new ArrayList<>(SYSTEM_PRESETS);
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE);
        String json = preferences.getString("user_presets", null);
        if (json != null) {
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<ArrayList<MeshPreset>>() {}.getType();
                List<MeshPreset> userPresets = gson.fromJson(json, type);
                if (userPresets != null) {
                    all.addAll(userPresets);
                }
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }
        return all;
    }

    public void saveUserPreset(String name) {
        List<MeshPreset> userPresets = getUserPresetsOnly();
        // Remove existing if same name
        for (int i = 0; i < userPresets.size(); i++) {
            if (userPresets.get(i).name.equalsIgnoreCase(name)) {
                userPresets.remove(i);
                break;
            }
        }
        userPresets.add(new MeshPreset(name, frequency, bandwidth, spreadingFactor, codingRate, false));
        saveUserPresetsList(userPresets);
    }

    public void deleteUserPreset(String name) {
        List<MeshPreset> userPresets = getUserPresetsOnly();
        for (int i = 0; i < userPresets.size(); i++) {
            if (userPresets.get(i).name.equalsIgnoreCase(name)) {
                userPresets.remove(i);
                break;
            }
        }
        saveUserPresetsList(userPresets);
    }

    private List<MeshPreset> getUserPresetsOnly() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE);
        String json = preferences.getString("user_presets", null);
        if (json != null) {
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<ArrayList<MeshPreset>>() {}.getType();
                List<MeshPreset> userPresets = gson.fromJson(json, type);
                if (userPresets != null) return userPresets;
            } catch (Exception e) {}
        }
        return new ArrayList<>();
    }

    private void saveUserPresetsList(List<MeshPreset> list) {
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putString("user_presets", new Gson().toJson(list));
        editor.apply();
    }

    public String getCurrentPresetName() {
        MeshPreset current = new MeshPreset("", frequency, bandwidth, spreadingFactor, codingRate, false);
        for (MeshPreset p : getPresets()) {
            if (p.equals(current)) return p.name;
        }
        return "Custom";
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
        try {
            message.custom_params = new org.telegram.tgnet.NativeByteBuffer(8);
            message.custom_params.writeInt32(0x4D455348); // "MESH" magic
            message.custom_params.writeInt32(hops);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
        
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
