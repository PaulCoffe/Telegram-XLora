package org.telegram.messenger.mesh;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DispatchQueue;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class MeshStorage extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "mesh_data.db";
    private static final int DATABASE_VERSION = 2;

    // FIX #1: volatile for double-checked locking thread safety
    private static volatile MeshStorage Instance;

    // FIX #2: In-memory cache avoids SQLite reads on the message-routing thread
    private final ConcurrentHashMap<String, Long> nodeUserCache = new ConcurrentHashMap<>();

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
        // Preload the node→tgUser mapping into memory on the storage thread
        preloadNodeCache();
    }

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
                "sender_hash INTEGER, " +
                "text TEXT, " +
                "date INTEGER, " +
                "is_out INTEGER, " +
                "hops INTEGER" +
                ")");

        db.execSQL("CREATE TABLE channels (" +
                "hash INTEGER PRIMARY KEY, " +
                "name TEXT" +
                ")");

        db.execSQL("CREATE TABLE device_pins (" +
                "address TEXT PRIMARY KEY, " +
                "pin INTEGER" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS device_pins (" +
                    "address TEXT PRIMARY KEY, " +
                    "pin INTEGER" +
                    ")");
        }
    }

    /**
     * Preloads all node→tgUserId mappings into the in-memory cache.
     * Called once at construction time on the storage background thread.
     */
    private void preloadNodeCache() {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getReadableDatabase();
                Cursor cursor = db.query("nodes",
                        new String[]{"pubkey", "tg_user_id"},
                        "tg_user_id != 0", null, null, null, null);
                while (cursor.moveToNext()) {
                    String pubkey = cursor.getString(0);
                    long tgId = cursor.getLong(1);
                    if (tgId != 0) nodeUserCache.put(pubkey, tgId);
                }
                cursor.close();
                FileLog.d("MeshStorage: node cache preloaded, " + nodeUserCache.size() + " entries");
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Fast non-blocking lookup — uses in-memory cache, never touches SQLite.
     * Safe to call from any thread including GATT callbacks.
     */
    public long getTgUserIdCached(String pubkey) {
        Long cached = nodeUserCache.get(pubkey);
        return cached != null ? cached : 0L;
    }

    public void updateNode(String pubkey, String nickname, int rssi, int hops) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put("pubkey", pubkey);
                if (nickname != null) values.put("nickname", nickname);
                values.put("last_seen", System.currentTimeMillis());
                values.put("last_rssi", rssi);
                values.put("last_hops", hops);
                db.insertWithOnConflict("nodes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                    org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(org.telegram.messenger.NotificationCenter.didUpdateMeshNodes)
                );
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void linkNodeToUser(String pubkey, long tgUserId) {
        // Update in-memory cache immediately, then persist async
        if (tgUserId != 0) {
            nodeUserCache.put(pubkey, tgUserId);
        } else {
            nodeUserCache.remove(pubkey);
        }
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put("tg_user_id", tgUserId);
                db.update("nodes", values, "pubkey = ?", new String[]{pubkey});
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() ->
                    org.telegram.messenger.NotificationCenter.getGlobalInstance()
                        .postNotificationName(org.telegram.messenger.NotificationCenter.didUpdateMeshNodes)
                );
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** @deprecated Use getTgUserIdCached() for non-blocking access. */
    public long getTgUserIdForNode(String pubkey) {
        // Return from cache first to avoid blocking; fallback to DB only if cache missed (e.g. cold start race)
        long cached = getTgUserIdCached(pubkey);
        if (cached != 0) return cached;
        SQLiteDatabase db = getReadableDatabase();
        long userId = 0;
        try (Cursor cursor = db.query("nodes", new String[]{"tg_user_id"},
                "pubkey = ?", new String[]{pubkey}, null, null, null)) {
            if (cursor.moveToFirst()) {
                userId = cursor.getLong(0);
                if (userId != 0) nodeUserCache.put(pubkey, userId); // warm cache
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return userId;
    }

    public ArrayList<MeshNode> getAllNodes() {
        ArrayList<MeshNode> nodes = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query("nodes", null, null, null, null, null, "last_seen DESC")) {
            while (cursor.moveToNext()) {
                MeshNode node = new MeshNode();
                node.pubkey = cursor.getString(0);
                node.nickname = cursor.getString(1);
                node.tgUserId = cursor.getLong(2);
                node.lastSeen = cursor.getLong(3);
                node.rssi = cursor.getInt(4);
                node.hops = cursor.getInt(5);
                nodes.add(node);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return nodes;
    }

    public void saveMessage(long dialogId, int senderHash, String text, boolean isOut) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put("dialog_id", dialogId);
                values.put("sender_hash", senderHash);
                values.put("text", text);
                values.put("date", (int) (System.currentTimeMillis() / 1000));
                values.put("is_out", isOut ? 1 : 0);
                db.insert("messages", null, values);

                ContentValues channelValues = new ContentValues();
                channelValues.put("hash", senderHash);
                channelValues.put("name", "Mesh User " + senderHash);
                db.insertWithOnConflict("channels", null, channelValues, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public ArrayList<MeshMessage> getMessages(long dialogId, int limit) {
        ArrayList<MeshMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query("messages", null,
                "dialog_id = ?", new String[]{String.valueOf(dialogId)},
                null, null, "date DESC", String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                MeshMessage msg = new MeshMessage();
                msg.id = cursor.getInt(0);
                msg.dialogId = cursor.getLong(1);
                msg.senderHash = cursor.getInt(2);
                msg.text = cursor.getString(3);
                msg.date = cursor.getInt(4);
                msg.isOut = cursor.getInt(5) == 1;
                msg.hops = cursor.getInt(6);
                messages.add(msg);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return messages;
    }

    public ArrayList<MeshChannel> getChannels() {
        ArrayList<MeshChannel> channels = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.query("channels", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                MeshChannel channel = new MeshChannel();
                channel.hash = cursor.getInt(0);
                channel.name = cursor.getString(1);
                channels.add(channel);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (channels.isEmpty()) {
            MeshChannel broadcast = new MeshChannel();
            broadcast.hash = 0;
            broadcast.name = "Mesh Broadcast";
            channels.add(broadcast);
        }
        return channels;
    }

    /**
     * Persists the BLE PIN reported by the device in PACKET_DEVICE_INFO.
     * Used to auto-confirm bonding when the device PIN differs from default 123456.
     */
    public void saveDevicePin(String address, int pin) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues v = new ContentValues();
                v.put("address", address);
                v.put("pin", pin);
                db.insertWithOnConflict("device_pins", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                FileLog.d("MeshStorage: saved BLE PIN for " + address);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** Returns the stored BLE PIN for a device, or 0 if not stored. */
    public int getDevicePin(String address) {
        SQLiteDatabase db = getReadableDatabase();
        try (android.database.Cursor c = db.query("device_pins", new String[]{"pin"},
                "address = ?", new String[]{address}, null, null, null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public String getLastMessage(int senderHash) {
        SQLiteDatabase db = getReadableDatabase();
        String text = "";
        try (Cursor cursor = db.query("messages", new String[]{"text"},
                "sender_hash = ?", new String[]{String.valueOf(senderHash)},
                null, null, "date DESC", "1")) {
            if (cursor.moveToFirst()) text = cursor.getString(0);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return text;
    }

    public static class MeshNode {
        public String pubkey;
        public String nickname;
        public long tgUserId;
        public long lastSeen;
        public int rssi;
        public int hops;
    }

    public static class MeshChannel {
        public int hash;
        public String name;
    }

    public static class MeshMessage {
        public int id;
        public long dialogId;
        public int senderHash;
        public String text;
        public int date;
        public boolean isOut;
        public int hops;
    }
}
