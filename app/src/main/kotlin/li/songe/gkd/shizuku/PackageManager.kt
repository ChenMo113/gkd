package li.songe.gkd.shizuku

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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

        // 备用 Context 获取方式：从 Application 或系统服务获取
        private fun getContext(): Context? {
            return try {
                app ?: android.app.ActivityThread.currentApplication()
            } catch (_: Exception) {
                null
            }
        }
    }

    val isSafeMode get() = safeInvokeShizuku { value.isSafeMode }

    /**
     * 获取已安装应用列表
     * 完全使用标准 PackageManager API，不依赖 Shizuku 的 IPackageManager，
     * 以绕过 MIUI 等系统的拦截。
     */
    fun getInstalledPackages(
        flags: Int,
        userId: Int = currentUserId,
    ): List<PackageInfo> {
        val context = getContext()
        if (context == null) {
            android.util.Log.e("SafePackageManager", "无法获取 Context，返回空列表")
            return emptyList()
        }

        return try {
            val pm = context.packageManager
            val applications = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            applications.map { appInfo ->
                PackageInfo().apply {
                    packageName = appInfo.packageName
                    // 复制完整的 ApplicationInfo 对象，避免后续引用空字段
                    applicationInfo = appInfo

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

    // 以下方法保持不变，但确保它们不会因 context 缺失而崩溃
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
