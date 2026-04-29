package org.bayton.tools.managedconfig

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyManager.DELEGATION_APP_RESTRICTIONS
import android.content.Context
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.enterprise.feedback.KeyedAppStatesReporter

interface ManagedConfigRepository {
  fun currentRestrictions(): Bundle
  suspend fun importSchema(packageName: String): List<RestrictionEntry>
  suspend fun fetchManagedRestrictions(packageName: String): ManagedRestrictionsFetchResult
}

data class ManagedRestrictionsFetchResult(
  val isAvailable: Boolean,
  val bundle: Bundle = Bundle(),
  val message: String? = null,
)

interface InstalledAppsRepository {
  suspend fun listApps(): List<InstalledAppOption>
  suspend fun findApp(packageName: String): InstalledAppOption?
}

interface KeyedAppStatesPublisher {
  fun publish(states: Collection<androidx.enterprise.feedback.KeyedAppState>)
}

class AndroidManagedConfigRepository(
  context: Context,
) : ManagedConfigRepository {
  private val appContext = context.applicationContext
  private val restrictionsManager: RestrictionsManager =
    appContext.getSystemService(RestrictionsManager::class.java)
  private val devicePolicyManager: DevicePolicyManager =
    appContext.getSystemService(DevicePolicyManager::class.java)

  override fun currentRestrictions(): Bundle =
    restrictionsManager.applicationRestrictions ?: Bundle()

  override suspend fun importSchema(packageName: String): List<RestrictionEntry> =
    restrictionsManager.getManifestRestrictions(packageName).orEmpty()

  override suspend fun fetchManagedRestrictions(packageName: String): ManagedRestrictionsFetchResult {
    if (packageName == appContext.packageName) {
      return ManagedRestrictionsFetchResult(
        isAvailable = true,
        bundle = currentRestrictions(),
      )
    }

    val scopes = devicePolicyManager.getDelegatedScopes(null, appContext.packageName)
    if (!scopes.contains(DELEGATION_APP_RESTRICTIONS)) {
      return ManagedRestrictionsFetchResult(
        isAvailable = false,
        message = "Delegated app-restrictions scope not available.",
      )
    }

    return try {
      ManagedRestrictionsFetchResult(
        isAvailable = true,
        bundle = devicePolicyManager.getApplicationRestrictions(null, packageName),
      )
    } catch (securityException: SecurityException) {
      ManagedRestrictionsFetchResult(
        isAvailable = false,
        message = securityException.message ?: "Unable to read managed restrictions for $packageName.",
      )
    }
  }
}

class AndroidInstalledAppsRepository(
  private val context: Context,
) : InstalledAppsRepository {
  private val packageManager: PackageManager = context.packageManager

  override suspend fun listApps(): List<InstalledAppOption> {
    @Suppress("DEPRECATION")
    val installedApplications =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(
          PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
      } else {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
      }

    return installedApplications
      .map(::toInstalledAppOption)
      .sortedWith(
        compareBy<InstalledAppOption, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
          .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName },
      )
  }

  override suspend fun findApp(packageName: String): InstalledAppOption? =
    runCatching {
      val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }
      toInstalledAppOption(info)
    }.getOrNull()

  private fun toInstalledAppOption(info: ApplicationInfo): InstalledAppOption =
    InstalledAppOption(
      label = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName },
      packageName = info.packageName,
      icon = packageManager.getApplicationIcon(info),
    )
}

class AndroidKeyedAppStatesPublisher(
  context: Context,
) : KeyedAppStatesPublisher {
  private val reporter by lazy { KeyedAppStatesReporter.create(context.applicationContext) }

  override fun publish(states: Collection<androidx.enterprise.feedback.KeyedAppState>) {
    reporter.setStates(states)
  }
}
