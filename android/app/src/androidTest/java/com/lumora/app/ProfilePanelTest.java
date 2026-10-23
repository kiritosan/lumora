package com.lumora.app;

import android.test.InstrumentationTestCase;
import android.view.View;

import java.util.concurrent.atomic.AtomicInteger;

/** 「我的」页备份/恢复按钮必须真正接线到宿主回调，busy 时禁用。 */
public final class ProfilePanelTest extends InstrumentationTestCase {

    private static final class FakeHost implements ProfilePanel.Host {
        final AtomicInteger exports = new AtomicInteger();
        final AtomicInteger restores = new AtomicInteger();
        @Override public void onBackupExport() { exports.incrementAndGet(); }
        @Override public void onBackupRestore() { restores.incrementAndGet(); }
        @Override public void onPrefsChanged() { }
    }

    private ProfilePanel panel;
    private FakeHost host;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        host = new FakeHost();
        getInstrumentation().runOnMainSync(() -> {
            panel = new ProfilePanel(getInstrumentation().getTargetContext(), host);
        });
    }

    private void onMain(Runnable r) throws Throwable {
        getInstrumentation().runOnMainSync(r);
    }

    public void testExportButtonWired() throws Throwable {
        View b = panel.findViewById(R.id.backup_export);
        assertNotNull("导出备份按钮必须存在且接回调", b);
        assertTrue(b.isEnabled());
        onMain(b::performClick);
        assertEquals(1, host.exports.get());
    }

    public void testRestoreButtonWired() throws Throwable {
        View b = panel.findViewById(R.id.backup_restore);
        assertNotNull("恢复备份按钮必须存在且接回调", b);
        onMain(b::performClick);
        assertEquals(1, host.restores.get());
    }

    public void testBusyDisablesBoth() throws Throwable {
        onMain(() -> panel.setBusy(true));
        View ex = panel.findViewById(R.id.backup_export);
        View re = panel.findViewById(R.id.backup_restore);
        assertFalse(ex.isEnabled());
        assertFalse(re.isEnabled());
        onMain(() -> panel.setBusy(false));
        assertTrue(ex.isEnabled());
        assertTrue(re.isEnabled());
    }
}
