package com.air.adcleaner

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。`xposed_init` 里声明的类名。
 *
 * 装哪些 App 由 LSPosed 的作用域决定 —— 这个模块不带 `xposedscope` 资源，
 * 装完后需要自己在管理器里勾选目标 App，再 force-stop 目标 App 使其生效。
 *
 * 不需要勾「系统框架」：所有 hook 都在目标 App 进程内完成。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("[AdCleaner] initZygote, path=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只装第三方 App。系统进程里没有广告 View 可拦，装上去纯属浪费。
        if (lpparam.packageName == "android" || lpparam.packageName.startsWith("com.android.")) return
        if (!lpparam.isFirstApplication) return

        AdCleaner.install(lpparam.packageName)
        AdDns.install(lpparam.classLoader)
    }
}
