package org.bayton.tools.managedconfig

import android.content.RestrictionEntry
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManagedConfigViewModel(
  private val managedConfigRepository: ManagedConfigRepository,
  private val installedAppsRepository: InstalledAppsRepository,
  private val keyedAppStatesPublisher: KeyedAppStatesPublisher,
  private val currentPackageName: String,
  private val isDebuggable: Boolean,
  private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

  private var currentOverrideJson: String? = savedStateHandle[STATE_LOCAL_OVERRIDE_JSON]
  private var currentParsedOverride: ParsedOverride? = null
  private var lastReportedManagedSignature: String? = null
  private var lastFeedbackStatusMessage: String = "No keyed app states reported yet"
  private var lastFeedbackUpdatedAt: String = ""
  private var availableSchemaApps: List<InstalledAppOption> = emptyList()
  private var selectedImportedApp: InstalledAppOption? = null
  private var importedSchemaDefinitions: List<ManagedConfigDefinition> = emptyList()
  private var importedSchemaError: String? = null
  private var importableAppsLoading = false
  private var importedSchemaLoading = false
  private var initialized = false

  private val _uiState = MutableStateFlow(ManagedConfigUiState())
  val uiState: StateFlow<ManagedConfigUiState> = _uiState.asStateFlow()

  private val _uiEvents = MutableSharedFlow<ManagedConfigUiEvent>()
  val uiEvents: SharedFlow<ManagedConfigUiEvent> = _uiEvents.asSharedFlow()

  fun initialize() {
    if (initialized) return
    initialized = true
    importableAppsLoading = true
    val rawManagedRestrictions = managedConfigRepository.currentRestrictions()
    val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
    rebuildUiState(
      source = "App start",
      effectiveRestrictions = currentParsedOverride?.bundle ?: Bundle(),
      managedRestrictions = normalizedManagedConfig.normalizedBundle,
      rawManagedRestrictions = rawManagedRestrictions,
      managedRuntimeStructure = normalizedManagedConfig.structure.label,
      managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
      localOverrideFormat = currentParsedOverride?.format?.label,
      localShapeHighlights = currentParsedOverride?.shapeHighlights?.map(::formatBundleArrayShapeHighlight).orEmpty(),
    )
    viewModelScope.launch {
      availableSchemaApps = withContext(Dispatchers.IO) { installedAppsRepository.listApps() }
      importableAppsLoading = false
      val restoredPackage = savedStateHandle.get<String>(STATE_SELECTED_IMPORTED_PACKAGE)?.takeIf { it.isNotBlank() }
      if (restoredPackage != null) {
        importSchemaForPackage(restoredPackage, source = "Restored validation target")
      }
      if (!currentOverrideJson.isNullOrBlank()) {
        applyLocalOverride(currentOverrideJson.orEmpty(), "Restored validation input")
      } else {
        refreshRestrictions("App start")
      }
    }
  }

  fun handleIncomingIntent(intent: Intent?) {
    val json = intent?.getStringExtra(EXTRA_MANAGED_CONFIG_JSON) ?: return
    intent.removeExtra(EXTRA_MANAGED_CONFIG_JSON)
    if (!isDebuggable) {
      refreshRestrictions("Ignored external test injection in non-debug build")
      return
    }
    applyLocalOverride(json, "Debug intent injection")
  }

  fun refreshRestrictions(source: String) {
    val rawManagedRestrictions = managedConfigRepository.currentRestrictions()
    val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
    val managedRestrictions = normalizedManagedConfig.normalizedBundle
    reportManagedConfigFeedbackIfNeeded(rawManagedRestrictions)
    val localSimulationRestrictions = currentParsedOverride?.bundle ?: Bundle()
    rebuildUiState(
      source = source,
      effectiveRestrictions = localSimulationRestrictions,
      managedRestrictions = managedRestrictions,
      rawManagedRestrictions = rawManagedRestrictions,
      managedRuntimeStructure = normalizedManagedConfig.structure.label,
      managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
      localOverrideFormat = currentParsedOverride?.format?.label,
      localShapeHighlights = currentParsedOverride?.shapeHighlights?.map(::formatBundleArrayShapeHighlight).orEmpty(),
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
        savedStateHandle[STATE_LOCAL_OVERRIDE_JSON] = normalized
        currentParsedOverride = parsedOverride
        val rawManagedRestrictions = managedConfigRepository.currentRestrictions()
        val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
        val managedRestrictions = normalizedManagedConfig.normalizedBundle
        rebuildUiState(
          source = source,
          effectiveRestrictions = parsedOverride.bundle,
          managedRestrictions = managedRestrictions,
          rawManagedRestrictions = rawManagedRestrictions,
          managedRuntimeStructure = normalizedManagedConfig.structure.label,
          managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
          localOverrideFormat = parsedOverride.format.label,
          localShapeHighlights = parsedOverride.shapeHighlights.map(::formatBundleArrayShapeHighlight),
        )
      }.onFailure { error ->
        currentOverrideJson = normalized
        savedStateHandle[STATE_LOCAL_OVERRIDE_JSON] = normalized
        currentParsedOverride = null
        val rawManagedRestrictions = managedConfigRepository.currentRestrictions()
        val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
        val managedRestrictions = normalizedManagedConfig.normalizedBundle
        rebuildUiState(
          source = source,
          effectiveRestrictions = Bundle(),
          managedRestrictions = managedRestrictions,
          rawManagedRestrictions = rawManagedRestrictions,
          managedRuntimeStructure = normalizedManagedConfig.structure.label,
          managedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight),
          localOverrideFormat = detectOverrideFormat(normalized).label,
          localShapeHighlights = emptyList(),
          localOverrideError = error.message ?: "Failed to parse local override JSON.",
        )
      }
  }

  fun clearLocalOverride(source: String = "Local override cleared") {
    currentOverrideJson = null
    savedStateHandle[STATE_LOCAL_OVERRIDE_JSON] = null
    currentParsedOverride = null
    refreshRestrictions(source)
  }

  fun selectImportedSchemaPackage(
    packageName: String,
    source: String = "Imported schema selected",
  ) {
    viewModelScope.launch {
      importSchemaForPackage(packageName, source)
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

  private fun loadSampleOverride(
    sampleJson: String,
    source: String,
    toastMessage: String,
  ) {
    viewModelScope.launch {
      if (selectedImportedApp?.packageName != currentPackageName) {
        importSchemaForPackage(currentPackageName, source = "Imported schema selected")
      }
      applyLocalOverride(sampleJson, source)
      _uiEvents.emit(ManagedConfigUiEvent.ToastMessage(toastMessage))
    }
  }

  private suspend fun importSchemaForPackage(
    packageName: String,
    source: String,
  ) {
    importedSchemaLoading = true
    importedSchemaError = null
    refreshRestrictions("Loading imported schema")

    val selectedApp =
      availableSchemaApps.firstOrNull { it.packageName == packageName }
        ?: withContext(Dispatchers.IO) { installedAppsRepository.findApp(packageName) }

    if (selectedApp == null) {
      importedSchemaDefinitions = emptyList()
      importedSchemaLoading = false
      importedSchemaError = "Unable to find installed app: $packageName"
      refreshRestrictions("Imported schema import failed")
      return
    }

    selectedImportedApp = selectedApp
    runCatching<List<ManagedConfigDefinition>> {
      withContext(Dispatchers.IO) {
        managedConfigRepository
          .importSchema(packageName)
          .flatMap(RestrictionEntry::toManagedConfigDefinitions)
      }
    }.onSuccess { definitions ->
      importedSchemaDefinitions = definitions
      savedStateHandle[STATE_SELECTED_IMPORTED_PACKAGE] = selectedApp.packageName
      importedSchemaError =
        if (definitions.isEmpty()) {
          "No managed configuration schema declared by ${selectedApp.packageName}."
        } else {
          null
        }
      importedSchemaLoading = false
      refreshRestrictions(source)
    }.onFailure { error ->
      importedSchemaDefinitions = emptyList()
      importedSchemaLoading = false
      importedSchemaError = "Unable to import schema from ${selectedApp.packageName}: ${error.message ?: "unknown error"}"
      refreshRestrictions("Imported schema import failed")
    }
  }

  private fun reportManagedConfigFeedbackIfNeeded(managedRestrictions: Bundle) {
    if (managedRestrictions.isEmpty) {
      if (lastReportedManagedSignature != null) {
        val states = buildManagedConfigFeedbackStates(Bundle(), managedConfigDefinitions)
        val timestamp = nowTimestamp()
        runCatching { keyedAppStatesPublisher.publish(states) }
          .onSuccess {
            lastReportedManagedSignature = EMPTY_MANAGED_CONFIG_SIGNATURE
            lastFeedbackStatusMessage = "Cleared keyed app states from the Android Enterprise feedback channel."
            lastFeedbackUpdatedAt = timestamp
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
    runCatching { keyedAppStatesPublisher.publish(states) }
      .onSuccess {
        lastReportedManagedSignature = signature
        lastFeedbackStatusMessage = "Reported ${states.size} keyed app states to the Android Enterprise feedback channel."
        lastFeedbackUpdatedAt = timestamp
      }.onFailure { error ->
        lastFeedbackStatusMessage = "Failed to report keyed app states: ${error.message ?: "unknown error"}"
        lastFeedbackUpdatedAt = timestamp
      }
  }

  private fun rebuildUiState(
    source: String,
    effectiveRestrictions: Bundle,
    managedRestrictions: Bundle,
    rawManagedRestrictions: Bundle,
    managedRuntimeStructure: String,
    managedShapeHighlights: List<String>,
    localOverrideFormat: String?,
    localShapeHighlights: List<String>,
    localOverrideError: String? = null,
  ) {
    _uiState.value =
      buildUiState(
        UiStateInputs(
          effectiveRestrictions = effectiveRestrictions,
          managedRestrictions = managedRestrictions,
          rawManagedRestrictions = rawManagedRestrictions,
          managedRuntimeStructure = managedRuntimeStructure,
          managedShapeHighlights = managedShapeHighlights,
          localOverrideJson = currentOverrideJson.orEmpty(),
          localOverrideFormat = localOverrideFormat,
          localShapeHighlights = localShapeHighlights,
          keyedAppStatesStatus = lastFeedbackStatusMessage,
          keyedAppStatesUpdatedAt = lastFeedbackUpdatedAt,
          source = source,
          importableAppsLoading = importableAppsLoading,
          importedSchemaLoading = importedSchemaLoading,
          importableApps = availableSchemaApps,
          selectedImportedApp = selectedImportedApp,
          importedSchemaDefinitions = importedSchemaDefinitions,
          importedSchemaError = importedSchemaError,
          localOverrideError = localOverrideError,
        ),
      )
  }

  class Factory(
    private val managedConfigRepository: ManagedConfigRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val keyedAppStatesPublisher: KeyedAppStatesPublisher,
    private val currentPackageName: String,
    private val isDebuggable: Boolean,
    private val savedStateHandle: SavedStateHandle,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      ManagedConfigViewModel(
        managedConfigRepository = managedConfigRepository,
        installedAppsRepository = installedAppsRepository,
        keyedAppStatesPublisher = keyedAppStatesPublisher,
        currentPackageName = currentPackageName,
        isDebuggable = isDebuggable,
        savedStateHandle = savedStateHandle,
      ) as T
  }
}

private const val STATE_SELECTED_IMPORTED_PACKAGE = "state_selected_imported_package"
private const val STATE_LOCAL_OVERRIDE_JSON = "state_local_override_json"
