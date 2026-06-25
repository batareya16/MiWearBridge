package test.hook.debug.xp;

import com.github.kyuubiran.ezxhelper.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import test.hook.debug.xp.utils.DexKit;

/**
 * Makes Mi Fitness treat our apps as genuinely installed, paired watch apps
 * and tells the band their companion is online - this opens the interconnect
 * channel for the third-party apps listed in {@link BoundApps}.
 *
 * Runs inside the Mi Fitness process (com.xiaomi.wearable / com.mi.health).
 */
public class BypassBond {

    private static boolean implHooked = false;

    /** Outgoing addressing header: the coordinator sends "@w:<watchPackage>\n<payload>". */
    static final String ADDR_PREFIX = "@w:";

    public static void apply(ClassLoader cl) {
        DexKitBridge bridge = DexKit.INSTANCE.getDexKitBridge();

        // 1) Swallow IllegalStateException("not bond") in service methods.
        try {
            MethodDataList methods = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings("not bond")));
            for (int i = 0; i < methods.size(); i++) {
                try {
                    hookSwallow(methods.get(i).getMethodInstance(cl));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            Log.e(t, "BypassBond: not bond");
        }

        // 2) Force all XMS methods returning Status -> RESULT_SUCCESS.
        try {
            Class<?> statusClass = cl.loadClass("com.xiaomi.xms.wearable.Status");
            Object success = XposedHelpers.getStaticObjectField(statusClass, "RESULT_SUCCESS");
            MethodDataList sm = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().returnType("com.xiaomi.xms.wearable.Status")));
            for (int i = 0; i < sm.size(); i++) {
                try {
                    Method m = sm.get(i).getMethodInstance(cl);
                    String dc = m.getDeclaringClass().getName();
                    if (!dc.startsWith("com.xiaomi.xms.wearable")) continue;
                    if (dc.equals("com.xiaomi.xms.wearable.Status")) continue;
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(success));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            Log.e(t, "BypassBond: force SUCCESS");
        }

        // 3) Install server hooks once the real service binder appears.
        try {
            Class<?> svc = cl.loadClass("com.xiaomi.xms.wearable.WearableXmsService");
            XposedBridge.hookAllMethods(svc, "onBind", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object b = param.getResult();
                    if (b == null || implHooked) return;
                    implHooked = true;
                    installServerHooks(b.getClass());
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: hook onBind");
        }
    }

    private static void installServerHooks(final Class<?> bc) {
        final ClassLoader mcl = bc.getClassLoader();

        // Permission/install checks -> true.
        for (String mn : new String[]{"x3", "d3", "u3"}) {
            try {
                XposedBridge.hookAllMethods(bc, mn, XC_MethodReplacement.returnConstant(Boolean.TRUE));
            } catch (Throwable ignore) {
            }
        }

        // f3(int) -> Status: always SUCCESS (so early checks in y5/S5 don't bail out).
        try {
            Class<?> st = mcl.loadClass("com.xiaomi.xms.wearable.Status");
            Object SUCCESS = XposedHelpers.getStaticObjectField(st, "RESULT_SUCCESS");
            XposedBridge.hookAllMethods(bc, "f3", XC_MethodReplacement.returnConstant(SUCCESS));
        } catch (Throwable ignore) {
        }

        // Repository: return a real app record and capability for listed packages.
        try {
            final Class<?> repo = mcl.loadClass("com.xiaomi.xms.wearable.db.WatchAppRepository");
            final Class<?> entCls = mcl.loadClass("com.xiaomi.xms.wearable.db.entity.WatchAppItemEntity");
            final Class<?> capCls = mcl.loadClass("com.xiaomi.xms.wearable.db.entity.WatchAppCapability");
            final Class<?> extCls = mcl.loadClass("com.xiaomi.xms.wearable.extensions.ExtensionsKt");
            final Constructor<?> entCtor = entCls.getDeclaredConstructor(
                    String.class, String.class, byte[].class, boolean.class, String.class, int.class);
            final Constructor<?> capCtor = capCls.getDeclaredConstructor(
                    String.class, String.class, boolean.class, boolean.class);

            XposedBridge.hookAllMethods(repo, "hasWatchApps", XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.hookAllMethods(repo, "hasWatchAppCapability", XC_MethodReplacement.returnConstant(Boolean.TRUE));

            XposedBridge.hookAllMethods(repo, "getWatchApp", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.getResult() != null || p.args.length < 2) return;
                        String pkg = (String) p.args[0];
                        String did = (String) p.args[1];
                        String coord = BoundApps.coordinatorOf(null, pkg);
                        if (coord == null) return; // not a bound watch app
                        byte[] fp;
                        try { fp = (byte[]) XposedHelpers.callStaticMethod(extCls, "getFingerPrintByPackage", coord); }
                        catch (Throwable t) { fp = new byte[0]; }
                        p.setResult(entCtor.newInstance(pkg, did, fp, Boolean.TRUE, pkg, Integer.valueOf(1)));
                    } catch (Throwable ignore) {
                    }
                }
            });
            XposedBridge.hookAllMethods(repo, "getWatchCapability", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.getResult() != null || p.args.length < 1) return;
                        String pkg = (String) p.args[0];
                        if (BoundApps.coordinatorOf(null, pkg) == null) return;
                        p.setResult(capCtor.newInstance(pkg, pkg, Boolean.TRUE, Boolean.TRUE));
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: repo forge");
        }

        // w2()==null -> model from the manager; isSupportThirdPartyApp -> true;
        // after addListener, announce "app online" to the band for each package.
        try {
            final Class<?> dmeCls = mcl.loadClass("com.xiaomi.xms.wearable.extensions.DeviceModelExtKt");
            final Class<?> ecqCls = mcl.loadClass("ecq");
            final Class<?> extCls = mcl.loadClass("com.xiaomi.xms.wearable.extensions.ExtensionsKt");

            XposedBridge.hookAllMethods(bc, "w2", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.getResult() == null) {
                            Object m = connectedModel(mcl);
                            if (m != null) p.setResult(m);
                        }
                    } catch (Throwable ignore) {
                    }
                }
            });

            try {
                XposedBridge.hookAllMethods(dmeCls, "isSupportThirdPartyApp",
                        XC_MethodReplacement.returnConstant(Boolean.TRUE));
            } catch (Throwable ignore) {
            }

            XposedBridge.hookAllMethods(bc, "y5", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object model = XposedHelpers.callMethod(p.thisObject, "w2");
                        if (model == null) model = connectedModel(mcl);
                        if (model == null) return;
                        // fallback: announce the calling app itself, by its own package/signature
                        try {
                            String self = (String) XposedHelpers.callStaticMethod(extCls, "getCallingPackage");
                            if (self != null && !self.isEmpty()) {
                                byte[] fp;
                                try { fp = (byte[]) XposedHelpers.callStaticMethod(extCls, "getFingerPrintByPackage", self); }
                                catch (Throwable t) { fp = new byte[0]; }
                                Object ecq = ecqCls.newInstance();
                                XposedHelpers.setObjectField(ecq, "a", self);
                                XposedHelpers.setObjectField(ecq, "b", fp);
                                XposedHelpers.callStaticMethod(dmeCls, "syncPhoneAppStatus", model, ecq, Boolean.TRUE);
                            }
                        } catch (Throwable ignore) {
                        }
                        for (java.util.Map.Entry<String, String> e : BoundApps.bindings(null).entrySet()) {
                            try {
                                String watch = e.getKey(), coord = e.getValue();
                                byte[] fp;
                                try { fp = (byte[]) XposedHelpers.callStaticMethod(extCls, "getFingerPrintByPackage", coord); }
                                catch (Throwable t) { fp = new byte[0]; }
                                Object ecq = ecqCls.newInstance();
                                XposedHelpers.setObjectField(ecq, "a", watch);
                                XposedHelpers.setObjectField(ecq, "b", fp);
                                XposedHelpers.callStaticMethod(dmeCls, "syncPhoneAppStatus", model, ecq, Boolean.TRUE);
                            } catch (Throwable ignore) {
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: online status");
        }

        // Inbound (watch -> phone): deliver the watch app's messages to the
        // listener of its coordinator. P2(watchPkg) -> coordinatorPkg, so delivery matches.
        try {
            XposedBridge.hookAllMethods(bc, "P2", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 1 || !(p.args[0] instanceof String)) return;
                        String watch = (String) p.args[0];
                        String coord = BoundApps.coordinatorOf(null, watch);
                        if (coord != null && !coord.equals(watch)) p.setResult(coord);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: P2 remap");
        }

        // Outbound (phone -> watch): the coordinator addresses a reply with the "@w:<pkg>\n" header.
        // Strip the header and override the target package the message is sent to.
        try {
            final Class<?> dmeCls2 = mcl.loadClass("com.xiaomi.xms.wearable.extensions.DeviceModelExtKt");
            XposedBridge.hookAllMethods(dmeCls2, "sendPhoneMessage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 3 || !(p.args[2] instanceof byte[])) return;
                        byte[] data = (byte[]) p.args[2];
                        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        if (!s.startsWith(ADDR_PREFIX)) return;
                        int nl = s.indexOf('\n');
                        if (nl < 0) return;
                        String target = s.substring(ADDR_PREFIX.length(), nl).trim();
                        byte[] payload = s.substring(nl + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if (!target.isEmpty()) p.args[1] = target;
                        p.args[2] = payload;
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: sendPhoneMessage addr");
        }

        Log.i("BypassBond: server hooks active for " + BoundApps.bindings(null), null);
    }

    /** Connected device model from WearableDeviceManager (when getCurrentDeviceModel is null). */
    static Object connectedModel(ClassLoader cl) {
        try {
            Class<?> wdm = cl.loadClass("com.xiaomi.fitness.device.manager.export.WearableDeviceManager");
            Class<?> ext = cl.loadClass("com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt");
            Object companion = XposedHelpers.getStaticObjectField(wdm, "Companion");
            Object mgr = XposedHelpers.callStaticMethod(ext, "getInstance", companion);
            for (String mname : new String[]{
                    "getCurrentDeviceModel", "getDeviceModels", "getDeviceList", "getLocalDeviceModels"}) {
                try {
                    Object r = XposedHelpers.callMethod(mgr, mname);
                    if (r == null) continue;
                    if (r instanceof java.util.List) {
                        for (Object o : (java.util.List<?>) r) if (o != null) return o;
                    } else {
                        return r;
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            Log.e(t, "BypassBond: connectedModel");
        }
        return null;
    }

    private static void hookSwallow(final Method method) {
        final Class<?> rt = method.getReturnType();
        final boolean isBool = (rt == boolean.class || rt == Boolean.class);
        XposedBridge.hookMethod(method, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Object r = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    if (isBool && Boolean.FALSE.equals(r)) return Boolean.TRUE;
                    return r;
                } catch (IllegalStateException e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.toLowerCase().contains("bond")) return defaultValue(rt);
                    throw e;
                }
            }
        });
    }

    private static Object defaultValue(Class<?> t) {
        if (t == void.class || t == Void.class) return null;
        if (t == boolean.class || t == Boolean.class) return Boolean.TRUE;
        if (t == int.class || t == Integer.class) return 1;
        if (t == long.class || t == Long.class) return 1L;
        if (t == short.class || t == Short.class) return (short) 1;
        if (t == byte.class || t == Byte.class) return (byte) 1;
        if (t == float.class || t == Float.class) return 1f;
        if (t == double.class || t == Double.class) return 1d;
        if (t == char.class || t == Character.class) return (char) 0;
        return null;
    }
}
