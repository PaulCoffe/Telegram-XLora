package org.telegram.messenger.mesh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * MeshProtocol implements the MeshCore v1/v2 packet format.
 * Header: [Version 2 bits][Type 4 bits][Flags 2 bits]
 */
public class MeshProtocol {
    public static final byte VERSION_1 = 0x01;
    
    public static final byte TYPE_REQ       = 0x00;
    public static final byte TYPE_RESPONSE  = 0x01;
    public static final byte TYPE_TXT_MSG   = 0x02;
    public static final byte TYPE_ACK       = 0x03;
    public static final byte TYPE_ADVERT    = 0x04;
    public static final byte TYPE_GRP_TXT   = 0x05;
    
    public static class Packet {
        public byte version;
        public byte type;
        public byte flags;
        public int[] path; // Node hashes
        public byte[] payload;

        public Packet(byte type, int[] path, byte[] payload) {
            this.version = VERSION_1;
            this.type = type;
            this.flags = 0x00;
            this.path = path;
            this.payload = payload;
        }

        public byte[] serialize() {
            byte header = (byte) ((version << 6) | (type << 2) | flags);
            int pathLen = (path != null) ? path.length : 0;
            
            ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + (pathLen * 4) + payload.length);
            buffer.put(header);
            buffer.put((byte) pathLen);
            if (path != null) {
                for (int hash : path) {
                    buffer.putInt(hash);
                }
            }
            buffer.put(payload);
            return buffer.array();
        }

        public static Packet deserialize(byte[] data) {
            if (data == null || data.length < 2) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte header = buffer.get();
            byte version = (byte) ((header >> 6) & 0x03);
            byte type = (byte) ((header >> 2) & 0x0F);
            byte flags = (byte) (header & 0x03);
            
            int pathLen = buffer.get() & 0xFF;
            if (data.length < 2 + (pathLen * 4)) return null;
            
            int[] path = new int[pathLen];
            for (int i = 0; i < pathLen; i++) {
                path[i] = buffer.getInt();
            }
            
            byte[] payload = new byte[buffer.remaining()];
            buffer.get(payload);
            
            Packet p = new Packet(type, path, payload);
            p.version = version;
            p.flags = flags;
            return p;
        }
    }

    /**
     * Generate a 4-byte hash for a node ID (e.g. from MAC or Telegram User ID).
     */
    public static int generateNodeHash(String identity) {
        if (identity == null) return 0;
        // Simple 32-bit hash for compatibility, should match MeshCore's hash if known
        int hash = 7;
        for (int i = 0; i < identity.length(); i++) {
            hash = hash * 31 + identity.charAt(i);
        }
        return hash;
    }
}
