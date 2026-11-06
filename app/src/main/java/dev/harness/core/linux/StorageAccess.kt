package dev.harness.core.linux

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * "All files access": MANAGE_EXTERNAL_STORAGE on Android 11+, the classic storage permissions
 * before that. With it, the agent's file tools and the Linux shell can read and edit files in
 * shared storage (/sdcard), not only inside the app's workspace.
 */
object StorageAccess {
    val needsSpecialAccess: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isGranted(context: Context): Boolean =
        if (needsSpecialAccess) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Root of shared storage (/storage/emulated/0), or null without permission. */
    fun rootIfGranted(context: Context): File? =
        if (isGranted(context)) Environment.getExternalStorageDirectory()?.takeIf { it.isDirectory } else null

    /** The system screen with the "Allow access to manage all files" switch for this app. */
    fun settingsIntent(context: Context): Intent {
        val appScreen = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        return if (appScreen.resolveActivity(context.packageManager) != null) appScreen
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}
