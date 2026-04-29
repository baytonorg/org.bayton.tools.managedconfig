package org.bayton.tools.managedconfig

import android.content.RestrictionEntry
import android.os.Bundle

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

internal fun inferManagedConfigValueType(value: Any?): ManagedConfigValueType =
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

internal fun resolveImportedDefinitionValue(
  rootBundle: Bundle,
  definition: ManagedConfigDefinition,
): Any? {
  if (!rootBundle.containsKey(definition.payloadKey)) return null
  val rootValue = rootBundle.valueForKey(definition.payloadKey)
  if (definition.pathWithinRoot.isEmpty()) return rootValue
  return extractImportedPathValue(rootValue, definition.pathWithinRoot)
}

internal fun formatImportedDefinitionValue(
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
