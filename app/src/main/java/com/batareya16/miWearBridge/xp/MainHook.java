package com.batareya16.miWearBridge.xp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Toast;

import com.github.kyuubiran.ezxhelper.ClassUtils;
import com.github.kyuubiran.ezxhelper.EzXHelper;
import com.github.kyuubiran.ezxhelper.HookFactory;
import com.github.kyuubiran.ezxhelper.Log;
import com.github.kyuubiran.ezxhelper.finders.MethodFinder;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import com.batareya16.miWearBridge.xp.ui.DialogView;
import com.batareya16.miWearBridge.xp.utils.DexKit;
import com.batareya16.miWearBridge.xp.utils.Save;
import com.batareya16.miWearBridge.xp.utils.SignUtils;

public class MainHook implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {
    public MainHook() {
    }

    private static void gotoDebugPage(ClassLoader classLoader, Context activity) {
        try {
            Class<?> xmsManager = XposedHelpers.findClass("com.xms.wearable.export.XmsManager", classLoader);
            Object companionObj = XposedHelpers.getStaticObjectField(xmsManager, "Companion");

            Class<?> xmsManagerExtKt = XposedHelpers.findClass("com.xms.wearable.export.XmsManagerExtKt", classLoader);
            Object instance = XposedHelpers.callStaticMethod(xmsManagerExtKt, "getInstance", new Class<?>[]{XposedHelpers.findClass("com.xms.wearable.export.XmsManager$Companion", classLoader)}, companionObj);

            XposedHelpers.callMethod(instance, "gotoDebugPage", new Class<?>[]{Activity.class}, activity);
        } catch (Throwable e) {
            Log.e(e, "gotoDebugPage");
        }
    }

    private static AlertDialog.Builder createWarningDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(Res.string(Res.firmware_warning_title));
        builder.setMessage(Res.string(Res.firmware_warning));
        builder.setCancelable(false);
        return builder;
    }

    private static Dialog createSelectDialog(ClassLoader loader, Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        DialogView view = DialogView.create(context);

        builder.setView(view.getView());
        AlertDialog result = builder.create();

        view.addNode(Save.Type.APP.getText(), v -> {
            Save.status = Save.Type.APP;
            gotoDebugPage(loader, context);
            result.dismiss();
        });

        view.addNode(Save.Type.WATCHFACE.getText(), v -> {
            Save.status = Save.Type.WATCHFACE;
            gotoDebugPage(loader, context);
            result.dismiss();
        });

        view.addNode(Save.Type.FIRMWARE.getText(), v -> {
            AlertDialog.Builder warningDialog = createWarningDialog(context);
            warningDialog.setPositiveButton("OK", (dialog, which) -> {
                Save.status = Save.Type.FIRMWARE;
                gotoDebugPage(loader, context);
            });
            warningDialog.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
            warningDialog.show();
            result.dismiss();
        });

        view.addNode(Save.Type.PULL_LOG.getText(), v -> {
            DeviceLog.pullLog(loader, new Callback<String>() {
                @Override
                public void onError(String msg, @Nullable Throwable e) {
                    Toast.makeText(context, String.format(Locale.getDefault(), "%s: %s\n%s",
                                    Res.string(Res.fail_log), msg, android.util.Log.getStackTraceString(e)),
                            Toast.LENGTH_LONG).show();
                }

                @Override
                public void onSuccess(String path) {
                    Toast.makeText(context, String.format(Locale.getDefault(), "%s: %s", Res.string(Res.success_log), path),
                            Toast.LENGTH_LONG).show();
                }
            });
            result.dismiss();
        });

        view.addNode(Save.Type.ENCRYPT_KEY.getText(), v -> {
            EncryptKey.showEncryptKey(loader, new Callback<Map<String, String[]>>() {
                @Override
                public void onError(String msg, @Nullable Throwable e) {
                    Toast.makeText(context, String.format(Locale.getDefault(), "%s: %s\n%s",
                                    Res.string(Res.fail_log), msg, android.util.Log.getStackTraceString(e)),
                            Toast.LENGTH_LONG).show();
                }

                @Override
                public void onSuccess(Map<String, String[]> obj) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    EditText text = new EditText(context);
                    text.setTextColor(context.getColor(android.R.color.primary_text_light));
                    text.setBackground(context.getDrawable(android.R.drawable.edit_text));
                    text.setHintTextColor(context.getColor(android.R.color.darker_gray));

                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String[]> entry : obj.entrySet()) {
                        sb.append(entry.getKey()).append(": ").append(Arrays.toString(entry.getValue())).append("\n");
                    }
                    text.setText(sb.toString());
                    builder.setView(text);
                    builder.show();
                }
            });
            result.dismiss();
        });

        view.addNode("Bind apps to watch", v -> {
            showBindDialog(context);
            result.dismiss();
        });

        view.addNode("Auto-sync to cloud", v -> {
            showAutoSyncDialog(context);
            result.dismiss();
        });

        return result;
    }

    /** Bindings: watch app -> phone coordinator (many watch apps to one coordinator). */
    @SuppressLint("SetTextI18n")
    private static void showBindDialog(final Context context) {
        final int dp = (int) (8 * context.getResources().getDisplayMetrics().density);
        final android.content.pm.PackageManager pm = context.getPackageManager();

        final android.widget.LinearLayout col = new android.widget.LinearLayout(context);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(dp * 2, dp * 2, dp * 2, dp * 2);

        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText("Watch app -> phone coordinator. Several watch apps can point to one coordinator.\n"
                + "Watch apps must be signed with the coordinator's key. Restart Mi Fitness after changes.");
        hint.setTextColor(context.getColor(android.R.color.darker_gray));
        col.addView(hint);

        final android.widget.LinearLayout list = new android.widget.LinearLayout(context);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.setPadding(0, dp, 0, dp);
        col.addView(list);

        final android.widget.Button add = new android.widget.Button(context);
        add.setText("+ Add binding");
        col.addView(add);

        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(col);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            for (java.util.Map.Entry<String, String> e : BoundApps.bindings(context).entrySet()) {
                final String watch = e.getKey();
                String coord = e.getValue();
                String coordLabel = coord;
                try { coordLabel = pm.getApplicationLabel(pm.getApplicationInfo(coord, 0)).toString(); }
                catch (Throwable ignore) { }
                android.widget.LinearLayout row = new android.widget.LinearLayout(context);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setPadding(0, dp, 0, dp);
                android.widget.TextView t = new android.widget.TextView(context);
                t.setText(watch + "\n→ " + coordLabel);
                t.setTextColor(context.getColor(android.R.color.primary_text_light));
                t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(t);
                android.widget.Button del = new android.widget.Button(context);
                del.setText("✕");
                del.setOnClickListener(v -> {
                    BoundApps.removeBinding(context, watch);
                    refresh[0].run();
                });
                row.addView(del);
                list.addView(row);
            }
        };

        add.setOnClickListener(v -> pickCoordinatorApp(context, refresh[0]));

        refresh[0].run();

        new AlertDialog.Builder(context)
                .setTitle("App ↔ watch bindings")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Step 1: pick the phone coordinator app from installed apps. */
    private static void pickCoordinatorApp(final Context context, final Runnable afterAdd) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_MAIN);
        it.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(it, 0);
        java.util.TreeMap<String, String> apps = new java.util.TreeMap<>(); // "label (pkg)" -> pkg
        for (android.content.pm.ResolveInfo ri : ris) {
            try {
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                apps.put(label + "  (" + pkg + ")", pkg);
            } catch (Throwable ignore) { }
        }
        final String[] keys = apps.keySet().toArray(new String[0]);
        final String[] pkgs = new String[keys.length];
        for (int i = 0; i < keys.length; i++) pkgs[i] = apps.get(keys[i]);

        new AlertDialog.Builder(context)
                .setTitle("Pick phone (coordinator) app")
                .setItems(keys, (d, which) -> enterWatchPackage(context, pkgs[which], afterAdd))
                .show();
    }

    /** Step 2: enter the watch app package and save the binding. */
    @SuppressLint("SetTextI18n")
    private static void enterWatchPackage(final Context context, final String coord, final Runnable afterAdd) {
        final EditText input = new EditText(context);
        input.setHint("watch app package, e.g. com.elli.lights");
        input.setSingleLine(true);
        input.setTextColor(context.getColor(android.R.color.primary_text_light));
        input.setHintTextColor(context.getColor(android.R.color.darker_gray));

        new AlertDialog.Builder(context)
                .setTitle("Watch package for\n" + coord)
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String watch = input.getText().toString().trim();
                    if (!watch.isEmpty()) {
                        BoundApps.addBinding(context, watch, coord);
                        if (afterAdd != null) afterAdd.run();
                        Toast.makeText(context, "Added. Restart Mi Fitness.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Toggle periodic watch -> cloud auto-sync + interval. */
    @SuppressLint("SetTextI18n")
    private static void showAutoSyncDialog(final Context context) {
        final int dp = (int) (8 * context.getResources().getDisplayMetrics().density);

        android.widget.LinearLayout col = new android.widget.LinearLayout(context);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(dp * 2, dp * 2, dp * 2, dp * 2);

        final android.widget.Switch sw = new android.widget.Switch(context);
        sw.setText("Periodic data sync from watch to cloud");
        sw.setTextColor(context.getColor(android.R.color.primary_text_light));
        sw.setChecked(BoundApps.autoSyncEnabled(context));
        col.addView(sw);

        android.widget.TextView lbl = new android.widget.TextView(context);
        lbl.setText("Interval, minutes:");
        lbl.setTextColor(context.getColor(android.R.color.darker_gray));
        lbl.setPadding(0, dp, 0, 0);
        col.addView(lbl);

        final EditText interval = new EditText(context);
        interval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        interval.setSingleLine(true);
        interval.setText(String.valueOf(BoundApps.autoSyncInterval(context)));
        interval.setTextColor(context.getColor(android.R.color.primary_text_light));
        col.addView(interval);

        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText("Requires: Mi Fitness with no battery restriction and the band connected.\n"
                + "Changes may need a Mi Fitness restart to take effect.");
        hint.setTextColor(context.getColor(android.R.color.darker_gray));
        hint.setPadding(0, dp, 0, 0);
        col.addView(hint);

        new AlertDialog.Builder(context)
                .setTitle("Auto-sync")
                .setView(col)
                .setPositiveButton("Save", (d, w) -> {
                    int min;
                    try { min = Integer.parseInt(interval.getText().toString().trim()); }
                    catch (Exception e) { min = BoundApps.DEFAULT_INTERVAL_MIN; }
                    if (min < 1) min = 1;
                    BoundApps.setAutoSync(context, sw.isChecked(), min);
                    AutoSync.reschedule();
                    Toast.makeText(context,
                            sw.isChecked() ? ("Auto-sync ON, " + min + " min") : "Auto-sync OFF",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Handle app install
     */
    private static void onHandleApp(Object thisObj, Intent intent) {
        XposedHelpers.callMethod(thisObj, "prepareInstall",
                new Class<?>[]{String.class, Intent.class}, "thirdapp.rpk", intent);
    }

    /**
     * Handle watchface install
     */
    private static boolean onHandleWatchFace(ClassLoader loader, Context context, Uri data) throws Throwable {
        File tmpFace = Install.saveTmpFile(context, data);
        if (tmpFace == null) {
            return false;
        }
        Install.installWatchFace(loader, tmpFace, context);
        return true;
    }

    /**
     * Handle firmware install
     */
    private static boolean onHandleFirmware(ClassLoader loader, Context context, Uri data) throws Throwable {
        File tmpFace = Install.saveTmpFile(context, data);
        if (tmpFace == null) {
            return false;
        }
        Install.invokeUpdate(loader, context, tmpFace.getAbsolutePath());
        return true;
    }


    @SuppressLint("DiscouragedApi")
    private static void loadHook(ClassLoader classLoader) throws ClassNotFoundException {
        // Initialize EzXHelper's context using the About page Activity
        Class<?> clazzAboutActivity = ClassUtils.loadClass("com.xiaomi.fitness.about.AboutActivity", null);
        Method methodOnCreate = MethodFinder.fromClass(clazzAboutActivity).filterByName("onCreate").first();
        HookFactory.createMethodHook(methodOnCreate, hookFactory -> hookFactory.before(param -> EzXHelper.initAppContext((Activity) param.thisObject, false)));

        Class<?> thirdAppDebugFragment = XposedHelpers.findClass("com.xiaomi.xms.wearable.ui.debug.ThirdAppDebugFragment", classLoader);
        if (thirdAppDebugFragment == null) {
            Log.e("ThirdAppDebugFragment not found", null);
            return;
        }

        Method methodStartWebView = EntryPoint.findEntryPoint();
        if (methodStartWebView == null) {
            Log.e("Current version is not supported", null);
            return;
        }

        Log.i("Entry point " + methodStartWebView.toString(), null);

        HookFactory.createMethodHook(methodStartWebView, hookFactory -> hookFactory.before(param -> {
            // Get the user-agreement string
            Context appContext = EzXHelper.getAppContext();
            Resources appResources = appContext.getResources();
            int identifierAboutPrivacyLicenseAgreement = appResources.getIdentifier("about_privacy_license_agreement", "string", EzXHelper.hostPackageName);
            String stringAboutPrivacyLicenseAgreement = appResources.getString(identifierAboutPrivacyLicenseAgreement);

            // If it matches, intercept the navigation
            if (!stringAboutPrivacyLicenseAgreement.equals(param.args[1])) {
                return;
            }

            ClassLoader loader = EzXHelper.getSafeClassLoader();

            // Show the install-mode selection dialog
            Dialog dialog = createSelectDialog(loader, appContext);
            dialog.show();

            // Show the current mode
            Method bindView = MethodFinder.fromClass(thirdAppDebugFragment).filterByName("bindView").firstOrNull();
            if (bindView != null) {
                HookFactory.createMethodHook(bindView, hookFactory1 -> hookFactory1.after(methodHookParam ->
                        XposedHelpers.callMethod(methodHookParam.thisObject, "setTitle",
                                new Class[]{String.class}, Save.status.getText())));
            }


            // Intercept file selection
            XposedHelpers.findAndHookMethod(thirdAppDebugFragment, "onActivityResult", int.class, int.class, Intent.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    if (((int) param.args[0]) != 10 || ((int) param.args[1]) != -1) {
                        return null;
                    }
                    Intent arg = (Intent) param.args[2];
                    Uri data = arg.getData();
                    if (data == null) {
                        return null;
                    }

                    Context context = (Context) XposedHelpers.callMethod(param.thisObject, "getMActivity");

                    switch (Save.status) {
                        case APP:
                            onHandleApp(param.thisObject, arg);
                            break;
                        case WATCHFACE:
                            if (!onHandleWatchFace(loader, context, data)) {
                                Toast.makeText(context, Res.string(Res.fail_watchface), Toast.LENGTH_LONG).show();
                            }
                            break;
                        case FIRMWARE:
                            if (!onHandleFirmware(loader, context, data)) {
                                Toast.makeText(context, Res.string(Res.fail_firmware), Toast.LENGTH_LONG).show();
                            }
                            break;
                        default:
                            throw new IllegalStateException("Unexpected value: " + Save.status);
                    }

                    return null;
                }
            });
            param.setResult(null);
        }));

        try {
            XposedHelpers.findAndHookMethod(thirdAppDebugFragment, "unInstallApp", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    return Install.unInstall(classLoader, methodHookParam.thisObject);
                }
            });
        } catch (Throwable t) {
            Log.e(t, "hook unInstallApp");
        }

        try {
            XposedHelpers.findAndHookMethod(thirdAppDebugFragment, "sendThirdAppFile", File.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    Save.sign = SignUtils.generateSign((File) param.args[0]);
                }
            });
        } catch (Throwable t) {
            Log.e(t, "hook sendThirdAppFile");
        }

        // Do not restrict the package name when installing a watchface
        try {
            XposedHelpers.findAndHookMethod(thirdAppDebugFragment, "isPackageReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    super.afterHookedMethod(param);
                    if (Save.status == Save.Type.APP) {
                        return;
                    }
                    param.setResult(true);
                }
            });
        } catch (Throwable t) {
            Log.e(t, "hook isPackageReady");
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws ClassNotFoundException {
        String packageName = loadPackageParam.packageName;
        boolean isMiFitness = "com.xiaomi.wearable".equals(packageName) || "com.mi.health".equals(packageName);
        // Any other package in the module scope is a third-party companion app:
        // suppress the client-side "not bond" in xms-wearable-lib and redirect the bind to Mi Fitness.
        if (!isMiFitness) {
            if ("com.batareya16.miWearBridge".equals(packageName)) return; // the module itself
            EzXHelper.initHandleLoadPackage(loadPackageParam);
            EzXHelper.setLogTag("WearableDebug");
            try { BypassBondClient.apply(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "BypassBondClient"); }
            return;
        }
        EzXHelper.initHandleLoadPackage(loadPackageParam);
        EzXHelper.setLogTag("WearableDebug");
        EzXHelper.setToastTag("WearableDebug");
        DexKit.INSTANCE.initDexKit(loadPackageParam);
        try {
            // Isolate each feature: if one hook can't find a method in this
            // Mi Fitness version, the others (including install) should still apply.
            try { DisableAd.interceptAd(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "interceptAd"); }
            try { DisableAd.disableReport(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "disableReport"); }
            try { DisableKeepLinkNotify.disableDeviceSystemRedDot(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "disableDeviceSystemRedDot"); }
            try { DisableKeepLinkNotify.disableTabRedDot(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "disableTabRedDot"); }
            try { DisableKeepLinkNotify.disableDialog(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "disableDialog"); }
            // bypass the "not bond" check in the XMS service (especially the :device process)
            try { BypassBond.apply(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "BypassBond"); }
            // periodic watch -> cloud auto-sync
            try { AutoSync.apply(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "AutoSync"); }
            // key feature - installing third-party apps/watchfaces/firmware
            try { loadHook(loadPackageParam.classLoader); } catch (Throwable t) { Log.e(t, "loadHook"); }
        } finally {
            DexKit.INSTANCE.closeDexKit();
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        String packageName = resparam.packageName;
        if (!"com.xiaomi.wearable".equals(packageName) && !"com.mi.health".equals(packageName)) {
            return;
        }
        Res.init(resparam);
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        EzXHelper.initZygote(startupParam);
    }
}
