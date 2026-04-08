package org.telegram.messenger.mesh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MeshFragmenter handles splitting large MTProto packets and Telegram messages
 * into chunks that fit into MeshCore's MTU (~200-240 bytes).
 */
public class MeshFragmenter {
    private static final int MAX_FRAGMENT_SIZE = 200; // Safe limit for MeshCore
    private static final int MAX_BUFFER_COUNT = 100;
    private static final long BUFFER_TTL_MS = 60000; // 60 seconds
    private final Map<Integer, ReassemblyBuffer> reassemblyBuffers = new HashMap<>();

    public static class Fragment {
        public final int sessionId;
        public final int partIndex;
        public final int totalParts;
        public final byte[] data;

        public Fragment(int sessionId, int partIndex, int totalParts, byte[] data) {
            this.sessionId = sessionId;
            this.partIndex = partIndex;
            this.totalParts = totalParts;
            this.data = data;
        }

        public byte[] serialize() {
            ByteBuffer buffer = ByteBuffer.allocate(8 + data.length);
            buffer.putInt(sessionId);
            buffer.putShort((short) partIndex);
            buffer.putShort((short) totalParts);
            buffer.put(data);
            return buffer.array();
        }

        public static Fragment deserialize(byte[] raw) {
            if (raw.length < 8) return null;
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            int sessionId = buffer.getInt();
            int partIndex = buffer.getShort() & 0xFFFF;
            int totalParts = buffer.getShort() & 0xFFFF;
            byte[] data = new byte[raw.length - 8];
            try {
                buffer.get(data);
            } catch (Exception e) {
                return null;
            }
            return new Fragment(sessionId, partIndex, totalParts, data);
        }
    }

    private static class ReassemblyBuffer {
        final byte[][] parts;
        int partsReceived = 0;
        long lastActivity;

        ReassemblyBuffer(int totalParts) {
            parts = new byte[totalParts][];
            lastActivity = System.currentTimeMillis();
        }

        void updateActivity() {
            lastActivity = System.currentTimeMillis();
        }

        boolean isComplete() {
            return partsReceived == parts.length;
        }

        byte[] assemble() {
            int totalLen = 0;
            for (byte[] p : parts) if (p != null) totalLen += p.length;
            ByteBuffer buffer = ByteBuffer.allocate(totalLen);
            for (byte[] p : parts) if (p != null) buffer.put(p);
            return buffer.array();
        }
    }

    private void cleanStaleBuffers() {
        long now = System.currentTimeMillis();
        reassemblyBuffers.entrySet().removeIf(entry -> now - entry.getValue().lastActivity > BUFFER_TTL_MS);
    }

    public List<Fragment> fragment(byte[] data, int sessionId) {
        List<Fragment> fragments = new ArrayList<>();
        int totalParts = (int) Math.ceil((double) data.length / MAX_FRAGMENT_SIZE);
        for (int i = 0; i < totalParts; i++) {
            int start = i * MAX_FRAGMENT_SIZE;
            int end = Math.min(start + MAX_FRAGMENT_SIZE, data.length);
            byte[] part = new byte[end - start];
            System.arraycopy(data, start, part, 0, part.length);
            fragments.add(new Fragment(sessionId, i, totalParts, part));
        }
        return fragments;
    }

    public byte[] onFragmentReceived(byte[] raw) {
        if (raw == null) return null;
        cleanStaleBuffers();
        Fragment frag = Fragment.deserialize(raw);
        if (frag == null) return null;

        if (reassemblyBuffers.size() >= MAX_BUFFER_COUNT && !reassemblyBuffers.containsKey(frag.sessionId)) {
            return null; // Reject new sessions if full
        }

        ReassemblyBuffer buffer = reassemblyBuffers.get(frag.sessionId);
        if (buffer == null) {
            if (frag.totalParts > 1000) return null; // Protective limit
            buffer = new ReassemblyBuffer(frag.totalParts);
            reassemblyBuffers.put(frag.sessionId, buffer);
        }

        buffer.updateActivity();
        if (frag.partIndex < buffer.parts.length && buffer.parts[frag.partIndex] == null) {
            buffer.parts[frag.partIndex] = frag.data;
            buffer.partsReceived++;
        }

        if (buffer.isComplete()) {
            reassemblyBuffers.remove(frag.sessionId);
            return buffer.assemble();
        }
        return null;
    }
}
