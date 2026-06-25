package test.hook.debug.xp;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.widget.ProgressBar;

import com.github.kyuubiran.ezxhelper.ClassUtils;
import com.github.kyuubiran.ezxhelper.Log;
import com.github.kyuubiran.ezxhelper.finders.MethodFinder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XposedHelpers;
import test.hook.debug.xp.utils.Save;

public class Install {
    /**
     * Read the ID of the given watchface file
     *
     * @param file watchface file path
     * @return watchface file ID
     */
    public static String getWatchFaceId(File file) {
        if (!file.exists()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        try (FileInputStream stream = new FileInputStream(file)) {
            if (stream.skip(40) != 40) {
                return null;
            }
            int read;
            while ((read = stream.read()) != 0) {
                builder.append((char) read);
            }
        } catch (IOException e) {
            Log.e(e, "getWatchFaceId");
        }
        return builder.toString();
    }

    /**
     * Install a watchface file
     *
     * @param loader  current class loader
     * @param file    watchface file path
     * @param context context
     */
    public static void installWatchFace(ClassLoader loader, File file, Context context) {
        try {
            String watchFaceId = getWatchFaceId(file);
            if (watchFaceId == null) {
                Log.e("Failed to get id from " + file.getAbsolutePath(), null);
                return;
            }

            Class<?> model = XposedHelpers.findClass("com.xiaomi.fitness.watch.face.viewmodel.FaceDetailViewModel", loader);
            Object instance = model.newInstance();

            Object controller = XposedHelpers.getObjectField(instance, "faceInstallController");

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setCancelable(false);
            ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(false);

            builder.setView(progressBar);
            AlertDialog dialog = builder.create();
            dialog.show();

            Class<?> callbackClass = XposedHelpers.findClass("com.xiaomi.fitness.watch.face.install.FaceInstallPushCallback", loader);
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                try {
                    switch (method.getName()) {
                        case "onProgress": {
                            int pos = (int) args[0];
                            Log.i("p: " + pos, null);
                            progressBar.setProgress(pos);
                            break;
                        }
                        case "onFinish": {
                            boolean success = (boolean) args[0];
                            int code = (int) args[1];
                            Log.i("success: " + success + " code: " + code, null);
                            dialog.dismiss();
                            break;
                        }
                        default:
                            throw new IllegalStateException("Unexpected value: " + method.getName());
                    }
                } catch (Throwable e) {
                    Log.e(e, method.toString());
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                }
                return null;
            });

            XposedHelpers.callMethod(controller, "doInstall", new Class<?>[]{
                    String.class, String.class, Integer.class, callbackClass
            }, file.getAbsolutePath(), watchFaceId, 0, callback);
        } catch (Throwable e) {
            Log.e(e, "installWatchFace");
        }
    }

    /**
     * Launch the firmware update page
     *
     * @param loader  current class loader
     * @param context context for the Intent
     * @param path    firmware file location
     */
    public static void invokeUpdate(ClassLoader loader, Object context, String path) {
        Class<?> checkUpdateManagerImpl = XposedHelpers.findClass("com.mi.fitness.checkupdate.util.CheckUpdateManagerImpl", loader);
        Object manager = XposedHelpers.newInstance(checkUpdateManagerImpl);
        // boolean true means silent install
        XposedHelpers.callMethod(manager, "manualUpgrade", new Class[]{Context.class, String.class, boolean.class},
                context, path, false);
    }

    /**
     * Get the device manager
     *
     * @param loader current class loader
     * @return com.xiaomi.fitness.device.manager.WearableDeviceManagerImpl
     */
    public static Object getDeviceManager(ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> deviceManager = ClassUtils.loadFirstClass("com.xiaomi.fitness.device.manager.export.DeviceManager", "com.xiaomi.fitness.device.manager.export.WearableDeviceManager");
        Object companion = XposedHelpers.getStaticObjectField(deviceManager, "Companion");
        Class<?> deviceManagerExtKt = XposedHelpers.findClass("com.xiaomi.fitness.device.manager.export.DeviceManagerExtKt", loader);
        return ClassUtils.invokeStaticMethodBestMatch(deviceManagerExtKt, "getInstance", null, companion);
    }

    public static Object getCurrentDevice(ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
        Object instance = getDeviceManager(loader);
        Object deviceModel = XposedHelpers.callMethod(instance, "getCurrentDeviceModel");
        if (deviceModel == null || !(boolean) XposedHelpers.callMethod(deviceModel, "isDeviceConnected")) {
            return null;
        }
        return deviceModel;
    }

    /**
     * Uninstall an app
     *
     * @param loader  current class loader
     * @param thisObj current method object
     */
    public static Object unInstall(ClassLoader loader, Object thisObj) throws InvocationTargetException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException {
        if (Save.sign == null) {
            return true;
        }
        Object deviceModel = getCurrentDevice(loader);
        if (deviceModel == null) {
            return true;
        }

        Object did = XposedHelpers.callMethod(deviceModel, "getDid");

        Object pkgName = XposedHelpers.getObjectField(thisObj, "pkgName");
        Class<?> deviceModelExtKt = XposedHelpers.findClass("com.xiaomi.xms.wearable.extensions.DeviceModelExtKt", loader);
        Class<?> callback = XposedHelpers.findClass("com.xiaomi.xms.wearable.ui.debug.ThirdAppDebugFragment$unInstallApp$1", loader);
        Object callbackObj = XposedHelpers.newInstance(callback, new Class<?>[]{XposedHelpers.findClass("com.xiaomi.xms.wearable.ui.debug.ThirdAppDebugFragment", loader), String.class}, thisObj, did);

        Method uninstallApp = MethodFinder.fromClass(deviceModelExtKt).filterByName("uninstallApp").first();
        uninstallApp.setAccessible(true);
        uninstallApp.invoke(deviceModelExtKt, deviceModel, pkgName, Save.sign, callbackObj);
        return false;
    }

    /**
     * Create a temporary file
     *
     * @param context context
     * @param data    file source
     * @return temporary file location
     */
    public static File saveTmpFile(Context context, Uri data) throws Throwable {
        File tmpFace = new File(context.getCacheDir(), "tmpFile");
        try (FileOutputStream stream = new FileOutputStream(tmpFace)) {
            byte[] bytes = new byte[0x400];
            try (InputStream inputStream = context.getContentResolver().openInputStream(data)) {
                if (inputStream == null) {
                    return null;
                }
                int read;
                while ((read = inputStream.read(bytes)) != -1) {
                    stream.write(bytes, 0, read);
                }
            }
        }
        return tmpFace;
    }
}
