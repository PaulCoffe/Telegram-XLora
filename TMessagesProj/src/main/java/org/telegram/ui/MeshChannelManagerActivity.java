package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshStorage;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;

/**
 * MeshChannelManagerActivity — manages LoRa channel slots 0-7.
 *
 * Allows the user to:
 *  - View all 8 channel slots (name, type, status)
 *  - Tap a slot → open MeshChatActivity for that slot
 *  - Long-press / button → Create private channel (CSPRNG 16-byte key)
 *  - Create hashtag channel (SHA256("#name")[0:16])
 *  - Delete (zero-out) a slot
 *
 * CMD_SET_CHANNEL is sent to the device via MeshManager.sendSetChannel().
 */
public class MeshChannelManagerActivity extends BaseFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private UniversalRecyclerView listView;

    // ---- IDs ----
    private static final int ID_SLOT_BASE       = 100;   // 100-107 = slot 0-7
    private static final int ID_ADD_PRIVATE     = 200;
    private static final int ID_ADD_HASHTAG     = 201;

    @Override
    public View createView(Context context) {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didMeshChannelsUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didUpdateMeshNodes);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("LoRa Каналы");
        actionBar.setSubtitle("Управление слотами 0–7");
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
        ArrayList<MeshStorage.LoraChannel> channels = MeshStorage.getInstance().getLoraChannels();

        items.add(UItem.asHeader("Слоты каналов (0–7)"));

        // Show all 8 slots, even empty ones
        for (int i = 0; i < 8; i++) {
            final int slot = i;
            MeshStorage.LoraChannel ch = findChannel(channels, i);

            String name  = (ch != null && !ch.name.isEmpty()) ? ch.name : "(пустой)";
            String type  = getChannelType(ch);
            String label = "[" + slot + "] " + name + " · " + type;
            items.add(UItem.asButton(ID_SLOT_BASE + slot, label,
                    ch != null && !ch.name.isEmpty() ? "Нажмите для входа" : "Нажмите для настройки"));
        }

        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader("Добавить канал"));

        boolean handshake = MeshManager.getInstance().isHandshakeComplete();
        String  hint      = handshake ? "" : " (нужно подключение)";

        items.add(UItem.asButton(ID_ADD_PRIVATE, "🔒 Приватный канал" + hint,
                "Случайный 16-байтный ключ (CSPRNG)").accent());
        items.add(UItem.asButton(ID_ADD_HASHTAG, "# Хештег канал" + hint,
                "Ключ из SHA256(\"#название\")").accent());
        items.add(UItem.asShadow("Приватный: ключ генерируется случайно и не покидает устройство без экспорта. "
                + "Хештег: любой знающий имя канала может вычислить ключ."));
    }

    private MeshStorage.LoraChannel findChannel(ArrayList<MeshStorage.LoraChannel> list, int slot) {
        for (MeshStorage.LoraChannel ch : list) {
            if (ch.slotIndex == slot) return ch;
        }
        return null;
    }

    private String getChannelType(MeshStorage.LoraChannel ch) {
        if (ch == null) return "—";
        if (ch.isPublic) return "Публичный";
        if (ch.slotIndex == 0) return "Публичный";
        return "Приватный";
    }

    // ============================================================
    // Click handling
    // ============================================================

    private void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;

        if (id >= ID_SLOT_BASE && id < ID_SLOT_BASE + 8) {
            int slotIdx = id - ID_SLOT_BASE;
            ArrayList<MeshStorage.LoraChannel> channels = MeshStorage.getInstance().getLoraChannels();
            MeshStorage.LoraChannel ch = findChannel(channels, slotIdx);

            if (ch != null && !ch.name.isEmpty()) {
                // Open chat for this slot
                MeshChatActivity chatActivity = new MeshChatActivity();
                chatActivity.setArguments(MeshChatActivity.channelArgs(slotIdx, ch.name));
                presentFragment(chatActivity);
            } else {
                // Prompt to create / name this slot
                showCreateSlotDialog(slotIdx);
            }

        } else if (id == ID_ADD_PRIVATE) {
            showCreatePrivateChannelDialog();
        } else if (id == ID_ADD_HASHTAG) {
            showCreateHashtagChannelDialog();
        }
    }

    // ============================================================
    // Create dialogs
    // ============================================================

    private void showCreateSlotDialog(int slotIdx) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Слот " + slotIdx);
        String[] options;
        if (slotIdx == 0) {
            options = new String[]{"Войти в публичный канал"};
        } else {
            options = new String[]{"Создать приватный канал", "Создать хештег канал", "Очистить слот"};
        }
        b.setItems(options, (dialog, which) -> {
            if (slotIdx == 0) {
                // Slot 0: always public channel — open chat
                MeshChatActivity chatActivity = new MeshChatActivity();
                chatActivity.setArguments(MeshChatActivity.channelArgs(0, "Primary"));
                presentFragment(chatActivity);
                return;
            }
            switch (which) {
                case 0: showCreatePrivateChannelDialogForSlot(slotIdx); break;
                case 1: showCreateHashtagChannelDialogForSlot(slotIdx); break;
                case 2: clearSlot(slotIdx); break;
            }
        });
        showDialog(b.create());
    }

    private void showCreatePrivateChannelDialog() {
        showCreatePrivateChannelDialogForSlot(-1); // auto-find free slot
    }

    private void showCreatePrivateChannelDialogForSlot(int preferredSlot) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Приватный канал");
        FrameLayout container = new FrameLayout(getParentActivity());
        EditText nameField = new EditText(getParentActivity());
        nameField.setHint("Название (макс. 32 символа)");
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        container.addView(nameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 8, 20, 8));
        b.setView(container);
        b.setPositiveButton("Создать", (dialog, which) -> {
            String name = nameField.getText().toString().trim();
            if (name.isEmpty()) return;
            int slot = preferredSlot >= 0 ? preferredSlot : findFreeSlot();
            if (slot < 0) {
                toast("Нет свободных слотов (0-7 заняты)");
                return;
            }
            byte[] secret = generateRandomSecret();
            applyChannel(slot, name, secret, false);
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    private void showCreateHashtagChannelDialog() {
        showCreateHashtagChannelDialogForSlot(-1);
    }

    private void showCreateHashtagChannelDialogForSlot(int preferredSlot) {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Хештег канал");
        FrameLayout container = new FrameLayout(getParentActivity());
        EditText nameField = new EditText(getParentActivity());
        nameField.setHint("#название (без #, например: moscow)");
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        container.addView(nameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 8, 20, 8));
        b.setView(container);
        b.setPositiveButton("Создать", (dialog, which) -> {
            String hashtag = nameField.getText().toString().trim().toLowerCase();
            if (hashtag.isEmpty()) return;
            int slot = preferredSlot >= 0 ? preferredSlot : findFreeSlot();
            if (slot < 0) {
                toast("Нет свободных слотов (0-7 заняты)");
                return;
            }
            byte[] secret = hashtagToSecret("#" + hashtag);
            if (secret == null) { toast("Ошибка вычисления хеша"); return; }
            applyChannel(slot, "#" + hashtag, secret, true);
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    // ============================================================
    // Channel operations
    // ============================================================

    /**
     * Applies a channel slot to device + storage.
     */
    private void applyChannel(int slotIndex, String name, byte[] secret16, boolean isPublic) {
        // Persist locally first (optimistic)
        String secretHex = bytesToHex(secret16);
        MeshStorage.getInstance().saveLoraChannel(slotIndex, name, secretHex, isPublic);

        // Send to device if connected
        if (MeshManager.getInstance().isHandshakeComplete()) {
            MeshManager.getInstance().sendSetChannel(slotIndex, name, secret16);
            toast("Канал '" + name + "' отправлен на устройство");
        } else {
            toast("Канал сохранён локально (устройство не подключено)");
        }

        if (listView != null) listView.adapter.update(true);
    }

    /**
     * Clears a channel slot by sending all-zero name + secret.
     */
    private void clearSlot(int slotIndex) {
        if (slotIndex == 0) { toast("Нельзя очистить публичный канал (слот 0)"); return; }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Очистить слот " + slotIndex + "?");
        b.setMessage("Все сообщения для этого канала будут сохранены локально, но канал перестанет работать.");
        b.setPositiveButton("Очистить", (d, w) -> {
            MeshStorage.getInstance().saveLoraChannel(slotIndex, "", MeshStorage.PUBLIC_CHANNEL_KEY_HEX, false);
            if (MeshManager.getInstance().isHandshakeComplete()) {
                MeshManager.getInstance().sendSetChannel(slotIndex, "", new byte[16]);
            }
            if (listView != null) listView.adapter.update(true);
        });
        b.setNegativeButton("Отмена", null);
        showDialog(b.create());
    }

    // ============================================================
    // Utilities
    // ============================================================

    /** Finds the first slot index (1-7) that has no name. */
    private int findFreeSlot() {
        ArrayList<MeshStorage.LoraChannel> channels = MeshStorage.getInstance().getLoraChannels();
        for (int i = 1; i <= 7; i++) {
            MeshStorage.LoraChannel ch = findChannel(channels, i);
            if (ch == null || ch.name.isEmpty()) return i;
        }
        return -1;
    }

    /** Generates a cryptographically random 16-byte secret. */
    private byte[] generateRandomSecret() {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /**
     * Computes a hashtag channel key: SHA256("#name")[0:16].
     * Any node knowing the hashtag name can derive the same key.
     */
    private byte[] hashtagToSecret(String hashtag) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(hashtag.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(hash, 0, key, 0, 16);
            return key;
        } catch (Exception e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void toast(String msg) {
        if (getParentActivity() != null) {
            android.widget.Toast.makeText(getParentActivity(), msg, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // NotificationCenter
    // ============================================================

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if ((id == NotificationCenter.didMeshChannelsUpdated ||
             id == NotificationCenter.didUpdateMeshNodes) && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didMeshChannelsUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didUpdateMeshNodes);
    }
}
