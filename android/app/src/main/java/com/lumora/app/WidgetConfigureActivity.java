package com.lumora.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Native launcher configuration and reconfiguration; no network or collection mutation. */
public final class WidgetConfigureActivity extends Activity {
    private int widgetId;
    private AppState app;
    private Spinner source, style, selection, theme;
    private CheckBox daily, personal, photos, notes, fullImage;
    private Button save;
    private TextView status;
    private List<Item> items = new ArrayList<>();
    private final AppState.DataListener listener = this::dataReady;
    private WidgetExhibit config;
    private boolean choicesLoaded;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
        if (widgetId < 0 || info == null || !getPackageName().equals(info.provider.getPackageName())) {
            finish();
            return;
        }
        config = WidgetExhibit.load(this, widgetId);
        app = AppState.get(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.canvasBackground());
        LinearLayout page = Ui.vbox(this);
        page.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32));
        page.addView(Ui.eyebrow(this, "DESKTOP EXHIBITION / 桌面展览"));
        page.addView(Ui.spacer(this, 14));
        page.addView(Ui.serifBold(this, "让收藏，\n在日常里发光。", 30, Ui.INK));
        page.addView(Ui.spacer(this, 20));
        source = spinner(page, "展览范围", WidgetExhibit.SOURCES);
        source.setSelection(config.source);
        style = spinner(page, "展陈方式", WidgetExhibit.STYLES);
        style.setSelection(config.style);
        theme = spinner(page, "展厅外观", WidgetExhibit.THEMES);
        theme.setSelection(config.theme);
        personal = check(page, "只展出我的收藏 · 隐藏示例", config.personalOnly);
        photos = check(page, "只展出照片藏品", config.photosOnly);
        notes = check(page, "只展出写有手记的藏品", config.notesOnly);
        fullImage = check(page, "照片完整构图 · 保留边缘与原色", config.fullImage);
        page.addView(Ui.text(this, "完整构图以展厅底色留边，不裁切、不加釉光；三联中的每张照片也保留全貌。关闭后使用材质光景。", 13, Ui.MUTED));
        page.addView(Ui.spacer(this, 14));
        selection = spinner(page, "固定一件（优先于每日轮换，需符合展览范围）", new String[]{"不固定，随展览轮换"});
        daily = new CheckBox(this);
        daily.setText("每日重逢 · 每天换一件精选");
        daily.setTextColor(Ui.INK);
        daily.setMinimumHeight(Ui.dp(this, 48));
        daily.setChecked(config.daily);
        page.addView(daily);
        page.addView(Ui.text(this, "三联展陈会从当前作品向后展开，最多展示三件。手记只在选择该样式后显示。每日轮换随系统刷新，可能延后；不使用精确闹钟。", 13, Ui.MUTED));
        page.addView(Ui.spacer(this, 16));
        status = Ui.text(this, "正在整理藏品…", 13, Ui.MUTED);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        save = Ui.button(this, "把展览放到桌面", true);
        save.setId(R.id.widget_save);
        save.setEnabled(false);
        save.setOnClickListener(v -> {
            readChoices();
            int index = selection.getSelectedItemPosition() - 1;
            config.pinned = index >= 0 && index < items.size() ? items.get(index).id : null;
            if (config.pinned != null) {
                boolean matches = false;
                for (Item item : config.candidates(items)) if (item.id.equals(config.pinned)) matches = true;
                if (!matches) {
                    status.setText("固定展品不在所选范围内。请调整展览范围，或选择不固定。");
                    return;
                }
            }
            config.save(this, widgetId);
            LumoraWidget.notifyChanged(this);
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
            finish();
        });
        page.addView(Ui.spacer(this, 16));
        page.addView(save, new LinearLayout.LayoutParams(-1, -2));
        Button cancel = Ui.button(this, "取消", false);
        cancel.setOnClickListener(v -> finish());
        page.addView(Ui.spacer(this, 10));
        page.addView(cancel, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(page, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);
        if (state != null) {
            source.setSelection(state.getInt("source", config.source));
            style.setSelection(state.getInt("style", config.style));
            theme.setSelection(state.getInt("theme", config.theme));
            daily.setChecked(state.getBoolean("daily", config.daily));
            personal.setChecked(state.getBoolean("personal", config.personalOnly));
            photos.setChecked(state.getBoolean("photos", config.photosOnly));
            notes.setChecked(state.getBoolean("notes", config.notesOnly));
            fullImage.setChecked(state.getBoolean("full", config.fullImage));
            config.pinned = state.getString("pin", config.pinned);
        }
        AdapterView.OnItemSelectedListener changes = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateSummary(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        for (Spinner spinner : new Spinner[]{source, style, theme, selection}) spinner.setOnItemSelectedListener(changes);
        for (CheckBox check : new CheckBox[]{daily, personal, photos, notes, fullImage}) {
            check.setOnCheckedChangeListener((button, checked) -> updateSummary());
        }
        app.ensureLoaded();
    }

    private CheckBox check(LinearLayout page, String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(Ui.INK);
        box.setMinimumHeight(Ui.dp(this, 48));
        box.setChecked(checked);
        page.addView(box);
        return box;
    }

    private void readChoices() {
        config.source = source.getSelectedItemPosition();
        config.style = style.getSelectedItemPosition();
        config.theme = theme.getSelectedItemPosition();
        config.daily = daily.isChecked();
        config.personalOnly = personal.isChecked();
        config.photosOnly = photos.isChecked();
        config.notesOnly = notes.isChecked();
        config.fullImage = fullImage.isChecked();
    }

    private void updateSummary() {
        if (!choicesLoaded) return;
        readChoices();
        int count = config.candidates(items).size();
        status.setText("当前匹配 " + count + " 件 · " + WidgetExhibit.THEMES[config.theme]
            + (count == 0 ? "\n桌面会显示等待展览；新增符合范围的藏品后自动更新。" : "\n点卡片赏光，点“布展”调整；大卡片可浏览上一件。")
            + "\n筛选有手记不等于展示手记；只有“一页手记”样式会显示正文。");
    }

    private Spinner spinner(LinearLayout page, String label, String[] choices) {
        page.addView(Ui.text(this, label, 14, Ui.INK));
        Spinner spinner = new Spinner(this);
        spinner.setContentDescription(label);
        spinner.setMinimumHeight(Ui.dp(this, 52));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        page.addView(spinner, new LinearLayout.LayoutParams(-1, -2));
        page.addView(Ui.spacer(this, 14));
        return spinner;
    }

    private void dataReady() {
        if (!app.storeReady()) {
            status.setText(app.storeError() == null ? "正在整理藏品…" : app.storeError());
            save.setEnabled(false);
            return;
        }
        int oldIndex = selection.getSelectedItemPosition() - 1;
        String selectedId = choicesLoaded
            ? (oldIndex >= 0 && oldIndex < items.size() ? items.get(oldIndex).id : null) : config.pinned;
        items = app.store().all();
        List<String> titles = new ArrayList<>();
        titles.add("不固定，随展览轮换");
        int selected = 0;
        for (Item item : items) {
            titles.add(item.title + " · " + item.serial());
            if (item.id.equals(selectedId)) selected = titles.size() - 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        selection.setAdapter(adapter);
        selection.setSelection(selected);
        choicesLoaded = true;
        updateSummary();
        save.setEnabled(true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (app != null) { app.register(listener); dataReady(); }
    }
    @Override protected void onPause() {
        if (app != null) app.unregister(listener);
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (config == null) return;
        out.putInt("source", source.getSelectedItemPosition());
        out.putInt("style", style.getSelectedItemPosition());
        out.putInt("theme", theme.getSelectedItemPosition());
        out.putBoolean("daily", daily.isChecked());
        out.putBoolean("personal", personal.isChecked());
        out.putBoolean("photos", photos.isChecked());
        out.putBoolean("notes", notes.isChecked());
        out.putBoolean("full", fullImage.isChecked());
        int i = selection.getSelectedItemPosition() - 1;
        out.putString("pin", choicesLoaded ? (i >= 0 && i < items.size() ? items.get(i).id : null) : config.pinned);
    }
}
