package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshTransportManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class MeshSettingsActivity extends BaseFragment implements MeshManager.MeshManagerListener {

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        MeshManager.getInstance().setListener(this);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("LoRa Mesh Settings");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(1, "Enable LoRa Mesh").setChecked(MeshTransportManager.getInstance().isMeshEnabled()));
        items.add(UItem.asShadow(null));
        items.get(items.size() - 1).text = "When enabled, messages will be sent via LoRa when offline.";
        
        items.add(UItem.asHeader("Bluetooth Devices"));
        
        ArrayList<String> devices = MeshManager.getInstance().getFoundDevices();
        if (devices.isEmpty()) {
            items.add(UItem.asHeader("No devices found..."));
        } else {
            for (int i = 0; i < devices.size(); i++) {
                items.add(UItem.asButton(2 + i, devices.get(i)));
            }
        }
        
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(100, "Scan for MeshCore Devices"));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            boolean enabled = !MeshTransportManager.getInstance().isMeshEnabled();
            MeshTransportManager.getInstance().setMeshEnabled(enabled);
            if (enabled) {
                checkPermissionsAndScan();
            } else {
                MeshManager.getInstance().stopScanning();
            }
            listView.adapter.update(true);
        } else if (item.id == 100) {
            checkPermissionsAndScan();
        } else if (item.id >= 2 && item.id < 100) {
            String deviceAddress = item.text.toString();
            if (deviceAddress.contains("\n")) {
                deviceAddress = deviceAddress.substring(deviceAddress.lastIndexOf("\n") + 1);
            }
            MeshManager.getInstance().connect(deviceAddress);
            listView.adapter.update(true);
        }
    }

    private void checkPermissionsAndScan() {
        if (getParentActivity() == null) return;

        ArrayList<String> permissions = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (getParentActivity().checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            MeshManager.getInstance().startScanning();
        } else {
            getParentActivity().requestPermissions(permissions.toArray(new String[0]), 101);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        MeshManager.getInstance().setListener(this);
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        MeshManager.getInstance().setListener(null);
    }

    @Override
    public void onDevicesUpdated() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onMessageReceived(byte[] data) {
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 101) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                MeshManager.getInstance().startScanning();
            }
        }
    }
}
