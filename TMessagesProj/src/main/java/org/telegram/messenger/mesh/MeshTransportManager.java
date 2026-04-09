package org.telegram.messenger.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;

/**
 * MeshTransportManager maintains the global state of the Mesh transport.
 */
public class MeshTransportManager {
    private static volatile MeshTransportManager Instance;
    private boolean meshEnabled;
    private String selectedDeviceAddress;

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

    private long frequency;
    private float bandwidth;
    private int spreadingFactor;
    private int codingRate;

    private MeshTransportManager() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE);
        meshEnabled = preferences.getBoolean("mesh_enabled", false);
        selectedDeviceAddress = preferences.getString("mesh_device_address", null);
        
        frequency = preferences.getLong("radio_freq", 868731018L);
        bandwidth = preferences.getFloat("radio_bw", 62.5f);
        spreadingFactor = preferences.getInt("radio_sf", 7);
        codingRate = preferences.getInt("radio_cr", 7);
    }

    public long getFrequency() { return frequency; }
    public float getBandwidth() { return bandwidth; }
    public int getSpreadingFactor() { return spreadingFactor; }
    public int getCodingRate() { return codingRate; }

    public void setRadioConfig(long freq, float bw, int sf, int cr) {
        this.frequency = freq;
        this.bandwidth = bw;
        this.spreadingFactor = sf;
        this.codingRate = cr;
        
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putLong("radio_freq", freq);
        editor.putFloat("radio_bw", bw);
        editor.putInt("radio_sf", sf);
        editor.putInt("radio_cr", cr);
        editor.apply();
        
        MeshManager.getInstance().sendRadioConfig(freq, bw, sf, cr);
    }

    public boolean isMeshEnabled() {
        return meshEnabled;
    }

    public void setMeshEnabled(boolean enabled) {
        this.meshEnabled = enabled;
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putBoolean("mesh_enabled", enabled);
        editor.apply();
        
        org.telegram.messenger.MessagesController.getInstance(org.telegram.messenger.UserConfig.selectedAccount).checkMeshFilter();

        if (enabled) {
            MeshManager.getInstance().startScanning();
        } else {
            MeshManager.getInstance().stopAll();
        }
    }

    public String getSelectedDeviceAddress() {
        return selectedDeviceAddress;
    }

    public void setSelectedDeviceAddress(String address) {
        this.selectedDeviceAddress = address;
        SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("mesh_config", Context.MODE_PRIVATE).edit();
        editor.putString("mesh_device_address", address);
        editor.apply();
    }
}
