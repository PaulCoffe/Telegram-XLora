package org.telegram.messenger.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MeshTransportManager — global coordinator of the MeshCore transport layer.
 *
 * Responsibilities:
 *  - Maintains user-facing Mesh state (enabled/disabled, selected device, radio config)
 *  - Receives typed callbacks from MeshManager and routes them to the correct destination:
 *      * Channel messages  → LoRa channel synthetic dialog (slot 0-7)
 *      * Contact messages  → Contact synthetic dialog (pubkey-derived)
 *      * Linked TG user    → Real Telegram chat via MessagesController (future)
 *  - Persists messages via MeshStorage (async, never blocks GATT thread)
 *  - Posts NotificationCenter events for UI refresh
 *
 * Threading:
 *  MeshManager delivers all typed callbacks on the main looper (handler.post).
 *  All storage writes go through MeshStorage.storageQueue.
 *  NotificationCenter.postNotificationName must be called on the main thread — guaranteed here.
 */
public class MeshTransportManager implements MeshManager.MeshManagerListener {

    private static volatile MeshTransportManager Instance;

    // Gson + Type instances — static to avoid repeated allocation
    private static final Gson GSON = new Gson();
    private static final Type PRESET_LIST_TYPE = new TypeToken<ArrayList<MeshPreset>>() {}.getType();

    // Synthetic message IDs for injected Mesh messages start at this negative base
    // to never collide with real Telegram message IDs (must be unique per session)
    private static final AtomicInteger meshSyntheticMsgId = new AtomicInteger(-90_000_000);

    private boolean meshEnabled;
    private String selectedDeviceAddress;

    // In-memory cache for user presets; invalidated on save/delete
    private List<MeshPreset> userPresetsCache = null;

    // Self-info from the connected device (set on PACKET_SELF_INFO)
    private String selfPubKeyHex;
    private String selfDeviceName;

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
        meshEnabled           = prefs.getBoolean("mesh_enabled", false);
        selectedDeviceAddress = prefs.getString("mesh_device_address", null);
        frequency             = prefs.getLong("radio_freq", 868731018L);
        bandwidth             = prefs.getFloat("radio_bw", 62.5f);
        spreadingFactor       = prefs.getInt("radio_sf", 7);
        codingRate            = prefs.getInt("radio_cr", 7);

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

    // ---- Self info ---------------------------------------------------------------

    /** Returns the public key hex of the connected device (set after handshake). */
    public String getSelfPubKeyHex()  { return selfPubKeyHex; }

    /** Returns the device display name (set after handshake). */
    public String getSelfDeviceName() { return selfDeviceName; }

    // ---- Presets -----------------------------------------------------------------

    public List<MeshPreset> getPresets() {
        List<MeshPreset> all = new ArrayList<>(SYSTEM_PRESETS);
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

    // ---- MeshManagerListener callbacks -------------------------------------------
    // All callbacks arrive on the main thread (MeshManager posts via handler).

    @Override
    public void onDevicesUpdated() {}

    @Override
    public void onConnectionStateChanged(boolean connected) {
        if (!connected) {
            FileLog.d("MeshTransportManager: device disconnected");
        }
    }

    /**
     * Device self-info received after CMD_APP_START handshake.
     * Updates radio config to match what the device actually reports.
     */
    @Override
    public void onSelfInfoLoaded(String pubKeyHex, String name,
                                  long freqHz, float bwKHz, int sf, int cr) {
        selfPubKeyHex  = pubKeyHex;
        selfDeviceName = name;

        // Sync local radio config to match device
        if (freqHz > 0) {
            this.frequency       = freqHz;
            this.bandwidth       = bwKHz;
            this.spreadingFactor = sf;
            this.codingRate      = cr;
            prefs().edit()
                    .putLong("radio_freq", freqHz)
                    .putFloat("radio_bw",  bwKHz)
                    .putInt("radio_sf",    sf)
                    .putInt("radio_cr",    cr)
                    .apply();
        }

        FileLog.d("MeshTransportManager: handshake OK device='" + name
                + "' pubkey=" + (pubKeyHex != null && pubKeyHex.length() > 12
                        ? pubKeyHex.substring(0, 12) + "..." : pubKeyHex)
                + " freq=" + freqHz + " sf=" + sf);
    }

    /**
     * A LoRa channel slot was loaded from device (PACKET_CHANNEL_INFO).
     * MeshStorage already saved it; here we notify the UI.
     */
    @Override
    public void onChannelLoaded(int slotIndex, String name, String secretHex, boolean isPublic) {
        // MeshStorage.saveLoraChannel() was already called by MeshManager's parseChannelInfo.
        // Just notify the UI so MeshSettingsActivity / folder refresh picks it up.
        NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.didMeshChannelsUpdated);
    }

    /**
     * Incoming LoRa channel message (PACKET_CHANNEL_MSG_RECV / V3).
     *
     * Routes to the synthetic dialog for that channel slot in the Mesh folder.
     */
    @Override
    public void onChannelMessage(int channelIndex, String senderInfo, String text,
                                  long timestampSec, int snr, int hops) {
        if (text == null || text.isEmpty()) return;

        // Persist to local DB (async)
        MeshStorage.getInstance().saveChannelMessage(channelIndex, senderInfo, text, false);

        // Determine the name of the channel for display
        MeshStorage.LoraChannel ch = MeshStorage.getInstance().getLoraChannel(channelIndex);
        String channelName = (ch != null && !ch.name.isEmpty()) ? ch.name : "Channel " + channelIndex;

        // Notify UI: Mesh folder should show new message badge
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.didReceiveMeshChannelMessage,
                channelIndex, text, channelName);

        // Also trigger general node update for badge counters
        NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.didUpdateMeshNodes);
    }

    /**
     * Incoming LoRa direct contact message (PACKET_CONTACT_MSG_RECV / V3).
     *
     * Phase 6.2 — Hybrid Mode:
     * If the sender pubkey is linked to a Telegram user, we inject a synthetic TL_message
     * into the real TG chat so that the conversation appears seamlessly in the TG UI.
     * The message is prefixed with "📡 LoRa" to indicate the transport origin.
     */
    @Override
    public void onContactMessage(String pubKeyHex, String text,
                                  long timestampSec, int snr, int hops) {
        if (text == null || text.isEmpty()) return;

        // Check if this node is linked to a real TG user
        long tgUserId = MeshStorage.getInstance().getTgUserIdCached(pubKeyHex);
        if (tgUserId != 0) {
            // Inject into the real Telegram chat for seamless hybrid experience
            injectMeshMessageToTgChat(tgUserId, pubKeyHex, text, (int) timestampSec);
        }

        // Always persist to Mesh contact storage
        MeshStorage.getInstance().saveContactMessage(pubKeyHex, text, false);

        // Notify UI
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.didReceiveMeshContactMessage, pubKeyHex, text);

        NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.didUpdateMeshNodes);
    }

    /**
     * Injects a synthetic Telegram message into a real user chat to represent an
     * incoming LoRa contact message from a linked node.
     *
     * The message is marked with a "📡 LoRa" prefix so the user can distinguish it
     * from regular Telegram messages. It is NOT persisted in Telegram's database —
     * it exists only in memory and is backed by MeshStorage.
     *
     * Threading: called from onContactMessage which always runs on the main thread.
     *
     * @param tgUserId   the Telegram user ID to inject the message into
     * @param pubKeyHex  the sender's Mesh pubkey prefix (for logging)
     * @param text       the message text
     * @param dateSeconds Unix timestamp in seconds
     */
    private void injectMeshMessageToTgChat(long tgUserId, String pubKeyHex, String text, int dateSeconds) {
        try {
            int account = UserConfig.selectedAccount;
            MessagesController mc = MessagesController.getInstance(account);

            // Build a synthetic TLRPC.TL_message
            TLRPC.TL_message msg = new TLRPC.TL_message();
            msg.id      = meshSyntheticMsgId.getAndDecrement();  // unique negative ID
            msg.date    = dateSeconds;
            msg.message = "📡 LoRa: " + text;
            msg.flags  |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;        // has from_id

            // from_id = sender (the linked TG user)
            TLRPC.TL_peerUser fromPeer = new TLRPC.TL_peerUser();
            fromPeer.user_id = tgUserId;
            msg.from_id = fromPeer;

            // peer_id = the DM dialog (user → me)
            TLRPC.TL_peerUser peerUser = new TLRPC.TL_peerUser();
            peerUser.user_id = tgUserId;
            msg.peer_id = peerUser;

            // Build MessageObject (in = not from us)
            MessageObject msgObj = new MessageObject(account, msg, false, false);

            ArrayList<MessageObject> arrayList = new ArrayList<>(1);
            arrayList.add(msgObj);

            // Route to MessagesController to show in the TG chat + update dialogs list
            mc.updateInterfaceWithMessages(tgUserId, arrayList, 0);

            FileLog.d("MeshTransportManager: injected LoRa msg into TG chat userId=" + tgUserId
                    + " from pubkey=" + pubKeyHex.substring(0, Math.min(12, pubKeyHex.length())));
        } catch (Exception e) {
            FileLog.e("MeshTransportManager: failed to inject LoRa message into TG chat", e);
        }
    }
}
