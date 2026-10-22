package org.qterm.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Статус сессии PackageInstaller: просьба подтвердить (системное окно), успех или ошибка. */
class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "org.qterm.android.update.INSTALL_STATUS"
        const val EXTRA_VERSION = "version"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        if (UpdateLog.file == null) UpdateLog.file = java.io.File(context.filesDir, "update.log")
        var confirm: Intent? = null
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                UpdateLog.note("установка $version: жду подтверждения пользователя")
                val c = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirm = c
                if (c != null) {
                    c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(c) }
                        .onFailure { UpdateLog.note("не открылось окно подтверждения: ${it.message}") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateLog.note("✓ установлена $version")
            else -> UpdateLog.note("✗ установка $version: статус $status ${msg.orEmpty()}")
        }
        runCatching { UpdateCenter.onInstallStatus(status, msg, confirm) }
    }
}
