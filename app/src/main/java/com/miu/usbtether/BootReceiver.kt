package com.miu.usbtether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * 开机自动开启共享（可选，默认关闭）。
 * 注意：开机时 iPad 通常还没插上，开启后需要等设备接入才会真正生效。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TetherCore.autoBootEnabled(context)) return

        val ctx = context.applicationContext
        thread(name = "boot-tether") {
            // 开机后 USB 协议栈需要一点时间就绪
            Thread.sleep(20_000L)
            TetherCore.setEnabledBlocking(ctx, true)
        }
    }
}