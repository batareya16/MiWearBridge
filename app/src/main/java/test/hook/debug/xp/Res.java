package test.hook.debug.xp;

import com.github.kyuubiran.ezxhelper.EzXHelper;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import test.hook.debug.R;

/**
 * Strings are read directly from the module resources via getModuleRes(), WITHOUT injecting
 * them into the host app: addResource/XResources does not work on newer firmware (HyperOS 3+).
 */
public class Res {
    public static final int firmware_warning = R.string.firmware_warning;
    public static final int firmware_warning_title = R.string.firmware_warning_title;
    public static final int fail_watchface = R.string.fail_watchface;
    public static final int fail_firmware = R.string.fail_firmware;
    public static final int fail_log = R.string.fail_log;
    public static final int success_log = R.string.success_log;

    /** string from the module's own resources (not the host app) */
    public static String string(int moduleResId) {
        return EzXHelper.getModuleRes().getString(moduleResId);
    }

    public static void init(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        // no longer inject resources into the host - read everything via getModuleRes()
    }
}
