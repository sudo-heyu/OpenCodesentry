# 验证记录

本文件记录已经**实测过**的内容，以及**尚未验证**的部分。区分这两者是这份记录存在的意义。

环境：

| 项 | 值 |
|---|---|
| OpenCode | 2.0.18（macOS，`opencode-cli serve --service`） |
| 宿主 | my-mac，tailnet `100.101.102.103`，MagicDNS `my-mac.tailnet.ts.net` |
| Android | 模拟器 Pixel_4a，Android 17 / SDK 37（`google_apis`，arm64-v8a） |
| 构建 | AGP 9.4.1（内置 Kotlin），compileSdk 37，minSdk 31，targetSdk 37 |

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
