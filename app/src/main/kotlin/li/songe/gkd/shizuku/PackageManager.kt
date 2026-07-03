package li.songe.gkd.shizuku

import android.Manifest
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
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
     * 降级方案：使用标准 PackageManager API 获取应用列表
     * 当 Shizuku API 被系统拦截时使用，绕过 MIUI 的权限限制
     * 借鉴 Owndroid 的实现方式
     */
    private fun getPackagesViaStandardApi(): List<PackageInfo> {
        return try {
            val packages = app.packageManager.getInstalledApplications(0)
            packages.map { applicationInfo ->
                PackageInfo().apply {
                    packageName = applicationInfo.packageName
                    // 这里只保留包名，足以让 GKD 显示列表
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SafePackageManager", "标准 API 获取列表失败", e)
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

        // 3. 如果获取失败或列表为空，则打印日志并降级使用标准 API
        android.util.Log.w("SafePackageManager", "IPackageManager 获取列表失败或为空，降级使用标准 PackageManager API")
        return getPackagesViaStandardApi()
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
