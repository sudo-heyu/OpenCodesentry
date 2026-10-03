<div align="center">

<img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/icon.png" width="116" alt="OpenCode Sentry">

# OpenCode Sentry · 哨兵

**代码在跑，手机先响。**

OpenCode 的安卓提醒端 —— 任务完成、需要授权、需要你回答、失败中断，
熄屏也能震动 / 响铃 / 语音播报；**被系统清理后，它还能自己爬回来。**

![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack-7F52FF?logo=kotlin&logoColor=white)
![Tailscale](https://img.shields.io/badge/Tailscale-tailnet-242424?logo=tailscale&logoColor=white)
![No cloud](https://img.shields.io/badge/数据-不经第三方-1B7F4B)

[![Android CI](https://github.com/sudo-heyu/OpenCodesentry/actions/workflows/android.yml/badge.svg)](https://github.com/sudo-heyu/OpenCodesentry/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/sudo-heyu/OpenCodesentry?sort=semver)](https://github.com/sudo-heyu/OpenCodesentry/releases)
[![License](https://img.shields.io/github/license/sudo-heyu/OpenCodesentry)](LICENSE)

</div>

---

## English

**OpenCode Sentry** is an Android notification companion for the
[OpenCode](https://opencode.ai) coding agent. A foreground service holds an SSE
connection to your own machine — exposed over Tailscale by a tiny Python bridge —
and alerts you the moment a task finishes, blocks on a permission prompt, asks a
question, or fails. It also ships a built-in console: scan the pairing QR that
the bridge prints and the official OpenCode web UI opens inside the app, so every
session can be watched — and driven — exactly as on the computer.

- ⚡ **Instant** — one long-lived SSE connection; events arrive in under a second.
- 📱 **Full console** — the official web UI in-app over Tailscale: all sessions,
  messages, tool runs, sub-agents, model / thinking-depth switching and replies.
- 🔔 **Loud with the screen off** — notification + vibration + ringtone + voice.
- ♻️ **Self-healing** — accessibility keeper / persistent Job / exact-alarm
  watchdog; survives the "one-tap clean" of aggressive vendor ROMs.
- 🔒 **Your network only** — direct over Tailscale (WireGuard); no third-party
  server and no telemetry.
- 🗣️ **Offline voice** — four pre-generated Chinese TTS voices shipped in the APK.

**Install:** Android 12+ (`minSdk 31`). Grab the APK from
[Releases](https://github.com/sudo-heyu/OpenCodesentry/releases), or build it with
`./gradlew assembleDebug`. The desktop bridge is documented in
[`tools/bridge/`](tools/bridge/README.md).

> [!IMPORTANT]
> **Not affiliated with OpenCode.** This is an independent, community-built
> project. “OpenCode” is used only to describe interoperability, and the app mark
> is derived from the OpenCode logo solely to indicate that. It is not built by,
> endorsed by, or affiliated with the OpenCode team / Anomaly. See [`NOTICE`](NOTICE).

---

## 一句话

你在电脑上让 OpenCode 干活，人走开去泡咖啡。它跑完了、或者卡在「要不要允许这个命令」上——
**OpenCode Sentry 会在你手机上把你叫回来**，不用盯着屏幕，也不怕手机熄屏、不怕后台被国产 ROM 清掉。

它有两个角色：**接收端**——不代替电脑上的 OpenCode，也不上传任何数据，手机通过 **Tailscale** 直连你自己的机器，
提醒内容只在你自己的设备之间流动；以及**控制台**——扫码配对后，官方 OpenCode web 界面直接装进 App 里，
所有会话、消息、工具执行、子智能体都能看，也能直接发指令。

---

## 📸 一眼看懂

| 状态 | 选项 | 配置 |
|:---:|:---:|:---:|
| <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/status-overview.png" width="235"> | <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/options-events.png" width="235"> | <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/config-tailscale.png" width="235"> |
| 守护状态 · 连接通道 · 权限自检 | 提醒哪些事件、怎么提醒 | 填两端地址，点一下自动检测本机 IP |

| 权限自检 · 运行诊断 | 语音 / 音量 / 最长响铃 | 凭据 · 连接测试 |
|:---:|:---:|:---:|
| <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/status-diagnostics.png" width="235"> | <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/options-voice.png" width="235"> | <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/config-credentials.png" width="235"> |
| 每一项都能一键跳到系统设置 | 4 种音色，切换即试听 | 测试连接，结果实时显示 |

<details>
<summary><b>Tailscale 侧要做的两件事（点开）</b></summary>

1. 手机和电脑加入**同一个 tailnet**。
2. 在 Tailscale 里给手机开启 **始终开启 VPN**（以及「阻止无 VPN 时的连接」）。

| 开启「始终开启 VPN」 |
|:---:|
| <img src="https://cdn.jsdelivr.net/gh/sudo-heyu/OpenCodesentry@main/docs/screenshots/tailscale-network.png" width="300"> |

</details>

---

## ✨ 为什么是它

- **秒级到达** —— 前台服务持有一条到 `/api/event` 的 SSE 长连接，事件一到就响。
- **控制台随身走** —— 扫码配对，官方 OpenCode web UI 直接进 App；会话列表、工具执行、子智能体与电脑端同一视角，
  模型与思考深度都能切，消息随手发。
- **熄屏也叫得醒** —— 通知 + 震动 + 铃声 + 语音播报，四路一起上。
- **杀了能回来** —— 无障碍守护 / 持久化 Job / 精确闹钟三重自愈，专治国产 ROM 的「一键清理」。
- **划不掉** —— 应用从「最近任务」隐藏，没有卡片可划，系统的清理也看不到它。
- **只走你自己的网** —— Tailscale 加密隧道直连，不经过任何第三方服务器。
- **中文语音离线可用** —— 晓伊 / 晓晓 / 云希 / 云扬 4 种音色，mp3 预置在包里，不用联网合成。
- **多品牌适配** —— 小米 / 华为 / OPPO / vivo / 三星 / 魅族 的自启动、后台运行页一键跳转。
- **首次打开有引导** —— 适用范围、操作规范、各权限的打开位置，按你的机型自动给出。

---

## 🚀 三分钟跑起来

### ① 电脑端：跑桥接（macOS / Linux / Windows 通用）

OpenCode 的服务只监听 `127.0.0.1`，而且每次重启都换随机端口。桥接脚本负责把它**稳定地**暴露到 tailnet。
脚本只用 Python 标准库，**三个平台通用**，会自动探测本机 tailnet 地址、找到 `opencode-cli`、跟随端口变化。

```sh
# 前台调试
python3 tools/bridge/opencode-bridge.py

# 常驻（登录自启 + 崩溃自动重启）
./tools/bridge/install-bridge-macos.sh     # macOS（launchd）
./tools/bridge/install-bridge-linux.sh     # Linux（systemd --user）
```

```powershell
# Windows（计划任务；用 pythonw 运行，不弹黑框）
powershell -ExecutionPolicy Bypass -File .\tools\bridge\install-bridge-windows.ps1
```

成功会打印监听地址，例如 `100.101.102.103:4096`。桥接每 15 秒重新解析一次 OpenCode 的端口，
所以 OpenCode 重启换端口后它会自动跟随。三个平台的细节见 [`tools/bridge/README.md`](tools/bridge/README.md)。

> 想免输密码：加 `--pair` 启动（`python3 tools/bridge/opencode-bridge.py --pair`），
> 会在终端画出一个 **5 分钟有效、单次使用**的配对二维码（同时存为 `opencode-pair.png`）。
> 手机（同一 tailnet）扫码后浏览器直接进入 OpenCode 自带的 web 客户端，与电脑端同一视角。

### ② 手机端：装 App + 加入同一个 tailnet

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tailscale 里给手机开启 **始终开启 VPN** 与 **阻止无 VPN 时的连接**，并在
`设置 → 应用 → Tailscale → 电池` 里关掉电池优化（否则隧道会被后台暂停，一切失效）。

### ③ 在 App 里收尾

1. **主页 / 配置页**：电脑端用 `--pair` 启动桥接，点「扫码配对」扫一下终端里的二维码——
   地址、凭据、控制台一次配好。也可以手动填「电脑端 IP」+ 端口 `4096` + 用户名 / 密码，
   密码在 OpenCode 的 `service.json` 里（macOS/Linux：`~/.config/opencode/service.json`；
   Windows：`%APPDATA%\opencode\service.json`），点 **测试连接**。
2. **状态页**：按顺序完成「保活与权限自检」，打开 **启用守护服务**。
3. 点 **发送测试提醒**，确认震动 / 铃声 / 语音都正常。

> 第一次打开会自动弹出《使用说明》，讲清适用范围、操作规范和**你这台手机**的权限打开位置。
> 之后它就在右上角的 **?** 里，随时可看，不再打扰。

---

## 🛡️ 保活：为什么「划掉也能回来」

国产 ROM 的「从最近任务划掉」≈ **force-stop**，它会一次做掉三件事：杀进程、**取消所有闹钟**、
把包置为 `stopped`（连开机广播都不再送达）。所以任何「靠闹钟拉起服务」的方案都跨不过它。

Sentry 换了个思路：**不让它被划掉，并且被杀之后自己回来。**

| 层 | 实现 | 生效场景 |
|---|---|---|
| **划不掉** | `excludeFromRecents` + `singleTask` | 最近任务里根本没有卡片；「一键清理」也看不到它 |
| **被杀了能回来** | 无障碍守护心跳（30 秒） | 无障碍服务被 `system_server` 以 `BIND_AUTO_CREATE` 绑定，进程被杀后系统会重建并回调 |
| **真起不来时** | 持久化 `JobScheduler`（15 分钟）+ 精确闹钟看门狗 | 进程被杀但未强停；或无逆被关掉时的最后防线 |
| **兜底** | `GuardFallback` 单次轮询直接发通知 | 系统拒绝后台启动前台服务时，也不会静默死掉 |

无障碍服务在这里**不读屏、不操作界面**：配置里把事件限制在本应用自己的包名，
`canRetrieveWindowContent=false`。它存在的唯一意义，是那份来自系统的绑定。

> 唯一的硬限制：真正的「强行停止」（应用信息页里点 Force stop）谁都救不了，必须手动再点一次图标。
> 而「划掉」在本应用上不会发生——因为它根本不在最近任务里。

---

## 📱 各品牌必须做的设置

应用会自动识别机型，并在《使用说明》里给出完整路径；这里汇总一张表：

| 品牌 | 自启动 | 后台运行 / 电池 |
|---|---|---|
| **小米 / Redmi** | `设置 → 应用设置 → 应用管理 → 权限管理 → 自启动` | `省电策略 → 无限制`；`其他权限 → 后台弹出界面 → 允许` |
| **华为 / 荣耀** | `设置 → 应用 → 应用启动管理` → 关闭「自动管理」，勾选后台活动 | `电池 → 更多电池设置 → 休眠时始终保持网络连接` |
| **OPPO / 一加 / realme** | `设置 → 应用 → 自启动管理` | `电池 → 更多设置` → 关闭睡眠待机优化 / 应用速冻 |
| **vivo / iQOO** | `i管家 → 应用管理 → 权限管理 → 自启动` | `设置 → 电池 → 后台耗电管理` → 允许后台高耗电 |
| **三星** | `电池和设备维护 → 电池 → 后台使用限制` → 加入「从不休眠的应用」 | 同左，并关闭对本应用的自动优化 |

所有品牌共通：**无障碍守护**（关键）、**电池优化白名单**、**通知权限**、**勿扰穿透**（可选）。
Android 13+ 侧载应用若无障碍开关是灰的，先到 `应用信息 → 右上角 ⋮ → 允许受限设置`。

> 别忘了给 **Tailscale 也做一遍**自启动 + 后台运行——它先被杀掉的话，一切都白搭。

---

## 🔧 工作原理

```
┌──────────────── macOS ────────────────┐        ┌────────── Android ──────────┐
│  opencode 后台服务                      │        │  OpenCode Sentry            │
│    └── 127.0.0.1:<随机端口>  /api/event │◀──────▶│   前台服务 (specialUse)      │
│                                        │Tailscale│    ├── SSE 长连接           │
│  opencode-bridge.py                    │ 加密隧道 │    ├── 看门狗精确闹钟        │
│    └── 100.x.x.x:4096 → 127.0.0.1:<端口>│        │    ├── 持久化 JobScheduler   │
└────────────────────────────────────────┘        │    ├── 无障碍守护（进程自愈） │
                                                  │    └── 震动 + 铃声 + 语音    │
                                                  └──────────────────────────────┘
```

**事件映射**（`AlertKind.kt`）：

| OpenCode 事件 | 提醒 |
|---|---|
| `session.idle` / `session.execution.succeeded` | ✅ 任务完成 |
| `permission.asked` | 🔐 需要授权 |
| `form.created` / `session.form.created` | ❓ 需要你回答 |
| `session.error` / `session.execution.failed` | ⛔ 任务失败 |
| `session.execution.interrupted` | ⏸️ 任务已中断 |

同一会话的一轮只提醒一次；会话重新开始运行后才会再次提醒。
连接侧还有两条自愈：服务端每 15 秒一次 `: heartbeat`，客户端 60 秒读超时判定死链并重连；
看门狗定时对比 `/api/session/active`，补齐休眠期间漏掉的「任务结束」。

---

## 🗣️ 语音

`res/raw/` 下是 **4 个音色 × 5 个场景 = 20 个 mp3**，由 `tools/tts/generate_voice.py` 用 Edge TTS 生成：

```sh
python3 -m pip install edge-tts
python3 tools/tts/generate_voice.py --list    # 预览
python3 tools/tts/generate_voice.py           # 生成（自动清理旧音色残留）
```

改文案或加音色：编辑脚本里的 `PHRASES` / `VOICES` → 同步 `Voice.kt` 的 id 与显示名 →
在 `AlertKind.clipFor()` 补上新组合（漏了会**编译不过**，`when` 是穷尽的，这是故意的）。

---

## 📂 目录结构

```
app/src/main/java/app/opencodesentry/
├── MainActivity.kt           三页界面 + 权限自检 + 使用说明
├── NotifyService.kt          前台守护：SSE 长连接 + 看门狗
├── GuardAccessibilityService.kt  进程自愈的锚点（不读屏）
├── GuardJobService.kt        持久化复活任务
├── KeepAlive.kt              三重自愈的统一入口
├── Alarms.kt / WatchdogReceiver.kt  精确闹钟看门狗
├── BootReceiver.kt           开机 / 更新后恢复
├── GuardFallback.kt          起不来时的轮询兜底
├── VendorShortcuts.kt        多品牌「自启动 / 后台运行」跳转
├── HelpContent.kt            首次引导 / 使用说明
├── Alerter.kt / AlertPlayer.kt / Voice.kt / AlertKind.kt  提醒与语音
├── OpenCodeClient.kt / TailnetIp.kt / Settings.kt
└── Logx.kt / ServiceStatus.kt

tools/bridge/  opencode-bridge.py（三平台通用）· install-bridge-{macos,linux,windows}
tools/mac/     probe_events.sh · e2e-emulator.sh（macOS 开发脚本）
tools/tts/     generate_voice.py
docs/          截图 · verification.md（实测记录）
```

---

## 🛠️ 构建与测试

```sh
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 单元测试
```

环境：AGP 9.2.1 · Kotlin · `minSdk 31`（Android 12）· `targetSdk 37` · Material 3（固定墨色配色，深浅色自适应）。

模拟器端到端（不需要 Tailscale，走 `adb reverse` 直连本机桥接）：

```sh
python3 tools/bridge/opencode-bridge.py --bind 127.0.0.1 --port 4096
adb reverse tcp:4096 tcp:4096
./tools/mac/e2e-emulator.sh    # 一键端到端，打印全部证据
```

---

## 🩺 故障排查

| 现象 | 处理 |
|---|---|
| `Failed to connect to <电脑端IP>` | **最常见：桥接没在跑。** 电脑上 `lsof -nP -iTCP:4096 -sTCP:LISTEN`，没输出就重跑桥接 |
| 桥接在跑还是连不上 | 确认它绑的是 Tailscale 地址（`100.x.x.x:4096` 而不是 `127.0.0.1:4096`） |
| 手机连不上但 ping 得通 | 电脑上 `curl -u opencode:<密码> http://<电脑端IP>:4096/api/info` 自测，再查 macOS 防火墙 |
| 亮屏能收、熄屏收不到 | 电池优化白名单 / 后台运行 / 睡眠待机优化没做全 |
| 完全收不到，Tailscale 图标变灰 | Tailscale 被后台杀了，给它也做一遍保活 |
| 「运行诊断 → 第几次启动」一直涨 | ROM 在反复杀进程：确认无障碍守护已开、自启动 / 后台运行已允许 |
| 无障碍心跳显示「未开启」或长时间不更新 | 无障碍被 ROM 静默关掉了，重新开启并确认「允许受限设置」+ 自启动 |
| 锁屏不显示通知内容 | 通知频道重要性被调低，或锁屏隐藏了内容 |

---

## ⚠️ 已知限制

- `permission.asked` / `form.*` 的轮询兜底是**单 location 作用域**的，断线期间漏掉的授权请求无法补发；
  `session` 相关事件是全局的，可以补齐。实时长连接正常时不受影响。
- 目前是**单向提醒 + 完整控制台**：提醒仍是单向的；回复授权 / 提问、发消息请在
  「主页」控制台里操作（官方 web UI）。手机本地文件上传（附件）暂不支持，web 界面里以主机侧文件为准。
- 扫码得到的控制台凭据有效期 **30 天**（自动同步给提醒服务），过期后重新扫码即可；
  手动填写的 `service.json` 密码不受影响。
- 深睡眠下 `setExactAndAllowWhileIdle` 会被限流（约 9–15 分钟一次）；要分钟级精确唤醒可打开
  **闹钟级唤醒**，代价是状态栏常驻一个闹钟图标。
- 「自启动」「后台运行」**没有公开查询接口**，应用只能把你送到设置页，无法替你确认
  （状态页显示「打开设置页 →」而不是「已就绪 ✓」是刻意的）。已开启就忽略这两行。
- 跳转适配覆盖小米 / 华为 / OPPO / vivo / 三星 / 魅族，其余品牌回退到「应用信息」页并给出通用指引。
  核心保活逻辑与品牌无关。
- 真正的 **force-stop** 无法自愈，必须手动再打开一次应用。

---

<div align="center">

**让 OpenCode 干活的时候，你可以放心走开。**

[MIT License](LICENSE)

</div>
