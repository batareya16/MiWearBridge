package test.hook.debug.xp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * @author user
 */
public class EncryptKey {
    /**
     * Get the currently stored EncryptKey info
     * Returns did -> [device name, EncryptKey]
     *
     * @param classLoader current class loader
     * @param cb          data callback
     */
    public static void showEncryptKey(ClassLoader classLoader, Callback<Map<String, String[]>> cb) {
        try {
            Object deviceManager = Install.getDeviceManager(classLoader);
            if (deviceManager == null) {
                cb.onError("Failed to getDeviceManager", null);
                return;
            }

            List<?> infoList = (List<?>) XposedHelpers.callMethod(deviceManager, "getDeviceList");

            Map<String, String[]> result = new HashMap<>();

            for (Object o : infoList) {
                String did = (String) XposedHelpers.callMethod(o, "getDid");
                if (did == null) {
                    continue;
                }
                String name = (String) XposedHelpers.callMethod(o, "getName");

                Object detail = XposedHelpers.callMethod(o, "getDetail");
                if (detail == null) {
                    continue;
                }
                String encryptKey = (String) XposedHelpers.callMethod(detail, "getEncryptKey");
                result.put(did, new String[]{name, encryptKey});
            }
            cb.onSuccess(result);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            cb.onError(e.getMessage(), e);
        }
    }
}
