package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshStorage;
import org.telegram.messenger.mesh.MeshTransportManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * MeshChatActivity — Universal chat screen for MeshCore LoRa conversations.
 *
 * Supports two modes (set via Bundle args):
 *   MODE_CHANNEL: shows messages for a LoRa channel slot (0-7)
 *   MODE_CONTACT: shows messages for a direct Mesh contact (pubkey)
 *
 * Threading: message list is loaded on storageQueue and displayed on main thread.
 * Incoming messages are received via NotificationCenter events.
 */
public class MeshChatActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    public MeshChatActivity(Bundle args) {
        super(args);
    }

    // ---- Bundle argument keys ----
    public static final String ARG_MODE       = "mode";
    public static final String ARG_CHANNEL_INDEX = "channel_index";
    public static final String ARG_PUBKEY     = "pubkey";
    public static final String ARG_TITLE      = "title";

    public static final int MODE_CHANNEL = 0;
    public static final int MODE_CONTACT = 1;

    // ---- State ----
    private int    mode;
    private int    channelIndex;
    private String contactPubKey;
    private String chatTitle;
    private long   dialogId;

    // ---- UI ----
    private RecyclerView       recyclerView;
    private MessageAdapter     adapter;
    private EditText           inputField;
    private ImageView          sendButton;
    private TextView           emptyView;

    private final ArrayList<MeshStorage.MeshMessage> messages = new ArrayList<>();
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final int MAX_MSG_CHARS = 133;

    // ============================================================
    // Factory helpers
    // ============================================================

    public static Bundle channelArgs(int slotIndex, String channelName) {
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, MODE_CHANNEL);
        b.putInt(ARG_CHANNEL_INDEX, slotIndex);
        b.putString(ARG_TITLE, channelName != null && !channelName.isEmpty() ? channelName : "Channel " + slotIndex);
        return b;
    }

    public static Bundle contactArgs(String pubKeyHex, String name) {
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, MODE_CONTACT);
        b.putString(ARG_PUBKEY, pubKeyHex);
        b.putString(ARG_TITLE, name != null && !name.isEmpty() ? name : pubKeyHex.substring(0, Math.min(12, pubKeyHex.length())) + "…");
        return b;
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    public View createView(Context context) {
        // Read args
        Bundle args = getArguments();
        mode         = args != null ? args.getInt(ARG_MODE, MODE_CHANNEL) : MODE_CHANNEL;
        channelIndex = args != null ? args.getInt(ARG_CHANNEL_INDEX, 0) : 0;
        contactPubKey = args != null ? args.getString(ARG_PUBKEY, "") : "";
        chatTitle    = args != null ? args.getString(ARG_TITLE, "Mesh") : "Mesh";

        dialogId = (mode == MODE_CHANNEL)
                ? MeshStorage.channelDialogId(channelIndex)
                : MeshStorage.contactDialogId(contactPubKey);

        // ActionBar
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(chatTitle);
        if (mode == MODE_CHANNEL) {
            actionBar.setSubtitle("LoRa канал · слот " + channelIndex);
        } else {
            actionBar.setSubtitle("LoRa DM · " + contactPubKey.substring(0, Math.min(12, contactPubKey.length())) + "…");
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        // Root layout
        FrameLayout rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = rootLayout;

        // Message list
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, true));
        adapter = new MessageAdapter();
        recyclerView.setAdapter(adapter);
        rootLayout.addView(recyclerView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP, 0, 0, 0, 56));

        // Empty view
        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText("Нет сообщений.\nОтправьте первое!");
        emptyView.setVisibility(View.GONE);
        rootLayout.addView(emptyView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        // Input bar
        LinearLayout inputBar = new LinearLayout(context);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        inputBar.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        rootLayout.addView(inputBar, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 56, Gravity.BOTTOM));

        // Separator line above input
        View separator = new View(context);
        separator.setBackgroundColor(Theme.getColor(Theme.key_divider));
        rootLayout.addView(separator, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM, 0, 0, 0, 56));

        inputField = new EditText(context);
        inputField.setHint("Сообщение (макс. " + MAX_MSG_CHARS + " симв.)");
        inputField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        inputField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        inputField.setSingleLine(false);
        inputField.setMaxLines(3);
        inputField.setBackground(null);
        inputField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int len = s != null ? s.length() : 0;
                sendButton.setAlpha(len > 0 && len <= MAX_MSG_CHARS ? 1f : 0.4f);
                if (len > MAX_MSG_CHARS) {
                    inputField.setTextColor(0xFFE53935);
                } else {
                    inputField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                }
            }
        });
        inputBar.addView(inputField, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.ic_send);
        sendButton.setAlpha(0.4f);
        sendButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        sendButton.setOnClickListener(v -> onSendClicked());
        inputBar.addView(sendButton, LayoutHelper.createLinear(
                40, 40, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        return rootLayout;
    }

    @Override
    public void onResume() {
        super.onResume();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didReceiveMeshChannelMessage);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didReceiveMeshContactMessage);
        // Load message history (async → post to UI thread)
        loadMessages();
    }

    @Override
    public void onPause() {
        super.onPause();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didReceiveMeshChannelMessage);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didReceiveMeshContactMessage);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didReceiveMeshChannelMessage);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didReceiveMeshContactMessage);
    }

    // ============================================================
    // NotificationCenter
    // ============================================================

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveMeshChannelMessage && mode == MODE_CHANNEL) {
            int idx = (args.length > 0 && args[0] instanceof Integer) ? (int) args[0] : -1;
            if (idx == channelIndex) {
                loadMessages();
            }
        } else if (id == NotificationCenter.didReceiveMeshContactMessage && mode == MODE_CONTACT) {
            String pubkey = (args.length > 0 && args[0] instanceof String) ? (String) args[0] : "";
            if (contactPubKey.startsWith(pubkey) || pubkey.startsWith(contactPubKey)) {
                loadMessages();
            }
        }
    }

    // ============================================================
    // Message loading
    // ============================================================

    private void loadMessages() {
        MeshStorage.getInstance().getStorageQueue().postRunnable(() -> {
            ArrayList<MeshStorage.MeshMessage> loaded =
                    MeshStorage.getInstance().getMessages(dialogId, 100);
            AndroidUtilities.runOnUIThread(() -> {
                messages.clear();
                messages.addAll(loaded);
                adapter.notifyDataSetChanged();
                if (!messages.isEmpty()) {
                    recyclerView.scrollToPosition(0);
                }
                updateEmptyView();
            });
        });
    }

    private void updateEmptyView() {
        if (emptyView == null) return;
        emptyView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ============================================================
    // Send
    // ============================================================

    private void onSendClicked() {
        if (inputField == null) return;
        String text = inputField.getText().toString().trim();
        if (text.isEmpty() || text.length() > MAX_MSG_CHARS) return;

        if (!MeshManager.getInstance().isHandshakeComplete()) {
            android.widget.Toast.makeText(
                    fragmentView.getContext(),
                    "Нет подключения к MeshCore устройству",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        if (mode == MODE_CHANNEL) {
            MeshManager.getInstance().sendChannelMessage(channelIndex, text);
        } else {
            // Direct contact message — use channel 0 for now (Phase 6 will add proper DM)
            FileLog.d("MeshChatActivity: DM send stub — text='" + text + "' to=" + contactPubKey);
            // Save locally with is_out = true
            MeshStorage.getInstance().saveContactMessage(contactPubKey, text, true);
        }

        inputField.setText("");
        // Reload to show outgoing message
        loadMessages();
    }

    // ============================================================
    // RecyclerView Adapter
    // ============================================================

    private class MessageAdapter extends RecyclerView.Adapter<MessageViewHolder> {
        private static final int TYPE_IN  = 0;
        private static final int TYPE_OUT = 1;

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).isOut ? TYPE_OUT : TYPE_IN;
        }

        @Override
        public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new MessageViewHolder(parent.getContext(), viewType);
        }

        @Override
        public void onBindViewHolder(MessageViewHolder holder, int position) {
            holder.bind(messages.get(position));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }
    }

    private class MessageViewHolder extends RecyclerView.ViewHolder {
        private final TextView textView;
        private final TextView metaView;
        private final boolean  isOut;

        MessageViewHolder(Context context, int viewType) {
            super(new FrameLayout(context));
            isOut = (viewType == MessageAdapter.TYPE_OUT);

            FrameLayout root = (FrameLayout) itemView;
            root.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3),
                    AndroidUtilities.dp(8), AndroidUtilities.dp(3));

            LinearLayout bubble = new LinearLayout(context);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6),
                    AndroidUtilities.dp(10), AndroidUtilities.dp(6));

            // Bubble background colour
            int color = isOut
                    ? Theme.getColor(Theme.key_chat_outBubble)
                    : Theme.getColor(Theme.key_chat_inBubble);
            bubble.setBackground(new ColorDrawable(color));

            textView = new TextView(context);
            textView.setTextSize(15);
            textView.setTextColor(isOut
                    ? Theme.getColor(Theme.key_chat_messageTextOut)
                    : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bubble.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(11);
            metaView.setTextColor(isOut
                    ? Theme.getColor(Theme.key_chat_messageTextOut)
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setAlpha(0.7f);
            bubble.addView(metaView, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    isOut ? Gravity.END : Gravity.START, 0, 2, 0, 0));

            int gravity = isOut ? Gravity.END : Gravity.START;
            root.addView(bubble, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, gravity));
        }

        void bind(MeshStorage.MeshMessage msg) {
            textView.setText(msg.text);
            String sender = msg.isOut ? "Вы" : (msg.senderPubkey != null && !msg.senderPubkey.isEmpty()
                    ? msg.senderPubkey.substring(0, Math.min(8, msg.senderPubkey.length()))
                    : "Node");
            String time = TIME_FMT.format(new Date((long) msg.date * 1000));
            metaView.setText(sender + " · " + time);
        }
    }

}
