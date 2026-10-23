package com.lumora.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 桌面小组件：展示最近一件「心动」藏品（无则最近一件），暖纸卡面，
 * 点击经 immutable PendingIntent 进入对应详情。无网络、无定时任务，
 * 由藏品变更（保存/心动/删除/恢复）显式驱动更新。
 * 取数与渲染全部走 AppState 共享线程，不在广播主线程做 IO。
 */
public final class LumoraWidget extends AppWidgetProvider {

    static final String ACTION_OPEN_ITEM = "com.lumora.app.OPEN_ITEM";
    static final String EXTRA_ITEM_ID = "itemId";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        AppState app = AppState.get(context);
        app.ensureLoaded();
        final PendingResult pending = goAsync();
        final Context appCtx = context.getApplicationContext();
        app.io().execute(() -> {
            try {
                renderAll(appCtx);
            } finally {
                pending.finish();
            }
        });
    }

    /** 藏品变更后调用（自身也是异步渲染）。 */
    public static void notifyChanged(Context context) {
        AppState app = AppState.get(context);
        app.ensureLoaded();
        final Context appCtx = context.getApplicationContext();
        app.io().execute(() -> renderAll(appCtx));
    }

    private static void renderAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(
            new ComponentName(context, LumoraWidget.class));
        if (ids == null || ids.length == 0) return;

        AppState app = AppState.get(context);
        Item item = pickItem(app);
        Bitmap art;
        if (item != null) {
            Bitmap photo = null;
            if (item.isPhoto()) {
                photo = app.repo().loadPhoto(item.photoName, 640);
            }
            int state = photo != null ? ArtworkRenderer.PHOTO_OK
                : (item.isPhoto() ? ArtworkRenderer.PHOTO_FAILED
                                  : ArtworkRenderer.PHOTO_OK);
            art = ArtworkRenderer.renderArtBitmap(480, 360, item, photo, state);
        } else {
            Item placeholder = Item.newProcedural("", "", Item.MATERIAL_MIST, 2);
            art = ArtworkRenderer.renderArtBitmap(480, 360, placeholder, null,
                ArtworkRenderer.PHOTO_OK);
        }

        for (int id : ids) {
            RemoteViews views =
                new RemoteViews(context.getPackageName(), R.layout.widget_collectible);
            views.setImageViewBitmap(R.id.widget_art, art);
            views.setTextViewText(R.id.widget_title,
                item != null && item.title != null ? item.title : "今天还没有藏品");

            Intent open = new Intent(context, MainActivity.class)
                .setAction(ACTION_OPEN_ITEM);
            if (item != null) open.putExtra(EXTRA_ITEM_ID, item.id);
            PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, pi);
            mgr.updateAppWidget(id, views);
        }
    }

    /** 最近心动 → 最近一件 → null。共享 store，不自建快照。 */
    private static Item pickItem(AppState app) {
        if (!app.storeReady()) return null;
        try {
            List<Item> all = app.store().all();
            if (all.isEmpty()) return null;
            for (Item it : all) {
                if (it.favorite) return it;
            }
            return all.get(0);
        } catch (Exception e) {
            return null;
        }
    }
}
