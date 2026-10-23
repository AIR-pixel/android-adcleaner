# AdCleaner — 控件级去广告 Xposed 模块

不碰 dex、不脱壳、不重打包，直接在 View 层把广告认出来并隐藏。
**对爱加密 / 360 / 梆梆 / zxprotect 这类加固包同样有效。**

在 **Xiaomi 11 Lite 5G (renoir) + 澎湃OS 4 移植包 (Android 17)** 上实测跑通，
配合 LSPosed v2.1.1 (7790) + Zygisk Next 1.4.5。

---

## 0. 为什么要这么绕

主流去广告模块（AdClose / FuckApp 那一类）的思路是**拦住广告 SDK 的初始化**——
hook `TTAdSdk.init()`，让它起不来，广告自然没有。这条路遇到加固包就死了。

实测目标 App `com.zjwh.android_wh_physicalfitness`，导出 `base.apk`（330MB）后
`dexdump -f` 读 dex header：

```
file_size       : 71061232      # 单个 dex 71MB
string_ids_size : 1061
class_defs_size : 30            # 但只有 30 个类
data_size       : 50244         # 真实 dex 只有 50KB
```

配合 `lib/arm64-v8a/libijiami_1011.so`、`assets/ijiami_1011.VData`、`libzxprotect.so`
—— **爱加密 + zxprotect 加固**。那 71MB 是运行时才解密的加密载荷。

后果：`handleLoadPackage` 发生在 Application 创建**之前**，那一刻只有壳的 30 个类，
`findClass("com.bytedance.sdk.openadsdk.TTAdSdk")` 必然返回 null，日志里只剩一句
`no ad sdk found`。想改包就得先脱壳（dump 运行时 dex → 修复 → 重打包 → 过签名校验），
而且**每次 App 更新都要重做一遍**。

### 换思路：不看类，看 View

广告最终一定要变成屏幕上的 View。而 View 上有三样东西**加固壳改不了**：

| 线索 | 怎么拿 | 为什么壳改不了 |
|---|---|---|
| 资源 id 属于哪个包 | `Resources.getResourcePackageName(id)` | SDK 资源编译在 `resources.arsc`，壳只处理 dex |
| 实现类的名字 | `v.javaClass.name` | 运行时拿到的就是真名；混淆只混淆 App 自己的代码 |
| 广告位 id 的名字 | `Resources.getResourceName(id)` 切词 | UI 开发给广告位起名不会回避 `ad` / `banner` |

三条线索互不依赖，命中任一即可。**整套逻辑不碰 dex，所以对加固包天然有效。**

> 顺带解释一个现象：同一台机器上，定位伪造的 hook 一直在生效，广告 hook 全部失败。
> 原因就在这——`Location` / `GnssStatus` / `WifiManager` 是 boot classpath 上的系统类，
> 任何时刻都可见；广告 SDK 的类不是。

---

## 1. 三条线索分别覆盖什么

| 线索 | 覆盖的投放形态 | 实测例子 |
|---|---|---|
| 资源包名 | SDK 自己 inflate 的布局 | `com.kwad.dy.sdk:id/ksad_container` |
| 类名 | App 用自家布局**包住** SDK 控件（id 变成 App 的包名） | `com.bytedance.sdk.openadsdk...TTNativeAdView` |
| 广告位 id | 容器是 App 写的、素材是网络的，前两条都落空 | `com.zjwh...:id/home_banner` |

实测这台机器上落到的完整清单：

```
com.kwad.dy.sdk:id/ksad_container        快手联盟插屏（资源包名锚点）
com.kwad.dy.sdk:id/ksad_tk_view          「扭一扭」摇一摇视图
com.zjwh...:id/ad_container              开屏/插屏容器（id 锚点）
com.zjwh...:id/home_banner               首页横幅，会员卡推广（id 锚点）
com.zjwh...:id/sport_banner              运动页横幅
com.zjwh...:id/fl_native                 信息流原生广告卡
AdDragFrameLayout ...:id/home_ad         可拖拽广告位
```

**第三条线索只在白名单包名内启用**（`SLOT_RULE_PKGS`）。别的 App 里 `ad` 可能是
address、adapter 的缩写，全量放开会误杀正常界面。用之前把 `SLOT_RULE_PKGS`
换成你自己的目标 App。

---

## 2. 四层执行路径

认出来只是第一步，难点在「什么时候认」。

| 层 | 入口 | 处理 |
|---|---|---|
| Dialog | `Dialog.show` | 广告面积 ≥ 50% → `dismiss()`；否则只隐藏广告子树 |
| PopupWindow | `showAtLocation` / `showAsDropDown` | 内容根即广告 → `dismiss()` |
| 窗口 | `WindowManagerImpl.addView` | 所有窗口的唯一入口，延时多次扫 |
| View 树 | `ViewGroup.addView` | 命中广告祖先 → `GONE` + 高度归零 |
| 兜底 | `WindowManagerGlobal.mViews` 轮询（1.2s） | 全局扫所有窗口 + 必要时移除窗口 |

第 5 层（轮询）是**必须的**，不是保险：前四层都依赖「某个事件发生」，
而实测这个 App 的广告**长期复用同一个窗口、只换里面的内容** ——
事件在第一次之后就再也不触发了，于是广告换个素材又冒出来。
`WindowManagerGlobal.mViews` 是所有窗口 root view 的全局列表，直接遍历它绕开一切时机问题。

1.2s 一次的代价可以忽略：目标界面不到 100 个节点，单次遍历在微秒级。
递归深度另有 40 层上限，不会把主线程拖死。

### 为什么只 `GONE` 不 `removeView`

移除会打断 SDK 的 View 引用计数，广告 SDK 普遍不做空判断，remove 之后容易
NPE 崩在它自己的回调里。`GONE` + 高度 0 既看不见也不占位，SDK 侧完全无感。

唯一例外是「整窗广告」：那类窗口带 `FLAG_DIM_BEHIND`，dim 是 WMS 画的（见下），
只 GONE 内容不够，必须整窗移除。

---

## 3. 踩过的三个坑

### 坑一：`handleLoadPackage` 在同一进程里会回调多次

目标 App 一加载 WebView，LSPosed 会额外回调一次 `com.google.android.webview`。
最初写成「记录当前包名、再和目标包名比较」，那次回调把包名覆盖成 webview，
之后 App 主进程里所有判定都因为「包名不匹配」而**静默返回 false**——
表现就是规则完全失效，**而且日志里一点错误都没有**。

改成单向布尔开关（一次命中即永久开启）才对。这个 bug 花了最长时间，因为它没有报错。

### 坑二：`setVisibility(GONE)` 会被广告组件自己改回来

首页 Banner 的 ViewPager2 每次翻页/自动轮播都会走一遍 `setVisibility` / `setLayoutParams`，
所以 scrub 日志里每次都 `hid 1`，界面上横幅却一直在。

解法：记住被隐藏的 View（`WeakHashMap`，父容器回收后自动释放），
再 hook `View.setVisibility` 把后续的「显示」请求压回 `GONE`。
只对已判定的对象生效，对正常界面的开销接近于零。

### 坑三：内容隐藏了，屏幕还是灰的

广告窗口带 `FLAG_DIM_BEHIND`，那层半透明黑由 **WindowManagerService** 绘制，
**不属于任何 View** —— GONE 内容影响不到它，实测就是这样。

两条修法都实现了：

1. `dimOff()`：改窗口属性把 `dimAmount` 清零、去掉 dim flag，再 `updateViewLayout`。比移除安全。
2. `removeViewImmediate()`：直接摘掉窗口。仅限**非 Activity 基础窗口**
   （`type != TYPE_BASE_APPLICATION`），误删主窗口会让用户直接黑屏。

另外，面积判据在整窗广告上算不出来：这些容器在 `addView` 阶段就被 hide 了，
等 sweeper 扫到时 `width/height` 已经是 0，`cover` 恒为 0.0。
所以全屏广告位改用「这窗口里装的是不是已知的全屏广告位 id」判定。

---

## 4. 编译与安装

```bash
cd android-adcleaner
gradle assembleDebug -Dorg.gradle.java.home="<你的 JDK 17+ 路径>"
```

- JDK 17+（Gradle 8.11 / AGP 8.7 要求），`gradle.properties` 里注释了
  `org.gradle.java.home`，按本机路径改或命令行传
- Xposed API 是 `compileOnly`，运行期由框架注入
- 装不上时（MIUI 拦 `adb install`）：`adb push` 到 `/data/local/tmp` 再
  `su -c 'pm install -r -d /data/local/tmp/xxx.apk'`

**激活**：LSPosed 里启用模块 → 勾选目标 App 作用域 → **force-stop 目标 App**（不用重启机器）。
不需要勾「系统框架」。

装完第一次打开时看日志确认装上了：

```bash
adb logcat | grep "AdCleaner"
# [AdCleaner] armed: com.xxx slotRule=true
# [AdCleaner] hooked Dialog.show / hooked PopupWindow / hooked WindowManagerImpl.addView
```

---

## 5. 开关与调试

```bash
# 总开关（默认开）
adb shell su -c 'settings put secure adcleaner_on 0'    # 关
adb shell su -c 'settings put secure adcleaner_on 1'    # 开

# 调试：把整棵 View 树打到日志，每行一个节点，命中广告的标 AD
adb shell su -c 'settings put secure adcleaner_dump 1'
adb logcat | grep "AdCleaner"
adb shell su -c 'settings put secure adcleaner_dump 0'
```

**dump 开关是这个模块最重要的自愈能力。** 广告 SDK 会换布局、换资源前缀，
硬编码规则迟早漏；漏了直接开 dump 抓一次日志，就能看到漏掉的那个节点长什么样
（类名 + id + 尺寸），把 id 名补进 `APP_AD_ID_NAMES` 即可，**不用重新反编译**。

dump 输出长这样：

```
---- dump HomeActivity 63 nodes ----
   FrameLayout com.zjwh...:id/content 1080x2160 v
     LinearLayout com.zjwh...:id/home_banner 1080x180 v
AD    RelativeLayout com.kwad.dy.sdk:id/ksad_container 1080x1920 v
AD      KSAdWebView com.kwad.dy.sdk:id/ksad_web 1080x1800 v
```

另外 `isAppAdSlot` 对「形状可疑但没命中」的 id 会打 `slot-miss name=[...] parts=[...]`，
方便判断该不该补规则（限 10 条，避免刷爆日志）。

---

## 6. 实测结果

目标 App `com.zjwh.android_wh_physicalfitness`（爱加密 + zxprotect 加固）：

- 千问插屏、小米应用商店插屏、「扭动手机」摇一摇广告、快手极速版插屏 —— 全部消失
- 首页会员卡推广横幅（`home_banner`）—— 消失
- 连开 3 轮无崩溃、无 ANR
- 与定位伪造模块并存时互不影响

**唯一残留**：偶尔某一轮会有一层极淡的灰（dim 处理晚于截图），
再等 1.2s 的下一轮 sweeper 自动清掉。

---

## 7. 与「上帝模式」（GodMode）的关系

GodMode（包名 `com.viewblocker.jrsen`，作者 jrsen）是同类思路的先行者：
它让用户手动在界面上框选要屏蔽的控件，按 View 的 id / 类名生成屏蔽规则。

差别：

| | GodMode | 本模块 |
|---|---|---|
| 规则来源 | 用户手动框选 | 自动按资源包名 / 类名 / id 名识别 |
| 新增广告 | 需要用户再框一次 | 三条线索自动命中 |
| 加固包 | 同样不依赖 dex，可用 | 同样可用 |
| 维护状态 | **原仓库已删除**，只剩 2023 年停更的 fork，无 release | 本文代码可直接编译 |

因为 GodMode 原仓库已下线、网上流传的都是来路不明的二手 APK，所以自己实现了一份，
并把「怎么认」这件事写成可维护的规则而不是一次性配置。

---

## 8. 已知边界

- **只处理 View 层**。SDK 的网络请求、素材下载不受影响——`AdDns` 那层只是顺带省点流量，
  挡不住界面广告（实测挡了 DNS 但广告还在）。
- **整窗移除有风险边界**。Activity 基础窗口一律不动，宁可留着广告窗口也不能让界面黑掉。
- **id 名切词是启发式**，不同 App 的命名习惯差异很大。换目标 App 时先用 dump 抓一次
  View 树，再决定补哪些 id。
- **不屏蔽加速度计**。目标 App 靠加速度计计步，屏蔽了步数就归零；「扭一扭」广告只要
  Dialog 被 dismiss 就不存在了，不需要动传感器。别的应用若靠摇一摇触发，需要另加处理。

---

## 9. 合规边界

自用去广告属于对自己设备上软件的修改。把去广告后的 App 重新分发，
或用于绕过付费/激励内容，责任在操作者。
