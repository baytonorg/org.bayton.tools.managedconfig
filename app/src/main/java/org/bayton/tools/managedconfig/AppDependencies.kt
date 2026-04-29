package org.bayton.tools.managedconfig

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
}

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
  private val restrictionsManager: RestrictionsManager =
    context.applicationContext.getSystemService(RestrictionsManager::class.java)

  override fun currentRestrictions(): Bundle =
    restrictionsManager.applicationRestrictions ?: Bundle()

  override suspend fun importSchema(packageName: String): List<RestrictionEntry> =
    restrictionsManager.getManifestRestrictions(packageName).orEmpty()
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
