# 验证记录

本文件记录已经**实测过**的内容，以及**尚未验证**的部分。区分这两者是这份记录存在的意义。

环境：

| 项 | 值 |
|---|---|
| OpenCode | 2.0.18（macOS，`opencode-cli serve --service`） |
| 宿主 | my-mac，tailnet `100.101.102.103`，MagicDNS `my-mac.tailnet.ts.net` |
| Android | 模拟器 Pixel_4a，Android 17 / SDK 37（`google_apis`，arm64-v8a） |
| 构建 | AGP 9.2.1（内置 Kotlin），compileSdk 37，minSdk 31，targetSdk 37 |

---

## 1. 构建

```
./gradlew assembleDebug         BUILD SUCCESSFUL
./gradlew testDebugUnitTest     BUILD SUCCESSFUL
```

生成的 APK：`app/build/outputs/apk/debug/app-debug.apk`（14 MB）
`res/raw/` 下 10 个 Edge TTS 语音（5 句 × 男女声）均正确打包。

## 2. 清单（静态验证）

`aapt2 dump xmltree` 确认：

- `NotifyService` 的 `android:foregroundServiceType=0x40000000`，即
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
- `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` 存在
- `WatchdogReceiver`、`BootReceiver` 均已注册
- 权限：`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`VIBRATE`、`WAKE_LOCK`、
  `RECEIVE_BOOT_COMPLETED`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`USE_EXACT_ALARM`、
  `SCHEDULE_EXACT_ALARM`、`ACCESS_NOTIFICATION_POLICY`

保活升级（本轮）新增，同样以 `aapt2 dump xmltree` 确认：

- `MainActivity`：`excludeFromRecents=true`（`0x01010017`）、`launchMode=2`（`singleTask`）
- `GuardAccessibilityService`：`permission=android.permission.BIND_ACCESSIBILITY_SERVICE`，
  intent-filter `android.accessibilityservice.AccessibilityService`，
  meta-data `android.accessibilityservice` → `@xml/accessibility_service_config`
- `GuardJobService`：`permission=android.permission.BIND_JOB_SERVICE`
- `<queries>` 含 `com.vivo.permissionmanager`、`com.iqoo.secure`、`com.android.settings`

## 3. 协议（对真实 OpenCode 服务实测）

| 项 | 结果 |
|---|---|
| 认证 | HTTP Basic，用户名 `opencode`，密码取 `service.json`。无凭据 → `401`；Bearer → `401`；Basic → `200` |
| `GET /api/info` | `{"version":"2.0.18","pid":46619,"urls":["http://127.0.0.1:49374"]}` |
| 监听地址 | **只有 `127.0.0.1`，且重启换端口** → 必须有桥接 |
| SSE `GET /api/event` | 可用，事件量极大（每个 `session.reasoning.delta` / `text.delta` 都是一条），必须强过滤 |
| 心跳 | `: heartbeat`，**实测每 15 秒一次**（14 次连续采样，间隔恒定） |
| 服务端推送字节 | 客户端 `readTimeout=60s`（连丢 4 次心跳判死） |
| `GET /api/session/active` | `{"data":{"ses_...":{"type":"running"}}}` —— 全局，非单 location |
| `GET /api/session/{id}` | `{"data":{...,"outcome":"failed","time":{...,"idle":...}}}`（**有 `data` 包裹**） |
| `GET /api/permission/request` | `{"location":{"directory":"$HOME"},"data":[]}` —— **单 location 作用域** |
| `GET /api/form` | 同上 |

### 事件序列实测（一次失败回合）

用 API 建会话 + 发 prompt，捕获到该会话的全部终止事件：

```
session.execution.started   {"sessionID":"ses_..."}
session.step.failed         {"sessionID":"...","error":{"type":"provider.quota",
                             "message":"Upstream request failed: Insufficient account funds","status":402}}
session.execution.failed    {"sessionID":"...","error":{"type":"provider.quota", ...}}
```

结论与影响：

- 失败路径确认映射到 `AlertKind.ERROR`。
- **该回合没有发出 `session.idle`**。因此 `session.idle` 与 `session.execution.succeeded` 都映射到
  `TASK_DONE`，并按 sessionID 去重（同一轮只提醒一次），无论哪个先到。
- 顺带发现：通过 API 新建的会话，其默认模型链路报 `Insufficient account funds (402)`。

### 桥接

`tools/bridge/opencode-bridge.py`：

- 绑定 Tailscale 地址（自动探测），监听固定 `4096`
- 每 15 秒重新解析 `opencode-cli api get /api/info`，跟随随机端口
- 实测：无凭据 `401`、带凭据 `200`、SSE 正常穿透
- 实测：服务端把 `127.0.0.1:49374` 暴露到 `100.101.102.103:4096`

## 4. 界面（三页，已逐页截图核对）

界面拆成 **状态 / 选项 / 配置** 三页，Material 3 卡片式布局。截图见
[`docs/screenshots/`](screenshots/)：

| 页面 | 截图 | 内容 |
|---|---|---|
| 状态 | `01-status.png` | 守护开关 + 运行状态圆点、连接通道（含本机 tailnet 地址）、权限自检（整行可点）、运行诊断、发送测试提醒 / 停止响铃 |
| 选项 | `02-options.png` | 提醒事件、提示方式、语音预览、后台策略 |
| 配置 | `03-config.png` | 两个 tailnet IP、自动检测本机地址、端口、凭据、检测结果 |
| 语音预览 | `04-voice-preview.png` | 5 个场景各一行 ▶ 试听 |
| 音色下拉 | `05-voice-menu.png` | 晓伊（默认）/ 晓晓 / 云希 / 云扬 |
| 键盘 | `06-keyboard.png` | 键盘弹出时底栏不动 |

### 4a. 界面改版（本轮，已逐页截图核对）

对三页做了一次统一的视觉与排版重做：

- **页头 / 底栏**：改成同色的 tonal 应用栏——页头与底栏都用 `colorSurfaceContainer`，中间内容区为
  `colorSurfaceContainerLowest`，形成上下两条同色带；用色调差代替原来的 1dp 分隔线。
- **品牌徽标**：页头左侧 40dp 圆角方块改为实心 `colorPrimary` + `colorOnPrimary` 图标，
  比原来的浅色 `colorPrimaryContainer` 更醒目。
- **页头文字**：应用名改为弱化的 `labelMedium`（`colorOnSurfaceVariant`）上标签，页面名用
  `titleLarge` 加粗主标题，字号比之前收敛，与徽标高度更平衡。
- **卡片**：改为无阴影的填充式面板（`colorSurfaceContainerLow`），圆角 20dp。
- **权限自检**：状态由裸文字改成带底色的胶囊（`paintState` / `paintAction` 运行时按状态着色），
  所有可点行统一最小 48dp 触控高度。
- **状态页**：「本机 tailnet 地址」从权限自检移入「连接通道」；「运行诊断」改成左右对齐的
  标签 / 数值行（`tvDiag*` 现在只承载数值，标签写死在布局里）。
- **选项页**：「选择提示音」「试听提醒」并排一行；「立即停止响铃」改成右对齐文字按钮。
- **配置页**：「检测」按钮原先与输入框并排、与浮起的标签对不齐，改为状态文字下方单独的
  「自动检测本机地址」。
- **设计 token**：新增 `values/dimens.xml` 统一间距；自定义浅 / 深两套兜底配色
  （Material You 动态取色不可用时生效），并补齐深色下的绿 / 红状态指示色。
- 键盘 `adjustNothing` + `pageContainer` 底部内边距的保活行为**未改动**，用 `06-keyboard.png` 复核仍然正确。

浅色、深色两种模式均已上机截图确认；`./gradlew assembleDebug` BUILD SUCCESSFUL。

清除数据后的首次启动实测渲染正确：状态「已停止」、通知权限「已就绪 ✓」、精确闹钟
「已就绪 ✓」、电池白名单「待处理 ✗」、本机 tailnet 地址提示「未检测到（Tailscale 未连接？）」
——与模拟器没有 Tailscale 的事实一致。

**本轮发现并修复的缺陷**

1. `enableEdgeToEdge()` 下 `AppBarLayout` 并不会自动应用状态栏 inset，标题会和系统时钟重叠。
   已在 `MainActivity.applyWindowInsets()` 里显式给 AppBarLayout 加顶部内边距。
2. 给底栏加 `android:minHeight="0dp"` 后 `wrap_content` 测量失控：`BottomNavigationView` 把
   自己量成了整屏高度，把 `pageContainer` 挤成 **0 高**，页面整体空白。
   实测证据（`uiautomator`）：`pageContainer boundsInParent: Rect(0,0 - 1080,0)`，
   `bottomNav [0,312][1080,2340]`。改为**固定高度**后恢复正常。

## 4b. 底栏高度与键盘行为（已实测）

| 项 | 键盘收起 | 键盘弹出（`ime.bottom=833`） |
|---|---|---|
| `pageContainer` | `[0,312][1080,2120]` | `[0,312][1080,2120]` |
| `bottomNav` | `[0,2120][1080,2340]` | `[0,2120][1080,2340]` |

- 底栏高度：内容 **56dp** + 手势条 inset（本机 66px），比默认的 80dp 矮了约 30%。
  `wrap_content` 会误测，所以高度写死 56dp，再由代码把 `bars.bottom` 加回去。
- 键盘：`windowSoftInputMode="adjustNothing"`，窗口不因键盘改变大小，**底栏底边固定在屏幕底部不动**；
  同时把 `ime.bottom - bars.bottom` 作为 `pageContainer` 的底部内边距（本机 767px），
  所以输入框仍能滚到键盘上方，不会被挡。

## 5. 前台服务与通知（Android 17 / SDK 37）

`adb install` → Success。启动应用（`enabled=true` 时自愈拉起守护）：

```
ServiceRecord{... app.opencodesentry/.NotifyService}
  isForeground=true foregroundId=1001 types=0x40000000
  startRequested=true stopIfKilled=false
```

- **前台服务以 `specialUse` 正常前台化** ✅
- 常驻通知存在，标题 `OpenCode 通知守护`，带一个「停止」动作 ✅
- 提醒渠道：

```
NotificationChannel{mId='opencode_alerts', mImportance=4, mSound=null,
                    mVibrationEnabled=false, mShowBadge=true, mLights=true, mBypassDnd=false}
```

  `mImportance=4` = `IMPORTANCE_HIGH` ✅，`mSound=null` / `mVibrationEnabled=false` 与设计一致
  （声音与震动由应用自己控制，渠道只负责重要性）。`mBypassDnd=false` 是预期的——本次没有授予
  勿扰访问权限。

- 应用日志：

```
I OpenCodeNotify: onStartCommand action=...action.START startId=1
I OpenCodeNotify: watchdog: setExactAndAllowWhileIdle in 5m
W OpenCodeNotify: stream failure: failed to connect to /10.0.2.2 (port 4096) ... after 10000ms
```

  中间这条证明 `canScheduleExactAlarms()==true`——侧载应用**自动获得了 `USE_EXACT_ALARM`**，
  不需要引导用户去设置页开精确闹钟。最后一条是测试脚本自身的网络路径没打通（桥接只绑在宿主回环，
  模拟器 `10.0.2.2` 别名走不通），**不是应用缺陷**；它同时证明了失败路径会记录日志并触发重连调度。

当时的临时方案是改用 `adb reverse tcp:4096 tcp:4096`，从模拟器内 `toybox nc` 验证已连通（`NC_OK`），
但随后按要求停止了设备验证。

## 6. 桥接常驻（launchd）——本轮发现并修复的第二个缺陷

首次把桥接装成 launchd 服务时它**直接崩了**：

```
UnicodeEncodeError: 'idna' codec can't encode characters in position 0-69: label too long
```

根因：`/Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4` 在**没有 GUI 会话**的环境
（launchd 就是）下，不返回 IP，而是把一条错误信息打到 **stdout 并且退出码仍然是 0**：

```
$ env -i /Applications/Tailscale.app/Contents/MacOS/Tailscale ip -4
The Tailscale GUI failed to start: The operation couldn't be completed. (Tailscale.CLIError error 3.)
rc=0
```

桥接脚本原先直接取 stdout 第一行当监听地址，于是把这串 70 多个字符的中文错误信息丢给
`asyncio.start_server(host=...)` → IDNA 编码失败。

修复：

1. **改为直接读网卡**（`/sbin/ifconfig` 里 100.64.0.0/10 的 IPv4），和安卓端同一个判定逻辑，
   完全不依赖 GUI CLI；`ifconfig` 用绝对路径，所以在 PATH 为空的极端环境下也能用。
2. 保留 CLI 作为兜底，但**用网段校验输出**，非 IP 一律丢弃并打印告警。

修复后验证：

```
$ env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin python3 -c "..."    # 模拟 launchd 环境
interface_tailnet_ip -> 100.101.102.103
[bridge] ignoring unusable output from Tailscale: The Tailscale GUI failed to start: ...
tailscale_ip         -> 100.101.102.103

$ env -i python3 -c "..."                                       # PATH 也是空
interface_tailnet_ip -> 100.101.102.103
```

launchd 服务实测：

| 项 | 结果 |
|---|---|
| 启动 | `state = running`，监听 `100.101.102.103:4096` ✅ |
| KeepAlive | `kill -9` 掉 pid 10481 后自动拉起为 pid 10694 ✅ |
| 经 tailnet 地址访问 | 无凭据 `401`，带凭据 `200` ✅ |
| 开机自启 | `~/Library/LaunchAgents/dev.opencode.bridge.plist` ✅ |

## 7. 尚未验证（需要在真机（vivo / iQOO / OriginOS）上确认）

- **熄屏 / Doze 下的真实行为**：电池优化白名单 + `setExactAndAllowWhileIdle` 的组合在深度 Doze 下的
  实际唤醒频率。
- **OriginOS 的后台治理**：自启动、后台高耗电、睡眠待机优化等是否真的放行前台服务（这是最大的变量）。
- **震动、铃声、语音的实际播放效果与音量**（模拟器无真实音频通路）。
- **锁屏展示**：`dumpsys` 显示渠道的 `mLockscreenVisibility=-1000`（默认「不覆盖」），与代码里设置的
  `VISIBILITY_PUBLIC` 不符，原因未定位；通知自身的 `setVisibility(PUBLIC)` 是有效的，但需要在真机上
  确认锁屏上是否正常显示内容。**这一条当作已知待办。**
- **权限请求 / 提问事件（`permission.asked`、`form.created`）**：没有构造出真实场景，映射是按
  OpenCode 的事件名与 `/api/permission/request`、`/api/form` 的 schema 写的，未经端到端触发。
- **开机自启**、**进程被杀后 `START_STICKY` 恢复**、**看门狗补齐漏报**：逻辑已实现，未实测。

### 7b. 本轮保活升级：已静态验证 vs 待真机验证

设计依据是 Android 官方文档
[Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
的豁免清单。关键发现：**无障碍服务不在豁免清单里**，而「用户关闭了电池优化」和「应用触发了精确闹钟」
**在**清单里。因此 `KeepAlive.ensure()` 的直接启动可能被拒，必须有 `retryViaAlarm` 这条回头路。

**已静态验证（构建产物层面）**

| 项 | 结果 |
|---|---|
| `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew testDebugUnitTest` | BUILD SUCCESSFUL |
| `excludeFromRecents` / `launchMode=singleTask` | 合并清单中确认存在 |
| `GuardAccessibilityService` 声明与 meta-data | 合并清单中确认存在 |
| `GuardJobService` 声明 | 合并清单中确认存在 |
| `<queries>` 三个厂商包 | 合并清单中确认存在 |

**逻辑已实现但未在真机上跑过**

- 无障碍守护被 ROM 杀死后，`system_server` 是否真的会重建进程并回调 `onServiceConnected`
  —— 这是整套方案的地基，也是 OriginOS 上最大的未知数。
- `excludeFromRecents` 之后，OriginOS 的「一键清理 / X」是否真的不再列到本应用。
- 持久化 Job 在 OriginOS 上是否会被 ROM 一并取消。
- 三个诊断计数在真实「杀掉 → 复活」循环里的行为是否符合预期。
- 无障碍开启流程：Android 13+ 的「允许受限设置」在 OriginOS 上的实际入口位置。

验证方法：装上之后看状态页「运行诊断」——正常稳定后**第几次启动**这个数字应当不再增长；
如果它在涨，说明还在被杀，把无障碍守护和自启动确认一遍。

## 8. 复现方式

```sh
# Mac 侧
python3 tools/bridge/opencode-bridge.py                # 绑定 tailnet IP，固定 4096

# 设备侧（模拟器需先做端口反向）
adb reverse tcp:4096 tcp:4096
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s OpenCodeNotify
./tools/mac/e2e-emulator.sh                          # 一键端到端并打印全部证据
```

`tools/mac/probe_events.sh` 会建一个 `event-probe` 会话、发一句 prompt、
捕获并打印该会话的终止事件，然后删除会话——用来复核事件映射是否仍然成立。

## 9. 控制台 + 扫码配对（本轮，已实测）

应用从三页变为四页：**主页（官方 OpenCode web 控制台）/ 状态 / 选项 / 配置**。

### 配对协议（对真实服务实测）

| 项 | 结果 |
|---|---|
| `POST /api/pair`（Basic 认证） | `{"code":"…","expires_in":300}`，一次性、5 分钟 |
| `GET /auth/connect/{code}`（无需凭据） | `Accept: application/json` → `{"token":"…"}`（54 位，前缀为 30 天后的 epoch 秒） |
| 浏览器路径 | `Accept: text/html` → `302 /` + `Set-Cookie: opencode_session_<外部端口>` |
| Cookie 值当 Basic 密码 | ✅ `GET /api/info` → `200`（一个扫码同时配好控制台与提醒服务） |
| 服务根路径 `/` | 官方 web 客户端（PWA），经桥接从 tailnet 访问同样 200 |

### 应用行为

- **扫码**：`ScanActivity`（CameraX + 打包版 ML Kit，不依赖 Play Services、离线可用）只接受
  `/auth/connect/…` 链接；扫到别的二维码会提示而不是半配置。
- **手动填密码也能进控制台**：打开主页时应用自己调 `POST /api/pair` 换一次性码，
  再让 WebView 兑换——所以不扫码也能用，且每次打开会自动续 30 天凭据。
- **凭据回填**：WebView 兑换后从 CookieManager 取出 `opencode_session_*`，存为提醒服务的
  Basic 密码；token 前缀解析出到期日，配置页显示「扫码凭据：有效至 …」。

### 证据（模拟器 Pixel 9a，Android 17 / SDK 37，OpenCode 2.0.22）

- `./gradlew assembleDebug` / `testDebugUnitTest` BUILD SUCCESSFUL；APK 43 MB（ML Kit 模型 +4 MB）
- 合并清单含 `android.permission.CAMERA`；`ScanActivity` 未导出、竖屏、Scanner 主题
- 种子密码启动 → 主页自动完成 pair → **官方 web 客户端完整渲染**（会话列表、项目分组），
  截图 [`screenshots/07-console-home.png`](screenshots/07-console-home.png)
- 配置页显示「扫码凭据：有效至 2026-11-01」，截图
  [`screenshots/08-config-pairing.png`](screenshots/08-config-pairing.png)
- 四页签逐页截图无崩溃；logcat 无 `FATAL EXCEPTION`；SSE `stream open`、守护运行正常
- 控制台加载走 `adb reverse` 回环桥接（模拟器无 Tailscale），与真机只差网络路径

### 尚未验证（真机）

- **相机扫码**：模拟器无法构造真实二维码入镜，`ScanActivity` 的运行时流程（权限弹窗、
  CameraX 取景、ML Kit 识别）需要真机确认。
- **窄屏 web 交互**：官方 web UI 在手机宽度下的可用性（列表、输入框、权限弹窗）需真机体验。
- 扫码凭据 30 天到期后的重新配对流程（应用已给出到期提示）。

## 10. UI 重构：墨色平面设计（本轮，已实测）

配色整体更换为固定的「墨色」体系，并去掉卡片式布局，改为紧凑的平面分区。

### 设计规则

| 项 | 旧 | 新 |
|---|---|---|
| 配色 | Material You 动态取色（壁纸决定）+ 靛蓝兜底 | **固定墨色**：近黑主色 + 中性灰；动态取色停用，任何壁纸上观感一致 |
| 彩色 | 主色 + 状态色 | **只有状态色**（绿 / 红 / 灰）带彩色，其余全部单色 |
| 布局 | 圆角 20dp 卡片 + 描边，卡片间距 12dp | **无卡片**：分区标签 + 1dp 细线分隔 |
| 行高 | 最小 48dp / 内边距 10dp | 最小 44dp / 内边距 8dp |
| 按钮 | M3 全圆角胶囊 | 10dp 圆角（`ShapeAppearance.Opencode.Button`，主题级默认 + Outlined/Text 变体） |
| 字号 | 行标题 bodyLarge、页头 titleLarge | 行标题 bodyMedium、页头 titleMedium |
| 状态胶囊 | 999dp 全圆、最小 68dp | 6dp 圆角、最小 56dp |
| 徽标 | 40dp 带高光渐变 | 32dp 纯色 |

### 本轮发现并修复的缺陷

1. 系统切换深色模式（或任何配置变更）触发 Activity 重建后，底栏页签被重置回「主页」。
   已用 `onSaveInstanceState` 保存当前页索引并在重建后恢复；实测：在「配置」页切深色，
   重建后仍停在「配置」。
2. 状态页文字重复：行标签已是「最近提醒」，值里又带「最近提醒：」前缀；已去掉。
   `TailnetIp.describe()` 同理去掉了与标签重复的前缀。
3. 「扫码配对」原为 tonal 按钮，浅色下与背景几乎同色不可见；改为描边按钮。

### 证据

浅色 / 深色两套、四页 + 滚动位置逐屏截图核对（模拟器 Pixel 9a，Android 17）：

- `screenshots/status-overview.png`、`status-diagnostics.png`
- `screenshots/options-events.png`、`options-voice.png`
- `screenshots/config-tailscale.png`、`config-credentials.png`
- `screenshots/07-console-home.png`（深色模式下官方 web 客户端同样跟随系统切换）
- `screenshots/ui-overview.png`：四页 × 浅色顶/底 × 深色顶的总览拼图
- 页签持久化：配置页 → 切深色 → 重建后仍在配置页
- `./gradlew assembleDebug` / `testDebugUnitTest` BUILD SUCCESSFUL

### 10b. 输入框重做（本轮追加）

- 从 M3 描边框（4dp 方角、浮动标签在描边上切出「缺口」）改为**无边框填充式**：
  12dp 圆角、`input_background` 填充、描边宽度 0；聚焦反馈 = 浮动标签转为墨色。
- 覆盖全部 8 个输入框（配置页 5 个 + 选项页 3 个，含「音色」下拉），
  下拉行为通过布局属性 `app:endIconMode="dropdown_menu"` 声明。
- 踩坑记录：`endIconMode` 的枚举值是 `dropdown_menu`（不是 `dropdown`），且不能在
  style 资源里以原始字符串赋值（aapt2 报 `expected enum but got (raw string)`）。
- 排查记录：深色截图中「扫码配对」按钮一度看似空白；经 uiautomator 文本属性 +
  像素级裁剪比对，确认是**键盘弹出时按钮被输入法裁掉下半部分的截图假象**，
  按钮本身在浅色（近黑文字 1996 px）与深色（近白文字 2094 px）下均正常。
- 浅色 / 深色、静置 / 聚焦、下拉框逐屏截图核对；`docs/screenshots/` 页面图已全部刷新。

## 11. Android Studio 兼容性（AGP 版本回退）

**现象**：Android Studio 2025.3（`AI-253.32098`）打开项目报
*"The project is using an incompatible version (AGP 9.4.1) of the Android Gradle plugin.
Latest supported version is AGP 9.2.1"*，Gradle Sync 被拒。

**原因**：项目初始提交（模板生成）就使用 AGP 9.4.1；该 Studio 版本内置的兼容表上限是 9.2.1。

**处理**：`gradle/libs.versions.toml` 中 `agp = "9.4.1"` → `"9.2.1"`。其余不动：

| 项 | 结果 |
|---|---|
| `optimization { enable = false }`（release） | AGP 9.2.1 支持，构建通过 |
| `compileSdk { version = release(37) }` 新 DSL | AGP 9.2.1 支持 |
| Gradle wrapper | 保持 9.6.0，与 AGP 9.2.1 组合构建通过 |
| `./gradlew buildEnvironment` | 解析为 `com.android.tools.build:gradle:9.2.1` |
| `assembleDebug` + `testDebugUnitTest` | BUILD SUCCESSFUL |
| CI（`ubuntu-latest` + JDK 21 + wrapper） | 不受影响 |

备选方案（未采用）：升级 Android Studio 到支持 AGP 9.4.1 的版本；届时可用 AGP Upgrade
Assistant 再把版本提上去。

## 12. 主页铺满与「音色」下拉重叠（本轮修复，已实测）

### 12a. 控制台 WebView 上下留白

**现象**：主页（官方 web 控制台）在会话列表与聊天视图上下都有明显空白——
标题栏上方约 55px、输入框下方约 24px（CSS 像素），空间利用率差。

**定位**：官方 web 端的 viewport 含 `viewport-fit=cover`，页面按
`env(safe-area-inset-*)` 留白；而 WebView 被 App 顶栏/底栏夹在中间，系统栏
inset 本应由外层布局消化，WebView 却仍把状态栏/导航栏高度报为安全区
（实测 `safe-area-inset-top` = 55px、`bottom` = 24px）。

**修复**：对 WebView 消费窗口 inset
（`ViewCompat.setOnApplyWindowInsetsListener(web) { _, _ -> CONSUMED }`），
安全区归零，官方页面不再重复留白。

**证据**（模拟器 Pixel 9a，Android 17，WebView 153）：
- 修复前 CDP 探针：`safeTop=55px safeBottom=24px`，`header` 计算
  `padding-top:55px`；修复后：`safeTop=0px safeBottom=0px`，
  `header` `padding-top:0px`。
- 截图：`docs/screenshots/07-console-home.png`（列表页）与聊天视图均上下贴满；
  键盘弹出时输入框仍正常上移（IME padding 不受影响）。
- 定位工具：debug 构建启用 WebView 调试（`setWebContentsDebuggingEnabled`），
  经 `adb forward` + CDP `Runtime.evaluate` 读取页面盒模型；诊断探针不随
  release 构建发布。

### 12b. 「音色」下拉标签与值重叠

**现象**：选项页「音色（切换即试听）」的浮动标签与选中值叠在一起，
输入区高度只有 48dp（正常 56dp）。

**定位**：`MaterialAutoCompleteTextView` 放在普通
`Widget.Material3.TextInputLayout.FilledBox` 里时，TextInputLayout 的
`materialThemeOverlay` 指向 TextInputEditText 的子样式，下拉子控件拿不到
自己的子样式（`autoCompleteTextViewStyle`），标签不收缩。

**修复**：新增 `Widget.Opencode.TextInput.Dropdown`（继承普通样式，仅把
`materialThemeOverlay` 换成
`ThemeOverlay.Material3.AutoCompleteTextView.FilledBox` 并声明
`endIconMode=dropdown_menu`），「音色」框改用它。这也是 Material 对 exposed
dropdown 的官方做法。

**证据**：浅色 / 深色下标签正确缩小、值与箭头对齐；下拉菜单四项完整、选中项
高亮；选择后重启 App 仍保持（持久化正常）。截图：
`docs/screenshots/options-voice.png`、`docs/screenshots/05-voice-menu.png`。

## 13. 权限全开时的「需要授权」误报（本轮修复，已实测）

**现象**：用户把权限全部授予（自动批准）后，任务并不会卡住，但手机仍弹出
「需要授权」提醒。

**定位**：对真实服务抓包（OpenCode 2.0.22）确认，自动批准不会省掉事件——
`permission.asked` 照常发出，随后立即跟上 `permission.replied`：

```
permission.asked   {"id":"per_…","sessionID":"…","action":"external_directory",
                    "resources":["…/*"],"source":{…}}
permission.replied {"sessionID":"…","requestID":"per_…","reply":"once"}
```

官方 web 客户端的 autoApprove 模式（`opencode run --auto` 同理）就是监听
`permission.asked` 并立刻回复。旧实现一见 `asked` 就提醒，于是误报。

**修复**：`permission.asked` 先交给 `PermissionGate` 挂起，2 秒宽限期内收到同一
`requestID` 的 `permission.replied` 就取消；到期仍未回复才提醒
（`NotifyService.holdPermissionAlert` / `dropPermissionAlert`）。
`notified` 去重集合改为 `Collections.synchronizedSet`，因为延迟检查跑在
service scope 上，与 SSE 线程并发。

**证据**：
- 单元测试 `PermissionGateTest`：asked/replied/fired、重复 ask 只排一次、
  未知 reply 忽略、并发请求互不干扰。
- 模拟器 + 真实服务：`opencode run --auto` 触发权限 → App 日志
  `permission … replied within the grace period; no alert`，通知栏无「需要授权」。
- 模拟器 + 受控 mock 服务（`POST /control` 向 `/api/event` 注入事件）：
  - 只发 `permission.asked` → 约 2 秒后日志
    `event permission.asked -> PERMISSION · external_directory · …`，
    通知栏出现「需要授权」；
  - `asked` 后 0.3 秒发 `replied` → 日志
    `answered within the grace period; no alert`，通知栏无提醒。


