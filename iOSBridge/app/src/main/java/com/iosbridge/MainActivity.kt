package com.iosbridge

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * iOSBridge 0.2.8 research shell.
 *
 * This app deliberately performs no USB gadget, ConfigFS, FunctionFS, Bluetooth,
 * or network reconfiguration on launch. Device-specific transport work must be
 * gated by capability checks, explicit consent, and a tested rollback path.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(28, 36, 28, 28)
        }

        content.addView(TextView(this).apply {
            text = "iOSBridge 0.2.8"
            textSize = 26f
        })

        content.addView(TextView(this).apply {
            text = """
                有线 CarPlay 设备能力研究原型

                当前状态：源码重建基线
                • Android 应用入口已建立
                • USB Gadget / ConfigFS / FunctionFS 尚未启用
                • iAP2 仅作为协议研究方向
                • 未实现完整 CarPlay sender
                • 未宣称 Apple MFi 认证或车机兼容

                下一阶段：
                1. 只读识别设备 UDC、ConfigFS、FunctionFS 和 NCM 能力
                2. 保存 USB 原始状态并提供回滚审计
                3. 在确认设备支持后，才设计受控 transport 初始化

                安全提示：本版本不会修改 USB 配置。
            """.trimIndent()
            textSize = 15f
            setPadding(0, 24, 0, 0)
            setTextIsSelectable(true)
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }
}
