package org.telegram.messenger.mesh;

import org.telegram.tgnet.TLRPC;

/**
 * A spoofed Dialog object for MeshCore channels.
 * This allows us to reuse the existing DialogsAdapter and DialogCell.
 */
public class MeshDialog extends TLRPC.TL_dialog {
    
    public String meshName;
    public String lastMessage;
    public boolean isMesh;

    public MeshDialog() {
        this.isMesh = true;
        this.pinned = false;
        this.folder_id = 0;
    }
}
