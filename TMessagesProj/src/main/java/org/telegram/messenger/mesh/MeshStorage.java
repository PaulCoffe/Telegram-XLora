package org.telegram.messenger.mesh;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MeshStorage — SQLite persistence for MeshCore companion protocol data.
 *
 * DB schema v3:
 *  - nodes: per-node metadata + TG user link
 *  - messages: chat history (channel + DM)
 *  - lora_channels: LoRa channel slots (0-7) per MeshCore spec
 *  - device_pins: BLE bonding PINs per device address
 *
 * Public key for the MeshCore default public channel (slot 0):
 *   8b3387e9c5cdea6ac9e5edbaa115cd72
 *
 * All SQL writes go through {@link #storageQueue} to prevent blocking the
 * GATT callback or main thread.
 */
public class MeshStorage extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "mesh_data.db";
    private static final int DATABASE_VERSION = 4;

    // MeshCore official public channel key (slot 0)
    public static final String PUBLIC_CHANNEL_KEY_HEX = "8b3387e9c5cdea6ac9e5edbaa115cd72";

    // Synthetic dialog ID base for LoRa channels in the Mesh folder.
    // Channel slot N → dialog_id = -(CHANNEL_DIALOG_BASE + N)
    // Designed to never collide with real Telegram IDs (which are positive longs < 2^40).
    private static final long CHANNEL_DIALOG_BASE = 2_000_000_000L;

    // Synthetic dialog ID base for direct Mesh contacts (unlinked to TG)
    private static final long CONTACT_DIALOG_BASE = 3_000_000_000L;

    // FIX: volatile for double-checked locking
    private static volatile MeshStorage Instance;

    // In-memory node→tgUser cache — avoids SQLite reads on GATT callbacks
    private final ConcurrentHashMap<String, Long> nodeUserCache = new ConcurrentHashMap<>();

    // In-memory channel slot cache — slot_index → LoraChannel
    private final ConcurrentHashMap<Integer, LoraChannel> channelCache = new ConcurrentHashMap<>();

    private final DispatchQueue storageQueue = new DispatchQueue("MeshStorageQueue");

    public static MeshStorage getInstance() {
        MeshStorage localInstance = Instance;
        if (localInstance == null) {
            synchronized (MeshStorage.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MeshStorage();
                }
            }
        }
        return localInstance;
    }

    public DispatchQueue getStorageQueue() {
        return storageQueue;
    }

    private MeshStorage() {
        super(ApplicationLoader.applicationContext, DATABASE_NAME, null, DATABASE_VERSION);
        preloadCaches();
    }

    // ============================================================================================
    // Schema
    // ============================================================================================

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE nodes (" +
                "pubkey TEXT PRIMARY KEY, " +
                "nickname TEXT, " +
                "tg_user_id INTEGER DEFAULT 0, " +
                "last_seen INTEGER, " +
                "last_rssi INTEGER, " +
                "last_hops INTEGER" +
                ")");

        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "dialog_id INTEGER, " +
                "sender_pubkey TEXT, " +    // pubkey prefix or empty for our own
                "text TEXT, " +
                "date INTEGER, " +
                "is_out INTEGER DEFAULT 0, " +
                "mesh_msg_id INTEGER DEFAULT 0" +  // random_id from MeshCore packet (for dedup)
                ");");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_dedup " +
                "ON messages(dialog_id, mesh_msg_id) WHERE mesh_msg_id != 0");

        db.execSQL("CREATE TABLE lora_channels (" +
                "slot_index INTEGER PRIMARY KEY, " +    // 0-7 per MeshCore spec
                "name TEXT NOT NULL DEFAULT '', " +
                "secret_hex TEXT, " +                  // 16-byte hex; NULL = public (slot 0)
                "is_public INTEGER DEFAULT 0" +        // 1 for slot 0 / hashtag channels
                ")");

        db.execSQL("CREATE TABLE device_pins (" +
                "address TEXT PRIMARY KEY, " +
                "pin INTEGER" +
                ")");

        // Seed slot 0 as the public channel (MeshCore public key)
        ContentValues pub = new ContentValues();
        pub.put("slot_index", 0);
        pub.put("name", "Primary");
        pub.put("secret_hex", PUBLIC_CHANNEL_KEY_HEX);
        pub.put("is_public", 1);
        db.insert("lora_channels", null, pub);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS device_pins (" +
                    "address TEXT PRIMARY KEY, pin INTEGER)");
        }
        if (oldVersion < 3) {
            // Replace old channels table with lora_channels
            db.execSQL("DROP TABLE IF EXISTS channels");

            db.execSQL("CREATE TABLE IF NOT EXISTS lora_channels (" +
                    "slot_index INTEGER PRIMARY KEY, " +
                    "name TEXT NOT NULL DEFAULT '', " +
                    "secret_hex TEXT, " +
                    "is_public INTEGER DEFAULT 0)");

            // Add sender_pubkey column to messages if missing
            try {
                db.execSQL("ALTER TABLE messages ADD COLUMN sender_pubkey TEXT");
            } catch (Exception ignored) {}

            // Seed public channel
            ContentValues pub = new ContentValues();
            pub.put("slot_index", 0);
            pub.put("name", "Primary");
            pub.put("secret_hex", PUBLIC_CHANNEL_KEY_HEX);
            pub.put("is_public", 1);
            db.insertWithOnConflict("lora_channels", null, pub, SQLiteDatabase.CONFLICT_IGNORE);
        }
        if (oldVersion < 4) {
            // Add deduplication: mesh_msg_id column + unique index
            try {
                db.execSQL("ALTER TABLE messages ADD COLUMN mesh_msg_id INTEGER DEFAULT 0");
            } catch (Exception ignored) {} // column may already exist if upgrading from freshly created v3
            try {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_dedup " +
                        "ON messages(dialog_id, mesh_msg_id) WHERE mesh_msg_id != 0");
            } catch (Exception ignored) {}
        }
    }

    // ============================================================================================
    // Cache preload
    // ============================================================================================

    /**
     * Preloads node→tgUser and channel slot caches on the storage thread.
     * Called once at construction.
     */
    private void preloadCaches() {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getReadableDatabase();

                // Node cache
                try (Cursor c = db.query("nodes", new String[]{"pubkey", "tg_user_id"},
                        "tg_user_id != 0", null, null, null, null)) {
                    while (c.moveToNext()) {
                        String pk = c.getString(0);
                        long id = c.getLong(1);
                        if (id != 0) nodeUserCache.put(pk, id);
                    }
                }

                // Channel cache
                try (Cursor c = db.query("lora_channels", null, null, null, null, null, null)) {
                    while (c.moveToNext()) {
                        LoraChannel ch = cursorToLoraChannel(c);
                        channelCache.put(ch.slotIndex, ch);
                    }
                }

                FileLog.d("MeshStorage: caches preloaded — " + nodeUserCache.size()
                        + " nodes, " + channelCache.size() + " channels");
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    // ============================================================================================
    // Node operations
    // ============================================================================================

    /** O(1) cache lookup — never touches SQLite. Safe on any thread. */
    public long getTgUserIdCached(String pubkey) {
        Long cached = nodeUserCache.get(pubkey);
        return cached != null ? cached : 0L;
    }

    public void updateNode(String pubkey, String nickname, int rssi, int hops) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues v = new ContentValues();
                v.put("pubkey", pubkey);
                if (nickname != null) v.put("nickname", nickname);
                v.put("last_seen", System.currentTimeMillis());
                v.put("last_rssi", rssi);
                v.put("last_hops", hops);
                db.insertWithOnConflict("nodes", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                AndroidUtilities.runOnUIThread(() ->
                        NotificationCenter.getGlobalInstance()
                                .postNotificationName(NotificationCenter.didUpdateMeshNodes));
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void linkNodeToUser(String pubkey, long tgUserId) {
        if (tgUserId != 0) {
            nodeUserCache.put(pubkey, tgUserId);
        } else {
            nodeUserCache.remove(pubkey);
        }
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues v = new ContentValues();
                v.put("tg_user_id", tgUserId);
                db.update("nodes", v, "pubkey = ?", new String[]{pubkey});
                AndroidUtilities.runOnUIThread(() ->
                        NotificationCenter.getGlobalInstance()
                                .postNotificationName(NotificationCenter.didUpdateMeshNodes));
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public ArrayList<MeshNode> getAllNodes() {
        ArrayList<MeshNode> nodes = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "nodes", null, null, null, null, null, "last_seen DESC")) {
            while (c.moveToNext()) {
                MeshNode n = new MeshNode();
                n.pubkey   = c.getString(0);
                n.nickname = c.getString(1);
                n.tgUserId = c.getLong(2);
                n.lastSeen = c.getLong(3);
                n.rssi     = c.getInt(4);
                n.hops     = c.getInt(5);
                nodes.add(n);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return nodes;
    }

    // ============================================================================================
    // LoRa channel operations (slots 0-7 per MeshCore spec)
    // ============================================================================================

    /**
     * Saves / updates a LoRa channel slot received from the device via PACKET_CHANNEL_INFO.
     * Called from MeshManager on GATT thread — writes via storageQueue.
     *
     * @param slotIndex  0-7
     * @param name       channel name (empty string = slot unused)
     * @param secretHex  16-byte hex secret; use {@link #PUBLIC_CHANNEL_KEY_HEX} for slot 0
     * @param isPublic   true for slot 0 (public) or hashtag channel
     */
    public void saveLoraChannel(int slotIndex, String name, String secretHex, boolean isPublic) {
        // Update cache immediately
        LoraChannel ch = new LoraChannel();
        ch.slotIndex = slotIndex;
        ch.name      = name;
        ch.secretHex = secretHex;
        ch.isPublic  = isPublic;
        channelCache.put(slotIndex, ch);

        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues v = new ContentValues();
                v.put("slot_index", slotIndex);
                v.put("name",       name);
                v.put("secret_hex", secretHex);
                v.put("is_public",  isPublic ? 1 : 0);
                db.insertWithOnConflict("lora_channels", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                FileLog.d("MeshStorage: saved channel slot[" + slotIndex + "] name='" + name + "'");
            } catch (Exception e) {
                FileLog.e(e);
            }
        });

        // Notify UI that channels list changed
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.didMeshChannelsUpdated));
    }

    /** Returns all LoRa channel slots (cache-first). */
    public ArrayList<LoraChannel> getLoraChannels() {
        ArrayList<LoraChannel> list = new ArrayList<>(channelCache.values());
        if (list.isEmpty()) {
            // Fallback: read from DB (should not happen after preload)
            try (Cursor c = getReadableDatabase().query(
                    "lora_channels", null, null, null, null, null, "slot_index ASC")) {
                while (c.moveToNext()) list.add(cursorToLoraChannel(c));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        list.sort((a, b) -> Integer.compare(a.slotIndex, b.slotIndex));
        return list;
    }

    /** Returns the LoraChannel for a given slot index (cache-first), or null. */
    public LoraChannel getLoraChannel(int slotIndex) {
        return channelCache.get(slotIndex);
    }

    /**
     * Returns the synthetic Telegram-style dialog_id for a LoRa channel slot.
     * These are large negative longs that never collide with real Telegram peer IDs.
     */
    public static long channelDialogId(int slotIndex) {
        return -(CHANNEL_DIALOG_BASE + slotIndex);
    }

    /**
     * Returns the synthetic dialog_id for a direct Mesh contact (pubkey prefix → stable id).
     */
    public static long contactDialogId(String pubKeyHex) {
        long hash = Math.abs((long) pubKeyHex.hashCode()) % 1_000_000_000L;
        return -(CONTACT_DIALOG_BASE + hash);
    }

    private LoraChannel cursorToLoraChannel(Cursor c) {
        LoraChannel ch = new LoraChannel();
        ch.slotIndex = c.getInt(0);
        ch.name      = c.getString(1);
        ch.secretHex = c.getString(2);
        ch.isPublic  = c.getInt(3) == 1;
        return ch;
    }

    // ============================================================================================
    // Message operations
    // ============================================================================================

    /**
     * Saves an incoming or outgoing channel message.
     *
     * @param slotIndex  LoRa channel slot (0-7)
     * @param senderPubkey hex pubkey prefix of sender (empty for our own outgoing)
     * @param text       message text
     * @param isOut      true = sent by us
     */
    public void saveChannelMessage(int slotIndex, String senderPubkey, String text, boolean isOut) {
        saveChannelMessage(slotIndex, senderPubkey, text, isOut, 0);
    }

    /** Saves a channel message with optional MeshCore packet ID for deduplication. */
    public void saveChannelMessage(int slotIndex, String senderPubkey, String text, boolean isOut, long meshMsgId) {
        long dialogId = channelDialogId(slotIndex);
        persistMessage(dialogId, senderPubkey != null ? senderPubkey : "", text, isOut, meshMsgId);
    }

    /**
     * Saves an incoming or outgoing direct contact message.
     *
     * @param pubKeyHex  hex pubkey prefix of the contact
     * @param text       message text
     * @param isOut      true = sent by us
     */
    public void saveContactMessage(String pubKeyHex, String text, boolean isOut) {
        saveContactMessage(pubKeyHex, text, isOut, 0);
    }

    /** Saves a contact message with optional MeshCore packet ID for deduplication. */
    public void saveContactMessage(String pubKeyHex, String text, boolean isOut, long meshMsgId) {
        long dialogId = contactDialogId(pubKeyHex);
        persistMessage(dialogId, isOut ? "" : pubKeyHex, text, isOut, meshMsgId);
    }

    /**
     * @deprecated Use {@link #saveChannelMessage} or {@link #saveContactMessage} instead.
     */
    @Deprecated
    public void saveMessage(long dialogId, int senderHash, String text, boolean isOut) {
        persistMessage(dialogId, String.valueOf(senderHash), text, isOut, 0);
    }

    private void persistMessage(long dialogId, String senderPubkey, String text, boolean isOut) {
        persistMessage(dialogId, senderPubkey, text, isOut, 0);
    }

    /**
     * Persists a message, with optional MeshCore-level deduplication.
     *
     * <p>If {@code meshMsgId} is non-zero we insert with INSERT OR IGNORE and the unique
     * index on (dialog_id, mesh_msg_id) silently drops duplicates.
     *
     * <p>If {@code meshMsgId} is zero we fall back to a fuzzy dedup: we skip the insert
     * if an identical (dialog_id, sender_pubkey, date, text) row already exists in the
     * last 5 seconds to protect against rapid re-sync replays.
     */
    private void persistMessage(long dialogId, String senderPubkey, String text,
                                boolean isOut, long meshMsgId) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                int nowSec = (int) (System.currentTimeMillis() / 1000);

                if (meshMsgId != 0) {
                    // Primary dedup path: relies on UNIQUE INDEX (dialog_id, mesh_msg_id)
                    ContentValues v = new ContentValues();
                    v.put("dialog_id",     dialogId);
                    v.put("sender_pubkey", senderPubkey);
                    v.put("text",          text);
                    v.put("date",          nowSec);
                    v.put("is_out",        isOut ? 1 : 0);
                    v.put("mesh_msg_id",   meshMsgId);
                    long rowId = db.insertWithOnConflict("messages", null, v,
                            SQLiteDatabase.CONFLICT_IGNORE);
                    if (rowId == -1) {
                        FileLog.d("MeshStorage: duplicate msg ignored (meshMsgId=" + meshMsgId + ")");
                    }
                } else {
                    // Fallback dedup: skip if same content seen in last 5 seconds
                    try (Cursor dup = db.query("messages",
                            new String[]{"id"},
                            "dialog_id=? AND sender_pubkey=? AND text=? AND date>?",
                            new String[]{String.valueOf(dialogId), senderPubkey, text,
                                    String.valueOf(nowSec - 5)},
                            null, null, null, "1")) {
                        if (dup.moveToFirst()) {
                            FileLog.d("MeshStorage: fuzzy-duplicate msg ignored");
                            return;
                        }
                    }
                    ContentValues v = new ContentValues();
                    v.put("dialog_id",     dialogId);
                    v.put("sender_pubkey", senderPubkey);
                    v.put("text",          text);
                    v.put("date",          nowSec);
                    v.put("is_out",        isOut ? 1 : 0);
                    v.put("mesh_msg_id",   0);
                    db.insert("messages", null, v);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public ArrayList<MeshMessage> getMessages(long dialogId, int limit) {
        ArrayList<MeshMessage> msgs = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("messages", null,
                "dialog_id = ?", new String[]{String.valueOf(dialogId)},
                null, null, "date DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                MeshMessage m = new MeshMessage();
                m.id           = c.getInt(0);
                m.dialogId     = c.getLong(1);
                m.senderPubkey = c.getString(2);
                m.text         = c.getString(3);
                m.date         = c.getInt(4);
                m.isOut        = c.getInt(5) == 1;
                msgs.add(m);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return msgs;
    }

    // ============================================================================================
    // Device PIN operations
    // ============================================================================================

    /** Persists the BLE PIN reported by device via PACKET_DEVICE_INFO. */
    public void saveDevicePin(String address, int pin) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues v = new ContentValues();
                v.put("address", address);
                v.put("pin",     pin);
                db.insertWithOnConflict("device_pins", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                FileLog.d("MeshStorage: saved BLE PIN for " + address);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Returns stored BLE PIN for a device, or 0 if none. */
    public int getDevicePin(String address) {
        try (Cursor c = getReadableDatabase().query("device_pins", new String[]{"pin"},
                "address = ?", new String[]{address}, null, null, null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    // ============================================================================================
    // Legacy compat (used by MeshSettingsActivity)
    // ============================================================================================

    @Deprecated
    public ArrayList<MeshChannel> getChannels() {
        ArrayList<MeshChannel> result = new ArrayList<>();
        for (LoraChannel lc : getLoraChannels()) {
            if (!lc.name.isEmpty()) {
                MeshChannel mc = new MeshChannel();
                mc.slotIndex = lc.slotIndex;
                mc.name      = lc.name;
                result.add(mc);
            }
        }
        if (result.isEmpty()) {
            MeshChannel bc = new MeshChannel();
            bc.slotIndex = 0;
            bc.name      = "Primary";
            result.add(bc);
        }
        return result;
    }

    // ============================================================================================
    // Data classes
    // ============================================================================================

    public static class MeshNode {
        public String pubkey;
        public String nickname;
        public long   tgUserId;
        public long   lastSeen;
        public int    rssi;
        public int    hops;
    }

    /**
     * Represents a MeshCore channel slot (0-7).
     *
     * Slot 0: public channel (public key = 8b3387e9c5cdea6ac9e5edbaa115cd72)
     * Slots 1-7: private / hashtag channels
     */
    public static class LoraChannel {
        public int     slotIndex;
        public String  name;
        public String  secretHex;   // 16-byte hex, null only during partial parse
        public boolean isPublic;

        /** Returns the synthetic Telegram-style dialog_id for this channel. */
        public long dialogId() { return channelDialogId(slotIndex); }
    }

    /**
     * Legacy channel model kept for API compatibility.
     * @deprecated Use {@link LoraChannel} instead.
     */
    @Deprecated
    public static class MeshChannel {
        public int    slotIndex;
        public String name;
    }

    public static class MeshMessage {
        public int     id;
        public long    dialogId;
        public String  senderPubkey;
        public String  text;
        public int     date;
        public boolean isOut;
    }

    // ============================================================================================
    // Mesh contact (node) operations — used by MessagesController.getMeshDialogs()
    // ============================================================================================

    /**
     * Returns all known Mesh nodes as {@link MeshContact} objects.
     * Contacts are nodes that have sent or received at least one message.
     * This is a synchronous DB read — call only when acceptable (e.g. not from GATT callback).
     */
    public ArrayList<MeshContact> getMeshContacts() {
        ArrayList<MeshContact> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "nodes",
                new String[]{"pubkey", "nickname", "tg_user_id"},
                null, null, null, null, "last_seen DESC")) {
            while (c.moveToNext()) {
                MeshContact mc = new MeshContact();
                mc.pubKeyHex = c.getString(0);
                mc.name      = c.isNull(1) ? "" : c.getString(1);
                mc.tgUserId  = c.getLong(2);
                result.add(mc);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    /**
     * Returns the text of the most recent message for a LoRa channel slot, or null.
     * Single-row query — cheap on small message tables.
     */
    public String getLastChannelMessageText(int slotIndex) {
        long dialogId = channelDialogId(slotIndex);
        try (Cursor c = getReadableDatabase().query(
                "messages", new String[]{"text"},
                "dialog_id = ?", new String[]{String.valueOf(dialogId)},
                null, null, "date DESC", "1")) {
            if (c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    /**
     * Returns the text of the most recent message for a direct Mesh contact, or null.
     */
    public String getLastContactMessageText(String pubKeyHex) {
        long dialogId = contactDialogId(pubKeyHex);
        try (Cursor c = getReadableDatabase().query(
                "messages", new String[]{"text"},
                "dialog_id = ?", new String[]{String.valueOf(dialogId)},
                null, null, "date DESC", "1")) {
            if (c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    /** A Mesh node presented as a contact in the virtual Mesh folder. */
    public static class MeshContact {
        public String pubKeyHex;
        public String name;
        public long   tgUserId;
    }
}
