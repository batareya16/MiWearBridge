package test.hook.debug.xp.utils

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.luckypray.dexkit.DexKitBridge

/**
 * DexKit utility
 *
 * @author YifePlayte
 */
object DexKit {
    private lateinit var hostDir: String
    private var isInitialized = false
    val dexKitBridge: DexKitBridge by lazy {
        System.loadLibrary("dexkit")
        DexKitBridge.create(hostDir)!!.also {
            isInitialized = true
        }
    }

    /**
     * Initialize the full apk path for DexKit
     */
    fun initDexKit(loadPackageParam: LoadPackageParam) {
        hostDir = loadPackageParam.appInfo.sourceDir
    }

    /**
     * Close the DexKit bridge
     */
    fun closeDexKit() {
        if (isInitialized) dexKitBridge.close()
    }
}