# 电脑端桥接（macOS / Linux / Windows）

OpenCode 的后台服务只监听 `127.0.0.1`，而且每次重启都换随机端口。
手机需要一个**固定地址**才能连上，所以由这个脚本把服务稳定地暴露到 tailnet。

```
OpenCode 127.0.0.1:<随机端口>  ←  opencode-bridge.py  ←  <tailnet IP>:4096  ←  手机
```

`opencode-bridge.py` 只用 Python 标准库，**三个平台通用**，会自动：
探测本机 tailnet 地址、找到 `opencode-cli`、每 15 秒跟随 OpenCode 换端口。

需要 **Python 3.9+**（`python3 --version`）。

---

## macOS

```sh
# 前台调试
python3 tools/bridge/opencode-bridge.py

# 常驻（launchd，登录自启 + 崩溃自动重启）
./tools/bridge/install-bridge-macos.sh
./tools/bridge/install-bridge-macos.sh --uninstall   # 卸载
```

日志：`/tmp/opencode-bridge.log`

## Linux

```sh
python3 tools/bridge/opencode-bridge.py            # 前台调试

./tools/bridge/install-bridge-linux.sh             # 常驻（systemd --user）
./tools/bridge/install-bridge-linux.sh --uninstall # 卸载

# 想让它在你注销后也活着：
sudo loginctl enable-linger "$USER"
```

日志：`journalctl --user -u opencode-bridge -f`

## Windows

```powershell
# 前台调试
python tools\bridge\opencode-bridge.py

# 常驻（计划任务，登录自启 + 失败自动重启；用 pythonw 运行，不弹黑框）
powershell -ExecutionPolicy Bypass -File .\tools\bridge\install-bridge-windows.ps1

# 卸载
powershell -ExecutionPolicy Bypass -File .\tools\bridge\install-bridge-windows.ps1 -Uninstall
```

首次监听时 Windows 会弹防火墙询问，**允许 Python 通过**（私有 / Tailscale 网络即可）。

---

## 常用参数

| 参数 | 作用 |
|---|---|
| `--port 4096` | 监听端口（默认 4096，和手机端「端口」一致） |
| `--bind auto` | 默认：自动绑定本机 tailnet 地址 |
| `--bind 0.0.0.0` | 同时暴露到局域网（无 Tailscale 时用） |
| `--bind 127.0.0.1` | 只绑本机回环（配合 `adb reverse` 做模拟器调试） |
| `--cli <路径>` | 手动指定 `opencode-cli`（自动探测失败时用） |

## 找不到 opencode-cli？

脚本会依次尝试：各平台的 OpenCode 桌面端 `cli/<版本>/` 目录 → PATH 里的
`opencode-cli` / `opencode`。都不行就手动指定：

```sh
python3 tools/bridge/opencode-bridge.py --cli /path/to/opencode-cli
```

## 验证

```sh
curl -u opencode:<密码> http://<本机tailnet地址>:4096/api/info
```

返回 `{"version": ...}` 即成功。密码在 OpenCode 的 `service.json` 里
（macOS/Linux：`~/.config/opencode/service.json`，Windows：`%APPDATA%\opencode\service.json`）。
