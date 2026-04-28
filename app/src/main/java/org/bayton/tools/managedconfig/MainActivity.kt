package org.bayton.tools.managedconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.RestrictionsManager
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
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

    enableEdgeToEdge()
    setContent {
      BaytonManagedConfigTheme {
        ManagedConfigScreen(
          uiState = uiState,
          knownConfigs = managedConfigDefinitions,
          onApplyLocalOverride = ::applyLocalOverride,
          onClearLocalOverride = ::clearLocalOverride,
          onLoadSampleOverride = ::loadSampleOverride,
        )
      }
    }

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
            localOverrideError = error.message ?: "Failed to parse local override JSON.",
          )
      }
  }

  private fun clearLocalOverride(source: String = "Local override cleared") {
    currentOverrideJson = null
    currentParsedOverride = null
    refreshRestrictions(source)
  }

  private fun loadSampleOverride() {
    applyLocalOverride(sampleOverrideJson, "Sample override loaded")
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

internal const val EXTRA_MANAGED_CONFIG_JSON = "managed_config_json"
internal const val EMPTY_MANAGED_CONFIG_SIGNATURE = "__empty_managed_config__"

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
        title = definition.title,
        description = definition.description,
        isManagedConfigured = isManagedConfigured,
        isEffectiveConfigured = isEffectiveConfigured,
        managedValue =
          if (isManagedConfigured) {
            if (rawManagedRestrictions.containsKey(definition.key)) {
              formatDeliveredValueForKey(definition.key, rawManagedRestrictions.valueForKey(definition.key))
            } else {
              formatValueForKey(definition.key, managedRestrictions.valueForKey(definition.key))
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
          title = key,
          description = "Additional managed configuration received at runtime.",
          isManagedConfigured = true,
          isEffectiveConfigured = effectiveRestrictions.containsKey(key),
          managedValue =
            if (rawManagedRestrictions.containsKey(key)) {
              formatDeliveredValueForKey(key, rawManagedRestrictions.valueForKey(key))
            } else {
              formatValueForKey(key, managedRestrictions.valueForKey(key))
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
          title = key,
          description = "Additional local simulation key.",
          isManagedConfigured = managedRestrictions.containsKey(key),
          isEffectiveConfigured = true,
          managedValue =
            if (managedRestrictions.containsKey(key)) {
              if (rawManagedRestrictions.containsKey(key)) {
                formatDeliveredValueForKey(key, rawManagedRestrictions.valueForKey(key))
              } else {
                formatValueForKey(key, managedRestrictions.valueForKey(key))
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

  val localOverrideActive = localOverrideJson.isNotBlank() && localOverrideError == null

  return ManagedConfigUiState(
    hasManagedConfig = managedKeys.isNotEmpty(),
    source = source,
    updatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
    managedConfiguredCount = managedKeys.size,
    effectiveConfiguredCount = effectiveKeys.size,
    managedItems = configuredItems + extraManagedItems,
    effectiveItems = configuredItems + extraEffectiveItems,
    effectivePayload = formatBundleRedacted(effectiveRestrictions),
    managedPayload = formatBundleRedacted(rawManagedRestrictions),
    effectiveRuntimePayload = formatBundleRuntimePreview(effectiveRestrictions),
    managedRuntimePayload = formatBundleRuntimePreview(rawManagedRestrictions),
    managedPayloadFormat = if (managedKeys.isNotEmpty()) managedRuntimeStructure else "No managed payload",
    managedShapeHighlights = managedShapeHighlights,
    localOverrideJson = localOverrideJson,
    localOverrideActive = localOverrideActive,
    localOverrideFormat = localOverrideFormat ?: "None",
    localShapeHighlights = localShapeHighlights,
    keyedAppStatesStatus = keyedAppStatesStatus,
    keyedAppStatesUpdatedAt = keyedAppStatesUpdatedAt,
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
)

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
  val title: String,
  val description: String,
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
  val effectivePayload: String = "{}",
  val managedPayload: String = "{}",
  val effectiveRuntimePayload: String = "Bundle {}",
  val managedRuntimePayload: String = "Bundle {}",
  val managedPayloadFormat: String = "No managed payload",
  val managedShapeHighlights: List<String> = emptyList(),
  val localOverrideJson: String = "",
  val localOverrideActive: Boolean = false,
  val localOverrideFormat: String = "None",
  val localShapeHighlights: List<String> = emptyList(),
  val keyedAppStatesStatus: String = "No keyed app states reported yet",
  val keyedAppStatesUpdatedAt: String = "",
  val localOverrideError: String? = null,
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
