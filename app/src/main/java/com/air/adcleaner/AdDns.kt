package com.air.adcleaner

import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 在 DNS 层掐掉广告 SDK 的素材与上报请求。
 *
 * 这一层**不能指望它单独去掉界面上的广告** —— 实测在加固包上确实命中了
 * （`blocked ad dns: api-access.pangolin-sdk-toutiao.com`、`v2mi.gdt.qq.com`），
 * 但界面上的广告没消失：素材可能已缓存（清过 cache 仍在）、或从 CDN 域/直连 IP 取、
 * 或走的是别家联盟。**真正去掉界面广告的是 [AdCleaner]。**
 *
 * 保留这一层的理由是它顺带挡掉了相当一部分上报请求，对省电/省流量有正收益，
 * 且失败路径是 SDK 自己的失败回调，不影响 App 主流程。
 *
 * 为什么不对广告 SDK 的初始化下手：加固包里那些类在 hook 安装时刻根本不存在，
 * `findClass("com.bytedance.sdk.openadsdk.TTAdSdk")` 必然返回 null。
 * 而 `java.net.InetAddress` 是 boot classpath 上的类，任何时刻都可见。
 */
object AdDns {

    private const val TAG = "AdCleaner/Dns"

    /**
     * 广告域名特征串。
     *
     * 用子串匹配而不是精确列表：这些 SDK 的接口域名带随机前缀
     * （`api-access.pangolin-sdk-toutiao.com`、`x1.gdt.qq.com`）。
     * 只列广告主域，不碰内容域（比如只挡 `gdt.qq.com`，不碰整个 `gtimg.cn`）。
     */
    private val AD_HOSTS = listOf(
        "pangolin-sdk-toutiao",   // 穿山甲
        "pangle",
        "gdt.qq.com",             // 优量汇
        "pgdt.gtimg.cn",
        "hiad.huawei.com",        // 华为广告
        "adkwai.com",             // 快手
        "kuaishouzt.com",
        "kwad",
        "mobads.baidu.com",       // 百度联盟
        "mobads-logs.baidu.com",
    )

    @Volatile private var hits = 0

    fun install(cl: ClassLoader) {
        val inet = runCatching { XposedHelpers.findClass("java.net.InetAddress", cl) }.getOrNull()
        if (inet == null) {
            log("InetAddress missing")
            return
        }
        var n = 0
        inet.declaredMethods
            .filter { it.name == "getAllByName" || it.name == "getByName" }
            .filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
            .forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!on()) return
                            val host = param.args[0] as? String ?: return
                            val h = host.lowercase()
                            if (AD_HOSTS.none { h.contains(it) }) return
                            hits++
                            if (hits <= 3) log("blocked ad dns: $host")
                            param.result = null
                            param.throwable = java.net.UnknownHostException(host)
                        }
                    })
                    n++
                }.onFailure { log("hook ${m.name} failed: ${it.message}") }
            }
        log("armed, hooked x$n")
    }

    /** 与 AdCleaner 共用同一个总开关。 */
    private fun on(): Boolean = runCatching {
        val app = android.app.AndroidAppHelper.currentApplication() ?: return@runCatching true
        Settings.Secure.getInt(app.contentResolver, AdCleaner.SECURE_KEY, 1) != 0
    }.getOrDefault(true)

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
