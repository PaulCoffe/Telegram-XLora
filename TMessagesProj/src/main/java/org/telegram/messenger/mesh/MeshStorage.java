package org.telegram.messenger.mesh;

import android.content.Context;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * MeshStorage handles the persistence of MeshCore channels, nodes, and history.
 * It uses a separate SQLite database file (mesh.db) to keep context clear.
 */
public class MeshStorage {
    private static volatile MeshStorage Instance;
    private SQLiteDatabase database;
    private final File dbFile;

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

    private MeshStorage() {
        dbFile = new File(ApplicationLoader.getFilesDirFixed(), "mesh_v1.db");
        openDatabase();
    }

    private void openDatabase() {
        try {
            database = new SQLiteDatabase(dbFile.getPath());
            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
            createTables();
        } catch (SQLiteException e) {
            FileLog.e(e);
        }
    }

    private void createTables() throws SQLiteException {
        database.executeFast("CREATE TABLE IF NOT EXISTS mesh_channels(hash INTEGER PRIMARY KEY, name TEXT, psk TEXT, flags INTEGER)").stepThis().dispose();
        database.executeFast("CREATE TABLE IF NOT EXISTS mesh_nodes(id INTEGER PRIMARY KEY, hash INTEGER, name TEXT, last_seen INTEGER, snr REAL)").stepThis().dispose();
        database.executeFast("CREATE TABLE IF NOT EXISTS mesh_history(id INTEGER PRIMARY KEY AUTOINCREMENT, channel_hash INTEGER, sender_hash INTEGER, text TEXT, date INTEGER, out INTEGER)").stepThis().dispose();
        database.executeFast("CREATE INDEX IF NOT EXISTS idx_mesh_history_channel ON mesh_history(channel_hash, date)").stepThis().dispose();
    }

    // --- Channel Management ---

    public void saveChannel(int hash, String name, String psk) {
        try {
            database.executeFast("INSERT OR REPLACE INTO mesh_channels VALUES(" + hash + ", '" + name + "', '" + psk + "', 0)").stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public List<MeshChannel> getChannels() {
        List<MeshChannel> channels = new ArrayList<>();
        try {
            SQLiteCursor cursor = database.queryFinalized("SELECT hash, name, psk FROM mesh_channels");
            while (cursor.next()) {
                channels.add(new MeshChannel(cursor.intValue(0), cursor.stringValue(1), cursor.stringValue(2)));
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return channels;
    }

    // --- History Management ---

    public String getLastMessage(int channelHash) {
        String text = "";
        try {
            SQLiteCursor cursor = database.queryFinalized("SELECT text FROM mesh_history WHERE channel_hash = " + channelHash + " ORDER BY date DESC LIMIT 1");
            if (cursor.next()) {
                text = cursor.stringValue(0);
            }
            cursor.dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return text;
    }

    public void saveMessage(int channelHash, int senderHash, String text, boolean outgoing) {
        try {
            database.executeFast("INSERT INTO mesh_history (channel_hash, sender_hash, text, date, out) VALUES (" + 
                channelHash + ", " + senderHash + ", '" + text.replace("'", "''") + "', " + 
                (System.currentTimeMillis() / 1000) + ", " + (outgoing ? 1 : 0) + ")").stepThis().dispose();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static class MeshChannel {
        public final int hash;
        public final String name;
        public final String psk;

        public MeshChannel(int hash, String name, String psk) {
            this.hash = hash;
            this.name = name;
            this.psk = psk;
        }
    }
}
