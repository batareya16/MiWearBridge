package test.hook.debug.xp;

import android.os.Handler;
import android.os.Looper;

import com.github.kyuubiran.ezxhelper.Log;

import de.robv.android.xposed.XposedHelpers;

/**
 * Periodically triggers syncing of watch data to the cloud, so the data is
 * pulled even when Mi Fitness is not opened manually.
 *
 * Runs as a timer inside the Mi Fitness process: every N minutes it calls
 * DeviceContact.syncData(did, true) for the connected device.
 * In processes without a connected device it simply does nothing.
 */
public class AutoSync {

    private static final long MIN_MS = 60_000L;

    private static Handler handler;
    private static ClassLoader cl;
    private static volatile boolean running = false;

    /** Called when Mi Fitness loads. Starts/stops the timer based on config. */
    public static synchronized void apply(ClassLoader classLoader) {
        cl = classLoader;
        reschedule();
    }

    /** Re-read config and restart the timer (after a settings change). */
    public static synchronized void reschedule() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.removeCallbacks(TASK);
        if (!BoundApps.autoSyncEnabled(null)) {
            running = false;
            Log.i("AutoSync: disabled", null);
            return;
        }
        running = true;
        long ms = intervalMs();
        Log.i("AutoSync: enabled, interval " + (ms / 60000) + " min", null);
        handler.postDelayed(TASK, ms);
    }

    private static long intervalMs() {
        return Math.max(MIN_MS, BoundApps.autoSyncInterval(null) * 60_000L);
    }

    private static final Runnable TASK = new Runnable() {
        @Override
        public void run() {
            try {
                doSync();
            } catch (Throwable t) {
                Log.e(t, "AutoSync: doSync");
            }
            if (running && handler != null) {
                handler.postDelayed(this, intervalMs());
            }
        }
    };

    private static void doSync() throws Throwable {
        if (cl == null) return;
        Object model = BypassBond.connectedModel(cl);
        if (model == null) {
            // no device in this process - the process that has it will sync
            return;
        }
        String did;
        try {
            did = (String) XposedHelpers.callMethod(model, "getDid");
        } catch (Throwable t) {
            Log.e(t, "AutoSync: getDid");
            return;
        }
        if (did == null || did.isEmpty()) return;

        Class<?> dc = cl.loadClass("com.xiaomi.fitness.device.contact.export.DeviceContact");
        Object companion = XposedHelpers.getStaticObjectField(dc, "Companion");
        Class<?> ext = cl.loadClass("com.xiaomi.fitness.device.contact.export.DeviceSyncExtKt");
        Object contact = XposedHelpers.callStaticMethod(ext, "getInstance", companion);

        XposedHelpers.callMethod(contact, "syncData", did, Boolean.TRUE);
        Log.i("AutoSync: syncData(" + did + ") started", null);
    }
}
