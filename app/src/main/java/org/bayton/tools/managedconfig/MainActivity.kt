package org.bayton.tools.managedconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.enterprise.feedback.KeyedAppStatesReporter
import org.bayton.tools.managedconfig.theme.BaytonManagedConfigTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
  private val restrictionsManager by lazy { getSystemService(RestrictionsManager::class.java) }
  private val keyedAppStatesReporter by lazy { KeyedAppStatesReporter.create(this) }
  private val isDebuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
  private var receiverRegistered = false
  private var currentOverrideJson: String? = null
  private var currentParsedOverride: ParsedOverride? = null
  private var lastReportedManagedSignature: String? = null
  private var lastFeedbackStatusMessage: String = "No keyed app states reported yet"
  private var lastFeedbackUpdatedAt: String = ""
  private var availableSchemaApps: List<InstalledAppOption> = emptyList()
  private var selectedImportedApp: InstalledAppOption? = null
  private var importedSchemaDefinitions: List<ManagedConfigDefinition> = emptyList()
  private var importedSchemaError: String? = null
  private var uiState by mutableStateOf(ManagedConfigUiState())

  private val restrictionsReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        refreshRestrictions("Restrictions changed broadcast")
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)

    enableEdgeToEdge(
      statusBarStyle =
        SystemBarStyle.auto(
          LIGHT_SYSTEM_BAR_SCRIM,
          DARK_SYSTEM_BAR_SCRIM,
        ),
      navigationBarStyle =
        SystemBarStyle.auto(
          LIGHT_SYSTEM_BAR_SCRIM,
          DARK_SYSTEM_BAR_SCRIM,
        ),
    )
    setContent {
      BaytonManagedConfigTheme {
        ManagedConfigScreen(
          uiState = uiState,
          knownConfigs = managedConfigDefinitions,
          onApplyLocalOverride = ::applyLocalOverride,
          onClearLocalOverride = ::clearLocalOverride,
          onLoadSampleOverride = ::loadSampleOverride,
          onLoadInvalidSampleOverride = ::loadInvalidSampleOverride,
          onSelectImportedSchemaPackage = ::selectImportedSchemaPackage,
        )
      }
    }

    loadInstalledApps()
    restoreUiState(savedInstanceState)
    handleIncomingIntent(intent)
  }

  override fun onStart() {
    super.onStart()
    if (!receiverRegistered) {
      ContextCompat.registerReceiver(
        this,
        restrictionsReceiver,
        IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
      receiverRegistered = true
    }
  }

  override fun onResume() {
    super.onResume()
    refreshRestrictions("Activity resume")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  override fun onStop() {
    if (receiverRegistered) {
      unregisterReceiver(restrictionsReceiver)
      receiverRegistered = false
    }
    super.onStop()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_SELECTED_IMPORTED_PACKAGE, selectedImportedApp?.packageName)
    outState.putString(STATE_LOCAL_OVERRIDE_JSON, currentOverrideJson)
  }

  private fun refreshRestrictions(source: String) {
    val rawManagedRestrictions = restrictionsManager.applicationRestrictions ?: Bundle()
    val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
    val managedRestrictions = normalizedManagedConfig.normalizedBundle
    reportManagedConfigFeedbackIfNeeded(rawManagedRestrictions)
    val localSimulationRestrictions = currentParsedOverride?.bundle ?: Bundle()

    uiState =
      buildUiState(
        effectiveRestrictions = localSimulationRestrictions,
        managedRestrictions = managedRestrictions,
        rawManagedRestrictions = rawManagedRestrictions,
        managedRuntimeStructure = normalizedManagedConfig.structure.label,
        managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
        localOverrideJson = currentOverrideJson.orEmpty(),
        localOverrideFormat = currentParsedOverride?.format?.label,
        localShapeHighlights = currentParsedOverride?.shapeHighlights?.map(::formatBundleArrayShapeHighlight).orEmpty(),
        keyedAppStatesStatus = lastFeedbackStatusMessage,
        keyedAppStatesUpdatedAt = lastFeedbackUpdatedAt,
        source = source,
        importableApps = availableSchemaApps,
        selectedImportedApp = selectedImportedApp,
        importedSchemaDefinitions = importedSchemaDefinitions,
        importedSchemaError = importedSchemaError,
      )
  }

  private fun handleIncomingIntent(intent: Intent?) {
    val json = intent?.getStringExtra(EXTRA_MANAGED_CONFIG_JSON) ?: return
    intent.removeExtra(EXTRA_MANAGED_CONFIG_JSON)
    setIntent(intent)
    if (!isDebuggable) {
      refreshRestrictions("Ignored external test injection in non-debug build")
      return
    }
    applyLocalOverride(json, "Debug intent injection")
  }

  private fun restoreUiState(savedInstanceState: Bundle?) {
    val restoredImportedPackage = savedInstanceState?.getString(STATE_SELECTED_IMPORTED_PACKAGE)
    val restoredOverrideJson = savedInstanceState?.getString(STATE_LOCAL_OVERRIDE_JSON)

    if (!restoredImportedPackage.isNullOrBlank()) {
      selectImportedSchemaPackage(restoredImportedPackage)
    }
    if (!restoredOverrideJson.isNullOrBlank()) {
      applyLocalOverride(restoredOverrideJson, "Restored validation input")
    }
  }

  private fun applyLocalOverride(rawJson: String, source: String = "Local override") {
    val normalized = rawJson.trim()
    if (normalized.isEmpty()) {
      clearLocalOverride("$source cleared")
      return
    }

    runCatching { parseJsonBundle(normalized) }
      .onSuccess { parsedOverride ->
        currentOverrideJson = normalized
        currentParsedOverride = parsedOverride
        val rawManagedRestrictions = restrictionsManager.applicationRestrictions ?: Bundle()
        val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
        val managedRestrictions = normalizedManagedConfig.normalizedBundle
        uiState =
          buildUiState(
            effectiveRestrictions = parsedOverride.bundle,
            managedRestrictions = managedRestrictions,
            rawManagedRestrictions = rawManagedRestrictions,
            managedRuntimeStructure = normalizedManagedConfig.structure.label,
            managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
            localOverrideJson = normalized,
            localOverrideFormat = parsedOverride.format.label,
            localShapeHighlights = parsedOverride.shapeHighlights.map(::formatBundleArrayShapeHighlight),
            keyedAppStatesStatus = lastFeedbackStatusMessage,
            keyedAppStatesUpdatedAt = lastFeedbackUpdatedAt,
            source = source,
            importableApps = availableSchemaApps,
            selectedImportedApp = selectedImportedApp,
            importedSchemaDefinitions = importedSchemaDefinitions,
            importedSchemaError = importedSchemaError,
          )
      }.onFailure { error ->
        currentOverrideJson = normalized
        currentParsedOverride = null
        val rawManagedRestrictions = restrictionsManager.applicationRestrictions ?: Bundle()
        val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
        val managedRestrictions = normalizedManagedConfig.normalizedBundle
        uiState =
          buildUiState(
            effectiveRestrictions = Bundle(),
            managedRestrictions = managedRestrictions,
            rawManagedRestrictions = rawManagedRestrictions,
            managedRuntimeStructure = normalizedManagedConfig.structure.label,
            managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
            localOverrideJson = normalized,
            localOverrideFormat = detectOverrideFormat(normalized).label,
            localShapeHighlights = emptyList(),
            keyedAppStatesStatus = lastFeedbackStatusMessage,
            keyedAppStatesUpdatedAt = lastFeedbackUpdatedAt,
            source = source,
            importableApps = availableSchemaApps,
            selectedImportedApp = selectedImportedApp,
            importedSchemaDefinitions = importedSchemaDefinitions,
            importedSchemaError = importedSchemaError,
            localOverrideError = error.message ?: "Failed to parse local override JSON.",
          )
      }
  }

  private fun clearLocalOverride(source: String = "Local override cleared") {
    currentOverrideJson = null
    currentParsedOverride = null
    refreshRestrictions(source)
  }

  private fun selectImportedSchemaPackage(packageName: String) {
    val selectedApp =
      availableSchemaApps.firstOrNull { it.packageName == packageName }
        ?: buildInstalledAppOption(packageName)
        ?: return
    selectedImportedApp = selectedApp
    runCatching {
      restrictionsManager
        .getManifestRestrictions(packageName)
        ?.flatMap { it.toManagedConfigDefinitions() }
        .orEmpty()
    }.onSuccess { definitions ->
      importedSchemaDefinitions = definitions
      importedSchemaError =
        if (definitions.isEmpty()) {
          "No managed configuration schema declared by ${selectedApp.packageName}."
        } else {
          null
        }
      refreshRestrictions("Imported schema selected")
    }.onFailure { error ->
      importedSchemaDefinitions = emptyList()
      importedSchemaError = "Unable to import schema from ${selectedApp.packageName}: ${error.message ?: "unknown error"}"
      refreshRestrictions("Imported schema import failed")
    }
  }

  private fun loadSampleOverride() {
    loadSampleOverride(
      sampleJson = sampleOverrideJson,
      source = "Sample override loaded",
      toastMessage = "Valid sample loaded",
    )
  }

  private fun loadInvalidSampleOverride() {
    loadSampleOverride(
      sampleJson = invalidSampleOverrideJson,
      source = "Invalid sample override loaded",
      toastMessage = "Long press: invalid sample loaded",
    )
  }

  private fun loadSampleOverride(sampleJson: String, source: String, toastMessage: String) {
    if (selectedImportedApp?.packageName != packageName) {
      selectImportedSchemaPackage(packageName)
    }
    applyLocalOverride(sampleJson, source)
    Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
  }

  @Suppress("DEPRECATION")
  private fun loadInstalledApps() {
    val installedApplications =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
      } else {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
      }

    availableSchemaApps =
      installedApplications
        .map { info ->
          InstalledAppOption(
            label = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName },
            packageName = info.packageName,
            icon = packageManager.getApplicationIcon(info),
          )
        }.sortedWith(
          compareBy<InstalledAppOption, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName },
        )
  }

  private fun buildInstalledAppOption(targetPackageName: String): InstalledAppOption? =
    runCatching {
      val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          packageManager.getApplicationInfo(
            targetPackageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          packageManager.getApplicationInfo(targetPackageName, PackageManager.GET_META_DATA)
        }

      InstalledAppOption(
        label = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName },
        packageName = info.packageName,
        icon = packageManager.getApplicationIcon(info),
      )
    }.getOrNull()

  private fun reportManagedConfigFeedbackIfNeeded(managedRestrictions: Bundle) {
    if (managedRestrictions.isEmpty) {
      if (lastReportedManagedSignature != null) {
        val states = buildManagedConfigFeedbackStates(Bundle(), managedConfigDefinitions)
        val timestamp = nowTimestamp()
        runCatching { keyedAppStatesReporter.setStates(states) }
          .onSuccess {
            lastReportedManagedSignature = EMPTY_MANAGED_CONFIG_SIGNATURE
            lastFeedbackStatusMessage = "No keyed app states reported yet"
            lastFeedbackUpdatedAt = ""
          }.onFailure { error ->
            lastFeedbackStatusMessage = "Failed to clear keyed app states: ${error.message ?: "unknown error"}"
            lastFeedbackUpdatedAt = timestamp
          }
      } else {
        lastFeedbackStatusMessage = "No keyed app states reported yet"
        lastFeedbackUpdatedAt = ""
      }
      return
    }

    val signature = feedbackSignature(managedRestrictions)
    if (signature == lastReportedManagedSignature) return

    val states = buildManagedConfigFeedbackStates(managedRestrictions, managedConfigDefinitions)
    val timestamp = nowTimestamp()
    runCatching { keyedAppStatesReporter.setStates(states) }
      .onSuccess {
        lastReportedManagedSignature = signature
        lastFeedbackStatusMessage = "Reported ${states.size} keyed app states to the Android Enterprise feedback channel."
        lastFeedbackUpdatedAt = timestamp
      }.onFailure { error ->
        lastFeedbackStatusMessage = "Failed to report keyed app states: ${error.message ?: "unknown error"}"
        lastFeedbackUpdatedAt = timestamp
      }
  }

}

internal const val EXTRA_MANAGED_CONFIG_JSON = "managed_config_json"
internal const val EMPTY_MANAGED_CONFIG_SIGNATURE = "__empty_managed_config__"
private const val STATE_SELECTED_IMPORTED_PACKAGE = "state_selected_imported_package"
private const val STATE_LOCAL_OVERRIDE_JSON = "state_local_override_json"
private const val LIGHT_SYSTEM_BAR_SCRIM = 0xFFF7F7F7.toInt()
private const val DARK_SYSTEM_BAR_SCRIM = 0xFF141218.toInt()

internal fun buildUiState(
  effectiveRestrictions: Bundle,
  managedRestrictions: Bundle,
  rawManagedRestrictions: Bundle,
  managedRuntimeStructure: String,
  managedShapeHighlights: List<String>,
  localOverrideJson: String,
  localOverrideFormat: String?,
  localShapeHighlights: List<String>,
  keyedAppStatesStatus: String,
  keyedAppStatesUpdatedAt: String,
  source: String,
  importableApps: List<InstalledAppOption> = emptyList(),
  selectedImportedApp: InstalledAppOption? = null,
  importedSchemaDefinitions: List<ManagedConfigDefinition> = emptyList(),
  importedSchemaError: String? = null,
  localOverrideError: String? = null,
): ManagedConfigUiState {
  val managedKeys = managedRestrictions.keySet().sorted()
  val effectiveKeys = effectiveRestrictions.keySet().sorted()
  val managedShapeHighlightByKey = managedShapeHighlights.associateBy { it.substringBefore(':') }
  val localShapeHighlightByKey = localShapeHighlights.associateBy { it.substringBefore(':') }
  val configuredItems =
    managedConfigDefinitions.map { definition ->
      val isManagedConfigured = managedRestrictions.containsKey(definition.key)
      val isEffectiveConfigured = effectiveRestrictions.containsKey(definition.key)
      val managedShapeHighlight = managedShapeHighlightByKey[definition.key]
      val localShapeHighlight = localShapeHighlightByKey[definition.key]
      val hasManagedSchemaError =
        managedShapeHighlight != null && isNonCanonicalBundleArrayShapeText(managedShapeHighlight)
      val hasEffectiveSchemaError =
        localShapeHighlight != null && isNonCanonicalBundleArrayShapeText(localShapeHighlight)
      ManagedConfigItem(
        key = definition.key,
        payloadKey = definition.payloadKey,
        title = definition.title,
        description = definition.description,
        typeLabel = definition.type.label,
        depth = definition.depth,
        isManagedConfigured = isManagedConfigured,
        isEffectiveConfigured = isEffectiveConfigured,
        managedValue =
          if (isManagedConfigured) {
            if (rawManagedRestrictions.containsKey(definition.key)) {
              formatDeliveredValueForKey(definition.key, rawManagedRestrictions.valueForKey(definition.key))
            } else {
              formatValueForDisplay(managedRestrictions.valueForKey(definition.key))
            }
          } else {
            "Not set by RestrictionsManager"
          },
        effectiveValue =
          if (isEffectiveConfigured) {
            formatDeliveredValueForKey(definition.key, effectiveRestrictions.valueForKey(definition.key))
          } else {
            "Not set"
          },
        managedHasSchemaError = hasManagedSchemaError,
        managedSchemaChipLabel = if (hasManagedSchemaError) "Bundle key present" else null,
        managedSchemaMessage = if (hasManagedSchemaError) managedShapeHighlight else null,
        effectiveHasSchemaError = hasEffectiveSchemaError,
        effectiveSchemaChipLabel = if (hasEffectiveSchemaError) "Bundle key present" else null,
        effectiveSchemaMessage = if (hasEffectiveSchemaError) localShapeHighlight else null,
      )
    }

  val extraManagedItems =
    managedKeys
      .filterNot { key -> managedConfigDefinitions.any { it.key == key } }
      .map { key ->
        ManagedConfigItem(
          key = key,
          payloadKey = key,
          title = key,
          description = "Additional managed configuration received at runtime.",
          typeLabel = inferManagedConfigValueType(rawManagedRestrictions.valueForKey(key)).label,
          isManagedConfigured = true,
          isEffectiveConfigured = effectiveRestrictions.containsKey(key),
          managedValue =
            if (rawManagedRestrictions.containsKey(key)) {
              formatDeliveredValueForKey(key, rawManagedRestrictions.valueForKey(key))
            } else {
              formatValueForDisplay(managedRestrictions.valueForKey(key))
            },
          effectiveValue =
            if (effectiveRestrictions.containsKey(key)) {
              formatDeliveredValueForKey(key, effectiveRestrictions.valueForKey(key))
            } else {
              "Not set"
            },
          managedHasSchemaError = false,
          managedSchemaChipLabel = null,
          managedSchemaMessage = null,
          effectiveHasSchemaError = false,
          effectiveSchemaChipLabel = null,
          effectiveSchemaMessage = null,
        )
      }

  val extraEffectiveItems =
    effectiveKeys
      .filterNot { key -> managedConfigDefinitions.any { it.key == key } }
      .map { key ->
        ManagedConfigItem(
          key = key,
          payloadKey = key,
          title = key,
          description = "Additional local simulation key.",
          typeLabel = inferManagedConfigValueType(effectiveRestrictions.valueForKey(key)).label,
          isManagedConfigured = managedRestrictions.containsKey(key),
          isEffectiveConfigured = true,
          managedValue =
            if (managedRestrictions.containsKey(key)) {
              if (rawManagedRestrictions.containsKey(key)) {
                formatDeliveredValueForKey(key, rawManagedRestrictions.valueForKey(key))
              } else {
                formatValueForDisplay(managedRestrictions.valueForKey(key))
              }
            } else {
              "Not set by RestrictionsManager"
            },
          effectiveValue = formatDeliveredValueForKey(key, effectiveRestrictions.valueForKey(key)),
          managedHasSchemaError = false,
          managedSchemaChipLabel = null,
          managedSchemaMessage = null,
          effectiveHasSchemaError =
            localShapeHighlightByKey[key]?.let(::isNonCanonicalBundleArrayShapeText) == true,
          effectiveSchemaChipLabel =
            if (localShapeHighlightByKey[key]?.let(::isNonCanonicalBundleArrayShapeText) == true) {
              "Bundle key present"
            } else {
              null
            },
          effectiveSchemaMessage =
            if (localShapeHighlightByKey[key]?.let(::isNonCanonicalBundleArrayShapeText) == true) {
              localShapeHighlightByKey[key]
            } else {
              null
            },
        )
      }

  val activeLocalSchemaDefinitions =
    if (selectedImportedApp != null && importedSchemaDefinitions.isNotEmpty()) {
      importedSchemaDefinitions
    } else {
      emptyList()
    }
  val activeLocalDefinitionKeys = activeLocalSchemaDefinitions.map { it.key }.toSet()
  val localValidationItems =
    activeLocalSchemaDefinitions.map { definition ->
      val resolvedValue = resolveImportedDefinitionValue(effectiveRestrictions, definition)
      val isConfigured = resolvedValue != null
      val localShapeHighlight = localShapeHighlightByKey[definition.payloadKey]
      val hasArrayWrapperSchemaError =
        definition.type == ManagedConfigValueType.BUNDLE_ARRAY &&
          definition.pathWithinRoot.isEmpty() &&
          localShapeHighlight?.let(::isNonCanonicalBundleArrayShapeText) == true
      ManagedConfigItem(
        key = definition.key,
        payloadKey = definition.payloadKey,
        title = definition.title,
        description = definition.description,
        typeLabel = definition.type.label,
        depth = definition.depth,
        isManagedConfigured = false,
        isEffectiveConfigured = isConfigured,
        managedValue = "Not set by RestrictionsManager",
        effectiveValue =
          if (isConfigured) {
            formatImportedDefinitionValue(definition, resolvedValue)
          } else {
            "Not set"
          },
        effectiveHasSchemaError = hasArrayWrapperSchemaError,
        effectiveSchemaChipLabel = if (hasArrayWrapperSchemaError) "Bundle key present" else null,
        effectiveSchemaMessage = if (hasArrayWrapperSchemaError) localShapeHighlight else null,
      )
    } +
      effectiveKeys
        .filterNot(activeLocalDefinitionKeys::contains)
        .map { key ->
          ManagedConfigItem(
            key = key,
            payloadKey = key,
            title = key,
            description =
              if (selectedImportedApp != null) {
                "Additional local simulation key not declared in the selected app schema."
              } else {
                "Additional local simulation key."
              },
            typeLabel = inferManagedConfigValueType(effectiveRestrictions.valueForKey(key)).label,
            depth = 0,
            isManagedConfigured = false,
            isEffectiveConfigured = true,
            managedValue = "Not set by RestrictionsManager",
            effectiveValue = formatDeliveredValueForKey(key, effectiveRestrictions.valueForKey(key)),
            effectiveHasSchemaError =
              localShapeHighlightByKey[key]?.let(::isNonCanonicalBundleArrayShapeText) == true,
            effectiveSchemaChipLabel =
              if (localShapeHighlightByKey[key]?.let(::isNonCanonicalBundleArrayShapeText) == true) {
                "Bundle key present"
              } else {
                null
              },
            effectiveSchemaMessage =
              if (localShapeHighlightByKey[key]?.let(::isNonCanonicalBundleArrayShapeText) == true) {
                localShapeHighlightByKey[key]
              } else {
                null
              },
          )
        }

  val localOverrideActive = localOverrideJson.isNotBlank() && localOverrideError == null

  val effectiveStructuredPayload = formatBundleAsStructuredJson(effectiveRestrictions)
  val managedRawStructuredPayload = formatBundleAsStructuredJson(rawManagedRestrictions)
  val managedNormalizedStructuredPayload = formatBundleAsStructuredJson(managedRestrictions)
  val localPayloadNormalizedDifference = localShapeHighlights.any(::isNonCanonicalBundleArrayShapeText)

  return ManagedConfigUiState(
    hasManagedConfig = managedKeys.isNotEmpty(),
    source = source,
    updatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
    managedConfiguredCount = managedKeys.size,
    effectiveConfiguredCount = effectiveKeys.size,
    managedItems = configuredItems + extraManagedItems,
    effectiveItems = configuredItems + extraEffectiveItems,
    localValidationItems = localValidationItems,
    localValidationConfiguredCount = localValidationItems.count { it.isEffectiveConfigured },
    localValidationSupportedCount = activeLocalSchemaDefinitions.size,
    effectivePayload = effectiveStructuredPayload,
    managedPayload = managedNormalizedStructuredPayload,
    effectiveRuntimePayload = formatBundleRuntimePreview(effectiveRestrictions),
    managedRuntimePayload = formatBundleRuntimePreview(rawManagedRestrictions),
    effectivePayloadNormalizedDifference = localPayloadNormalizedDifference,
    managedPayloadNormalizedDifference = managedRawStructuredPayload != managedNormalizedStructuredPayload,
    managedPayloadFormat = if (managedKeys.isNotEmpty()) managedRuntimeStructure else "No managed payload",
    managedShapeHighlights = managedShapeHighlights,
    localOverrideJson = localOverrideJson,
    localOverrideActive = localOverrideActive,
    localOverrideFormat = localOverrideFormat ?: "None",
    localShapeHighlights = localShapeHighlights,
    keyedAppStatesStatus = keyedAppStatesStatus,
    keyedAppStatesUpdatedAt = keyedAppStatesUpdatedAt,
    importableApps = importableApps,
    selectedImportedAppLabel = selectedImportedApp?.label,
    selectedImportedAppPackage = selectedImportedApp?.packageName,
    importedSchemaError = importedSchemaError,
    localOverrideError = localOverrideError,
  )
}

private fun nowTimestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun formatBundleArrayShapeHighlight(highlight: BundleArrayShapeHighlight): String =
  when (highlight.variant) {
    BundleArrayShapeVariant.DIRECT_ITEM_BUNDLES ->
      "${highlight.parentKey}: no item wrapper key"
    BundleArrayShapeVariant.WRAPPED_ITEM_BUNDLE_KEY ->
      "${highlight.parentKey}: wrapper key ${highlight.wrapperKey}"
    BundleArrayShapeVariant.MIXED_DIRECT_AND_WRAPPED ->
      "${highlight.parentKey}: mixed item wrapper usage (${highlight.wrapperKey})"
  }

private fun isNonCanonicalBundleArrayShapeText(highlight: String): Boolean =
  highlight.contains(": wrapper key ", ignoreCase = true) ||
    highlight.contains("mixed item wrapper usage", ignoreCase = true)

data class ManagedConfigDefinition(
  val key: String,
  val title: String,
  val description: String,
  val type: ManagedConfigValueType,
  val payloadKey: String = key,
  val pathWithinRoot: List<ImportedSchemaPathSegment> = emptyList(),
  val depth: Int = 0,
)

sealed interface ImportedSchemaPathSegment {
  data class Key(val value: String) : ImportedSchemaPathSegment
  data object ArrayItems : ImportedSchemaPathSegment
}

enum class ManagedConfigValueType(val label: String) {
  BOOL("bool"),
  STRING("string"),
  INTEGER("integer"),
  CHOICE("choice"),
  MULTISELECT("multi-select"),
  HIDDEN("hidden"),
  BUNDLE("bundle"),
  BUNDLE_ARRAY("bundle_array"),
}

data class ManagedConfigItem(
  val key: String,
  val payloadKey: String = key,
  val title: String,
  val description: String,
  val typeLabel: String,
  val depth: Int = 0,
  val isManagedConfigured: Boolean,
  val isEffectiveConfigured: Boolean,
  val managedValue: String,
  val effectiveValue: String,
  val managedHasSchemaError: Boolean = false,
  val managedSchemaChipLabel: String? = null,
  val managedSchemaMessage: String? = null,
  val effectiveHasSchemaError: Boolean = false,
  val effectiveSchemaChipLabel: String? = null,
  val effectiveSchemaMessage: String? = null,
)

data class ManagedConfigUiState(
  val hasManagedConfig: Boolean = false,
  val source: String = "App start",
  val updatedAt: String = "",
  val managedConfiguredCount: Int = 0,
  val effectiveConfiguredCount: Int = 0,
  val managedItems: List<ManagedConfigItem> = emptyList(),
  val effectiveItems: List<ManagedConfigItem> = emptyList(),
  val localValidationItems: List<ManagedConfigItem> = emptyList(),
  val localValidationConfiguredCount: Int = 0,
  val localValidationSupportedCount: Int = 0,
  val effectivePayload: String = "{}",
  val managedPayload: String = "{}",
  val effectiveRuntimePayload: String = "[]",
  val managedRuntimePayload: String = "[]",
  val effectivePayloadNormalizedDifference: Boolean = false,
  val managedPayloadNormalizedDifference: Boolean = false,
  val managedPayloadFormat: String = "No managed payload",
  val managedShapeHighlights: List<String> = emptyList(),
  val localOverrideJson: String = "",
  val localOverrideActive: Boolean = false,
  val localOverrideFormat: String = "None",
  val localShapeHighlights: List<String> = emptyList(),
  val keyedAppStatesStatus: String = "No keyed app states reported yet",
  val keyedAppStatesUpdatedAt: String = "",
  val importableApps: List<InstalledAppOption> = emptyList(),
  val selectedImportedAppLabel: String? = null,
  val selectedImportedAppPackage: String? = null,
  val importedSchemaError: String? = null,
  val localOverrideError: String? = null,
)

data class InstalledAppOption(
  val label: String,
  val packageName: String,
  val icon: android.graphics.drawable.Drawable?,
)

val managedConfigDefinitions =
  listOf(
    ManagedConfigDefinition("my_bool_key", "My BOOL", "Boolean baseline managed configuration.", ManagedConfigValueType.BOOL),
    ManagedConfigDefinition("my_string_key", "My STRING", "String baseline managed configuration.", ManagedConfigValueType.STRING),
    ManagedConfigDefinition("my_integer_key", "My INTEGER", "Integer baseline managed configuration.", ManagedConfigValueType.INTEGER),
    ManagedConfigDefinition("my_choice_key", "My CHOICE", "Single-select managed configuration.", ManagedConfigValueType.CHOICE),
    ManagedConfigDefinition("my_multiselect_key", "My MULTISELECT", "Multi-select managed configuration.", ManagedConfigValueType.MULTISELECT),
    ManagedConfigDefinition("my_hidden_key", "My HIDDEN", "Hidden payload managed configuration.", ManagedConfigValueType.HIDDEN),
    ManagedConfigDefinition("my_bundle_key", "My BUNDLE", "Nested managed configuration bundle.", ManagedConfigValueType.BUNDLE),
    ManagedConfigDefinition("my_bundle_array_key", "My BUNDLE ARRAY", "Managed configuration array of bundles.", ManagedConfigValueType.BUNDLE_ARRAY),
  )

private val sampleOverrideJson =
  """
  {
    "my_bool_key": true,
    "my_string_key": "qa-string",
    "my_integer_key": 37,
    "my_choice_key": "another",
    "my_multiselect_key": ["one", "two"],
    "my_hidden_key": "hidden-test-token",
    "my_bundle_key": {
      "my_bool_key_in_bundle": true,
      "my_string_key_in_bundle": "bundle-string",
      "my_choice_key_in_bundle": "another"
    },
    "my_bundle_array_key": [
      {
        "my_bool_key_in_bundle_array": true,
        "my_string_key_in_bundle_array": "array-item-1",
        "my_choice_key_in_bundle_array": "another"
      },
      {
        "my_bool_key_in_bundle_array": false,
        "my_string_key_in_bundle_array": "array-item-2",
        "my_choice_key_in_bundle_array": ""
      }
    ]
  }
  """.trimIndent()

private val invalidSampleOverrideJson =
  """
  {
    "my_bool_key": true,
    "my_string_key": "qa-string",
    "my_integer_key": 37,
    "my_choice_key": "another",
    "my_multiselect_key": ["one", "two"],
    "my_hidden_key": "hidden-test-token",
    "my_bundle_key": {
      "my_bool_key_in_bundle": true,
      "my_string_key_in_bundle": "bundle-string",
      "my_choice_key_in_bundle": "another"
    },
    "my_bundle_array_key": [
      {
        "my_bundle_array_item": {
          "my_bool_key_in_bundle_array": true,
          "my_string_key_in_bundle_array": "array-item-1",
          "my_choice_key_in_bundle_array": "another"
        }
      },
      {
        "my_bundle_array_item": {
          "my_bool_key_in_bundle_array": false,
          "my_string_key_in_bundle_array": "array-item-2",
          "my_choice_key_in_bundle_array": ""
        }
      }
    ]
  }
  """.trimIndent()

internal fun RestrictionEntry.toManagedConfigDefinitions(
  rootKey: String = key,
  pathWithinRoot: List<ImportedSchemaPathSegment> = emptyList(),
  depth: Int = 0,
): List<ManagedConfigDefinition> {
  val currentPathKey = buildImportedDefinitionKey(rootKey, pathWithinRoot)
  val current =
    ManagedConfigDefinition(
      key = currentPathKey,
      title = title?.ifBlank { key } ?: key,
      description = buildImportedRestrictionDescription(),
      type = type.toManagedConfigValueType(),
      payloadKey = rootKey,
      pathWithinRoot = pathWithinRoot,
      depth = depth,
    )

  val children =
    restrictions
      ?.flatMap { child ->
        when (type) {
          RestrictionEntry.TYPE_BUNDLE ->
            child.toManagedConfigDefinitions(
              rootKey = rootKey,
              pathWithinRoot = pathWithinRoot + ImportedSchemaPathSegment.Key(child.key),
              depth = depth + 1,
            )
          RestrictionEntry.TYPE_BUNDLE_ARRAY ->
            child.toManagedConfigDefinitionsForBundleArray(rootKey, pathWithinRoot, depth + 1)
          else -> emptyList()
        }
      }.orEmpty()

  return listOf(current) + children
}

private fun RestrictionEntry.toManagedConfigDefinitionsForBundleArray(
  rootKey: String,
  parentPath: List<ImportedSchemaPathSegment>,
  depth: Int,
): List<ManagedConfigDefinition> {
  val childType = type.toManagedConfigValueType()

  if (type == RestrictionEntry.TYPE_BUNDLE) {
    return restrictions
      ?.flatMap { nestedChild ->
        nestedChild.toManagedConfigDefinitions(
          rootKey = rootKey,
          pathWithinRoot = parentPath + ImportedSchemaPathSegment.ArrayItems + ImportedSchemaPathSegment.Key(nestedChild.key),
          depth = depth,
        )
      }.orEmpty()
  }

  return toManagedConfigDefinitions(
    rootKey = rootKey,
    pathWithinRoot = parentPath + ImportedSchemaPathSegment.ArrayItems + ImportedSchemaPathSegment.Key(key),
    depth = depth,
  )
}

private fun RestrictionEntry.buildImportedRestrictionDescription(): String {
  val declaredDescription = description?.takeIf { it.isNotBlank() }
  if (declaredDescription != null) return declaredDescription

  val nestedCount = restrictions?.size ?: 0
  val typeLabel = type.toManagedConfigValueType().label
  return if (nestedCount > 0) {
    "Imported $typeLabel managed configuration with $nestedCount nested field${if (nestedCount == 1) "" else "s"}."
  } else {
    "Imported $typeLabel managed configuration."
  }
}

private fun buildImportedDefinitionKey(
  rootKey: String,
  pathWithinRoot: List<ImportedSchemaPathSegment>,
): String {
  if (pathWithinRoot.isEmpty()) return rootKey

  return buildString {
    append(rootKey)
    pathWithinRoot.forEach { segment ->
      when (segment) {
        ImportedSchemaPathSegment.ArrayItems -> append("[]")
        is ImportedSchemaPathSegment.Key -> {
          if (isNotEmpty()) append('.')
          append(segment.value)
        }
      }
    }
  }
}

private fun Int.toManagedConfigValueType(): ManagedConfigValueType =
  when (this) {
    RestrictionEntry.TYPE_BOOLEAN -> ManagedConfigValueType.BOOL
    RestrictionEntry.TYPE_CHOICE -> ManagedConfigValueType.CHOICE
    RestrictionEntry.TYPE_MULTI_SELECT -> ManagedConfigValueType.MULTISELECT
    RestrictionEntry.TYPE_INTEGER -> ManagedConfigValueType.INTEGER
    RestrictionEntry.TYPE_NULL -> ManagedConfigValueType.HIDDEN
    RestrictionEntry.TYPE_BUNDLE -> ManagedConfigValueType.BUNDLE
    RestrictionEntry.TYPE_BUNDLE_ARRAY -> ManagedConfigValueType.BUNDLE_ARRAY
    else -> ManagedConfigValueType.STRING
  }

private fun inferManagedConfigValueType(value: Any?): ManagedConfigValueType =
  when (value) {
    is Boolean -> ManagedConfigValueType.BOOL
    is Int -> ManagedConfigValueType.INTEGER
    is Bundle -> ManagedConfigValueType.BUNDLE
    is Array<*> ->
      when {
        value.all { it is String } -> ManagedConfigValueType.MULTISELECT
        value.all { it is Bundle || it == null } -> ManagedConfigValueType.BUNDLE_ARRAY
        else -> ManagedConfigValueType.STRING
      }
    else -> ManagedConfigValueType.STRING
  }

private fun resolveImportedDefinitionValue(
  rootBundle: Bundle,
  definition: ManagedConfigDefinition,
): Any? {
  if (!rootBundle.containsKey(definition.payloadKey)) return null
  val rootValue = rootBundle.valueForKey(definition.payloadKey)
  if (definition.pathWithinRoot.isEmpty()) return rootValue
  return extractImportedPathValue(rootValue, definition.pathWithinRoot)
}

private fun formatImportedDefinitionValue(
  definition: ManagedConfigDefinition,
  resolvedValue: Any?,
): String {
  val usesArrayItems = definition.pathWithinRoot.any { it is ImportedSchemaPathSegment.ArrayItems }
  if (!usesArrayItems || definition.type == ManagedConfigValueType.BUNDLE_ARRAY) {
    return formatDeliveredValueForKey(definition.payloadKey, resolvedValue)
  }

  val itemValues = resolvedValue as? Array<*> ?: return formatDeliveredValueForKey(definition.payloadKey, resolvedValue)
  if (itemValues.isEmpty()) return "[]"

  return itemValues.mapIndexedNotNull { index, itemValue ->
    formatImportedArrayItemValue("${definition.payloadKey}[$index]", itemValue)
  }.joinToString("\n")
}

private fun formatImportedArrayItemValue(
  prefix: String,
  value: Any?,
): String? =
  when (value) {
    null -> null
    is Bundle -> renderImportedArrayBundleLines(prefix, value).joinToString("\n")
    else -> "$prefix: ${formatValue(value)}"
  }

private fun renderImportedArrayBundleLines(
  prefix: String,
  bundle: Bundle,
): List<String> {
  val lines = mutableListOf<String>()
  bundle.keySet().sorted().forEach { key ->
    when (val childValue = bundle.valueForKey(key)) {
      is Bundle -> {
        lines += "$prefix.$key:"
        renderImportedArrayBundleLines("$prefix.$key", childValue).forEach { nestedLine ->
          lines += "  $nestedLine"
        }
      }
      is Array<*> -> {
        childValue.forEachIndexed { index, arrayValue ->
          val line = formatImportedArrayItemValue("$prefix.$key[$index]", arrayValue)
          if (line != null) {
            lines += line
          }
        }
      }
      else -> lines += "$prefix.$key: ${formatValue(childValue)}"
    }
  }
  return lines
}

private fun extractImportedPathValue(
  currentValue: Any?,
  pathWithinRoot: List<ImportedSchemaPathSegment>,
): Any? {
  val segment = pathWithinRoot.firstOrNull() ?: return currentValue
  val remaining = pathWithinRoot.drop(1)

  return when (segment) {
    is ImportedSchemaPathSegment.Key -> {
      val currentBundle = currentValue as? Bundle ?: return null
      if (!currentBundle.containsKey(segment.value)) return null
      val childValue = currentBundle.valueForKey(segment.value)
      if (remaining.isEmpty()) {
        Bundle().apply { putImportedValue(segment.value, childValue) }
      } else {
        val nestedValue = extractImportedPathValue(childValue, remaining) ?: return null
        Bundle().apply { putImportedValue(segment.value, nestedValue) }
      }
    }
    ImportedSchemaPathSegment.ArrayItems -> {
      val itemBundles = (currentValue as? Array<*>)?.filterIsInstance<Bundle>().orEmpty()
      if (itemBundles.isEmpty()) return null
      val extractedItems =
        itemBundles.mapNotNull { itemBundle ->
          when (val nestedValue = extractImportedPathValue(itemBundle, remaining)) {
            is Bundle -> nestedValue as android.os.Parcelable
            null -> null
            else -> Bundle().apply { putImportedValue("value", nestedValue) } as android.os.Parcelable
          }
        }
      if (extractedItems.isEmpty()) null else extractedItems.toTypedArray()
    }
  }
}

private fun Bundle.putImportedValue(
  key: String,
  value: Any?,
) {
  when (value) {
    null -> putString(key, null)
    is Boolean -> putBoolean(key, value)
    is Int -> putInt(key, value)
    is Long -> putLong(key, value)
    is String -> putString(key, value)
    is Bundle -> putBundle(key, value)
    is Array<*> -> putParcelableArray(key, value.filterIsInstance<android.os.Parcelable>().toTypedArray())
    is BooleanArray -> putBooleanArray(key, value)
    is IntArray -> putIntArray(key, value)
    is LongArray -> putLongArray(key, value)
    is FloatArray -> putFloatArray(key, value)
    is DoubleArray -> putDoubleArray(key, value)
    else -> putString(key, value.toString())
  }
}
