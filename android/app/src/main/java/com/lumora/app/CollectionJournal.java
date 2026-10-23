package com.lumora.app;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Accessible native calendar and material portrait. No streaks, quotas or writes. */
public final class CollectionJournal extends LinearLayout {
    private final List<Item> items = new ArrayList<>();
    private final Consumer<Item> open;
    private YearMonth month = YearMonth.now();
    private YearMonth firstMonth = month;
    private final TextView monthTitle, monthSummary, portraitSummary;
    private final Button previous, next;
    private final LinearLayout days, portrait;

    public CollectionJournal(Context context, Consumer<Item> open) {
        super(context);
        this.open = open;
        setOrientation(VERTICAL);
        addView(Ui.eyebrow(context, "JOURNAL · 收藏的日子"));
        addView(Ui.spacer(context, 12));
        LinearLayout calendar = surface(context);
        LinearLayout header = Ui.hbox(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        previous = Ui.button(context, "‹", false);
        previous.setContentDescription("上一个月");
        previous.setId(R.id.calendar_previous);
        next = Ui.button(context, "›", false);
        next.setContentDescription("下一个月");
        next.setId(R.id.calendar_next);
        header.addView(previous, new LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
        monthTitle = Ui.serifBold(context, "", 19, Ui.INK);
        monthTitle.setGravity(Gravity.CENTER);
        monthTitle.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(monthTitle, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(next, new LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
        calendar.addView(header);
        calendar.addView(Ui.spacer(context, 10));
        LinearLayout week = Ui.hbox(context);
        for (String label : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
            TextView cell = Ui.text(context, label, 11, Ui.MUTED);
            cell.setGravity(Gravity.CENTER);
            week.addView(cell, new LayoutParams(0, Ui.dp(context, 28), 1));
        }
        calendar.addView(week);
        days = Ui.vbox(context);
        calendar.addView(days);
        calendar.addView(Ui.spacer(context, 12));
        monthSummary = Ui.text(context, "", 12, Ui.MUTED);
        calendar.addView(monthSummary);
        addView(calendar);
        addView(Ui.spacer(context, 24));
        addView(Ui.eyebrow(context, "PALETTE · 你的收藏光谱"));
        addView(Ui.spacer(context, 12));
        portrait = surface(context);
        portraitSummary = Ui.serif(context, "", 18, Ui.INK);
        addView(portrait);
        previous.setOnClickListener(v -> changeMonth(-1));
        next.setOnClickListener(v -> changeMonth(1));
        renderCalendar();
        renderPortrait();
    }

    private static LinearLayout surface(Context c) {
        LinearLayout box = Ui.vbox(c);
        box.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16));
        box.setBackground(Ui.glass(c, 24));
        return box;
    }

    public void setItems(List<Item> source) {
        items.clear();
        for (Item it : source) items.add(it.copy());
        firstMonth = YearMonth.now();
        for (Item it : items) {
            if (it.sample) continue;
            YearMonth m = YearMonth.from(CollectionInsights.date(it.created));
            if (m.isBefore(firstMonth)) firstMonth = m;
        }
        if (month.isBefore(firstMonth) || month.isAfter(YearMonth.now())) month = YearMonth.now();
        renderCalendar();
        renderPortrait();
    }

    private void changeMonth(int delta) {
        YearMonth target = month.plusMonths(delta);
        if (target.isBefore(firstMonth) || target.isAfter(YearMonth.now())) return;
        month = target;
        Ui.haptic(getContext(), delta < 0 ? previous : next);
        renderCalendar();
    }

    private void renderCalendar() {
        Context c = getContext();
        monthTitle.setText(CollectionInsights.monthLabel(month));
        previous.setEnabled(month.isAfter(firstMonth));
        next.setEnabled(month.isBefore(YearMonth.now()));
        previous.setAlpha(previous.isEnabled() ? 1 : 0.35f);
        next.setAlpha(next.isEnabled() ? 1 : 0.35f);
        days.removeAllViews();
        int[] counts = CollectionInsights.monthDays(items, month);
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        int total = ((offset + counts.length + 6) / 7) * 7;
        LinearLayout row = null;
        int collected = 0, active = 0;
        for (int n : counts) { collected += n; if (n > 0) active++; }
        for (int i = 0; i < total; i++) {
            if (i % 7 == 0) { row = Ui.hbox(c); days.addView(row); }
            int day = i - offset + 1;
            TextView cell = Ui.text(c, "", 13, Ui.MUTED);
            cell.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(0, Ui.dp(c, 48), 1);
            lp.setMargins(Ui.dp(c, 1), Ui.dp(c, 2), Ui.dp(c, 1), Ui.dp(c, 2));
            row.addView(cell, lp);
            if (day < 1 || day > counts.length) {
                cell.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                continue;
            }
            LocalDate date = month.atDay(day);
            int count = counts[day - 1];
            cell.setText(day + (count > 0 ? "\n•" : ""));
            cell.setTextColor(count > 0 ? Ui.PAPER : Ui.MUTED);
            cell.setContentDescription(date + "，" + (count > 0 ? count + " 件收藏，点按查看" : "没有收藏"));
            if (count > 0) {
                cell.setBackground(Ui.rounded(c, Ui.INK, 12, null, 0));
                Ui.touchSurface(c, cell, 12);
                cell.setOnClickListener(v -> showDay(date));
            } else if (date.equals(LocalDate.now())) {
                cell.setBackground(Ui.rounded(c, 0x00FFFFFF, 12, Ui.COPPER, 1));
            }
        }
        monthSummary.setText(collected == 0
            ? "这个月的光，等你慢慢发现。日历仅记录自己的收藏。"
            : "本月 " + collected + " 件收藏 · " + active + " 个有光的日子\n点亮的日期可查看当日收藏 · 不含示例");
    }

    private void showDay(LocalDate date) {
        List<Item> matches = new ArrayList<>();
        for (Item it : items) if (!it.sample && CollectionInsights.date(it.created).equals(date)) matches.add(it);
        String[] titles = new String[matches.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = matches.get(i).title;
        new AlertDialog.Builder(getContext()).setTitle(Ui.dateStr(
            date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
            .setItems(titles, (d, which) -> open.accept(matches.get(which).copy()))
            .setNegativeButton("关闭", null).show();
    }

    private void renderPortrait() {
        Context c = getContext();
        portrait.removeAllViews();
        int[] counts = new int[Item.MATERIAL_COUNT];
        int personal = 0;
        for (Item it : items) if (!it.sample) { counts[it.material]++; personal++; }
        int dominant = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[dominant]) dominant = i;
        portraitSummary.setText(personal == 0 ? "收藏，会慢慢长成你的颜色。"
            : Ui.materialDescription(dominant));
        portrait.addView(portraitSummary);
        portrait.addView(Ui.spacer(c, 16));
        int[] colors = {Ui.COPPER, 0xFF548B91, 0xFF899487};
        for (int i = 0; i < counts.length; i++) {
            LinearLayout label = Ui.hbox(c);
            label.addView(Ui.text(c, Ui.materialName(i), 13, Ui.INK),
                new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            label.addView(Ui.text(c, counts[i] + " 件", 12, Ui.MUTED));
            portrait.addView(label);
            portrait.addView(Ui.spacer(c, 6));
            LinearLayout track = Ui.hbox(c);
            track.setBackground(Ui.rounded(c, Ui.LINE, 3, null, 0));
            if (counts[i] > 0) {
                View bar = new View(c);
                bar.setBackground(Ui.rounded(c, colors[i], 3, null, 0));
                track.addView(bar, new LayoutParams(0, Ui.dp(c, 5), counts[i]));
            }
            track.addView(new View(c), new LayoutParams(0, Ui.dp(c, 5),
                Math.max(0.01f, personal - counts[i])));
            track.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            portrait.addView(track);
            portrait.addView(Ui.spacer(c, 12));
        }
        portrait.addView(Ui.text(c, "只描绘自己的收藏，不含示例藏品。", 11, Ui.MUTED));
    }

    public void saveState(Bundle out) { out.putString("journalMonth", month.toString()); }
    public void restoreState(Bundle in) {
        try { month = YearMonth.parse(in.getString("journalMonth", YearMonth.now().toString())); }
        catch (java.time.format.DateTimeParseException ignored) { month = YearMonth.now(); }
        renderCalendar();
    }
}
