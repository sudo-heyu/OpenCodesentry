package app.opencodesentry

/**
 * The first-run guide, also reachable any time from the header button.
 *
 * It answers the three things a new user cannot guess from the UI: what the app
 * is actually for, how the phone and the computer are expected to be connected,
 * and where each OEM hides the switches the self-check cannot turn on for them.
 *
 * Returned as a small HTML string so `Html.fromHtml` can bold the headings.
 */
object HelpContent {

    fun html(): String {
        val vendor = VendorShortcuts.vendor()
        val brand = vendor.label.ifEmpty { "通用" }
        return buildString {
            append("<b>适用范围</b><br>")
            append(
                "本应用只做一件事：在手机上接收 OpenCode 的提醒" +
                    "（任务完成 / 需要授权 / 需要你回答 / 失败中断）。" +
                    "它不是电脑端的替代品，只是一个接收端；电脑上的 OpenCode 照常使用。<br>",
            )
            append("要求 Android 12 及以上；电脑端需要运行 OpenCode 和桥接脚本。<br><br>")

            append("<b>操作规范</b><br>")
            append("1. <b>组网</b>：手机与电脑必须处于同一个可互访的网络。推荐用 " +
                "<b>Tailscale</b> 组网（手机与电脑登录同一账号、同一 tailnet），" +
                "这样在外网也能连上，地址不会变。<br>")
            append("2. <b>Tailscale 也要保活</b>：给 Tailscale 同样做一遍自启动 / 后台运行，"
                + "否则它会先被系统杀掉，连不上电脑，一切白搭。<br>")
            append("3. <b>电脑端</b>：运行桥接脚本，让它监听一个固定端口（默认 4096）。<br>")
            append("4. <b>手机端</b>：在「配置」页填 电脑端地址 / 端口 / 用户名 / 密码，"
                + "点「测试连接」直到显示成功。<br>")
            append("5. <b>保活</b>：在「状态」页逐项完成下面的权限自检，"
                + "然后打开「启用守护服务」。<br><br>")

            append("<b>权限打开位置（$brand）</b><br>")
            append(permissions(vendor))
            append("<br>")

            append("<b>说明</b><br>")
            append("「自启动」「后台运行」这类开关<b>没有公开查询接口</b>，应用无法自动检测。"
                + "状态页里它们显示「打开设置页」而不是「已就绪」是正常的；"
                + "只要你已经在系统里开好了，忽略这两行即可。<br>")
        }
    }

    private fun permissions(vendor: VendorShortcuts.Vendor): String = when (vendor) {
        VendorShortcuts.Vendor.XIAOMI -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 无障碍 → 已下载的服务 → OpenCode 通知 → 开启<br>
            （Android 13+ 侧载应用开关是灰的时：设置 → 应用设置 → 应用管理 → OpenCode 通知 → 右上角 ⋮ → 允许受限设置）<br><br>
            <b>自启动</b><br>
            设置 → 应用设置 → 应用管理 → OpenCode 通知 → 权限管理 → 自启动 → 允许<br><br>
            <b>后台运行 / 省电</b><br>
            设置 → 应用设置 → 应用管理 → OpenCode 通知 → 省电策略 → 无限制<br>
            再到 设置 → 应用设置 → 应用管理 → OpenCode 通知 → 其他权限 → 后台弹出界面 → 允许<br><br>
            <b>电池优化白名单</b><br>
            点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            设置 → 通知与状态栏 → OpenCode 通知 → 允许通知；把「OpenCode 提醒」频道设为 重要 / 横幅。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()

        VendorShortcuts.Vendor.HUAWEI -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 辅助功能 → 无障碍 → 已安装的服务 → OpenCode 通知 → 开启<br><br>
            <b>自启动 / 后台运行</b><br>
            设置 → 应用 → 应用启动管理 → OpenCode 通知 → 关闭「自动管理」，手动勾选 自启动、关联启动、后台活动<br><br>
            <b>电池优化白名单</b><br>
            设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接 → 开启；<br>
            再点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            设置 → 通知 → OpenCode 通知 → 允许通知。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()

        VendorShortcuts.Vendor.OPPO -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 系统设置 / 其他设置 → 无障碍 → 已安装的服务 → OpenCode 通知 → 开启<br><br>
            <b>自启动</b><br>
            设置 → 应用 → 自启动管理（或 手机管家 → 权限隐私 → 自启动管理）→ 允许 OpenCode 通知<br><br>
            <b>后台运行 / 电池</b><br>
            设置 → 电池 → 更多设置 → 关闭「睡眠待机优化」；<br>
            并关闭对 OpenCode 通知的「应用速冻 / 后台冻结」。<br><br>
            <b>电池优化白名单</b><br>
            点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            设置 → 通知与状态栏 → OpenCode 通知 → 允许通知。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()

        VendorShortcuts.Vendor.VIVO -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 无障碍 → 已下载的服务 → OpenCode 通知 → 开启<br>
            （开关是灰的时：设置 → 应用 → 应用管理 → OpenCode 通知 → 右上角 ⋮ → 允许受限设置）<br><br>
            <b>自启动</b><br>
            i管家 → 应用管理 → 权限管理 → 自启动 → 允许 OpenCode 通知<br><br>
            <b>后台高耗电</b><br>
            设置 → 电池 → 后台耗电管理 → OpenCode 通知 → 允许后台高耗电<br><br>
            <b>电池优化白名单</b><br>
            点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            设置 → 通知与状态栏 → OpenCode 通知 → 允许通知。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()

        VendorShortcuts.Vendor.SAMSUNG -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 辅助功能 → 已安装的服务 → OpenCode 通知 → 开启<br><br>
            <b>后台运行 / 电池</b><br>
            设置 → 电池和设备维护 → 电池 → 后台使用限制 → 把 OpenCode 通知加入「从不休眠的应用」，<br>
            并确保它不在「休眠应用」列表里。<br><br>
            <b>自启动</b><br>
            部分机型在 设置 → 电池和设备维护 → 自动优化 中，关闭对本应用的自动优化。<br><br>
            <b>电池优化白名单</b><br>
            点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            设置 → 通知 → 应用通知 → OpenCode 通知 → 允许通知。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()

        VendorShortcuts.Vendor.MEIZU -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 辅助功能 → 无障碍 → 已安装的服务 → OpenCode 通知 → 开启<br><br>
            <b>自启动 / 后台</b><br>
            手机管家 → 权限管理 → 自启动 → 允许 OpenCode 通知；<br>
            并关闭对 OpenCode 通知的「后台冻结 / 智能省电」。<br><br>
            <b>电池优化白名单</b><br>
            点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            设置 → 通知和状态栏 → OpenCode 通知 → 允许通知。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()

        VendorShortcuts.Vendor.OTHER -> """
            <b>无障碍守护（关键）</b><br>
            设置 → 辅助功能 / 无障碍 → 已安装的服务 → OpenCode 通知 → 开启<br>
            （Android 13+ 侧载应用开关是灰的时，先在「应用信息 → ⋮ → 允许受限设置」放行）<br><br>
            <b>自启动 / 后台运行</b><br>
            到系统设置里找到「自启动 / 后台运行 / 电池优化」相关的开关，允许本应用后台常驻。<br>
            也可以在状态页点「自启动」「后台运行」两行，让应用把你送到最接近的系统设置页。<br><br>
            <b>电池优化白名单</b><br>
            点状态页「电池优化白名单」一行，按提示允许后台运行。<br><br>
            <b>通知权限 / 勿扰穿透</b><br>
            在系统设置里允许本应用发送通知。<br>
            勿扰穿透：点状态页「勿扰穿透」一行授权。
        """.trimIndent()
    }
}
