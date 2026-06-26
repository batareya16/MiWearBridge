package com.batareya16.miWearBridge.xp;

import android.content.ContextWrapper;
import android.content.Intent;

import com.github.kyuubiran.ezxhelper.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes the companion app work through Mi Fitness instead of Notify.
 *
 * The xms-wearable-lib SDK hard-binds to the Notify package (com.mc.xiaomi1) via the
 * com.xiaomi.wearable.XMS_WEARABLE_SERVICE action. Mi Fitness (com.xiaomi.wearable) exposes the
 * same service and is connected to the band, so we intercept the bind and replace the
 * target package com.mc.xiaomi1 -> com.xiaomi.wearable.
 *
 * It also waits for the service binder (field f in com.xiaomi.xms.wearable.d) to come up
 * before tasks l/m/n.a() throw "not bond" (async bind - a race).
 */
public class BypassBondClient {

    private static final String NOTIFY = "com.mc.xiaomi1";
    private static final String MIFIT = "com.xiaomi.wearable";

    private static final String[] TASKS = {
            "com.xiaomi.xms.wearable.l",
            "com.xiaomi.xms.wearable.m",
            "com.xiaomi.xms.wearable.n"
    };
    private static final int TRIES = 240;     // 240 * 25ms = 6s
    private static final long STEP_MS = 25;

    public static void apply(final ClassLoader cl) {
        // 0) Suppress the client-side status-to-exception conversion
        // (app not installed / permission denied arrive as int codes and are thrown here)
        try {
            Class<?> exUtil = cl.loadClass("com.xiaomi.xms.wearable.exception.ExceptionUtil");
            XposedBridge.hookAllMethods(exUtil, "convertStatusToException",
                    XC_MethodReplacement.returnConstant(null));
            Log.i("BypassBondClient: convertStatusToException -> null", null);
        } catch (Throwable t) {
            Log.e(t, "BypassBondClient: hook convertStatusToException");
        }

        // 0b) Force a success int status in onMessageSent callbacks (status -> 0)
        for (final String cn : new String[]{"com.xiaomi.xms.wearable.h$a", "com.xiaomi.xms.wearable.i$b"}) {
            try {
                Class<?> c = cl.loadClass(cn);
                XposedBridge.hookAllMethods(c, "onMessageSent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null) {
                            for (int k = 0; k < param.args.length; k++) {
                                if (param.args[k] instanceof Integer && (Integer) param.args[k] != 0) {
                                    Log.i("BypassBondClient: " + cn + ".onMessageSent " + param.args[k] + " -> 0", null);
                                    param.args[k] = 0;
                                }
                            }
                        }
                    }
                });
                Log.i("BypassBondClient: hooked " + cn + ".onMessageSent", null);
            } catch (Throwable t) {
                Log.e(t, "BypassBondClient: hook onMessageSent " + cn);
            }
        }

        // 1) Redirect the bind target package: Notify -> Mi Fitness
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "setPackage", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String pkg = (String) param.args[0];
                    if (pkg != null && pkg.startsWith(NOTIFY)) {
                        param.args[0] = MIFIT;
                        Log.i("BypassBondClient: setPackage " + pkg + " -> " + MIFIT, null);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBondClient: hook setPackage");
        }

        // 2) Just in case - rewrite the package directly in bindService
        try {
            XposedBridge.hookAllMethods(ContextWrapper.class, "bindService", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object a : param.args) {
                        if (a instanceof Intent) {
                            Intent it = (Intent) a;
                            String pkg = it.getPackage();
                            if (pkg != null && pkg.startsWith(NOTIFY)) {
                                it.setPackage(MIFIT);
                                Log.i("BypassBondClient: bindService " + pkg + " -> " + MIFIT, null);
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBondClient: hook bindService");
        }

        // 3) Wait for the binder before l/m/n.a() to avoid a "not bond" race
        final Class<?> dClass;
        try {
            dClass = cl.loadClass("com.xiaomi.xms.wearable.d");
        } catch (Throwable t) {
            Log.e(t, "BypassBondClient: connection class d not found");
            return;
        }
        for (String name : TASKS) {
            try {
                Class<?> clazz = cl.loadClass(name);
                XposedBridge.hookAllMethods(clazz, "a", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            waitForBinder(param.thisObject, dClass);
                        } catch (Throwable t) {
                            Log.e(t, "BypassBondClient: waitForBinder");
                        }
                    }
                });
                Log.i("BypassBondClient: hooked " + name + ".a()", null);
            } catch (Throwable t) {
                Log.e(t, "BypassBondClient: failed to load " + name);
            }
        }
    }

    private static void waitForBinder(Object task, Class<?> dClass) throws Exception {
        Object conn = null;
        for (Field f : task.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object v = f.get(task);
            if (v != null && dClass.isInstance(v)) {
                conn = v;
                break;
            }
        }
        if (conn == null) return;
        for (int i = 0; i < TRIES; i++) {
            if (XposedHelpers.getObjectField(conn, "f") != null) {
                if (i > 0) Log.i("BypassBondClient: binder f ready after " + (i * STEP_MS) + "ms", null);
                return;
            }
            Thread.sleep(STEP_MS);
        }
        Log.i("BypassBondClient: binder f never came up within " + (TRIES * STEP_MS) + "ms", null);
    }
}
