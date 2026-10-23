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
    private static final String PREFS = "lumora_widget_display";

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
        android.content.SharedPreferences.Editor prefs =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int id : ids) prefs.remove("selection_" + id);
        prefs.apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_NEXT.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (id != -1) queueRender(context, new int[] {id}, goAsync(), true);
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
            Item item = choose(items, prefs.getString("selection_" + id, null), advance);
            if (item != null) prefs.edit().putString("selection_" + id, item.id).apply();
            else prefs.edit().remove("selection_" + id).apply();
            renderOne(context, mgr, id, item, items.size());
        }
    }

    private static void renderOne(Context context, AppWidgetManager mgr, int id,
                                   Item item, int count) {
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
        Bitmap photo = item != null && item.isPhoto()
            ? AppState.get(context).repo().loadPhoto(item.photoName, 640) : null;
        int state = item != null && item.isPhoto() && photo == null
            ? ArtworkRenderer.PHOTO_FAILED : ArtworkRenderer.PHOTO_OK;
        Item display = item != null ? item
            : Item.newProcedural("", "", Item.MATERIAL_MIST, 2);
        views.setImageViewBitmap(R.id.widget_art,
            ArtworkRenderer.renderArtBitmap(480, 360, display, photo, state));
        views.setTextViewText(R.id.widget_title,
            item != null ? item.title : "收集第一束光");
        views.setTextViewText(R.id.widget_sub, item != null
            ? (item.favorite ? "♥  " : "") + Ui.materialName(item.material)
                + "  /  " + Ui.dateCompact(item.created) : "LUMORA  /  今日一藏");
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
        views.setOnClickPendingIntent(R.id.widget_next, count > 1
            ? PendingIntent.getBroadcast(context, id, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : openPi);
        views.setTextViewText(R.id.widget_next, count > 1 ? "下一件  →" : "打开  ↗");
        mgr.updateAppWidget(id, views);
    }
}
