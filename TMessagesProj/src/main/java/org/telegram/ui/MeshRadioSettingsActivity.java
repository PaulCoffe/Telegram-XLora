package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.mesh.MeshManager;
import org.telegram.messenger.mesh.MeshTransportManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.UItem;

import org.telegram.messenger.Utilities;
import java.util.ArrayList;

public class MeshRadioSettingsActivity extends BaseFragment {

    private UniversalRecyclerView listView;

    private static final int ID_RADIO_FREQ       = 1;
    private static final int ID_RADIO_BW         = 2;
    private static final int ID_RADIO_SF         = 3;
    private static final int ID_RADIO_CR         = 4;
    private static final int ID_PRESET_SELECT    = 5;
    private static final int ID_PRESET_SAVE      = 6;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Настройки Радио-эфира");
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

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Параметры LoRa"));
        items.add(UItem.asButton(ID_RADIO_FREQ, "Частота (Гц)",
                String.valueOf(MeshTransportManager.getInstance().getFrequency())));
        items.add(UItem.asButton(ID_RADIO_BW,   "Полоса пропускания (кГц)",
                String.valueOf(MeshTransportManager.getInstance().getBandwidth())));
        items.add(UItem.asButton(ID_RADIO_SF,   "Spreading Factor (7–12)",
                String.valueOf(MeshTransportManager.getInstance().getSpreadingFactor())));
        items.add(UItem.asButton(ID_RADIO_CR,   "Coding Rate (5–8 для 4/X)",
                "4/" + MeshTransportManager.getInstance().getCodingRate()));
        
        items.add(UItem.asShadow("Узлы должны иметь одинаковые параметры радиоэфира для успешной связи друг с другом. Менять параметры рекомендуется только опытным пользователям."));

        items.add(UItem.asHeader("Пресеты"));
        items.add(UItem.asButton(ID_PRESET_SELECT, "Текущий регион / пресет",
                MeshTransportManager.getInstance().getCurrentPresetName()).accent());
        items.add(UItem.asButton(ID_PRESET_SAVE, "Сохранить текущие как пресет", ""));
        items.add(UItem.asShadow("Пресет — это набор базовых настроек для выбранного региона, например EU_868 или US_915."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RADIO_FREQ) {
            showInput("Частота (Гц)", String.valueOf(MeshTransportManager.getInstance().getFrequency()), val -> {
                try {
                    MeshTransportManager.getInstance().setRadioConfig(Long.parseLong(val), MeshTransportManager.getInstance().getBandwidth(), MeshTransportManager.getInstance().getSpreadingFactor(), MeshTransportManager.getInstance().getCodingRate());
                } catch (Exception ignored) {}
                refreshList();
            });
        } else if (item.id == ID_RADIO_BW) {
            showInput("Полоса (кГц)", String.valueOf(MeshTransportManager.getInstance().getBandwidth()), val -> {
                try {
                    MeshTransportManager.getInstance().setRadioConfig(MeshTransportManager.getInstance().getFrequency(), Float.parseFloat(val), MeshTransportManager.getInstance().getSpreadingFactor(), MeshTransportManager.getInstance().getCodingRate());
                } catch (Exception ignored) {}
                refreshList();
            });
        } else if (item.id == ID_RADIO_SF) {
            showInput("SF (7–12)", String.valueOf(MeshTransportManager.getInstance().getSpreadingFactor()), val -> {
                try {
                    int sf = Integer.parseInt(val);
                    if (sf >= 7 && sf <= 12) MeshTransportManager.getInstance().setRadioConfig(MeshTransportManager.getInstance().getFrequency(), MeshTransportManager.getInstance().getBandwidth(), sf, MeshTransportManager.getInstance().getCodingRate());
                    else toast("SF должен быть от 7 до 12");
                } catch (Exception ignored) {}
                refreshList();
            });
        } else if (item.id == ID_RADIO_CR) {
            showInput("CR (5–8)", String.valueOf(MeshTransportManager.getInstance().getCodingRate()), val -> {
                try {
                    int cr = Integer.parseInt(val);
                    if (cr >= 5 && cr <= 8) MeshTransportManager.getInstance().setRadioConfig(MeshTransportManager.getInstance().getFrequency(), MeshTransportManager.getInstance().getBandwidth(), MeshTransportManager.getInstance().getSpreadingFactor(), cr);
                    else toast("CR должен быть от 5 до 8");
                } catch (Exception ignored) {}
                refreshList();
            });
        } else if (item.id == ID_PRESET_SAVE) {
            showInput("Название пресета", "", val -> {
                if (val != null && !val.trim().isEmpty()) {
                    MeshTransportManager.getInstance().saveUserPreset(val.trim());
                    toast("Пресет '" + val.trim() + "' сохранён");
                    refreshList();
                }
            });
        } else if (item.id == ID_PRESET_SELECT) {
            showPresetSelector();
        }
    }

    private void showInput(String title, String defValue, Utilities.Callback<String> onOk) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        final EditText input = new EditText(getParentActivity());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(defValue);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        
        FrameLayout container = new FrameLayout(getParentActivity());
        int pad = AndroidUtilities.dp(20);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 10, 20, 10));
        builder.setView(container);
        
        builder.setPositiveButton("OK", (dialog, which) -> onOk.run(input.getText().toString()));
        builder.setNegativeButton("Отмена", null);
        showDialog(builder.create());
    }

    private void showPresetSelector() {
        java.util.List<MeshTransportManager.MeshPreset> presets = MeshTransportManager.getInstance().getPresets();
        CharSequence[] names = new CharSequence[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            names[i] = presets.get(i).name + (presets.get(i).isSystem ? " ✔" : "");
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Выберите пресет");
        builder.setItems(names, (dialog, which) -> {
            if (which >= 0 && which < presets.size()) {
                MeshTransportManager.MeshPreset p = presets.get(which);
                MeshTransportManager.getInstance().setRadioConfig(p.frequency, p.bandwidth, p.spreadingFactor, p.codingRate);
                toast("Применён пресет: " + p.name);
                refreshList();
            }
        });
        showDialog(builder.create());
    }

    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void toast(String msg) {
        Toast.makeText(ApplicationLoader.applicationContext, msg, Toast.LENGTH_SHORT).show();
    }
}
