package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshStorage;
import org.telegram.messenger.mesh.MeshTransportManager;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * MeshSettingsActivity — Main settings screen for MeshCore LoRa integration.
 *
 * Sections:
 *   1. Connection status + device info (name, pubkey, fw, battery)
 *   2. Enable / Disable Mesh toggle
 *   3. LoRa Channels — opens MeshChannelManagerActivity
 *   4. Radio config (freq, BW, SF, CR, presets)
 *   5. BLE Device scanning / selection
 *   6. Discovered nodes (link to Telegram contact)
 */
public class MeshSettingsActivity extends BaseFragment
        implements MeshManager.MeshManagerListener, NotificationCenter.NotificationCenterDelegate {

    private UniversalRecyclerView listView;

    // ---- Section item IDs ----
    private static final int ID_TOGGLE_MESH      = 1;
    private static final int ID_OPEN_CHANNELS    = 10;
    private static final int ID_DISCONNECT       = 11;
    private static final int ID_SCAN             = 100;

    private static final int ID_SET_NODE_NAME    = 199;
    private static final int ID_OPEN_RADIO_SETTINGS = 200;

    private static final int ID_DEVICE_BASE      = 2;      //  2–99
    private static final int ID_NODE_BASE        = 1000;   // 1000+

    @Override
    public View createView(Context context) {
        MeshManager.getInstance().addListener(this);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didRequestMeshPairing);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didUpdateMeshNodes);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didMeshChannelsUpdated);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки LoRa Mesh");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    // ============================================================
    // List building
    // ============================================================

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {

        // ── Section 1: Connection Status ──────────────────────────────
        items.add(UItem.asHeader("Статус соединения"));

        if (MeshManager.getInstance().isConnected()) {
            if (MeshManager.getInstance().isHandshakeComplete()) {
                String deviceName = MeshTransportManager.getInstance().getSelfDeviceName();
                String pubKey     = MeshTransportManager.getInstance().getSelfPubKeyHex();
                String nameStr    = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "MeshCore Node";
                String pkStr      = (pubKey != null && pubKey.length() >= 12)
                        ? pubKey.substring(0, 12) + "…" : (pubKey != null ? pubKey : "—");

                items.add(UItem.asButton(0, nameStr, "Ключ: " + pkStr + " · Подключено ✓").accent());
                items.add(UItem.asButton(ID_SET_NODE_NAME, "Изменить имя узла", ""));
                items.add(UItem.asButton(ID_DISCONNECT, "Отключиться", ""));
            } else {
                items.add(UItem.asButton(0, "Синхронизация…", "Идёт рукопожатие с устройством"));
            }
        } else if (MeshManager.getInstance().isConnecting()) {
            items.add(UItem.asButton(0, "Соединение…", "Подключение по Bluetooth"));
        } else {
            items.add(UItem.asButton(0, "Не подключено", "Нажмите 'Поиск' ниже"));
        }
        items.add(UItem.asShadow(null));

        // ── Section 2: Mesh Toggle ──────────────────────────────────
        items.add(UItem.asCheck(ID_TOGGLE_MESH, "Включить LoRa Mesh")
                .setChecked(MeshTransportManager.getInstance().isMeshEnabled()));
        items.add(UItem.asShadow("Если включено — сообщения отправляются через LoRa при отсутствии интернета."));

        // ── Section 3: Channels ─────────────────────────────────────
        items.add(UItem.asHeader("LoRa Каналы"));
        int channelCount = MeshStorage.getInstance().getLoraChannels().size();
        items.add(UItem.asButton(ID_OPEN_CHANNELS, "Управление каналами",
                "Слотов заполнено: " + channelCount + " из 8").accent());
        items.add(UItem.asShadow(null));

        // ── Section 4: Radio Config ──────────────────────────────────
        items.add(UItem.asHeader("Настройки радио"));
        items.add(UItem.asButton(ID_OPEN_RADIO_SETTINGS, "Открыть параметры радио эфира",
                MeshTransportManager.getInstance().getCurrentPresetName() + " (нажмите для настройки)").accent());
        items.add(UItem.asShadow("Настройка частоты, полосы пропускания и SF для совместимости модулей."));

        // ── Section 5: BLE Scan / Device Selection ───────────────────
        items.add(UItem.asButton(ID_SCAN,
                MeshManager.getInstance().isScanning() ? "Остановить поиск" : "Поиск устройств MeshCore", "").accent());

        ArrayList<String> foundDevices = MeshManager.getInstance().getFoundDevices();
        if (!foundDevices.isEmpty()) {
            items.add(UItem.asHeader("Найденные устройства"));
            for (int i = 0; i < foundDevices.size(); i++) {
                String dev = foundDevices.get(i);
                String name = dev.contains("\n") ? dev.split("\n")[0] : dev;
                String addr = dev.contains("\n") ? dev.split("\n")[1] : "";
                items.add(UItem.asButton(ID_DEVICE_BASE + i, name, addr));
            }
        }

        // ── Section 6: Discovered Mesh Nodes ────────────────────────
        ArrayList<MeshStorage.MeshNode> nodes = MeshStorage.getInstance().getAllNodes();
        if (!nodes.isEmpty()) {
            items.add(UItem.asHeader("Обнаруженные узлы"));
            for (int i = 0; i < nodes.size(); i++) {
                MeshStorage.MeshNode node = nodes.get(i);
                String displayName = (node.nickname != null && !node.nickname.isEmpty())
                        ? node.nickname : "Node " + node.pubkey.substring(0, Math.min(8, node.pubkey.length()));
                String sub = "RSSI: " + node.rssi + " dBm · Hops: " + node.hops;
                if (node.tgUserId != 0) sub += " · 🔗 TG привязан";
                items.add(UItem.asButton(ID_NODE_BASE + i, displayName, sub));
            }
        }
    }

    // ============================================================
    // Click handling
    // ============================================================

    private void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;

        if (id == ID_TOGGLE_MESH) {
            boolean enabled = !MeshTransportManager.getInstance().isMeshEnabled();
            MeshTransportManager.getInstance().setMeshEnabled(enabled);
            refreshList();

        } else if (id == ID_OPEN_CHANNELS) {
            presentFragment(new MeshChannelManagerActivity());

        } else if (id == ID_DISCONNECT) {
            MeshManager.getInstance().stopAll();
            refreshList();

        } else if (id == ID_SCAN) {
            if (MeshManager.getInstance().isScanning()) {
                MeshManager.getInstance().stopScanning();
            } else {
                checkPermissionsAndScan();
            }
            refreshList();

        } else if (id >= ID_DEVICE_BASE && id < ID_SCAN) {
            ArrayList<String> devices = MeshManager.getInstance().getFoundDevices();
            int idx = id - ID_DEVICE_BASE;
            if (idx >= 0 && idx < devices.size()) {
                String entry = devices.get(idx);
                String addr  = entry.contains("\n") ? entry.split("\n")[1] : entry;
                MeshManager.getInstance().connect(addr);
                refreshList();
            }

        } else if (id == ID_OPEN_RADIO_SETTINGS) {
            presentFragment(new MeshRadioSettingsActivity());

        } else if (id == ID_SET_NODE_NAME) {
            showInput("Новое имя узла", MeshTransportManager.getInstance().getSelfDeviceName(), newName -> {
                if (newName != null && !newName.trim().isEmpty()) {
                    MeshManager.getInstance().sendSetOwnerInfo(newName.trim());
                    toast("Имя отправлено на устройство");
                    refreshList();
                }
            });

        } else if (id >= ID_NODE_BASE) {
            int idx = id - ID_NODE_BASE;
            ArrayList<MeshStorage.MeshNode> nodes = MeshStorage.getInstance().getAllNodes();
            if (idx >= 0 && idx < nodes.size()) {
                showNodeOptions(nodes.get(idx));
            }
        }
    }

    // ============================================================
    // Node Options (Hybrid Mode — Phase 6)
    // ============================================================

    private void showNodeOptions(MeshStorage.MeshNode node) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Узел · " + (node.nickname != null ? node.nickname : node.pubkey.substring(0, 12)));

        // Build menu
        ArrayList<CharSequence> options = new ArrayList<>();
        options.add("Открыть чат (LoRa DM)");
        if (node.tgUserId != 0) {
            options.add("Отвязать от Telegram");
        } else {
            options.add("Привязать к контакту Telegram");
        }

        b.setItems(options.toArray(new CharSequence[0]), (dialog, which) -> {
            if (which == 0) {
                // Open Mesh DM chat
                presentFragment(new MeshChatActivity(MeshChatActivity.contactArgs(node.pubkey, node.nickname)));

            } else if (which == 1) {
                if (node.tgUserId != 0) {
                    // Unlink
                    MeshStorage.getInstance().linkNodeToUser(node.pubkey, 0);
                    toast("Привязка удалена");
                    refreshList();
                } else {
                    // Link to TG contact
                    android.os.Bundle args = new android.os.Bundle();
                    args.putBoolean("onlyUsers", true);
                    args.putBoolean("destroyAfterSelect", true);
                    args.putBoolean("returnAsChild", true);
                    ContactsActivity contactsActivity = new ContactsActivity(args);
                    contactsActivity.setDelegate((user, param, fragment) -> {
                        MeshStorage.getInstance().linkNodeToUser(node.pubkey,
                                user == null ? 0 : user.id);
                        toast("Узел привязан к " + (user != null ? user.first_name : "контакту"));
                        refreshList();
                        fragment.finishFragment();
                    });
                    presentFragment(contactsActivity);
                }
            }
        });
        showDialog(b.create());
    }

    // ============================================================
    // Preset selector
    // ============================================================

    private void showPresetSelector() {
        List<MeshTransportManager.MeshPreset> presets = MeshTransportManager.getInstance().getPresets();
        CharSequence[] names = new CharSequence[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            names[i] = presets.get(i).name + (presets.get(i).isSystem ? " ✔" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Выберите регион или пресет");
        b.setItems(names, (dialog, which) -> {
            if (which < 0 || which >= presets.size()) return;
            MeshTransportManager.MeshPreset p = presets.get(which);
            if (!p.isSystem) {
                // Show apply / delete for user presets
                new AlertDialog.Builder(getParentActivity())
                        .setTitle(p.name)
                        .setItems(new CharSequence[]{"Применить", "Удалить"}, (d, w) -> {
                            if (w == 0) applyPreset(p);
                            else {
                                MeshTransportManager.getInstance().deleteUserPreset(p.name);
                                refreshList();
                            }
                        }).show();
            } else {
                applyPreset(p);
            }
        });
        showDialog(b.create());
    }

    private void applyPreset(MeshTransportManager.MeshPreset p) {
        MeshTransportManager.getInstance().setRadioConfig(p.frequency, p.bandwidth, p.spreadingFactor, p.codingRate);
        toast("Применён пресет: " + p.name);
        refreshList();
    }

    // ============================================================
    // Permissions & scan
    // ============================================================

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
        if (allGranted) MeshManager.getInstance().startScanning();
        else getParentActivity().requestPermissions(permissions.toArray(new String[0]), 101);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 101) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) MeshManager.getInstance().startScanning();
            refreshList();
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void refreshList() {
        if (listView != null) listView.adapter.update(true);
    }

    private void toast(String msg) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void showInput(String title, String current, Utilities.Callback<String> callback) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle(title);
        FrameLayout container = new FrameLayout(getParentActivity());
        EditText editText = new EditText(getParentActivity());
        editText.setText(current);
        boolean isText = title.contains("Название") || title.contains("пресет");
        editText.setInputType(isText
                ? android.text.InputType.TYPE_CLASS_TEXT
                : android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 8, 20, 8));
        b.setView(container);
        b.setPositiveButton("OK", (d, w) -> callback.run(editText.getText().toString()));
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    // ============================================================
    // MeshManagerListener callbacks
    // ============================================================

    @Override
    public void onDevicesUpdated() { refreshList(); }

    @Override
    public void onConnectionStateChanged(boolean connected) { refreshList(); }

    @Override
    public void onSelfInfoLoaded(String pubKeyHex, String name, long freqHz, float bwKHz, int sf, int cr) {
        refreshList(); // update device name / pubkey in status section
    }

    // ============================================================
    // NotificationCenter
    // ============================================================

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didRequestMeshPairing) {
            android.bluetooth.BluetoothDevice device = (android.bluetooth.BluetoothDevice) args[0];
            showInput("Введите PIN для " + device.getName(), "",
                    pin -> MeshManager.getInstance().confirmPairing(pin));
        } else if (id == NotificationCenter.didUpdateMeshNodes
                || id == NotificationCenter.didMeshChannelsUpdated) {
            refreshList();
        }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    public void onResume() {
        super.onResume();
        MeshManager.getInstance().addListener(this);
        refreshList();
    }

    @Override
    public void onPause() {
        super.onPause();
        MeshManager.getInstance().removeListener(this);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didRequestMeshPairing);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didUpdateMeshNodes);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didMeshChannelsUpdated);
    }
}
