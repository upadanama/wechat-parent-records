package com.jz.chatshot

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 通过 Shizuku 授予的 WRITE_SECURE_SETTINGS 自行开启无障碍服务。 */
object A11y {

    const val GRANT_COMMAND =
        "pm grant com.jz.chatshot android.permission.WRITE_SECURE_SETTINGS"

    private const val SERVICE = "com.jz.chatshot/com.jz.chatshot.ShotService"

    fun canSelfEnable(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    fun ensureEnabled(ctx: Context): Boolean {
        return try {
            if (!canSelfEnable(ctx)) return false
            val resolver = ctx.contentResolver
            val current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val services = current.split(':')
                .filter { it.isNotEmpty() }
                .toMutableList()
            val present = services.contains(SERVICE)

            if (present && ShotService.instance != null) {
                return enableFlag(resolver)
            }

            // 系统里残留了已启用项但服务没有跑起来：先移除再追加，触发系统重新绑定。
            if (present) {
                services.removeAll { it == SERVICE }
                if (!writeServices(resolver, services)) return false
            }

            services.add(SERVICE)
            if (!writeServices(resolver, services)) return false
            enableFlag(resolver)
        } catch (e: Exception) {
            false
        }
    }

    private fun writeServices(resolver: ContentResolver, services: List<String>): Boolean =
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(":")
        )

    private fun enableFlag(resolver: ContentResolver): Boolean =
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            "1"
        )
}
