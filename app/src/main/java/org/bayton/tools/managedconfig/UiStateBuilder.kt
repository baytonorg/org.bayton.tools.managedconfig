package org.bayton.tools.managedconfig

import android.os.Bundle

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
