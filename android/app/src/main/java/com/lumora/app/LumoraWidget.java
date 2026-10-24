package com.lumora.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.List;

/** Three sizes of offline gallery cards, with independent read-only browsing state.
 * All storage and rendering runs on AppState's shared IO thread, never on the broadcast thread.
 */
public class LumoraWidget extends AppWidgetProvider {

    static final String ACTION_OPEN_ITEM = "com.lumora.app.OPEN_ITEM";
    static final String EXTRA_ITEM_ID = "itemId";
    static final String ACTION_NEXT = "com.lumora.app.WIDGET_NEXT";
    private static final String PREFS = WidgetExhibit.PREFS;

    public static final class Compact extends LumoraWidget { }
    public static final class Wide extends LumoraWidget { }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        queueRender(context, ids, goAsync(), false);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr,
                                           int id, Bundle options) {
        super.onAppWidgetOptionsChanged(context, mgr, id, options);
        queueRender(context, new int[] {id}, goAsync(), false);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) WidgetExhibit.remove(context, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_NEXT.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            android.appwidget.AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
            if (info != null && info.provider.equals(new ComponentName(context, getClass()))) {
                queueRender(context, new int[] {id}, goAsync(), true);
            }
            return;
        }
        super.onReceive(context, intent);
    }

    /** 藏品变更后刷新所有尺寸；只读藏品，不覆盖每个小组件的选择。 */
    public static void notifyChanged(Context context) {
        AppState app = AppState.get(context);
        app.ensureLoaded();
        final Context appCtx = context.getApplicationContext();
        app.io().execute(() -> {
            AppWidgetManager mgr = AppWidgetManager.getInstance(appCtx);
            for (Class<?> provider : new Class<?>[] {
                LumoraWidget.class, Compact.class, Wide.class
            }) {
                renderIds(appCtx, mgr.getAppWidgetIds(new ComponentName(appCtx, provider)), false);
            }
        });
    }

    private static void queueRender(Context context, int[] ids, PendingResult pending,
                                     boolean advance) {
        Context appCtx = context.getApplicationContext();
        AppState app = AppState.get(appCtx);
        app.ensureLoaded();
        app.io().execute(() -> {
            try {
                renderIds(appCtx, ids, advance);
            } finally {
                pending.finish();
            }
        });
    }

    static Item choose(List<Item> items, String selectedId, boolean advance) {
        if (items.isEmpty()) return null;
        int index = -1;
        if (selectedId != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(selectedId)) { index = i; break; }
            }
        }
        if (index < 0) {
            index = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).favorite) { index = i; break; }
            }
        } else if (advance) {
            index = (index + 1) % items.size();
        }
        return items.get(index);
    }

    private static void renderIds(Context context, int[] ids, boolean advance) {
        if (ids == null || ids.length == 0) return;
        AppState app = AppState.get(context);
        // A failed load must never replace a real collection with the empty state.
        if (!app.storeReady()) return;
        List<Item> items = app.store().all();
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        android.content.SharedPreferences prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int id : ids) {
            WidgetExhibit exhibit = WidgetExhibit.load(context, id);
            List<Item> candidates = exhibit.candidates(items);
            java.time.LocalDate day = java.time.LocalDate.now();
            Item item = exhibit.choose(candidates, prefs.getString("selection_" + id, null), advance,
                day, prefs.getString("day_" + id, null));
            prefs.edit().putString("selection_" + id, item == null ? null : item.id)
                .putString("day_" + id, day.toString()).apply();
            renderOne(context, mgr, id, item, candidates, exhibit);
        }
    }

    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        for (int i = 0; i < Math.min(oldIds.length, newIds.length); i++) {
            WidgetExhibit.load(context, oldIds[i]).save(context, newIds[i]);
            WidgetExhibit.remove(context, oldIds[i]);
        }
    }

    private static void renderOne(Context context, AppWidgetManager mgr, int id,
                                   Item item, List<Item> candidates, WidgetExhibit exhibit) {
        int count = candidates.size();
        android.appwidget.AppWidgetProviderInfo info = mgr.getAppWidgetInfo(id);
        if (info == null) return;
        Bundle options = mgr.getAppWidgetOptions(id);
        boolean compact = info.provider.getClassName().equals(Compact.class.getName());
        boolean wide = info.provider.getClassName().equals(Wide.class.getName());
        int width = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) : 0;
        int height = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) : 0;
        int layout = compact || (!wide && width > 0 && width < 160)
            ? R.layout.widget_compact
            : wide || (height > 0 && height < 170)
                ? R.layout.widget_wide : R.layout.widget_collectible;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        Bitmap photo = exhibit.style != 1 && item != null && item.isPhoto()
            ? AppState.get(context).repo().loadPhoto(item.photoName, 640) : null;
        int state = item != null && item.isPhoto() && photo == null
            ? ArtworkRenderer.PHOTO_FAILED : ArtworkRenderer.PHOTO_OK;
        Item display = item != null ? item
            : Item.newProcedural("", "", Item.MATERIAL_MIST, 2);
        Bitmap artwork = exhibit.style == 1 && item != null
            ? renderTriptych(context, candidates, item)
            : ArtworkRenderer.renderArtBitmap(480, 360, display, photo, state);
        views.setImageViewBitmap(R.id.widget_art, artwork);
        String description = item == null ? "还没有符合范围的藏品"
            : exhibit.style == 1 ? "三联展陈，从“" + item.title + "”开始，点按查看首件藏品"
            : "藏品“" + item.title + "”，点按赏光";
        views.setContentDescription(R.id.widget_art, description);
        boolean tiny = layout == R.layout.widget_compact && height > 0 && height < 160;
        views.setViewVisibility(R.id.widget_sub, tiny ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_title, tiny && exhibit.style != 2 ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_art, tiny && exhibit.style == 2 ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setViewVisibility(R.id.widget_note, exhibit.style == 2 ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setInt(R.id.widget_note, "setMaxLines", tiny ? 1 : 2);
        views.setTextViewText(R.id.widget_note, exhibit.style != 2 ? "" : item == null ? "等一件值得留下的收藏。"
            : item.note == null || item.note.trim().isEmpty() ? "这一件，还没有写下手记。" : "“" + item.note.trim() + "”");
        views.setTextViewText(R.id.widget_title,
            item != null ? item.title : count == 0 ? "展览等待一束光" : "收集第一束光");
        views.setTextViewText(R.id.widget_sub, item != null
            ? (item.favorite ? "♥  " : "") + Ui.materialName(item.material)
                + "  /  " + (exhibit.isPinned(item) ? "常驻展品" : exhibit.daily ? "每日重逢" : Ui.dateCompact(item.created))
            : WidgetExhibit.SOURCES[exhibit.source] + " / 暂无匹配");
        Intent open = new Intent(context, MainActivity.class).setAction(ACTION_OPEN_ITEM)
            .setData(Uri.parse("lumora://widget/" + id + "/"
                + (item == null ? "home" : item.id)));
        if (item != null) open.putExtra(EXTRA_ITEM_ID, item.id);
        PendingIntent openPi = PendingIntent.getActivity(context, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPi);
        Intent next = new Intent(ACTION_NEXT).setComponent(info.provider)
            .setData(Uri.parse("lumora://widget/" + id + "/next"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        views.setOnClickPendingIntent(R.id.widget_next, count > 1 && !exhibit.isPinned(item)
            ? PendingIntent.getBroadcast(context, id, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : openPi);
        views.setTextViewText(R.id.widget_next, count > 1 && !exhibit.isPinned(item) ? "换一件 →" : "赏光 ↗");
        views.setContentDescription(R.id.widget_next, count > 1 && !exhibit.isPinned(item)
            ? "在当前展览范围内切换下一件藏品" : "打开当前展品");
        Intent configure = new Intent(context, WidgetConfigureActivity.class)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setData(Uri.parse("lumora://widget/" + id + "/configure"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        views.setOnClickPendingIntent(R.id.widget_configure, PendingIntent.getActivity(context, id, configure,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        mgr.updateAppWidget(id, views);
    }
    private static Bitmap renderTriptych(Context context, List<Item> items, Item selected) {
        Bitmap bitmap = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(Ui.PAPER);
        int first = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(selected.id)) first = i;
        int count = Math.min(3, items.size());
        int width = (480 - (count - 1) * 8) / count;
        for (int i = 0; i < count; i++) {
            Item item = items.get((first + i) % items.size());
            Bitmap photo = item.isPhoto() ? AppState.get(context).repo().loadPhoto(item.photoName, 320) : null;
            int state = item.isPhoto() && photo == null ? ArtworkRenderer.PHOTO_FAILED : ArtworkRenderer.PHOTO_OK;
            canvas.save();
            canvas.translate(i * (width + 8), 0);
            canvas.clipRect(0, 0, width, 360);
            ArtworkRenderer.drawArtwork(canvas, width, 360, item, photo, 0, 0, state);
            canvas.restore();
        }
        return bitmap;
    }

}
