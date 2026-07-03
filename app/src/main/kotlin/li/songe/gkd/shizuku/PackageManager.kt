package li.songe.gkd.shizuku

import android.Manifest
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import li.songe.gkd.META
import li.songe.gkd.app
import li.songe.gkd.permission.Manifest_permission_GET_APP_OPS_STATS
import li.songe.gkd.permission.canQueryPkgState
import li.songe.gkd.util.AndroidTarget
import rikka.shizuku.Shizuku


class SafePackageManager(private val value: IPackageManager) {
    companion object {
        
        fun newBinder() = getShizukuService("package")?.let {
            SafePackageManager(IPackageManager.Stub.asInterface(it))
        }

        private var canUseGetInstalledApps = true
    }

    val isSafeMode get() = safeInvokeShizuku { value.isSafeMode }

    /**
     * 通过执行 shell 命令 `pm list packages` 获取应用包名列表（备用方案）
     * 当 Shizuku API 调用失败时使用，以绕过 MIUI 等系统的拦截
     */
    private fun getPackagesViaShellCommand(): List<PackageInfo> {
        return try {
            // 通过 Shizuku 执行 pm list packages 命令
            val process = Shizuku.newProcess(arrayOf("pm", "list", "packages"), null, null)
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // 解析输出，格式为 "package:包名"
            output.lines()
                .mapNotNull { line ->
                    if (line.startsWith("package:")) line.substringAfter("package:") else null
                }
                .mapNotNull { pkgName ->
                    // 为每个包名创建一个基础的 PackageInfo 对象
                    // 注意：此方式只能获取包名，其他信息（版本、应用名等）将为空
                    // 但足以让 GKD 显示应用列表，避免列表为空
                    PackageInfo().apply {
                        packageName = pkgName
                    }
                }
        } catch (e: Exception) {
            android.util.Log.e("SafePackageManager", "pm 命令执行失败", e)
            emptyList()
        }
    }

    fun getInstalledPackages(
        flags: Int,
        userId: Int = currentUserId,
    ): List<PackageInfo> {
        // 1. 优先尝试通过 Shizuku 的 IPackageManager 获取
        val result = safeInvokeShizuku {
            if (AndroidTarget.CINNAMON_BUN) {
                value.getInstalledPackagesV17(flags.toLong(), userId).list
            } else if (AndroidTarget.TIRAMISU) {
                value.getInstalledPackages(flags.toLong(), userId).list
            } else {
                value.getInstalledPackages(flags, userId).list
            }
        }

        // 2. 如果获取成功且列表不为空，则直接返回
        if (result != null && result.isNotEmpty()) {
            return result
        }

        // 3. 如果获取失败或列表为空，则打印日志并降级使用 shell 命令
        android.util.Log.w("SafePackageManager", "IPackageManager 获取列表失败或为空，降级使用 pm 命令")
        return getPackagesViaShellCommand()
    }

    @Suppress("unused")
    fun getPackageInfo(
        packageName: String,
        flags: Int,
        userId: Int,
    ): PackageInfo? = safeInvokeShizuku {
        if (AndroidTarget.TIRAMISU) {
            value.getPackageInfo(packageName, flags.toLong(), userId)
        } else {
            value.getPackageInfo(packageName, flags, userId)
        }
    }

    fun getApplicationEnabledSetting(
        packageName: String,
        userId: Int,
    ): Int = safeInvokeShizuku {
        value.getApplicationEnabledSetting(packageName, userId)
    } ?: 0

    private fun grantRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int = currentUserId,
    ) = safeInvokeShizuku {
        value.grantRuntimePermission(
            packageName,
            permissionName,
            userId
        )
    }

    private fun grantSelfPermission(name: String, skipCheck: Boolean = false) {
        if (!skipCheck) {
            if (app.checkGrantedPermission(name)) return
        }
        grantRuntimePermission(
            packageName = META.appId,
            permissionName = name,
        )
    }

    fun allowAllSelfPermission() {
        if (canUseGetInstalledApps && !canQueryPkgState.value) {
            try {
                grantSelfPermission("com.android.permission.GET_INSTALLED_APPS", skipCheck = true)
            } catch (_: IllegalArgumentException) {
                canUseGetInstalledApps = false
            }
        }
        grantSelfPermission(Manifest_permission_GET_APP_OPS_STATS)
        grantSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        if (AndroidTarget.TIRAMISU) {
            grantSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
