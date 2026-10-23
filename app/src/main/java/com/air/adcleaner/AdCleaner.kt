package com.air.adcleaner

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Dialog
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 控件级去广告。
 *
 * ## 为什么不用 SDK 层拦截
 *
 * 主流去广告模块（AdClose / FuckApp 那一类）的思路是「拦住广告 SDK 的初始化」。
 * 这条路遇到**加固包就废了**：以 `com.zjwh.android_wh_physicalfitness` 为例，它是
 * 爱加密 + zxprotect 加固，`handleLoadPackage` 执行时真实 dex 还没解密，
 * `findClass("com.kwad...")` 必然是 null，日志只会留下一句 `no ad sdk found`。
 *
 * 实测 dex header（加固包的判壳方法）：
 *
 * ```
 * file_size       : 71061232      # 单个 dex 71MB
 * class_defs_size : 30            # 但只有 30 个类
 * data_size       : 50244         # 真实 dex 只有 50KB
 * ```
 *
 * 那 71MB 是运行时才解密的加密载荷（`lib/arm64-v8a/libijiami_1011.so` +
 * `assets/ijiami_1011.VData` + `libzxprotect.so`）。想改包就得先脱壳，
 * 而且每次 App 更新都要重做一遍 —— 成本远大于收益。
 *
 * ## 换思路：不看类，看 View
 *
 * 广告最终一定要变成屏幕上的 View。而 View 上有三样东西**加固壳改不了**：
 *
 * 1. **资源 id 属于哪个包** —— SDK 的资源编译在 `resources.arsc` 里，壳只处理 dex；
 * 2. **实现类的名字** —— 运行时 `javaClass.name` 拿到的就是真名，混淆只混淆 App 自己的代码；
 * 3. **广告位 id 的名字** —— UI 开发给广告位起名时不会回避 `ad` / `banner` 这个词。
 *
 * 三条线索互不依赖，命中任一即可。整套逻辑**不碰 dex，所以对加固包天然有效**。
 */
object AdCleaner {

    private const val TAG = "AdCleaner"

    /** 总开关（Settings.Secure），默认开。AdDns 共用同一个开关。 */
    internal const val SECURE_KEY = "adcleaner_on"

    /** 调试开关：把整棵 View 树打到日志，命中标 AD。默认关。 */
    private const val SECURE_DUMP = "adcleaner_dump"

    /**
     * 广告 SDK 的资源包名白名单。
     *
     * 匹配规则是「精确相等 或 前缀 + .」，避免 `com.qq.evilapp` 这类误命中。
     * 首个是实测目标 App 真正在用的，其余按常见联盟补全。
     */
    private val AD_RES_PKGS = arrayOf(
        "com.kwad.dy.sdk",              // 快手联盟 ← 实测在用
        "com.kwad",                     // 快手（其他打包变体）
        "com.bytedance.sdk.openadsdk",  // 穿山甲 / Pangle / GroMore
        "com.qq.e",                     // 优量汇 GDT
        "com.qq.e.ads",
        "com.baidu.mobads",
        "com.sigmob",
        "com.mbridge",
        "com.applovin",
        "com.unity3d.ads",
        "com.google.android.gms.ads",
        "com.miui.ads",                 // 小米广告联盟
        "com.xiaomi.ad",
    )

    /**
     * 类名关键词。小写比对。
     *
     * 资源 id 只能命中「SDK 自己 inflate 的布局」。一旦广告 View 是 App 用自家布局
     * 包起来的（容器写成 `R.layout.home_ad_slot`），id 就属于 App 的包名，资源锚点失效。
     * 类名不会：`com.bytedance...TTNativeAdView` 这种实现类的名字是 SDK 编译进 dex 的。
     */
    private val AD_CLASS_MARKS = arrayOf(
        "ksad",                                     // 快手 KSAd*
        "ttnativead", "ttnativeexpress", "ttad",
        "pangle", "csj",
        "unifiednativead", "nativeadview", "nativeexpressad",
        "gdt", "gromore",
        "mobads",
        "sigmob",
    )

    /**
     * 启用「自留广告位」规则的包名。
     *
     * **用之前把这里换成你自己的目标 App。** 理由见 [isAppAdSlot]：按 id 名切词是
     * 启发式，别的 App 里 `ad` 可能是 address、adapter 的缩写，全量放开会误杀正常界面。
     * 所以这条规则只在白名单包名内启用，宁窄勿宽。
     */
    private val SLOT_RULE_PKGS = setOf(
        "com.zjwh.android_wh_physicalfitness",      // 实测目标
    )

    /** id 名里出现这些词段，就当广告位。刻意不收 native / feed / recommend。 */
    private val AD_ID_WORDS = setOf("ad", "ads", "advert", "advertisement", "banner")

    /** 实测确认过的广告位 id（去掉包名前缀）。切词规则会漏，这份名单是兜底。 */
    private val APP_AD_ID_NAMES = setOf(
        "ad_container",   // SplashActivity / HomeActivity 的插屏与开屏容器
        "home_banner",    // 首页横幅（会员卡推广等运营广告）
        "sport_banner",   // 运动页横幅
        "home_ad",        // AdDragFrameLayout
        "fl_native",      // 信息流原生广告卡
    )

    /** 会占满整个窗口的广告位。命中就整窗移除，而不是只隐藏内容。 */
    private val FULLSCREEN_AD_IDS = setOf("ad_container")

    /**
     * 是否启用「自留广告位」规则。**一旦置位不再回退。**
     *
     * 这里踩过一个坑，记下来：`handleLoadPackage` 在**同一个进程里会被回调多次**。
     * 目标 App 一加载 WebView，LSPosed 还会额外回调一次 `com.google.android.webview`。
     * 如果按「记录当前包名、再和目标包名比较」的写法，那次回调会把包名覆盖成 webview，
     * 之后 App 主进程里所有判定都会因为「包名不匹配」而静默返回 false ——
     * 表现就是规则完全失效，**而且日志里一点错误都没有**。
     *
     * 所以改成单向开关：只要有一次回调命中白名单，就永久开启。
     */
    @Volatile private var slotRuleOn = false
    @Volatile private var slotLogs = 0

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var hits = 0
    @Volatile private var dismissed = 0
    @Volatile private var flagOn = true
    @Volatile private var flagAt = 0L
    @Volatile private var sweepLogs = 0

    fun install(packageName: String) {
        if (packageName in SLOT_RULE_PKGS) slotRuleOn = true
        hookDialogShow()
        hookPopupWindow()
        hookWindowAdd()
        hookAddView()
        hookActivityResume()
        hookSetVisibility()
        startSweeper()
        log("armed: $packageName slotRule=$slotRuleOn")
    }

    // ---------------------------------------------------------------- 窗口层

    /**
     * `WindowManagerImpl.addView` 是**所有**窗口的唯一入口 —— Dialog、PopupWindow、
     * Toast、以及 App 自己 new 一个 WindowManager 直接挂的 view，最终都走这里。
     *
     * 实测「小米应用商店」那个插屏就是这条路径：它有 Dialog 的阴影 insets、
     * 类型是 `ty=APPLICATION`，但 `Dialog.show` 完全捕获不到 —— 说明它是自己拿
     * WindowManager 加进去的，不经过任何 Dialog 实例。
     *
     * 这里只做「认出来 + 隐藏」，不做移除：raw window 拿不到持有它的对象，
     * 无法 dismiss，硬拔 ViewRootImpl 会直接把进程搞崩。
     */
    private fun hookWindowAdd() {
        runCatching {
            val impl = Class.forName("android.view.WindowManagerImpl")
            XposedBridge.hookAllMethods(impl, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!on()) return
                    val v = param.args.getOrNull(0) as? View ?: return
                    for (delay in longArrayOf(300L, 1500L, 3000L)) {
                        main.postDelayed({
                            runCatching { scrubWindow(v) }
                                .onFailure { log("scrubWindow: ${it.message}") }
                        }, delay)
                    }
                }
            })
            log("hooked WindowManagerImpl.addView")
        }.onFailure { log("hook WindowManagerImpl failed: ${it.message}") }
    }

    private fun scrubWindow(root: View) {
        maybeDump(root, "win")
        val roots = ArrayList<View>(4)
        collectAdRoots(root, roots, 0)
        if (roots.isEmpty()) return
        roots.forEach { hide(it) }
        hits += roots.size
        log("scrub win: hid ${roots.size} ad view(s)")
    }

    /**
     * 定时扫遍**所有窗口**。
     *
     * 前面那些 hook 各管一段，但都依赖「某个事件发生」：
     * Dialog.show、PopupWindow.show、addView、WindowManager.addView。
     * 实测目标 App 的广告**长期复用同一个窗口，只换里面的内容** —— 上面这些事件
     * 在第一次之后就再也不触发了，于是广告换个素材又冒出来。
     *
     * 所以加一个兜底轮询：`WindowManagerGlobal.mViews` 是所有窗口 root view 的全局列表
     * （Dialog、PopupWindow、Activity、自建窗口全在里面），直接遍历它，绕开一切时机问题。
     *
     * 1.2s 一次的代价：目标界面不到 100 个节点，单次遍历在微秒级。
     * 递归深度另有 40 层上限，不会把主线程拖死。
     */
    private fun startSweeper() {
        main.postDelayed(object : Runnable {
            override fun run() {
                runCatching { sweepAllWindows() }.onFailure { log("sweep: ${it.message}") }
                main.postDelayed(this, 1200L)
            }
        }, 1000L)
    }

    private fun sweepAllWindows() {
        if (!on()) return
        val g = runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            de.robv.android.xposed.XposedHelpers.callStaticMethod(cls, "getInstance")
        }.getOrNull() ?: return

        @Suppress("UNCHECKED_CAST")
        val views = runCatching {
            (de.robv.android.xposed.XposedHelpers.getObjectField(g, "mViews") as? List<View>)?.toList()
        }.getOrNull() ?: return

        @Suppress("UNCHECKED_CAST")
        val impls = runCatching {
            (de.robv.android.xposed.XposedHelpers.getObjectField(g, "mRoots") as? List<Any>)?.toList()
        }.getOrNull() ?: emptyList()

        for (i in views.indices) {
            val root = views[i] ?: continue
            val found = ArrayList<View>(2)
            collectAdRoots(root, found, 0)
            if (found.isEmpty()) continue

            // 整窗口都是广告 → 直接摘掉窗口。
            // 只 GONE 内容不够：窗口的 dim（半透明黑色蒙层）是 WindowManagerService 画的，
            // 不属于任何 View，内容隐藏之后整屏还是一片灰。实测就是这样。
            //
            // 面积判不出来：这些容器在 addView 阶段就被 hide 了，等 sweeper 扫到时
            // width/height 已经是 0。所以改用「这窗口里装的是不是已知的全屏广告位」判定。
            val impl = impls.getOrNull(i)
            val fullscreenSlot = slotRuleOn && found.any { v ->
                resName(v).substringAfter(":id/", "") in FULLSCREEN_AD_IDS
            }
            if (fullscreenSlot && impl != null) {
                dimOff(root, impl)
                if (!isBaseWindow(impl) && removeWindow(root)) continue
            }

            found.forEach { hide(it) }
            if (found.size <= 3 && sweepLogs++ < 8) {
                log("sweep hid ${found.size}: ${found.map { resName(it) }}")
            }
        }
    }

    /**
     * 关掉广告窗口的 dim（半透明黑蒙层）。
     *
     * 内容 GONE 之后整屏还发灰，就是因为窗口本身带 `FLAG_DIM_BEHIND` ——
     * dim 由 WindowManagerService 绘制，不属于任何 View，隐藏 View 影响不到它。
     * 直接改窗口属性再 updateViewLayout，比移除窗口安全得多。
     */
    private fun dimOff(root: View, impl: Any) {
        runCatching {
            val attrs = de.robv.android.xposed.XposedHelpers
                .getObjectField(impl, "mWindowAttributes") as android.view.WindowManager.LayoutParams
            val dimFlag = android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
            if (attrs.dimAmount == 0f && (attrs.flags and dimFlag) == 0) return
            attrs.dimAmount = 0f
            attrs.flags = attrs.flags and dimFlag.inv()
            val wm = root.context.getSystemService(android.content.Context.WINDOW_SERVICE)
                as android.view.WindowManager
            wm.updateViewLayout(root, attrs)
            log("dim off")
        }.onFailure { log("dimOff: ${it.message}") }
    }

    /**
     * 是不是 Activity 的基础窗口。
     *
     * 拿不到属性时按「是」处理 —— 宁可留着广告窗口，也不能误删 Activity 主窗口，
     * 那会让用户直接黑屏。
     */
    private fun isBaseWindow(rootImpl: Any): Boolean = runCatching {
        val attrs = de.robv.android.xposed.XposedHelpers
            .getObjectField(rootImpl, "mWindowAttributes")
        de.robv.android.xposed.XposedHelpers.getIntField(attrs, "type") == 1
    }.getOrDefault(true)

    /**
     * 移除一个广告窗口。
     *
     * 走 `WindowManager.removeViewImmediate`，等于替用户点了那个关闭按钮：
     * 窗口连同 dim、阴影一起消失。必须在主线程调用，这里由 sweeper 保证。
     */
    private fun removeWindow(root: View): Boolean = runCatching {
        val wm = root.context.getSystemService(android.content.Context.WINDOW_SERVICE)
            as android.view.WindowManager
        wm.removeViewImmediate(root)
        dismissed++
        log("removed ad window #$dismissed")
        true
    }.getOrElse {
        log("removeWindow failed: ${it.message}")
        false
    }

    // ---------------------------------------------------------------- PopupWindow 层

    /**
     * PopupWindow 走 WindowManager 直接挂窗口，不经过任何 ViewGroup.addView，
     * Dialog.show 也捕获不到。这类弹窗（角标推广常用）只能从 PopupWindow 自身的 show 入口拦。
     */
    private fun hookPopupWindow() {
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!on()) return
                val pw = param.thisObject as? android.widget.PopupWindow ?: return
                val content = runCatching { pw.contentView }.getOrNull() ?: return
                for (delay in longArrayOf(150L, 700L)) {
                    main.postDelayed({
                        runCatching { scrubPopup(pw, content) }
                            .onFailure { log("scrubPopup: ${it.message}") }
                    }, delay)
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(android.widget.PopupWindow::class.java, "showAtLocation", cb)
            XposedBridge.hookAllMethods(android.widget.PopupWindow::class.java, "showAsDropDown", cb)
            log("hooked PopupWindow")
        }.onFailure { log("hook PopupWindow failed: ${it.message}") }
    }

    private fun scrubPopup(pw: android.widget.PopupWindow, content: View) {
        val roots = ArrayList<View>(2)
        collectAdRoots(content, roots, 0)
        if (roots.isEmpty()) return
        // 内容根本身就是广告 → 整个弹窗都是广告，直接关
        if (isAd(content)) {
            runCatching { pw.dismiss() }
            dismissed++
            log("dismissed ad popup #$dismissed (${resName(content)})")
            return
        }
        roots.forEach { hide(it) }
        hits += roots.size
    }

    // ---------------------------------------------------------------- Dialog 层

    /**
     * 插屏广告走的是 Dialog。
     *
     * 为什么 hook `Dialog.show` 而不是广告 SDK 的 show：Dialog 是 framework 类，
     * 加固包也一定加载它；而 SDK 的类名混淆 + 加密，两头都不可靠。
     *
     * show 之后广告 View 是异步 inflate 的，所以在 120/600/1500/3000ms 各查一次。
     * 查多次是因为素材走网络时 inflate 会远晚于 show（「先出空壳、后填内容」）。
     */
    private fun hookDialogShow() {
        runCatching {
            XposedBridge.hookAllMethods(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!on()) return
                    val d = param.thisObject as? Dialog ?: return
                    val deco = runCatching { d.window?.decorView }.getOrNull() ?: return
                    for (delay in longArrayOf(120L, 600L, 1500L, 3000L)) {
                        main.postDelayed({
                            runCatching { inspectDialog(d, deco) }
                                .onFailure { log("inspectDialog: ${it.message}") }
                        }, delay)
                    }
                }
            })
            log("hooked Dialog.show")
        }.onFailure { log("hook Dialog.show failed: ${it.message}") }
    }

    /**
     * 判断这个 Dialog 是不是「整个就是广告」。
     *
     * 面积占比 ≥ 50% 才 dismiss：广告卡片常常带自家关闭按钮/外框，
     * 但只要广告主体占了大半屏，这个 Dialog 就没有别的用途了。
     * 面积判不准时退回 GONE——宁可留个空壳，也不能误关正常弹窗
     * （实测目标 App 有「开始跑步」「权限申请」等业务弹窗）。
     */
    private fun inspectDialog(d: Dialog, deco: View) {
        maybeDump(deco, "dialog")
        val roots = ArrayList<View>(4)
        collectAdRoots(deco, roots, 0)
        if (roots.isEmpty()) return

        val biggest = roots.maxByOrNull { areaRatio(it, deco) } ?: return
        if (areaRatio(biggest, deco) >= 0.5f) {
            runCatching { d.dismiss() }
            dismissed++
            log("dismissed ad dialog #$dismissed (${resName(biggest)})")
            return
        }
        roots.forEach { hide(it) }
        hits += roots.size
        log("hid ${roots.size} ad view(s) in dialog")
    }

    // ---------------------------------------------------------------- addView 层

    /**
     * banner / 信息流广告位是运行时 add 进来的，Dialog 层看不到。
     *
     * 这里在 after 阶段处理而不是 before：`param.result = null` 跳过原方法会让
     * SDK 以为自己加成功了、实际 parent 是 null，后续操作它的 view 就 NPE。
     * 让它正常 add，再把尺寸压成 0，SDK 侧完全察觉不到。
     */
    private fun hookAddView() {
        var n = 0
        ViewGroup::class.java.declaredMethods
            .filter { it.name == "addView" }
            .filter { it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == View::class.java }
            .forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!on()) return
                            val child = param.args.getOrNull(0) as? View ?: return
                            // child 自己可能是广告根，也可能只是广告容器里的一个子控件
                            val ad = if (isAd(child)) child else nearestAdAncestor(child)
                            ad ?: return
                            hide(ad)
                            hits++
                            if (hits <= 5) log("hid ad view via addView: ${resName(ad)}")
                        }
                    })
                    n++
                }.onFailure { log("hook addView(${m.parameterTypes.size}) failed: ${it.message}") }
            }
        log("hooked addView x$n")
    }

    // ---------------------------------------------------------------- Activity 层

    /**
     * 兜底扫描。
     *
     * 有些广告是「先占位、后填内容」，addView 的时候还不是广告 View；
     * 还有些是复用的容器，内容通过 setVisibility / replace 切换。
     * 所以在页面恢复时延时补扫几拍。
     *
     * 这里**绝不清屏**（不 finish Activity）：主界面也是 Activity，
     * 一旦按面积误判就会把用户踢出去。只做 GONE。
     */
    private fun hookActivityResume() {
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!on()) return
                val a = param.thisObject as? Activity ?: return
                val deco = runCatching { a.window?.decorView }.getOrNull() ?: return
                for (delay in longArrayOf(300L, 1200L, 3000L)) {
                    main.postDelayed({
                        runCatching { scrubActivity(a, deco) }.onFailure { log("scrub: ${it.message}") }
                    }, delay)
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", cb)
            XposedBridge.hookAllMethods(Activity::class.java, "onWindowFocusChanged", cb)
            log("hooked Activity resume/focus")
        }.onFailure { log("hook Activity failed: ${it.message}") }
    }

    private fun scrubActivity(a: Activity, deco: View) {
        maybeDump(deco, a.javaClass.simpleName)
        val roots = ArrayList<View>(4)
        collectAdRoots(deco, roots, 0)
        if (roots.isEmpty()) return
        roots.forEach { hide(it) }
        hits += roots.size
        log("scrub ${a.javaClass.simpleName}: hid ${roots.size} ad view(s)")
    }

    // ---------------------------------------------------------------- 调试 dump

    /** 每个 tag 上次 dump 的时间戳，防止信息流反复刷新时刷爆日志。 */
    private val dumpAt = java.util.concurrent.ConcurrentHashMap<String, Long>(4)

    /**
     * 把整棵 View 树打到日志，每行一个节点，命中的节点前面标 `AD`。
     *
     * 存在的意义：自愈能力。广告 SDK 会换布局、换资源前缀，硬编码规则迟早漏；
     * 有了这个开关，漏了直接 `settings put secure adcleaner_dump 1`，
     * 打开 App 抓一次日志就能看到漏掉的那个节点长什么样，不用重新反编译。
     */
    private fun maybeDump(root: View, tag: String) {
        if (!dumpOn()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = dumpAt[tag] ?: 0L
        if (now - last < 1000L) return
        dumpAt[tag] = now

        val lines = ArrayList<String>(96)
        fun walk(v: View, d: Int) {
            if (d > 14 || lines.size > 96) return
            val r = Rect()
            val shown = runCatching { v.getGlobalVisibleRect(r) }.getOrDefault(false)
            val idn = runCatching {
                if (v.id == View.NO_ID) "-" else v.resources.getResourceName(v.id)
            }.getOrNull() ?: "-"
            val txt = (v as? android.widget.TextView)
                ?.text?.toString()?.replace('\n', ' ')?.take(28) ?: ""
            val mark = if (isAd(v)) "AD " else "   "
            lines.add(
                "${"  ".repeat(d)}$mark${v.javaClass.simpleName} $idn " +
                    "${r.width()}x${r.height()} ${if (shown) "v" else "-"} $txt"
            )
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
        }
        walk(root, 0)
        log("---- dump $tag ${lines.size} nodes ----")
        lines.forEach { log(it) }
    }

    // ---------------------------------------------------------------- 识别

    /**
     * 判断这个 View 是不是广告。
     *
     * 三条独立线索，命中任一即可 —— 分别覆盖三种投放形态：
     *   资源锚点：SDK 自己 inflate 布局（`com.kwad.dy.sdk:id/ksad_container`）；
     *   类名锚点：App 用自家布局包住 SDK 控件，id 变成 App 的包名，但类名还在；
     *   广告位锚点：容器是 App 写的、素材是网络的，前两条都落空，只能看 id 名。
     */
    private fun isAd(v: View): Boolean {
        val res = isAdRes(v)
        val cls = if (res) false else isAdClass(v)
        val slot = if (res || cls) false else isAppAdSlot(v)
        return res || cls || slot
    }

    /**
     * `getResourcePackageName` 对无 id 的 View 和动态 id 会抛
     * NotFoundException / IllegalArgumentException，一律当「不是广告」放行。
     */
    private fun isAdRes(v: View): Boolean {
        val id = v.id
        if (id == View.NO_ID) return false
        val pkg = runCatching { v.resources.getResourcePackageName(id) }.getOrNull() ?: return false
        return AD_RES_PKGS.any { pkg == it || pkg.startsWith("$it.") }
    }

    private fun isAdClass(v: View): Boolean {
        val n = v.javaClass.name.lowercase()
        if (AD_RES_PKGS.any { n.startsWith(it) }) return true
        return AD_CLASS_MARKS.any { n.contains(it) }
    }

    /**
     * 第三条线索：App 自己给广告位起的 id 名。
     *
     * 前两条锚点都失效的情况真的存在 —— 实测目标 App 里：
     *   `id/ad_container`  开屏广告位（RelativeLayout，App 自己的布局）
     *   `id/home_banner`   横幅广告位（Banner 组件）
     *   `id/home_ad`       AdDragFrameLayout
     * 容器是 App 写的，素材是网络的，资源包名和类名都落在 App 自己身上。
     *
     * 按 id 名切词能捞出来：`home_banner` → [home, banner]、`ad_container` → [ad, container]。
     *
     * **只在 [SLOT_RULE_PKGS] 内启用**：别的 App 里 `ad` 可能是 address、adapter 的缩写，
     * 全量放开会误杀正常界面。
     */
    private fun isAppAdSlot(v: View): Boolean {
        if (!slotRuleOn) return false
        val id = v.id
        if (id == View.NO_ID) return false
        val full = runCatching { v.resources.getResourceName(id) }.getOrNull() ?: return false
        val name = full.substringAfter(":id/", full).lowercase()

        val parts = name.split('_', '-')
        val byWord = parts.any { it in AD_ID_WORDS }
        val byExact = name in APP_AD_ID_NAMES
        val bySuffix = name.endsWith("adcontainer") ||
            name.endsWith("adview") || name.endsWith("adslot")
        val hit = byWord || byExact || bySuffix

        // 命中不了但形状可疑的，打几条出来，方便下次补规则
        if (!hit && slotLogs++ < 10 &&
            (name.contains("ban") || name.contains("_ad") || name.startsWith("ad"))
        ) {
            log("slot-miss name=[$name] parts=$parts")
        }
        return hit
    }

    /**
     * 从 v 往上找最近的广告祖先。
     *
     * 限 12 层：广告容器不会嵌得比这更深，再往上就是页面根布局了，
     * 继续找会把整个页面 GONE 掉。
     */
    private fun nearestAdAncestor(v: View): View? {
        var p = v.parent as? View
        var guard = 0
        while (p != null && guard++ < 12) {
            if (isAd(p)) return p
            p = p.parent as? View
        }
        return null
    }

    /**
     * 收集广告子树的根。
     *
     * 命中一个就停、不再往下走：它的子孙必然同属这个广告，重复收集只会拖慢扫描。
     * 深度限 40 层，防止畸形布局把递归打爆。
     */
    private fun collectAdRoots(v: View, out: MutableList<View>, depth: Int) {
        if (depth > 40) return
        if (isAd(v)) {
            out.add(v)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                collectAdRoots(v.getChildAt(i), out, depth + 1)
            }
        }
    }

    // ---------------------------------------------------------------- 隐藏

    /** 隐藏并让出位置。GONE 之后再压高度，应对部分自定义 ViewGroup 忽略 GONE 的情况。 */
    private fun hide(v: View) {
        if (v.visibility == View.GONE && v.height == 0) return
        killed.add(v)
        runCatching {
            v.visibility = View.GONE
            v.layoutParams?.let {
                it.height = 0
                v.layoutParams = it
            }
        }
    }

    /**
     * 已经判定为广告、需要持续压制的 View。
     *
     * 为什么需要这个集合：`setVisibility(GONE)` 只是「改一次状态」，
     * 而广告位组件自己会不断把它改回显示 —— 实测首页 Banner 就是如此：
     * ViewPager2 每次翻页/自动轮播都会走一遍 setVisibility / setLayoutParams，
     * 所以 scrub 日志里每次都 `hid 1`，界面上却始终有一条横幅。
     *
     * 用 WeakHashMap 存，父容器回收后自动释放，不会把 Activity 泄漏住。
     */
    private val killed: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /**
     * 压制「被我们隐藏过的 View」重新显示。
     *
     * 只对 [killed] 里的对象生效，所以对正常界面的开销接近于零 ——
     * 除了 Set.contains，不阻断任何其他 View 的可见性变更。
     */
    private fun hookSetVisibility() {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val m = View::class.java.getDeclaredMethod("setVisibility", Integer.TYPE)
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (killed.isEmpty()) return
                    if (v !in killed) return
                    val want = param.args[0] as? Int ?: return
                    if (want != View.GONE) param.args[0] = View.GONE
                }
            })
            log("hooked View.setVisibility")
        }.onFailure { log("hook setVisibility failed: ${it.message}") }
    }

    private fun areaRatio(v: View, deco: View): Float {
        val r = Rect()
        if (!v.getGlobalVisibleRect(r)) return 0f
        val dr = Rect()
        if (!deco.getGlobalVisibleRect(dr)) return 0f
        val da = dr.width().toLong() * dr.height()
        if (da <= 0) return 0f
        return (r.width().toLong() * r.height()).toFloat() / da
    }

    private fun resName(v: View): String =
        runCatching { v.resources.getResourceName(v.id) }.getOrDefault("?")

    // ---------------------------------------------------------------- 开关

    /** 调试 dump 开关，默认关，不缓存（改完设置立刻生效）。 */
    private fun dumpOn(): Boolean = runCatching {
        val app = AndroidAppHelper.currentApplication() ?: return@runCatching false
        Settings.Secure.getInt(app.contentResolver, SECURE_DUMP, 0) != 0
    }.getOrDefault(false)

    /**
     * 读 Settings.Secure 的 `adcleaner_on`，1s 缓存。
     *
     * 目标进程读不到 /data/system（SELinux category 隔离），只能走 Settings.Secure。
     * 读不到时按「开启」处理——去广告是默认期望，且它不会破坏任何业务逻辑。
     */
    private fun on(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - flagAt < 1000L) return flagOn
        flagAt = now
        flagOn = runCatching {
            val app = AndroidAppHelper.currentApplication() ?: return@runCatching true
            Settings.Secure.getInt(app.contentResolver, SECURE_KEY, 1) != 0
        }.getOrDefault(true)
        return flagOn
    }

    private fun log(s: String) = XposedBridge.log("$TAG $s")
}
