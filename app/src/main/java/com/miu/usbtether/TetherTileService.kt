package com.miu.usbtether

import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 控制中心快速设置磁贴：一键开关 USB 网络共享
 */
class TetherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        val t = qsTile ?: return

        busy = true
        t.state = Tile.STATE_UNAVAILABLE
        t.label = "处理中…"
        t.icon = Icon.createWithResource(this, R.drawable.ic_tether)
        t.updateTile()

        thread(name = "tether-toggle") {
            val turnOn = !TetherCore.isOn()
            val out = TetherCore.setEnabledBlocking(applicationContext, turnOn)
            val failed = out.startsWith("ERR") || out == "TIMEOUT"
            busy = false

            Handler(Looper.getMainLooper()).post {
                refresh()
                val msg = when {
                    failed -> "操作失败：" + out.take(80)
                    turnOn -> "网络共享已开启"
                    else -> "网络共享已关闭"
                }
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        if (busy) return
        val on = TetherCore.isOn()
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (on) "网络共享·开" else "网络共享·关"
        t.icon = Icon.createWithResource(this, R.drawable.ic_tether)
        t.updateTile()
    }

    companion object {
        @Volatile
        var busy: Boolean = false
    }
}