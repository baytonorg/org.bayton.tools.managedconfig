package org.bayton.tools.managedconfig

import android.content.RestrictionEntry
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

  private var currentEditorJson: String = savedStateHandle[STATE_LOCAL_EDITOR_JSON] ?: ""
  private var currentEmmPayloadAvailable: Boolean = savedStateHandle[STATE_LOCAL_OVERRIDE_EMM_AVAILABLE] ?: false
  private var currentEmmSeedJson: String? = savedStateHandle[STATE_LOCAL_OVERRIDE_SEED_JSON]
  private var currentAppliedOverrideJson: String? = savedStateHandle[STATE_APPLIED_OVERRIDE_JSON]
  private var currentAppliedOverridePresent: Boolean = savedStateHandle[STATE_APPLIED_OVERRIDE_PRESENT] ?: false
  private var currentParsedOverride: ParsedOverride? = restoreAppliedOverride()
  private var currentEditorError: String? = null
  private var lastReportedManagedSignature: String? = null
  private var feedbackPublishInFlightSignature: String? = null
  private var lastFeedbackStatusMessage: String = "No keyed app states reported yet"
  private var lastFeedbackUpdatedAt: String = ""
  private var availableSchemaApps: List<InstalledAppOption> = emptyList()
  private var selectedImportedApp: InstalledAppOption? = null
  private var importedSchemaDefinitions: List<ManagedConfigDefinition> = emptyList()
  private var importedSchemaError: String? = null
  private var importableAppsLoading = false
  private var importedSchemaLoading = false
  private var importSchemaJob: Job? = null
  private var initialized = false
  private var currentManagedRestrictions: Bundle = Bundle()
  private var currentRawManagedRestrictions: Bundle = Bundle()
  private var currentManagedRuntimeStructure: String = "No managed payload"
  private var currentManagedShapeHighlights: List<String> = emptyList()

  private val _uiState = MutableStateFlow(ManagedConfigUiState())
  val uiState: StateFlow<ManagedConfigUiState> = _uiState.asStateFlow()

  private val _uiEvents = MutableSharedFlow<ManagedConfigUiEvent>()
  val uiEvents: SharedFlow<ManagedConfigUiEvent> = _uiEvents.asSharedFlow()

  fun initialize() {
    if (initialized) return
    initialized = true
    importableAppsLoading = true
    val rawManagedRestrictions = managedConfigRepository.currentRestrictions()
    cacheManagedRuntimeState(rawManagedRestrictions)
    rebuildCurrentUiState("App start")
    viewModelScope.launch {
      availableSchemaApps = withContext(ioDispatcher) { installedAppsRepository.listApps() }
      importableAppsLoading = false
      val restoredPackage = savedStateHandle.get<String>(STATE_SELECTED_IMPORTED_PACKAGE)?.takeIf { it.isNotBlank() }
      if (restoredPackage != null) {
        launchSchemaImport(
          packageName = restoredPackage,
          source = "Restored validation target",
          resetLocalValidation = false,
        )
      } else {
        rebuildCurrentUiState("App start")
      }
    }
  }

  fun refreshImportedAppsAndSelectedSchema(
    changedPackageName: String? = null,
    source: String = "Installed apps changed",
  ) {
    viewModelScope.launch {
      importableAppsLoading = true
      rebuildCurrentUiState(source)
      availableSchemaApps = withContext(ioDispatcher) { installedAppsRepository.listApps() }
      importableAppsLoading = false
      val selectedPackage = selectedImportedApp?.packageName
      if (selectedPackage != null && (changedPackageName == null || changedPackageName == selectedPackage)) {
        launchSchemaImport(
          packageName = selectedPackage,
          source = source,
          resetLocalValidation = false,
        )
      } else {
        rebuildCurrentUiState(source)
      }
    }
  }

  fun applyIncomingManagedConfigJson(
    json: String,
    source: String = "Debug intent injection",
  ) {
    if (!isDebuggable) {
      refreshRestrictions("Ignored external test injection in non-debug build")
      return
    }
    applyExternalOverride(json, source)
  }

  fun refreshRestrictions(source: String) {
    val rawManagedRestrictions = managedConfigRepository.currentRestrictions()
    cacheManagedRuntimeState(rawManagedRestrictions)
    reportManagedConfigFeedbackIfNeeded(rawManagedRestrictions)
    rebuildCurrentUiState(source)
  }

  fun updateEditorJson(rawJson: String) {
    currentEditorJson = rawJson
    savedStateHandle[STATE_LOCAL_EDITOR_JSON] = rawJson
    currentEditorError = null
    rebuildCurrentUiState(_uiState.value.source)
  }

  fun applyCurrentEditor(source: String = "Local override") {
    val normalized = currentEditorJson.trim()
    currentEditorError = null
    if (normalized.isEmpty()) {
      applyEmptyLocalOverride(source)
      return
    }

    applyParsedOverride(
      normalized = normalized,
      source = source,
      parseFailureMessageSuffix = "Applied payload unchanged.",
    )
  }

  private fun applyExternalOverride(
    rawJson: String,
    source: String,
  ) {
    val normalized = rawJson.trim()
    currentEditorError = null
    if (normalized.isEmpty()) {
      applyEmptyLocalOverride(source)
      return
    }

    applyParsedOverride(
      normalized = normalized,
      source = source,
      parseFailureMessageSuffix = "Applied payload unchanged.",
      updateEditor = false,
    )
  }

  private fun applyParsedOverride(
    normalized: String,
    source: String,
    parseFailureMessageSuffix: String,
    updateEditor: Boolean = true,
  ) {
    if (updateEditor) {
      currentEditorJson = normalized
      savedStateHandle[STATE_LOCAL_EDITOR_JSON] = normalized
    }

    runCatching { parseJsonBundle(normalized) }
      .onSuccess { parsedOverride ->
        currentAppliedOverridePresent = true
        savedStateHandle[STATE_APPLIED_OVERRIDE_PRESENT] = true
        currentAppliedOverrideJson = normalized
        savedStateHandle[STATE_APPLIED_OVERRIDE_JSON] = normalized
        currentParsedOverride = parsedOverride
        rebuildCurrentUiState(source)
      }.onFailure { error ->
        currentEditorError = "${error.message ?: "Failed to parse local override JSON."} $parseFailureMessageSuffix"
        rebuildCurrentUiState(source)
      }
  }

  private fun applyEmptyLocalOverride(source: String) {
    val parsedOverride = ParsedOverride(Bundle(), OverrideFormat.EMPTY)
    currentAppliedOverridePresent = true
    savedStateHandle[STATE_APPLIED_OVERRIDE_PRESENT] = true
    currentAppliedOverrideJson = ""
    savedStateHandle[STATE_APPLIED_OVERRIDE_JSON] = ""
    currentParsedOverride = parsedOverride
    rebuildCurrentUiState(source)
  }

  fun clearEditor(source: String = "Validation editor cleared") {
    currentEditorJson = ""
    savedStateHandle[STATE_LOCAL_EDITOR_JSON] = ""
    currentEditorError = null
    rebuildCurrentUiState(source)
  }

  fun loadEmmPayloadIntoEditor(source: String = "EMM payload loaded into editor") {
    val packageName = selectedImportedApp?.packageName ?: return
    if (!currentEmmPayloadAvailable) return
    viewModelScope.launch {
      val fetchResult = withContext(ioDispatcher) { managedConfigRepository.fetchManagedRestrictions(packageName) }
      applyFetchedManagedRestrictionsSeed(fetchResult)
      val seedJson = currentEmmSeedJson ?: run {
        rebuildCurrentUiState(source)
        return@launch
      }
      currentEditorJson = seedJson
      savedStateHandle[STATE_LOCAL_EDITOR_JSON] = seedJson
      currentEditorError = null
      rebuildCurrentUiState(source)
    }
  }

  fun selectImportedSchemaPackage(
    packageName: String,
    source: String = "Imported schema selected",
  ) {
    launchSchemaImport(
      packageName = packageName,
      source = source,
      resetLocalValidation = selectedImportedApp?.packageName != packageName && selectedImportedApp != null,
    )
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
    if (selectedImportedApp?.packageName != currentPackageName) {
      launchSchemaImport(
        packageName = currentPackageName,
        source = "Imported schema selected",
        resetLocalValidation = selectedImportedApp != null,
      ) {
        loadSampleIntoEditor(sampleJson, source, toastMessage)
      }
      return
    }
    viewModelScope.launch {
      loadSampleIntoEditor(sampleJson, source, toastMessage)
    }
  }

  private fun launchSchemaImport(
    packageName: String,
    source: String,
    resetLocalValidation: Boolean,
    afterImport: (suspend () -> Unit)? = null,
  ) {
    val previousJob = importSchemaJob
    importSchemaJob =
      viewModelScope.launch {
        previousJob?.cancelAndJoin()
        if (resetLocalValidation) {
          resetLocalValidationState()
        }
        importSchemaForPackage(packageName, source)
        afterImport?.invoke()
      }
  }

  private suspend fun loadSampleIntoEditor(
    sampleJson: String,
    source: String,
    toastMessage: String,
  ) {
    currentEditorJson = sampleJson
    savedStateHandle[STATE_LOCAL_EDITOR_JSON] = sampleJson
    currentEditorError = null
    rebuildCurrentUiState(source)
    _uiEvents.emit(ManagedConfigUiEvent.ToastMessage(toastMessage))
  }

  private suspend fun importSchemaForPackage(
    packageName: String,
    source: String,
  ) {
    val wasSelectedPackage = selectedImportedApp?.packageName == packageName
    importedSchemaLoading = true
    importedSchemaError = null
    currentEmmPayloadAvailable = false
    savedStateHandle[STATE_LOCAL_OVERRIDE_EMM_AVAILABLE] = false
    currentEmmSeedJson = null
    savedStateHandle[STATE_LOCAL_OVERRIDE_SEED_JSON] = null
    rebuildCurrentUiState("Loading imported schema")

    val selectedApp =
      availableSchemaApps.firstOrNull { it.packageName == packageName }
        ?: withContext(ioDispatcher) { installedAppsRepository.findApp(packageName) }

    if (selectedApp == null) {
      selectedImportedApp = null
      savedStateHandle[STATE_SELECTED_IMPORTED_PACKAGE] = null
      importedSchemaDefinitions = emptyList()
      importedSchemaLoading = false
      importedSchemaError = "Unable to find installed app: $packageName"
      rebuildCurrentUiState("Imported schema import failed")
      if (wasSelectedPackage) {
        _uiEvents.emit(ManagedConfigUiEvent.ToastMessage("Selected app removed"))
      }
      return
    }

    selectedImportedApp = selectedApp
    runCatching<List<ManagedConfigDefinition>> {
      withContext(ioDispatcher) {
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
      val managedRestrictionsPresenceProbe =
        withContext(ioDispatcher) { managedConfigRepository.fetchManagedRestrictions(packageName) }
      currentEmmPayloadAvailable =
        managedRestrictionsPresenceProbe.isAvailable && !managedRestrictionsPresenceProbe.bundle.isEmpty
      savedStateHandle[STATE_LOCAL_OVERRIDE_EMM_AVAILABLE] = currentEmmPayloadAvailable
      applyFetchedManagedRestrictionsSeed(managedRestrictionsPresenceProbe)
      importedSchemaLoading = false
      rebuildCurrentUiState(source)
    }.onFailure { error ->
      importedSchemaDefinitions = emptyList()
      importedSchemaLoading = false
      currentEmmPayloadAvailable = false
      savedStateHandle[STATE_LOCAL_OVERRIDE_EMM_AVAILABLE] = false
      currentEmmSeedJson = null
      savedStateHandle[STATE_LOCAL_OVERRIDE_SEED_JSON] = null
      importedSchemaError = "Unable to import schema from ${selectedApp.packageName}: ${error.message ?: "unknown error"}"
      rebuildCurrentUiState("Imported schema import failed")
    }
  }

  private fun reportManagedConfigFeedbackIfNeeded(managedRestrictions: Bundle) {
    if (managedRestrictions.isEmpty) {
      if (lastReportedManagedSignature != null) {
        val states = buildManagedConfigFeedbackStates(Bundle(), managedConfigDefinitions)
        if (feedbackPublishInFlightSignature == EMPTY_MANAGED_CONFIG_SIGNATURE) return
        feedbackPublishInFlightSignature = EMPTY_MANAGED_CONFIG_SIGNATURE
        publishKeyedAppStatesAsync(
          states = states,
          inFlightSignature = EMPTY_MANAGED_CONFIG_SIGNATURE,
          onSuccess = {
            lastReportedManagedSignature = EMPTY_MANAGED_CONFIG_SIGNATURE
            lastFeedbackStatusMessage = "Cleared keyed app states from the Android Enterprise feedback channel."
          },
          onFailure = { error ->
            lastFeedbackStatusMessage = "Failed to clear keyed app states: ${error.message ?: "unknown error"}"
          },
        )
      } else {
        lastFeedbackStatusMessage = "No keyed app states reported yet"
        lastFeedbackUpdatedAt = ""
      }
      return
    }

    val signature = feedbackSignature(managedRestrictions)
    if (signature == lastReportedManagedSignature || signature == feedbackPublishInFlightSignature) return

    val states = buildManagedConfigFeedbackStates(managedRestrictions, managedConfigDefinitions)
    feedbackPublishInFlightSignature = signature
    publishKeyedAppStatesAsync(
      states = states,
      inFlightSignature = signature,
      onSuccess = {
        lastReportedManagedSignature = signature
        lastFeedbackStatusMessage = "Reported ${states.size} keyed app states to the Android Enterprise feedback channel."
      },
      onFailure = { error ->
        lastFeedbackStatusMessage = "Failed to report keyed app states: ${error.message ?: "unknown error"}"
      },
    )
  }

  private fun rebuildUiState(
    source: String,
    effectiveRestrictions: Bundle,
    managedRestrictions: Bundle,
    rawManagedRestrictions: Bundle,
    managedRuntimeStructure: String,
    managedShapeHighlights: List<String>,
    editorFormat: String?,
    appliedOverrideFormat: String?,
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
          editorJson = currentEditorJson,
          emmPayloadJson = currentEmmSeedJson,
          emmPayloadAvailable = currentEmmPayloadAvailable,
          localOverrideApplied = currentParsedOverride != null,
          editorFormat = editorFormat,
          appliedOverrideFormat = appliedOverrideFormat,
          localShapeHighlights = localShapeHighlights,
          editorMatchesEmmPayload = currentEmmSeedJson?.trim() == currentEditorJson.trim(),
          editorHasUnappliedChanges = hasUnappliedEditorChanges(),
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

  private fun applyFetchedManagedRestrictionsSeed(fetchResult: ManagedRestrictionsFetchResult) {
    if (!fetchResult.isAvailable || fetchResult.bundle.isEmpty) {
      currentEmmSeedJson = null
      savedStateHandle[STATE_LOCAL_OVERRIDE_SEED_JSON] = null
      return
    }

    val seedJson = formatBundleAsStructuredJson(fetchResult.bundle)
    currentEmmSeedJson = seedJson
    savedStateHandle[STATE_LOCAL_OVERRIDE_SEED_JSON] = seedJson
  }

  private fun hasUnappliedEditorChanges(): Boolean {
    if (currentAppliedOverridePresent) {
      return currentEditorJson.trim() != currentAppliedOverrideJson.orEmpty().trim()
    }
    return currentEditorJson.isNotBlank()
  }

  private fun restoreAppliedOverride(): ParsedOverride? {
    if (!currentAppliedOverridePresent) return null
    val appliedJson = currentAppliedOverrideJson.orEmpty()
    return if (appliedJson.isBlank()) {
      ParsedOverride(Bundle(), OverrideFormat.EMPTY)
    } else {
      parseJsonBundleSafely(appliedJson)
    }
  }

  private fun resetLocalValidationState() {
    currentEditorJson = ""
    savedStateHandle[STATE_LOCAL_EDITOR_JSON] = ""
    currentEditorError = null
    currentEmmPayloadAvailable = false
    savedStateHandle[STATE_LOCAL_OVERRIDE_EMM_AVAILABLE] = false
    currentEmmSeedJson = null
    savedStateHandle[STATE_LOCAL_OVERRIDE_SEED_JSON] = null
    currentAppliedOverrideJson = null
    savedStateHandle[STATE_APPLIED_OVERRIDE_JSON] = null
    currentAppliedOverridePresent = false
    savedStateHandle[STATE_APPLIED_OVERRIDE_PRESENT] = false
    currentParsedOverride = null
  }

  private fun publishKeyedAppStatesAsync(
    states: Collection<androidx.enterprise.feedback.KeyedAppState>,
    inFlightSignature: String,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
  ) {
    viewModelScope.launch(ioDispatcher) {
      val timestamp = nowTimestamp()
      runCatching { keyedAppStatesPublisher.publish(states) }
        .onSuccess {
          withContext(Dispatchers.Main) {
            if (feedbackPublishInFlightSignature == inFlightSignature) {
              feedbackPublishInFlightSignature = null
            }
            onSuccess()
            lastFeedbackUpdatedAt = timestamp
            rebuildCurrentUiState("Feedback updated")
          }
        }.onFailure { error ->
          withContext(Dispatchers.Main) {
            if (feedbackPublishInFlightSignature == inFlightSignature) {
              feedbackPublishInFlightSignature = null
            }
            onFailure(error)
            lastFeedbackUpdatedAt = timestamp
            rebuildCurrentUiState("Feedback updated")
          }
        }
    }
  }

  private fun rebuildCurrentUiState(source: String) {
    rebuildUiState(
      source = source,
      effectiveRestrictions = currentParsedOverride?.bundle ?: Bundle(),
      managedRestrictions = currentManagedRestrictions,
      rawManagedRestrictions = currentRawManagedRestrictions,
      managedRuntimeStructure = currentManagedRuntimeStructure,
      managedShapeHighlights = currentManagedShapeHighlights,
      editorFormat = detectOverrideFormat(currentEditorJson).label,
      appliedOverrideFormat = currentParsedOverride?.format?.label,
      localShapeHighlights = currentParsedOverride?.shapeHighlights?.map(::formatBundleArrayShapeHighlight).orEmpty(),
      localOverrideError = currentEditorError,
    )
  }

  private fun cacheManagedRuntimeState(rawManagedRestrictions: Bundle) {
    val normalizedManagedConfig = normalizeRuntimeManagedConfig(rawManagedRestrictions)
    currentRawManagedRestrictions = rawManagedRestrictions
    currentManagedRestrictions = normalizedManagedConfig.normalizedBundle
    currentManagedRuntimeStructure = normalizedManagedConfig.structure.label
    currentManagedShapeHighlights = normalizedManagedConfig.shapeHighlights.map(::formatBundleArrayShapeHighlight)
  }

  class Factory(
    private val managedConfigRepository: ManagedConfigRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val keyedAppStatesPublisher: KeyedAppStatesPublisher,
    private val currentPackageName: String,
    private val isDebuggable: Boolean,
    private val savedStateHandle: SavedStateHandle,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        ioDispatcher = ioDispatcher,
      ) as T
  }
}

private const val STATE_SELECTED_IMPORTED_PACKAGE = "state_selected_imported_package"
private const val STATE_LOCAL_EDITOR_JSON = "state_local_editor_json"
private const val STATE_LOCAL_OVERRIDE_EMM_AVAILABLE = "state_local_override_emm_available"
private const val STATE_LOCAL_OVERRIDE_SEED_JSON = "state_local_override_seed_json"
private const val STATE_APPLIED_OVERRIDE_JSON = "state_applied_override_json"
private const val STATE_APPLIED_OVERRIDE_PRESENT = "state_applied_override_present"
