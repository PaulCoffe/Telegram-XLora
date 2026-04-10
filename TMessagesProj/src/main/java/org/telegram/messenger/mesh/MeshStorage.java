package org.telegram.messenger.mesh;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DispatchQueue;
import java.util.ArrayList;

public class MeshStorage extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "mesh_data.db";
    private static final int DATABASE_VERSION = 1;

    private static MeshStorage Instance;
    private final DispatchQueue storageQueue = new DispatchQueue("MeshStorageQueue");

    public static MeshStorage getInstance() {
        if (Instance == null) {
            Instance = new MeshStorage();
        }
        return Instance;
    }

    public DispatchQueue getStorageQueue() {
        return storageQueue;
    }

    private MeshStorage() {
        super(ApplicationLoader.applicationContext, DATABASE_NAME, null, DATABASE_VERSION);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
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
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.didUpdateMeshNodes);
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void linkNodeToUser(String pubkey, long tgUserId) {
        storageQueue.postRunnable(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put("tg_user_id", tgUserId);
                db.update("nodes", values, "pubkey = ?", new String[]{pubkey});
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                    org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.didUpdateMeshNodes);
                });
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public long getTgUserIdForNode(String pubkey) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query("nodes", new String[]{"tg_user_id"}, "pubkey = ?", new String[]{pubkey}, null, null, null);
        long userId = 0;
        if (cursor.moveToFirst()) {
            userId = cursor.getLong(0);
        }
        cursor.close();
        return userId;
    }

    public ArrayList<MeshNode> getAllNodes() {
        ArrayList<MeshNode> nodes = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query("nodes", null, null, null, null, null, "last_seen DESC");
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
        cursor.close();
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

                // Update dialog/channel last message reference if needed
                ContentValues channelValues = new ContentValues();
                channelValues.put("hash", senderHash);
                channelValues.put("name", "Mesh User " + senderHash);
                db.insertWithOnConflict("channels", null, channelValues, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
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
        // If empty, add a default broadcast channel
        if (channels.isEmpty()) {
            MeshChannel broadcast = new MeshChannel();
            broadcast.hash = 0;
            broadcast.name = "Mesh Broadcast";
            channels.add(broadcast);
        }
        return channels;
    }

    public String getLastMessage(int senderHash) {
        SQLiteDatabase db = getReadableDatabase();
        String text = "";
        try (Cursor cursor = db.query("messages", new String[]{"text"}, "sender_hash = ?", new String[]{String.valueOf(senderHash)}, null, null, "date DESC", "1")) {
            if (cursor.moveToFirst()) {
                text = cursor.getString(0);
            }
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
}
