package org.bayton.tools.managedconfig

import android.os.Bundle

internal data class UiStateInputs(
  val effectiveRestrictions: Bundle,
  val managedRestrictions: Bundle,
  val rawManagedRestrictions: Bundle,
  val managedRuntimeStructure: String,
  val managedShapeHighlights: List<String>,
  val localOverrideJson: String,
  val localOverrideSeedJson: String? = null,
  val localOverrideSeededFromEmmPayload: Boolean = false,
  val localOverrideFormat: String?,
  val localShapeHighlights: List<String>,
  val keyedAppStatesStatus: String,
  val keyedAppStatesUpdatedAt: String,
  val source: String,
  val importableAppsLoading: Boolean = false,
  val importedSchemaLoading: Boolean = false,
  val importableApps: List<InstalledAppOption> = emptyList(),
  val selectedImportedApp: InstalledAppOption? = null,
  val importedSchemaDefinitions: List<ManagedConfigDefinition> = emptyList(),
  val importedSchemaError: String? = null,
  val localOverrideError: String? = null,
)

internal fun buildUiState(inputs: UiStateInputs): ManagedConfigUiState {
  val managedKeys = inputs.managedRestrictions.keySet().sorted()
  val effectiveKeys = inputs.effectiveRestrictions.keySet().sorted()
  val managedShapeHighlightByKey = inputs.managedShapeHighlights.associateBy { it.substringBefore(':') }
  val localShapeHighlightByKey = inputs.localShapeHighlights.associateBy { it.substringBefore(':') }
  val configuredItems =
    managedConfigDefinitions.map { definition ->
      val isManagedConfigured = inputs.managedRestrictions.containsKey(definition.key)
      val isEffectiveConfigured = inputs.effectiveRestrictions.containsKey(definition.key)
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
            if (inputs.rawManagedRestrictions.containsKey(definition.key)) {
              formatDeliveredValueForKey(definition.key, inputs.rawManagedRestrictions.valueForKey(definition.key))
            } else {
              formatValueForDisplay(inputs.managedRestrictions.valueForKey(definition.key))
            }
          } else {
            "Not set by RestrictionsManager"
          },
        effectiveValue =
          if (isEffectiveConfigured) {
            formatDeliveredValueForKey(definition.key, inputs.effectiveRestrictions.valueForKey(definition.key))
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
          typeLabel = inferManagedConfigValueType(inputs.rawManagedRestrictions.valueForKey(key)).label,
          isManagedConfigured = true,
          isEffectiveConfigured = inputs.effectiveRestrictions.containsKey(key),
          managedValue =
            if (inputs.rawManagedRestrictions.containsKey(key)) {
              formatDeliveredValueForKey(key, inputs.rawManagedRestrictions.valueForKey(key))
            } else {
              formatValueForDisplay(inputs.managedRestrictions.valueForKey(key))
            },
          effectiveValue =
            if (inputs.effectiveRestrictions.containsKey(key)) {
              formatDeliveredValueForKey(key, inputs.effectiveRestrictions.valueForKey(key))
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
          typeLabel = inferManagedConfigValueType(inputs.effectiveRestrictions.valueForKey(key)).label,
          isManagedConfigured = inputs.managedRestrictions.containsKey(key),
          isEffectiveConfigured = true,
          managedValue =
            if (inputs.managedRestrictions.containsKey(key)) {
              if (inputs.rawManagedRestrictions.containsKey(key)) {
                formatDeliveredValueForKey(key, inputs.rawManagedRestrictions.valueForKey(key))
              } else {
                formatValueForDisplay(inputs.managedRestrictions.valueForKey(key))
              }
            } else {
              "Not set by RestrictionsManager"
            },
          effectiveValue = formatDeliveredValueForKey(key, inputs.effectiveRestrictions.valueForKey(key)),
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
    if (inputs.selectedImportedApp != null && inputs.importedSchemaDefinitions.isNotEmpty()) {
      inputs.importedSchemaDefinitions
    } else {
      emptyList()
    }
  val activeLocalDefinitionKeys = activeLocalSchemaDefinitions.map { it.key }.toSet()
  val localValidationItems =
    activeLocalSchemaDefinitions.map { definition ->
      val resolvedValue = resolveImportedDefinitionValue(inputs.effectiveRestrictions, definition)
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
              if (inputs.selectedImportedApp != null) {
                "Additional local simulation key not declared in the selected app schema."
              } else {
                "Additional local simulation key."
              },
            typeLabel = inferManagedConfigValueType(inputs.effectiveRestrictions.valueForKey(key)).label,
            depth = 0,
            isManagedConfigured = false,
            isEffectiveConfigured = true,
            managedValue = "Not set by RestrictionsManager",
            effectiveValue = formatDeliveredValueForKey(key, inputs.effectiveRestrictions.valueForKey(key)),
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

  val localOverrideActive = inputs.localOverrideJson.isNotBlank() && inputs.localOverrideError == null

  val effectiveStructuredPayload = formatBundleAsStructuredJson(inputs.effectiveRestrictions)
  val managedRawStructuredPayload = formatBundleAsStructuredJson(inputs.rawManagedRestrictions)
  val managedNormalizedStructuredPayload = formatBundleAsStructuredJson(inputs.managedRestrictions)
  val localPayloadNormalizedDifference = inputs.localShapeHighlights.any(::isNonCanonicalBundleArrayShapeText)

  return ManagedConfigUiState(
    hasManagedConfig = managedKeys.isNotEmpty(),
    source = inputs.source,
    updatedAt = nowTimestamp(),
    managedConfiguredCount = managedKeys.size,
    effectiveConfiguredCount = effectiveKeys.size,
    managedItems = configuredItems + extraManagedItems,
    effectiveItems = configuredItems + extraEffectiveItems,
    localValidationItems = localValidationItems,
    localValidationConfiguredCount = localValidationItems.count { it.isEffectiveConfigured },
    localValidationSupportedCount = activeLocalSchemaDefinitions.size,
    effectivePayload = effectiveStructuredPayload,
    managedPayload = managedNormalizedStructuredPayload,
    effectiveRuntimePayload = formatBundleRuntimePreview(inputs.effectiveRestrictions),
    managedRuntimePayload = formatBundleRuntimePreview(inputs.rawManagedRestrictions),
    effectivePayloadNormalizedDifference = localPayloadNormalizedDifference,
    managedPayloadNormalizedDifference = managedRawStructuredPayload != managedNormalizedStructuredPayload,
    managedPayloadFormat = if (managedKeys.isNotEmpty()) inputs.managedRuntimeStructure else "No managed payload",
    managedShapeHighlights = inputs.managedShapeHighlights,
    localOverrideJson = inputs.localOverrideJson,
    localOverrideSeedJson = inputs.localOverrideSeedJson,
    localOverrideActive = localOverrideActive,
    localOverrideSeededFromEmmPayload = inputs.localOverrideSeededFromEmmPayload,
    localOverrideFormat = inputs.localOverrideFormat ?: "None",
    localShapeHighlights = inputs.localShapeHighlights,
    keyedAppStatesStatus = inputs.keyedAppStatesStatus,
    keyedAppStatesUpdatedAt = inputs.keyedAppStatesUpdatedAt,
    importableAppsLoading = inputs.importableAppsLoading,
    importedSchemaLoading = inputs.importedSchemaLoading,
    importableApps = inputs.importableApps,
    selectedImportedAppLabel = inputs.selectedImportedApp?.label,
    selectedImportedAppPackage = inputs.selectedImportedApp?.packageName,
    importedSchemaError = inputs.importedSchemaError,
    localOverrideError = inputs.localOverrideError,
  )
}

internal fun nowTimestamp(): String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

internal fun formatBundleArrayShapeHighlight(highlight: BundleArrayShapeHighlight): String =
  when (highlight.variant) {
    BundleArrayShapeVariant.DIRECT_ITEM_BUNDLES ->
      "${highlight.parentKey}: no item wrapper key"
    BundleArrayShapeVariant.WRAPPED_ITEM_BUNDLE_KEY ->
      "${highlight.parentKey}: wrapper key ${highlight.wrapperKey}"
    BundleArrayShapeVariant.MIXED_DIRECT_AND_WRAPPED ->
      "${highlight.parentKey}: mixed item wrapper usage (${highlight.wrapperKey})"
  }

internal fun isNonCanonicalBundleArrayShapeText(highlight: String): Boolean =
  highlight.contains(": wrapper key ", ignoreCase = true) ||
    highlight.contains("mixed item wrapper usage", ignoreCase = true)
