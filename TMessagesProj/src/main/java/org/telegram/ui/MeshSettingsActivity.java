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

public class MeshSettingsActivity extends BaseFragment {

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
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
        items.add(UItem.asButton(100, "Start Scanning"));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            boolean enabled = !MeshTransportManager.getInstance().isMeshEnabled();
            MeshTransportManager.getInstance().setMeshEnabled(enabled);
            if (enabled) {
                MeshManager.getInstance().startScanning();
            } else {
                MeshManager.getInstance().stopScanning();
            }
            listView.adapter.update(true);
        } else if (item.id == 100) {
            MeshManager.getInstance().startScanning();
            listView.adapter.update(true);
        } else if (item.id >= 2 && item.id < 100) {
            String deviceAddress = item.text.toString();
            MeshManager.getInstance().connect(deviceAddress);
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }
}
