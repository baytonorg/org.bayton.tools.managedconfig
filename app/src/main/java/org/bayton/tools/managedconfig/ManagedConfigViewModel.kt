package org.bayton.tools.managedconfig

import android.app.Application
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.enterprise.feedback.KeyedAppStatesReporter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ManagedConfigViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private val appContext = application.applicationContext
  private val restrictionsManager by lazy { appContext.getSystemService(RestrictionsManager::class.java) }
  private val keyedAppStatesReporter by lazy { KeyedAppStatesReporter.create(appContext) }
  private val isDebuggable by lazy {
    (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  private var currentOverrideJson: String? = null
  private var currentParsedOverride: ParsedOverride? = null
  private var lastReportedManagedSignature: String? = null
  private var lastFeedbackStatusMessage: String = "No keyed app states reported yet"
  private var lastFeedbackUpdatedAt: String = ""
  private var availableSchemaApps: List<InstalledAppOption> = emptyList()
  private var selectedImportedApp: InstalledAppOption? = null
  private var importedSchemaDefinitions: List<ManagedConfigDefinition> = emptyList()
  private var importedSchemaError: String? = null

  private val _uiState = MutableStateFlow(ManagedConfigUiState())
  val uiState: StateFlow<ManagedConfigUiState> = _uiState.asStateFlow()

  private val _uiEvents = MutableSharedFlow<ManagedConfigUiEvent>()
  val uiEvents: SharedFlow<ManagedConfigUiEvent> = _uiEvents.asSharedFlow()

  fun loadInstalledApps() {
    val packageManager = appContext.packageManager
    @Suppress("DEPRECATION")
    val installedApplications =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getInstalledApplications(
          PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
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

  fun restoreUiState(
    selectedImportedPackage: String?,
    localOverrideJson: String?,
  ) {
    if (!selectedImportedPackage.isNullOrBlank()) {
      selectImportedSchemaPackage(selectedImportedPackage)
    }
    if (!localOverrideJson.isNullOrBlank()) {
      applyLocalOverride(localOverrideJson, "Restored validation input")
    }
  }

  fun handleIncomingIntent(intent: android.content.Intent?) {
    val json = intent?.getStringExtra(EXTRA_MANAGED_CONFIG_JSON) ?: return
    intent.removeExtra(EXTRA_MANAGED_CONFIG_JSON)
    if (!isDebuggable) {
      refreshRestrictions("Ignored external test injection in non-debug build")
      return
    }
    applyLocalOverride(json, "Debug intent injection")
  }

  fun refreshRestrictions(source: String) {
    val rawManagedRestrictions = restrictionsManager.applicationRestrictions ?: Bundle()
    val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
    val managedRestrictions = normalizedManagedConfig.normalizedBundle
    reportManagedConfigFeedbackIfNeeded(rawManagedRestrictions)
    val localSimulationRestrictions = currentParsedOverride?.bundle ?: Bundle()

    _uiState.value =
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

  fun applyLocalOverride(
    rawJson: String,
    source: String = "Local override",
  ) {
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
        _uiState.value =
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
        _uiState.value =
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

  fun clearLocalOverride(source: String = "Local override cleared") {
    currentOverrideJson = null
    currentParsedOverride = null
    refreshRestrictions(source)
  }

  fun selectImportedSchemaPackage(packageName: String) {
    val selectedApp =
      availableSchemaApps.firstOrNull { it.packageName == packageName }
        ?: buildInstalledAppOption(packageName)
        ?: return
    selectedImportedApp = selectedApp
    runCatching {
      restrictionsManager
        .getManifestRestrictions(packageName)
        ?.flatMap(RestrictionEntry::toManagedConfigDefinitions)
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

  fun loadSampleOverride() {
    loadSampleOverride(
      sampleJson = sampleOverrideJson,
      source = "Sample override loaded",
      toastMessage = "Valid sample loaded",
    )
  }

  fun loadInvalidSampleOverride() {
    loadSampleOverride(
      sampleJson = invalidSampleOverrideJson,
      source = "Invalid sample override loaded",
      toastMessage = "Long press: invalid sample loaded",
    )
  }

  fun selectedImportedPackage(): String? = selectedImportedApp?.packageName

  fun currentOverrideJson(): String? = currentOverrideJson

  private fun loadSampleOverride(
    sampleJson: String,
    source: String,
    toastMessage: String,
  ) {
    if (selectedImportedApp?.packageName != appContext.packageName) {
      selectImportedSchemaPackage(appContext.packageName)
    }
    applyLocalOverride(sampleJson, source)
    viewModelScope.launch {
      _uiEvents.emit(ManagedConfigUiEvent.ToastMessage(toastMessage))
    }
  }

  private fun buildInstalledAppOption(targetPackageName: String): InstalledAppOption? {
    val packageManager = appContext.packageManager
    return runCatching {
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
  }

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
