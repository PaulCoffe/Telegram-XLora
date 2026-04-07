package org.thunderdog.challegram.mesh;

import org.drinkless.tdlib.TdApi;

/**
 * MeshTransportManager holds the global state of the LoRa Mesh transport.
 */
public class MeshTransportManager {
    private static MeshTransportManager instance;
    private boolean meshMode = false;
    private String connectedDeviceAddress = null;

    private MeshTransportManager() {}

    public static synchronized MeshTransportManager getInstance() {
        if (instance == null) {
            instance = new MeshTransportManager();
        }
        return instance;
    }

    public boolean isMeshMode() {
        return meshMode;
    }

    public void setMeshMode(boolean meshMode) {
        this.meshMode = meshMode;
    }

    public String getConnectedDeviceAddress() {
        return connectedDeviceAddress;
    }

    public void setConnectedDeviceAddress(String address) {
        this.connectedDeviceAddress = address;
    }

    /**
     * Checks if we should use LoRa for the given message.
     */
    public boolean shouldRouteViaMesh(TdApi.NetworkType currentNetwork) {
        return meshMode || (currentNetwork != null && currentNetwork.getConstructor() == TdApi.NetworkTypeNone.CONSTRUCTOR);
    }
}
