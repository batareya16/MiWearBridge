package test.hook.debug.xp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Coordinator bindings: each watch app (watch package) is bound to a
 * phone coordinator app (coordinator package). Many watch apps
 * can point to a single coordinator.
 *
 * interconnect requirement: watch apps must be signed with the same
 * key as the coordinator (the fingerprint used in checks is taken from the coordinator).
 *
 * Stored in Mi Fitness's own prefs (the UI and the server hook live in the same app).
 */
public class BoundApps {

    public static final String PREFS = "ha_bridge";
    public static final String K_BINDINGS = "bindings";   // Set<"watch>coord">
    private static final String SEP = ">";

    public static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return (Context) app;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    /** watch package -> coordinator package. */
    public static Map<String, String> bindings(Context ctx) {
        if (ctx == null) ctx = appContext();
        Map<String, String> m = new LinkedHashMap<>();
        if (ctx == null) return m;
        Set<String> s = prefs(ctx).getStringSet(K_BINDINGS, null);
        if (s == null) return m;
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String e : s) {
            int i = e.indexOf(SEP);
            if (i > 0 && i < e.length() - 1) sorted.put(e.substring(0, i), e.substring(i + 1));
        }
        m.putAll(sorted);
        return m;
    }

    public static Set<String> watchPackages(Context ctx) {
        return new HashSet<>(bindings(ctx).keySet());
    }

    /**
     * Coordinator for a watch package. If there is no explicit binding, falls back to the
     * package itself (watch and phone with the same name - the default behaviour).
     */
    public static String coordinatorOf(Context ctx, String watch) {
        if (watch == null) return null;
        String c = bindings(ctx).get(watch);
        return c != null ? c : watch;
    }

    /** Explicit binding (no fallback) - null if the package is not in the list. */
    public static String explicitCoordinatorOf(Context ctx, String watch) {
        if (watch == null) return null;
        return bindings(ctx).get(watch);
    }

    public static void addBinding(Context ctx, String watch, String coord) {
        if (ctx == null) ctx = appContext();
        if (ctx == null || watch == null || coord == null) return;
        Map<String, String> m = bindings(ctx);
        m.put(watch, coord);
        persist(ctx, m);
    }

    public static void removeBinding(Context ctx, String watch) {
        if (ctx == null) ctx = appContext();
        if (ctx == null) return;
        Map<String, String> m = bindings(ctx);
        m.remove(watch);
        persist(ctx, m);
    }

    private static void persist(Context ctx, Map<String, String> m) {
        Set<String> s = new HashSet<>();
        for (Map.Entry<String, String> e : m.entrySet()) s.add(e.getKey() + SEP + e.getValue());
        prefs(ctx).edit().putStringSet(K_BINDINGS, s).apply();
    }

    // -- Periodic watch -> cloud auto-sync --------------------------------------

    public static final String K_AUTOSYNC = "autosync_enabled";
    public static final String K_INTERVAL = "autosync_interval_min";
    public static final int DEFAULT_INTERVAL_MIN = 30;

    public static boolean autoSyncEnabled(Context ctx) {
        if (ctx == null) ctx = appContext();
        if (ctx == null) return false;
        return prefs(ctx).getBoolean(K_AUTOSYNC, false);
    }

    public static int autoSyncInterval(Context ctx) {
        if (ctx == null) ctx = appContext();
        if (ctx == null) return DEFAULT_INTERVAL_MIN;
        int v = prefs(ctx).getInt(K_INTERVAL, DEFAULT_INTERVAL_MIN);
        return v < 1 ? 1 : v;
    }

    public static void setAutoSync(Context ctx, boolean enabled, int intervalMin) {
        if (ctx == null) ctx = appContext();
        if (ctx == null) return;
        if (intervalMin < 1) intervalMin = 1;
        prefs(ctx).edit().putBoolean(K_AUTOSYNC, enabled).putInt(K_INTERVAL, intervalMin).apply();
    }
}
