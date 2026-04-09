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
        actionBar.setTitle("Настройки LoRa Mesh");
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
        items.add(UItem.asCheck(1, "Включить LoRa Mesh").setChecked(MeshTransportManager.getInstance().isMeshEnabled()));
        items.add(UItem.asShadow("Если включено, сообщения будут отправляться через LoRa при отсутствии интернет-соединения."));
        
        items.add(UItem.asHeader("Настройки радио"));
        items.add(UItem.asButton(200, "Частота (Гц)", String.valueOf(MeshTransportManager.getInstance().getFrequency())));
        items.add(UItem.asButton(201, "Полоса (кГц)", String.valueOf(MeshTransportManager.getInstance().getBandwidth())));
        items.add(UItem.asButton(202, "SF (SF7...SF12)", String.valueOf(MeshTransportManager.getInstance().getSpreadingFactor())));
        items.add(UItem.asButton(203, "CR (4/5...4/8)", "4/" + MeshTransportManager.getInstance().getCodingRate()));
        items.add(UItem.asButton(204, "Пресет: Москва").setAccent(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Bluetooth устройства"));
        
        ArrayList<String> devices = MeshManager.getInstance().getFoundDevices();
        if (devices.isEmpty()) {
            items.add(UItem.asHeader("Устройства не найдены..."));
        } else {
            for (int i = 0; i < devices.size(); i++) {
                items.add(UItem.asButton(2 + i, devices.get(i)));
            }
        }
        
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(100, "Поиск устройств MeshCore"));
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
            ArrayList<String> devices = MeshManager.getInstance().getFoundDevices();
            int idx = item.id - 2;
            if (idx >= 0 && idx < devices.size()) {
                String deviceAddress = devices.get(idx);
                if (deviceAddress.contains("\n")) {
                    deviceAddress = deviceAddress.substring(deviceAddress.lastIndexOf("\n") + 1);
                }
                MeshManager.getInstance().connect(deviceAddress);
                listView.adapter.update(true);
            }
        } else if (item.id == 200) { // Frequency
            showNumberInput("Частота (Гц)", String.valueOf(MeshTransportManager.getInstance().getFrequency()), (val) -> {
                MeshTransportManager.getInstance().setRadioConfig(Long.parseLong(val), MeshTransportManager.getInstance().getBandwidth(), MeshTransportManager.getInstance().getSpreadingFactor(), MeshTransportManager.getInstance().getCodingRate());
                listView.adapter.update(true);
            });
        } else if (item.id == 201) { // Bandwidth
            showNumberInput("Полоса (кГц)", String.valueOf(MeshTransportManager.getInstance().getBandwidth()), (val) -> {
                MeshTransportManager.getInstance().setRadioConfig(MeshTransportManager.getInstance().getFrequency(), Float.parseFloat(val), MeshTransportManager.getInstance().getSpreadingFactor(), MeshTransportManager.getInstance().getCodingRate());
                listView.adapter.update(true);
            });
        } else if (item.id == 202) { // SF
            showNumberInput("SF (7-12)", String.valueOf(MeshTransportManager.getInstance().getSpreadingFactor()), (val) -> {
                MeshTransportManager.getInstance().setRadioConfig(MeshTransportManager.getInstance().getFrequency(), MeshTransportManager.getInstance().getBandwidth(), Integer.parseInt(val), MeshTransportManager.getInstance().getCodingRate());
                listView.adapter.update(true);
            });
        } else if (item.id == 203) { // CR
            showNumberInput("CR (5-8 for 4/5-4/8)", String.valueOf(MeshTransportManager.getInstance().getCodingRate()), (val) -> {
                MeshTransportManager.getInstance().setRadioConfig(MeshTransportManager.getInstance().getFrequency(), MeshTransportManager.getInstance().getBandwidth(), MeshTransportManager.getInstance().getSpreadingFactor(), Integer.parseInt(val));
                listView.adapter.update(true);
            });
        } else if (item.id == 204) { // Moscow Preset
            MeshTransportManager.getInstance().setRadioConfig(868731018L, 62.5f, 7, 7);
            listView.adapter.update(true);
            android.widget.Toast.makeText(getParentActivity(), "Применен пресет: Москва", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showNumberInput(String title, String current, org.telegram.messenger.Utilities.Callback<String> callback) {
        org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        final android.widget.EditText editText = new android.widget.EditText(getParentActivity());
        editText.setText(current);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        FrameLayout container = new FrameLayout(getParentActivity());
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 10, 20, 10));
        builder.setView(container);
        builder.setPositiveButton("OK", (dialog, which) -> callback.run(editText.getText().toString()));
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
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
