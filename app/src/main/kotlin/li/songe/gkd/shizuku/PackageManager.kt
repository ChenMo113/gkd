package li.songe.gkd.shizuku

import android.Manifest
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import li.songe.gkd.META
import li.songe.gkd.app
import li.songe.gkd.permission.Manifest_permission_GET_APP_OPS_STATS
import li.songe.gkd.permission.canQueryPkgState
import li.songe.gkd.util.AndroidTarget

class SafePackageManager(private val value: IPackageManager) {
    companion object {
        fun newBinder() = getShizukuService("package")?.let {
            SafePackageManager(IPackageManager.Stub.asInterface(it))
        }

        private var canUseGetInstalledApps = true
    }

    val isSafeMode get() = safeInvokeShizuku { value.isSafeMode }

    /**
     * 获取已安装应用列表
     * 完全使用标准 PackageManager API，绕过 Shizuku 拦截，
     * 与 Owndroid 的实现方式一致。
     */
    fun getInstalledPackages(
        flags: Int,
        userId: Int = currentUserId,
    ): List<PackageInfo> {
        // 防止 app 全局变量未初始化
        if (app == null) {
            android.util.Log.e("SafePackageManager", "app 为 null，无法获取列表")
            return emptyList()
        }

        return try {
            val pm = app.packageManager
            val applications = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            applications.map { appInfo ->
                PackageInfo().apply {
                    packageName = appInfo.packageName
                    applicationInfo = appInfo  // 复制完整 ApplicationInfo，避免后续空引用
                    // 尝试填充版本信息（可选）
                    try {
                        val pkgInfo = pm.getPackageInfo(packageName, 0)
                        versionName = pkgInfo.versionName
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pkgInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pkgInfo.versionCode
                        }
                    } catch (_: PackageManager.NameNotFoundException) {
                        // 忽略
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SafePackageManager", "获取列表失败", e)
            emptyList()
        }
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
