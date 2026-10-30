package io.github.nitsuya.aa.display.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Android-facing Shizuku lifecycle adapter.
 *
 * Own this from an Application/service-scoped component. Call start once and destroy when
 * that owner is disposed. This reports the actual Shizuku UID; shell (2000) is not root.
 * It does not provide LSPosed injection or grant capabilities beyond Shizuku's Binder API.
 */
class ShizukuAccessController(private val onStateChanged: (ShizukuAccessState) -> Unit) {
    private val binderReceived = Shizuku.OnBinderReceivedListener { publish() }
    private val binderDead = Shizuku.OnBinderDeadListener { publish() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) publish()
    }

    fun start() {
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        publish()
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            publish()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_CODE)
        } else publish()
    }

    fun refresh() = publish()

    fun destroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    private fun publish() {
        val alive = Shizuku.pingBinder()
        val granted = alive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val uid = if (granted) Shizuku.getUid() else null
        onStateChanged(ShizukuAccessPolicy.resolve(alive, granted, uid))
    }

    companion object { const val REQUEST_CODE = 0xAAD1 }
}
