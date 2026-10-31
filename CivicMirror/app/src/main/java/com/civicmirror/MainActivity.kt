package com.civicmirror

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        content.addView(text("CivicMirror", 28f, true))
        content.addView(text("独立 Android Auto 镜像项目", 16f, false).apply {
            setPadding(0, dp(8), 0, dp(24))
        })
        content.addView(section("当前版本：0.1.0（工程骨架）"))
        content.addView(text(
            "此版本用于确认 Android 工程结构与自动构建流程。当前尚未实现屏幕捕获、Android Auto Surface 输出或车机触控回传。",
            15f, false
        ))
        content.addView(section("开发路线"))
        listOf(
            "1. MediaProjection 用户授权与生命周期",
            "2. Android Auto Car App 与车机 Surface 接入",
            "3. 稳定画面输出与首帧检测",
            "4. 720p / 1080p、Fit / Fill、帧率策略",
            "5. 无障碍触控回传与坐标映射",
            "6. DHU 验证及 2019 款本田思域实车测试"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("重要限制"))
        content.addView(text(
            "普通应用无法保证修改手机物理分辨率；MediaProjection 需要用户授权。受 DRM 或安全窗口保护的内容可能无法捕获。Shizuku 不能绕过这些系统限制。",
            14f, false
        ))

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun section(value: String) =
        text(value, 17f, true).apply {
            setPadding(0, dp(22), 0, dp(10))
        }

    private fun text(value: String, size: Float, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
