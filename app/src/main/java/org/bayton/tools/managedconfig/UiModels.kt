package org.bayton.tools.managedconfig

import android.graphics.drawable.Drawable

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
  val icon: Drawable?,
)

sealed interface ManagedConfigUiEvent {
  data class ToastMessage(val message: String) : ManagedConfigUiEvent
}
