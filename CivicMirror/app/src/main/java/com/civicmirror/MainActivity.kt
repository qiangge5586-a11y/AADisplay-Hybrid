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
        content.addView(text("独立 Android Auto 镜像项目", 16f, false))
        content.addView(section("版本 0.1.0 · 工程骨架"))

        content.addView(text(
            "当前版本用于验证 Android 工程结构和自动构建流程。屏幕捕获、Android Auto 车机 Surface 输出及触控回传尚未实现。",
            15f, false
        ))

        content.addView(section("开发路线"))
        listOf(
            "MediaProjection 用户授权与会话生命周期",
            "Android Auto Car App / Surface 接入",
            "稳定画面输出与首帧检测",
            "720p / 1080p、Fit / Fill、帧率控制",
            "触控回传与坐标映射",
            "DHU 验证及 2019 款本田思域实车测试"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("系统限制"))
        content.addView(text(
            "MediaProjection 需要用户授权；DRM 或安全窗口可能无法捕获。镜像输出尺寸不等于修改手机物理屏幕分辨率。Shizuku 不能绕过这些系统限制。",
            14f, false
        ))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun section(value: String) =
        text(value, 17f, true).apply { setPadding(0, dp(22), 0, dp(10)) }

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
