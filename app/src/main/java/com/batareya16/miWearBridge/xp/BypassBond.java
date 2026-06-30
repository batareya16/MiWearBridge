package com.batareya16.miWearBridge.xp;

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
import com.batareya16.miWearBridge.xp.utils.DexKit;

/**
 * Makes Mi Fitness treat our apps as genuinely installed, paired watch apps
 * and tells the band their companion is online - this opens the interconnect
 * channel for the third-party apps listed in {@link BoundApps}.
 *
 * Runs inside the Mi Fitness process (com.xiaomi.wearable / com.mi.health).
 */
public class BypassBond {

    private static boolean implHooked = false;

    // Captured once server hooks are installed, so the periodic re-announce timer
    // can keep bound packages "online" without going stale.
    private static volatile Class<?> sDmeCls;
    private static volatile Class<?> sExtCls;
    private static volatile ClassLoader sMcl;
    private static volatile boolean reannounceStarted = false;

    // Dump the announce info-struct once per side (real vs synthesized) for diagnosis.
    private static volatile boolean dumpedReal = false;
    private static volatile boolean dumpedSynth = false;

    // A known-good announce info-struct captured from a real coordinator announce; cloned
    // (package overridden) for bound packages so every field matches the working case.
    private static volatile Object sTemplateInfo;

    /** Re-announce interval (ms) for bound packages, to defeat status staleness. */
    private static final long REANNOUNCE_MS = 15000L;

    /** Outgoing addressing header: the coordinator sends "@w:<watchPackage>\n<payload>". */
    static final String ADDR_PREFIX = "@w:";

    // Obfuscated binder method names.
    // Centralized so a future-version remap is a one-line change. The unique-signature ones
    // (perm-check, status-wrap, current-model) also self-heal via a signature fallback.
    static final String M_PERM_CHECK   = "x3"; // (Permission,String)->boolean : permission check
    static final String M_INSTALLED_1  = "d3"; // (String)->boolean : install check
    static final String M_INSTALLED_2  = "u3"; // (String)->boolean : install check
    static final String M_STATUS_WRAP  = "f3"; // (int)->Status : code -> Status
    static final String M_CUR_MODEL    = "w2"; // ()->WearableDeviceModel : current device
    static final String M_ADD_LISTENER = "y5"; // (String,listener)->void : addListener
    static final String M_PKG_KEY      = "P2"; // (String)->String : package key (inbound routing)

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

            // The band actively QUERIES app online-status: handlePacket(model, intent, type).
            // type 6 = "request app status" -> phone answers syncPhoneAppStatus(.., isServiceConnected(pkg)).
            // This is THE gate behind the watch-side "401 not connected". Log every packet + the
            // package the band asks about, so we can see whether the band even queries our app.
            try {
                XposedBridge.hookAllMethods(svc, "handlePacket", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            int type = -1;
                            for (Object a : p.args) if (a instanceof Integer) { type = (Integer) a; break; }
                            String pkg = null;
                            for (Object a : p.args) {
                                if (a instanceof android.content.Intent) {
                                    pkg = ((android.content.Intent) a).getStringExtra("param_basic_info_package_name");
                                    break;
                                }
                            }
                            Log.i("BypassBond: handlePacket type=" + type + " pkg=" + pkg, null);
                        } catch (Throwable ignore) {
                        }
                    }
                });
            } catch (Throwable t) {
                Log.e(t, "BypassBond: hook handlePacket");
            }

            // isServiceConnected(pkg) is what the band-query answer uses for the online flag.
            // Force TRUE for bound watch packages (and log every call) so the band-query for a
            // phantom package (e.g. com.elli.plants, which has no live phone process) answers ONLINE.
            try {
                XposedBridge.hookAllMethods(svc, "isServiceConnected", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length < 1 || !(p.args[0] instanceof String)) return;
                            String pkg = (String) p.args[0];
                            // Only the explicit watch-package bindings (the phantom apps with no
                            // live phone process), NOT the self-fallback that matches everything.
                            boolean bound = BoundApps.bindings(null).containsKey(pkg);
                            Object res = p.getResult();
                            if (bound && !Boolean.TRUE.equals(res)) p.setResult(Boolean.TRUE);
                            Log.i("BypassBond: isServiceConnected " + pkg + " was=" + res
                                    + " bound=" + bound + " -> " + p.getResult(), null);
                        } catch (Throwable ignore) {
                        }
                    }
                });
            } catch (Throwable t) {
                Log.e(t, "BypassBond: hook isServiceConnected");
            }
        } catch (Throwable t) {
            Log.e(t, "BypassBond: hook onBind");
        }
    }

    private static void installServerHooks(final Class<?> bc) {
        final ClassLoader mcl = bc.getClassLoader();
        verifyBinder(bc);

        // Permission check x3(Permission,String)->boolean -> true. Unique signature -> sig fallback.
        try {
            Class<?> permCls = mcl.loadClass("com.xiaomi.xms.wearable.auth.Permission");
            hookNameOrSig(bc, M_PERM_CHECK, "boolean", new Class<?>[]{permCls, String.class},
                    XC_MethodReplacement.returnConstant(Boolean.TRUE));
        } catch (Throwable ignore) {
        }
        // Install checks d3/u3 (String)->boolean -> true. Non-unique signature -> by name.
        if (hookNamed(bc, M_INSTALLED_1, XC_MethodReplacement.returnConstant(Boolean.TRUE)) == 0)
            Log.e("BypassBond: install-check '" + M_INSTALLED_1 + "' not found — remap may be needed", null);
        hookNamed(bc, M_INSTALLED_2, XC_MethodReplacement.returnConstant(Boolean.TRUE));

        // f3(int) -> Status: always SUCCESS. Unique signature -> sig fallback.
        try {
            Class<?> st = mcl.loadClass("com.xiaomi.xms.wearable.Status");
            Object SUCCESS = XposedHelpers.getStaticObjectField(st, "RESULT_SUCCESS");
            hookNameOrSig(bc, M_STATUS_WRAP, "com.xiaomi.xms.wearable.Status", new Class<?>[]{int.class},
                    XC_MethodReplacement.returnConstant(SUCCESS));
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
            final Class<?> extCls = mcl.loadClass("com.xiaomi.xms.wearable.extensions.ExtensionsKt");
            // Capture for the periodic re-announce timer.
            sDmeCls = dmeCls; sExtCls = extCls; sMcl = mcl;
            startReannounce();

            hookNameOrSig(bc, M_CUR_MODEL,
                    "com.xiaomi.fitness.device.manager.export.WearableDeviceModel", new Class<?>[]{},
                    new XC_MethodHook() {
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

            hookNamed(bc, M_ADD_LISTENER, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object model = XposedHelpers.callMethod(p.thisObject, M_CUR_MODEL);
                        if (model == null) model = connectedModel(mcl);
                        if (model == null) return;
                        // fallback: announce the calling app itself (watch package == phone package)
                        try {
                            String self = (String) XposedHelpers.callStaticMethod(extCls, "getCallingPackage");
                            if (self != null && !self.isEmpty()) announceOnline(dmeCls, extCls, model, self, self);
                        } catch (Throwable ignore) {
                        }
                        // explicit coordinator bindings: announce each watch package (with its coordinator's fp)
                        for (java.util.Map.Entry<String, String> e : BoundApps.bindings(null).entrySet()) {
                            announceOnline(dmeCls, extCls, model, e.getKey(), e.getValue());
                        }
                    } catch (Throwable ignore) {
                    }
                }
            });

            // Piggy-back: whenever Mi Fitness announces a COORDINATOR's online status (it does this
            // itself, with a valid model — that's why same-package apps work), announce each watch
            // package bound to that coordinator too, with the SAME model and online flag. This fixes
            // the case where our addListener-time announce ran too early (model still null).
            XposedBridge.hookAllMethods(dmeCls, "syncPhoneAppStatus", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 3 || p.args[1] == null) return;
                        Object model = p.args[0], info = p.args[1], online = p.args[2];
                        // Only mirror ONLINE announces to bound watch apps. A transient offline
                        // for the coordinator would knock the watch app's channel into 401.
                        if (!((online instanceof Boolean) && (Boolean) online)) return;
                        String pkg = firstString(info);
                        if (pkg == null) return;
                        // Capture a known-good template + one-time dump for diagnosis.
                        sTemplateInfo = info;
                        if (!dumpedReal) { dumpedReal = true; dumpInfo("REAL announce " + pkg, info); }
                        for (java.util.Map.Entry<String, String> e : BoundApps.bindings(null).entrySet()) {
                            String watch = e.getKey(), coord = e.getValue();
                            if (!coord.equals(pkg) || watch.equals(pkg)) continue; // only when announcing a coordinator
                            byte[] fp;
                            try { fp = (byte[]) XposedHelpers.callStaticMethod(extCls, "getFingerPrintByPackage", coord); }
                            catch (Throwable t) { fp = null; }
                            // Clone the ENTIRE working info object and override only the package string
                            // (and fp if present), so every other field (version/type/capability flags)
                            // matches the known-good announce byte-for-byte.
                            Object info2 = cloneOverridePackage(info, watch, fp);
                            if (!dumpedSynth) { dumpedSynth = true; dumpInfo("SYNTH announce " + watch, info2); }
                            XposedBridge.invokeOriginalMethod(p.method, null, new Object[]{model, info2, online});
                            Log.i("BypassBond: piggy-announce " + watch + " (coord " + coord + ", online=" + online + ")", null);
                        }
                    } catch (Throwable t) {
                        Log.e(t, "BypassBond: piggy-announce");
                    }
                }
            });

            // A bound watch package has NO matching PHONE app installed, so Mi Fitness'
            // isAppInstalled(pkg) throws NameNotFound and marks it offline -> watch send 401.
            // Tell Mi Fitness that bound packages are "installed" so it keeps them online.
            XposedBridge.hookAllMethods(dmeCls, "isAppInstalled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length >= 1 && p.args[0] instanceof String
                                && BoundApps.bindings(null).containsKey((String) p.args[0])) {
                            p.setResult(Boolean.TRUE);
                        }
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: online status");
        }

        // Diagnostics: log listener registration (G5) and inbound dispatch (v0) with the did
        // and registered-callback count, so we can see whether the coordinator's listener
        // actually matches the incoming message (a did mismatch silently drops delivery).
        try {
            final Class<?> extClsD = mcl.loadClass("com.xiaomi.xms.wearable.extensions.ExtensionsKt");
            hookNamed(bc, "G5", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        String callPkg = (String) XposedHelpers.callStaticMethod(extClsD, "getCallingPackage");
                        String did = (p.args.length > 0 && p.args[0] instanceof String) ? (String) p.args[0] : "?";
                        Log.i("BypassBond: G5 register callingPkg=" + callPkg + " did=" + did
                                + " msgKey=key_message_" + callPkg + " count=" + fCount(p.thisObject), null);
                    } catch (Throwable ignore) {
                    }
                }
            });
            hookNamed(bc, "v0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        String did = (p.args.length > 0 && p.args[0] instanceof String) ? (String) p.args[0] : "?";
                        String pkg = (p.args.length > 1 && p.args[1] instanceof String) ? (String) p.args[1] : "?";
                        int len = (p.args.length > 2 && p.args[2] instanceof byte[]) ? ((byte[]) p.args[2]).length : -1;
                        Log.i("BypassBond: v0 dispatch did=" + did + " pkg=" + pkg + " len=" + len
                                + " listeners=" + fCount(p.thisObject), null);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: G5/v0 diag");
        }

        // Inbound (watch -> phone): deliver the watch app's messages to the
        // listener of its coordinator. P2(watchPkg) -> coordinatorPkg, so delivery matches.
        try {
            hookNamed(bc, M_PKG_KEY, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 1 || !(p.args[0] instanceof String)) return;
                        String watch = (String) p.args[0];
                        String coord = BoundApps.coordinatorOf(null, watch);
                        if (coord != null && !coord.equals(watch)) {
                            // P2 returns a wrapped key like "key_message_<pkg>". Keep the wrapper,
                            // just swap the watch package for its coordinator so the inbound key
                            // matches the coordinator's registered listener.
                            Object res = p.getResult();
                            if (res instanceof String) {
                                String mapped = ((String) res).replace(watch, coord);
                                p.setResult(mapped);
                                Log.i("BypassBond: P2 remap " + res + " -> " + mapped, null);
                            }
                        }
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
                        // sendPhoneMessage(model, package, fingerprint[], MESSAGE[], cb):
                        // the actual payload is args[3]; args[2] is the signature fingerprint.
                        if (p.args.length < 4 || !(p.args[3] instanceof byte[])) return;
                        byte[] data = (byte[]) p.args[3];
                        String origPkg = (p.args[1] instanceof String) ? (String) p.args[1] : "?";
                        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        if (!s.startsWith(ADDR_PREFIX)) {
                            // Reply going phone -> watch with no addressing header (default app).
                            Log.i("BypassBond: sendPhoneMessage pkg=" + origPkg + " len=" + data.length + " (no @w)", null);
                            return;
                        }
                        int nl = s.indexOf('\n');
                        if (nl < 0) return;
                        String target = s.substring(ADDR_PREFIX.length(), nl).trim();
                        byte[] payload = s.substring(nl + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if (!target.isEmpty()) p.args[1] = target;
                        p.args[3] = payload;
                        // Reply re-addressed to a specific watch app (coordinator routing).
                        Log.i("BypassBond: sendPhoneMessage @w re-addr " + origPkg + " -> " + target
                                + " len=" + payload.length, null);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(t, "BypassBond: sendPhoneMessage addr");
        }

        Log.i("BypassBond: server hooks active for " + BoundApps.bindings(null), null);
    }

    /** Hook every method named {@code name} on the binder. Returns how many were hooked. */
    private static int hookNamed(Class<?> bc, String name, XC_MethodHook cb) {
        int n = 0;
        for (Method m : bc.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                try { XposedBridge.hookMethod(m, cb); n++; } catch (Throwable ignore) { }
            }
        }
        return n;
    }

    /**
     * Hook by obfuscated name; if the name isn't found (renamed in a newer Mi Fitness),
     * fall back to matching the method's signature (return type + parameter types).
     * Only use for methods whose signature is unique on the binder.
     */
    private static void hookNameOrSig(Class<?> bc, String name, String retTypeName,
                                      Class<?>[] params, XC_MethodHook cb) {
        if (hookNamed(bc, name, cb) > 0) return;
        int s = 0;
        for (Method m : bc.getDeclaredMethods()) {
            if (!m.getReturnType().getName().equals(retTypeName)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != params.length) continue;
            boolean ok = true;
            for (int i = 0; i < p.length; i++) if (p[i] != params[i]) { ok = false; break; }
            if (!ok) continue;
            try { XposedBridge.hookMethod(m, cb); s++; Log.i("BypassBond: '" + name + "' resolved by signature -> " + m.getName(), null); }
            catch (Throwable ignore) { }
        }
        if (s == 0) Log.e("BypassBond: '" + name + "' not found by name or signature — remap needed", null);
    }

    /** Warn about any expected binder method missing on this Mi Fitness version. */
    private static void verifyBinder(Class<?> bc) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Method m : bc.getDeclaredMethods()) names.add(m.getName());
        for (String n : new String[]{M_PERM_CHECK, M_INSTALLED_1, M_INSTALLED_2,
                M_STATUS_WRAP, M_CUR_MODEL, M_ADD_LISTENER, M_PKG_KEY}) {
            if (!names.contains(n)) {
                Log.e("BypassBond: expected binder method '" + n + "' MISSING on "
                        + bc.getName() + " — remap needed for this Mi Fitness version", null);
            }
        }
        Log.i("BypassBond: binder = " + bc.getName(), null);
    }

    // syncPhoneAppStatus' info-struct class (ecq on 3.52, ckq on 3.55, ...). Resolved at runtime.
    private static volatile Class<?> sInfoCls;

    private static Class<?> infoClass(Class<?> dmeCls) {
        Class<?> c = sInfoCls;
        if (c != null) return c;
        for (Method m : dmeCls.getDeclaredMethods()) {
            if (m.getName().equals("syncPhoneAppStatus")) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 2) { sInfoCls = p[1]; return p[1]; }
            }
        }
        return null;
    }

    /**
     * Tell the band a phone app is online (opens the watch-side interconnect channel).
     * The info-struct class and its field names are obfuscated and version-specific, so we
     * resolve the class from syncPhoneAppStatus' parameter type and fill fields by type
     * (String = package, byte[] = signature fingerprint).
     */
    private static void announceOnline(Class<?> dmeCls, Class<?> extCls, Object model, String watchPkg, String fpPkg) {
        try {
            byte[] fp;
            try { fp = (byte[]) XposedHelpers.callStaticMethod(extCls, "getFingerPrintByPackage", fpPkg); }
            catch (Throwable t) { fp = null; }
            Object info;
            Object template = sTemplateInfo;
            if (template != null) {
                // Preferred: clone a known-good announce, override only the package (+ fp).
                info = cloneOverridePackage(template, watchPkg, fp);
            } else {
                // Fallback before any real announce was seen: build a fresh struct by type.
                Class<?> ic = infoClass(dmeCls);
                if (ic == null) { Log.e("BypassBond: announce " + watchPkg + " — infoClass null", null); return; }
                info = ic.newInstance();
                boolean setS = false, setB = false;
                for (java.lang.reflect.Field f : ic.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == String.class && !setS) { f.set(info, watchPkg); setS = true; }
                    else if (t == byte[].class && !setB && fp != null) { f.set(info, fp); setB = true; }
                }
            }
            XposedHelpers.callStaticMethod(dmeCls, "syncPhoneAppStatus", model, info, Boolean.TRUE);
            Log.i("BypassBond: announce ONLINE " + watchPkg + " (fp=" + (fp == null ? "kept" : fp.length + "b") + ", model=" + (model != null) + ")", null);
        } catch (Throwable t) {
            Log.e(t, "BypassBond: announce " + watchPkg + " FAILED");
        }
    }

    /**
     * Clone an announce info-struct copying ALL non-static fields, then override the first
     * String field with {@code pkg} and the first byte[] field with {@code fp} (if non-null).
     * Cloning the whole object means every version/type/capability flag matches the known-good
     * announce — only the package (and signature) differ.
     */
    private static Object cloneOverridePackage(Object src, String pkg, byte[] fp) throws Exception {
        Object dst = src.getClass().newInstance();
        boolean sDone = false, bDone = false;
        for (java.lang.reflect.Field f : src.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == String.class && !sDone) { f.set(dst, pkg); sDone = true; }
            else if (t == byte[].class && !bDone && fp != null) { f.set(dst, fp); bDone = true; }
            else f.set(dst, f.get(src)); // copy every other field verbatim
        }
        return dst;
    }

    /** Log every non-static field (name : type = value) of an announce info-struct. */
    private static void dumpInfo(String tag, Object o) {
        try {
            StringBuilder sb = new StringBuilder("BypassBond: dump[" + tag + "] " + o.getClass().getName() + " { ");
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                String vs;
                if (v instanceof byte[]) vs = "byte[" + ((byte[]) v).length + "]";
                else vs = String.valueOf(v);
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName())
                  .append('=').append(vs).append("  ");
            }
            sb.append('}');
            Log.i(sb.toString(), null);
        } catch (Throwable t) {
            Log.e(t, "BypassBond: dumpInfo");
        }
    }

    /**
     * Periodically re-announce online=true for every bound watch package, so its phone-side
     * status never goes stale before the watch quick-app opens its channel (which would
     * otherwise surface as a watch-side "401 not connected" on the first send).
     */
    private static void startReannounce() {
        if (reannounceStarted) return;
        reannounceStarted = true;
        try {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> dme = sDmeCls, ext = sExtCls; ClassLoader mcl = sMcl;
                        if (dme != null && ext != null && mcl != null) {
                            java.util.Map<String, String> b = BoundApps.bindings(null);
                            if (!b.isEmpty()) {
                                Object model = connectedModel(mcl);
                                if (model != null) {
                                    for (java.util.Map.Entry<String, String> e : b.entrySet()) {
                                        announceOnline(dme, ext, model, e.getKey(), e.getValue());
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignore) {
                    } finally {
                        h.postDelayed(this, REANNOUNCE_MS);
                    }
                }
            }, REANNOUNCE_MS);
            Log.i("BypassBond: re-announce timer every " + REANNOUNCE_MS + "ms", null);
        } catch (Throwable t) {
            Log.e(t, "BypassBond: startReannounce");
        }
    }

    /** Registered message-listener count from the binder's RemoteCallbackList field "f". */
    private static String fCount(Object binder) {
        try {
            java.lang.reflect.Field f = binder.getClass().getDeclaredField("f");
            f.setAccessible(true);
            Object rcl = f.get(binder);
            if (rcl == null) return "f=null";
            return String.valueOf(XposedHelpers.callMethod(rcl, "getRegisteredCallbackCount"));
        } catch (Throwable t) {
            return "f?";
        }
    }

    /** First non-static String field value of an object (the package in ecq/ckq). */
    private static String firstString(Object o) {
        if (o == null) return null;
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != String.class) continue;
            try { f.setAccessible(true); return (String) f.get(o); } catch (Throwable ignore) { }
        }
        return null;
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
