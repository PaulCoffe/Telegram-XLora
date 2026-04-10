package org.telegram.messenger.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

/**
 * MeshTransportManager maintains the global state of the Mesh transport
 * and is responsible for routing incoming packets to the correct destination.
 */
public class MeshTransportManager implements MeshManager.MeshManagerListener {

    private static volatile MeshTransportManager Instance;

    // FIX #5: Static Gson + Type instances avoid repeated expensive allocation on every scroll/render
    private static final Gson GSON = new Gson();
    private static final Type PRESET_LIST_TYPE = new TypeToken<ArrayList<MeshPreset>>() {}.getType();

    private boolean meshEnabled;
    private String selectedDeviceAddress;

    // FIX #5: In-memory cache for user presets; invalidated on save/delete
    private List<MeshPreset> userPresetsCache = null;

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

    // ---- MeshPreset ----------------------------------------------------------------

    public static class MeshPreset {
        public String name;
        public long frequency;
        public float bandwidth;
        public int spreadingFactor;
        public int codingRate;
        public boolean isSystem;

        public MeshPreset(String name, long frequency, float bandwidth,
                          int spreadingFactor, int codingRate, boolean isSystem) {
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
        SYSTEM_PRESETS.add(new MeshPreset("Москва (MOW)",        868731018L, 62.5f,  7,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Липецк (LPK)",        868950012L, 62.5f,  9,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Бийск (BSK)",         869000000L, 62.5f,  8,  5, true));
        SYSTEM_PRESETS.add(new MeshPreset("Иркутск (IKT)",       868731018L, 62.5f,  7,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Иваново (IWA)",       868731018L, 62.5f,  8,  8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Хабаровск (KHV)",     864281250L, 62.5f,  8,  6, true));
        SYSTEM_PRESETS.add(new MeshPreset("Тверь (KLD)",         869169000L, 62.5f,  8,  8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Калуга (KLF)",        868731018L, 62.5f,  7,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Киров (KVX)",         868731018L, 62.5f,  8,  8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Казань (KZN)",        868731018L, 62.5f,  8,  6, true));
        SYSTEM_PRESETS.add(new MeshPreset("Новосибирск (OVB)",   869000000L, 62.5f,  9,  8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Ростов-на-Дону (ROV)",868731018L, 62.5f,  9,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Рязань (RZN)",        868880000L, 62.5f,  9,  5, true));
        SYSTEM_PRESETS.add(new MeshPreset("Екатеринбург (SVX)",  869047000L, 62.5f,  7,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Тамбов (TBW)",        868950000L, 125.0f, 10, 5, true));
        SYSTEM_PRESETS.add(new MeshPreset("Тула (TYA)",          868731018L, 62.5f,  7,  8, true));
        SYSTEM_PRESETS.add(new MeshPreset("Волгоград (VOG)",     869525000L, 62.5f,  7,  7, true));
        SYSTEM_PRESETS.add(new MeshPreset("Владивосток (VVO)",   864281250L, 62.5f,  8,  6, true));
    }

    // ---- Radio config fields -------------------------------------------------------

    private long frequency;
    private float bandwidth;
    private int spreadingFactor;
    private int codingRate;

    // ---- Constructor --------------------------------------------------------------

    private MeshTransportManager() {
        SharedPreferences prefs = prefs();
        meshEnabled        = prefs.getBoolean("mesh_enabled", false);
        selectedDeviceAddress = prefs.getString("mesh_device_address", null);
        frequency          = prefs.getLong("radio_freq", 868731018L);
        bandwidth          = prefs.getFloat("radio_bw", 62.5f);
        spreadingFactor    = prefs.getInt("radio_sf", 7);
        codingRate         = prefs.getInt("radio_cr", 7);

        MeshManager.getInstance().addListener(this);
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("mesh_config", Context.MODE_PRIVATE);
    }

    // ---- Radio config getters/setters -----------------------------------------

    public long    getFrequency()       { return frequency; }
    public float   getBandwidth()       { return bandwidth; }
    public int     getSpreadingFactor() { return spreadingFactor; }
    public int     getCodingRate()      { return codingRate; }

    public void setRadioConfig(long freq, float bw, int sf, int cr) {
        this.frequency       = freq;
        this.bandwidth       = bw;
        this.spreadingFactor = sf;
        this.codingRate      = cr;

        prefs().edit()
                .putLong("radio_freq", freq)
                .putFloat("radio_bw", bw)
                .putInt("radio_sf", sf)
                .putInt("radio_cr", cr)
                .apply();

        MeshManager.getInstance().sendRadioConfig(freq, bw, sf, cr);
    }

    // ---- Mesh enable/disable ----------------------------------------------------

    public boolean isMeshEnabled() { return meshEnabled; }

    public void setMeshEnabled(boolean enabled) {
        this.meshEnabled = enabled;
        prefs().edit().putBoolean("mesh_enabled", enabled).apply();

        MessagesController.getInstance(UserConfig.selectedAccount).checkMeshFilter();

        if (enabled) {
            MeshManager.getInstance().startScanning();
            MeshForegroundService.start();
        } else {
            MeshManager.getInstance().stopAll();
            MeshForegroundService.stop();
        }
    }

    // ---- Device address ----------------------------------------------------------

    public String getSelectedDeviceAddress() { return selectedDeviceAddress; }

    public void setSelectedDeviceAddress(String address) {
        this.selectedDeviceAddress = address;
        prefs().edit().putString("mesh_device_address", address).apply();
    }

    // ---- Presets (FIX #5: cached, no Gson allocation on each call) ---------------

    public List<MeshPreset> getPresets() {
        List<MeshPreset> all = new ArrayList<>(SYSTEM_PRESETS);
        // FIX #5: Use cached list; only parse JSON once
        if (userPresetsCache == null) {
            userPresetsCache = loadUserPresetsFromDisk();
        }
        all.addAll(userPresetsCache);
        return all;
    }

    public void saveUserPreset(String name) {
        if (userPresetsCache == null) userPresetsCache = loadUserPresetsFromDisk();
        for (int i = 0; i < userPresetsCache.size(); i++) {
            if (userPresetsCache.get(i).name.equalsIgnoreCase(name)) {
                userPresetsCache.remove(i);
                break;
            }
        }
        userPresetsCache.add(new MeshPreset(name, frequency, bandwidth, spreadingFactor, codingRate, false));
        persistUserPresets(userPresetsCache);
    }

    public void deleteUserPreset(String name) {
        if (userPresetsCache == null) userPresetsCache = loadUserPresetsFromDisk();
        for (int i = 0; i < userPresetsCache.size(); i++) {
            if (userPresetsCache.get(i).name.equalsIgnoreCase(name)) {
                userPresetsCache.remove(i);
                break;
            }
        }
        persistUserPresets(userPresetsCache);
    }

    private List<MeshPreset> loadUserPresetsFromDisk() {
        String json = prefs().getString("user_presets", null);
        if (json != null) {
            try {
                List<MeshPreset> list = GSON.fromJson(json, PRESET_LIST_TYPE);
                if (list != null) return list;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return new ArrayList<>();
    }

    private void persistUserPresets(List<MeshPreset> list) {
        prefs().edit().putString("user_presets", GSON.toJson(list)).apply();
    }

    public String getCurrentPresetName() {
        MeshPreset current = new MeshPreset("", frequency, bandwidth, spreadingFactor, codingRate, false);
        for (MeshPreset p : getPresets()) {
            if (p.equals(current)) return p.name;
        }
        return "Custom";
    }

    // ---- MeshManagerListener callbacks ------------------------------------------

    @Override
    public void onDevicesUpdated() {}

    @Override
    public void onConnectionStateChanged(boolean connected) {}

    /**
     * FIX #6: Non-blocking — uses in-memory cache for node lookup instead of SQLite.
     * The updateNode call is async (posts to storageQueue internally).
     */
    @Override
    public void onMessageReceived(byte[] data, int rssi, int hops) {
        MeshProtocol.Packet packet = MeshProtocol.Packet.deserialize(data);
        if (packet == null) return;

        String senderId = (packet.path != null && packet.path.length > 0)
                ? String.valueOf(packet.path[0]) : "unknown";

        // Async — posts to storageQueue, never blocks
        MeshStorage.getInstance().updateNode(senderId, null, rssi, hops);

        // FIX #6: Cache lookup — O(1), no SQLite, safe on any thread
        long tgUserId = MeshStorage.getInstance().getTgUserIdCached(senderId);
        if (tgUserId != 0) {
            routeToTelegramChat(tgUserId, packet, rssi, hops);
        } else {
            routeToMeshPureChat(senderId, packet, rssi, hops);
        }
    }

    /**
     * Routes a message from a node linked to a Telegram user into that user's chat.
     */
    private void routeToTelegramChat(long userId, MeshProtocol.Packet packet, int rssi, int hops) {
        if (packet.type != MeshProtocol.TYPE_TXT_MSG) return;
        if (packet.payload == null || packet.payload.length == 0) return;

        final String text = new String(packet.payload, StandardCharsets.UTF_8);

        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = text;
        message.date    = (int) (System.currentTimeMillis() / 1000);
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = userId;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = userId;
        message.out    = false;
        message.unread = true;

        // Embed Mesh metadata into custom_params for UI indicator downstream
        try {
            message.custom_params = new org.telegram.tgnet.NativeByteBuffer(8);
            message.custom_params.writeInt32(0x4D455348); // "MESH" magic
            message.custom_params.writeInt32(hops);
        } catch (Exception e) {
            FileLog.e(e);
        }

        TLRPC.TL_messages_messages container = new TLRPC.TL_messages_messages();
        container.messages.add(message);

        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController.getInstance(UserConfig.selectedAccount).processLoadedMessages(
                        container, 1, userId, 0, 1, 0, 0, false,
                        0, 0, 0, 0, 0, 1, false, 0, 0, 0, false, 0, true, false, null);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * FIX #7: Routes a message from an unlinked Mesh node into the virtual Mesh folder.
     *
     * A stable synthetic dialog ID is derived from the sender's hash so that all messages
     * from the same node group into the same "conversation" in the Mesh folder.
     */
    private void routeToMeshPureChat(String senderId, MeshProtocol.Packet packet, int rssi, int hops) {
        if (packet.payload == null || packet.payload.length == 0) return;

        // Only handle text and group text packets; ignore control packets
        if (packet.type != MeshProtocol.TYPE_TXT_MSG &&
            packet.type != MeshProtocol.TYPE_GRP_TXT) return;

        final String text        = new String(packet.payload, StandardCharsets.UTF_8);
        final int    senderHash  = senderId.hashCode();

        // Synthetic dialog ID: negative, unique per sender, fits in long without collision with real TG IDs
        final long meshDialogId  = -(Math.abs((long) senderHash) % 1_000_000_000L + 1_000_000_001L);

        // Persist message asynchronously (storageQueue inside MeshStorage)
        MeshStorage.getInstance().saveMessage(meshDialogId, senderHash, text, false);

        // Notify the Mesh folder UI on the main thread
        AndroidUtilities.runOnUIThread(() ->
            NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.didUpdateMeshNodes)
        );
    }
}
