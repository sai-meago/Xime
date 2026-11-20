package com.kingzcheung.xime.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.R

/**
 * 系统状态栏常驻通知：在通知栏顶部常驻一个通知，下拉可见
 * 当前输入方案名 / 中英模式 / Alt 锁定 三项状态。
 *
 * 说明：Android 系统状态栏图标区域只能显示单色小图标，正文文字需下拉通知栏查看，
 * 这是系统限制，输入法无法绕过。
 */
class StatusNotificationManager(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "xime_status_bar"
        private const val CHANNEL_NAME = "输入法状态栏"
        private const val NOTIFICATION_ID = 0x58494D45 // "XIME"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var channelCreated = false

    private fun ensureChannel() {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW, // 低优先级：常驻但不发声
            ).apply {
                description = "显示当前输入法方案、中英文与 Alt 锁定状态"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        channelCreated = true
    }

    /** 构建状态文本，如「五笔86 · 中 · ALT○」。 */
    private fun buildStatusText(
        schemaName: String,
        isAsciiMode: Boolean,
        hwAltLocked: Boolean,
    ): String {
        val scheme = schemaName.ifBlank { "—" }
        val lang = if (isAsciiMode) "英" else "中"
        val alt = if (hwAltLocked) "ALT●" else "ALT○"
        return "$scheme · $lang · $alt"
    }

    /**
     * 刷新常驻通知。uiState 每次变化时调用。
     * 无通知权限（Android 13+ 未授权）时静默跳过，不抛异常。
     */
    fun update(
        schemaName: String,
        isAsciiMode: Boolean,
        hwAltLocked: Boolean,
    ) {
        ensureChannel()

        val title = "莓码输入法"
        val text = buildStatusText(schemaName, isAsciiMode, hwAltLocked)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)              // 常驻，不可滑动清除
            .setShowWhen(false)
            .setOnlyAlertOnce(true)        // 更新时不重复提醒
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        try {
            if (hasNotificationPermission()) {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // 未授予通知权限，静默跳过
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 服务销毁时移除常驻通知。 */
    fun cancel() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // ignore
        }
    }
}