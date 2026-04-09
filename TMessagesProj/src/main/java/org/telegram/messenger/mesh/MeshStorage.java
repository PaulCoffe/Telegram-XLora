package org.telegram.messenger.mesh;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import java.util.ArrayList;

public class MeshStorage extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "mesh_data.db";
    private static final int DATABASE_VERSION = 1;

    private static MeshStorage Instance;

    public static MeshStorage getInstance() {
        if (Instance == null) {
            Instance = new MeshStorage();
        }
        return Instance;
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void updateNode(String pubkey, String nickname, int rssi, int hops) {
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
    }

    public void linkNodeToUser(String pubkey, long tgUserId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("tg_user_id", tgUserId);
        db.update("nodes", values, "pubkey = ?", new String[]{pubkey});
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.didUpdateMeshNodes);
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
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("dialog_id", dialogId);
        values.put("sender_hash", senderHash);
        values.put("text", text);
        values.put("date", (int) (System.currentTimeMillis() / 1000));
        values.put("is_out", isOut ? 1 : 0);
        db.insert("messages", null, values);
    }
    public static class MeshNode {
        public String pubkey;
        public String nickname;
        public long tgUserId;
        public long lastSeen;
        public int rssi;
        public int hops;
    }
}
