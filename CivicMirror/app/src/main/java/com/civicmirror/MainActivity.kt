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
        content.addView(text("独立 Android Auto USB 镜像工程", 16f, false))
        content.addView(section("版本 0.1.0 · 工程骨架"))

        content.addView(text(
            "当前版本仅用于验证工程结构和自动构建流程。屏幕捕获、Android Auto 车机画面输出、触控回传尚未实现。",
            15f, false
        ))

        content.addView(section("开发路线"))
        listOf(
            "MediaProjection 捕获与会话管理",
            "Android Auto Car App Surface 接入",
            "稳定渲染、首帧检测与黑屏诊断",
            "Auto / 720p / 1080p 输出配置",
            "Fit / Fill 与帧率策略",
            "触控回传、DHU 和思域实车验证"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("系统限制"))
        content.addView(text(
            "MediaProjection 需要用户授权。DRM 或安全窗口可能无法捕获。输出分辨率不等于修改手机物理屏幕分辨率。无 Root 基线也不能绕过 Android Auto 的应用分发限制。",
            14f, false
        ))

        setContentView(ScrollView(this).apply { addView(content) })
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
