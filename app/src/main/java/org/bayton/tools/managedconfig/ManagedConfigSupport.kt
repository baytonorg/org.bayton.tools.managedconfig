package org.bayton.tools.managedconfig

import android.os.Bundle
import android.os.Parcelable
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_OVERRIDE_JSON_LENGTH = 32_768
private const val MAX_JSON_DEPTH = 8
private const val MAX_ARRAY_LENGTH = 32
private const val MAX_FLATTENED_SEGMENTS = 16
private const val MAX_BUNDLE_ARRAY_INDEX = 31

private val bundleArrayWrapperKeys = mapOf("my_bundle_array_key" to "my_bundle_array_item")

private fun escapeJsonString(value: String): String =
  buildString(value.length + 2) {
    for (ch in value) {
      when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\b' -> append("\\b")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
      }
    }
  }

data class ParsedOverride(
  val bundle: Bundle,
  val format: OverrideFormat,
  val shapeHighlights: List<BundleArrayShapeHighlight> = emptyList(),
)

data class RuntimeManagedConfig(
  val normalizedBundle: Bundle,
  val structure: RuntimeManagedConfigStructure,
  val shapeHighlights: List<BundleArrayShapeHighlight> = emptyList(),
)

data class BundleArrayShapeHighlight(
  val parentKey: String,
  val wrapperKey: String,
  val variant: BundleArrayShapeVariant,
)

enum class BundleArrayShapeVariant {
  DIRECT_ITEM_BUNDLES,
  WRAPPED_ITEM_BUNDLE_KEY,
  MIXED_DIRECT_AND_WRAPPED,
}

enum class OverrideFormat(val label: String) {
  FLATTENED("Flattened keys"),
  UNFLATTENED("Unflattened nested JSON"),
  MIXED_UNSUPPORTED("Mixed flat/nested JSON"),
  EMPTY("Empty"),
  INVALID("Invalid JSON"),
}

enum class RuntimeManagedConfigStructure(val label: String) {
  EMPTY("Empty runtime Bundle"),
  CANONICAL("Canonical nested Bundle / Parcelable[]"),
  FLATTENED_PATH_STYLE("Flattened path-style keys normalized from runtime Bundle"),
  MIXED("Mixed nested and flattened path-style keys normalized from runtime Bundle"),
}

fun parseJsonBundleSafely(rawJson: String): ParsedOverride =
  runCatching { parseJsonBundle(rawJson) }.getOrDefault(ParsedOverride(Bundle(), detectOverrideFormat(rawJson)))

@Suppress("DEPRECATION")
internal fun Bundle.valueForKey(key: String): Any? = get(key)

@Suppress("DEPRECATION")
internal fun Bundle.parcelableArrayForKey(key: String): Array<Parcelable>? = getParcelableArray(key)

fun normalizeRuntimeManagedConfig(bundle: Bundle): RuntimeManagedConfig {
  if (bundle.isEmpty) {
    return RuntimeManagedConfig(Bundle(), RuntimeManagedConfigStructure.EMPTY)
  }

  val analysis = normalizeRuntimeBundle(bundle)
  val structure =
    when {
      analysis.foundFlattenedKeys && analysis.foundStructuredValues -> RuntimeManagedConfigStructure.MIXED
      analysis.foundFlattenedKeys -> RuntimeManagedConfigStructure.FLATTENED_PATH_STYLE
      else -> RuntimeManagedConfigStructure.CANONICAL
    }
  val knownShapeNormalization = canonicalizeKnownBundleArrayShapes(analysis.bundle)

  return RuntimeManagedConfig(
    normalizedBundle = knownShapeNormalization.bundle,
    structure = structure,
    shapeHighlights = knownShapeNormalization.shapeHighlights,
  )
}

fun parseJsonBundle(rawJson: String): ParsedOverride {
  require(rawJson.length <= MAX_OVERRIDE_JSON_LENGTH) { "JSON exceeds ${MAX_OVERRIDE_JSON_LENGTH / 1024} KB limit." }
  val jsonObject = JSONObject(rawJson)
  val format = detectOverrideFormat(rawJson)
  require(format != OverrideFormat.MIXED_UNSUPPORTED) {
    "Mixed flattened and nested JSON is not supported. Use one representation consistently."
  }

  val bundle =
    when (format) {
      OverrideFormat.FLATTENED -> flattenedJsonObjectToBundle(jsonObject)
      OverrideFormat.UNFLATTENED, OverrideFormat.EMPTY -> jsonObjectToBundle(jsonObject)
      OverrideFormat.MIXED_UNSUPPORTED, OverrideFormat.INVALID -> error("Unsupported override format")
    }
  val knownShapeNormalization = canonicalizeKnownBundleArrayShapes(bundle)

  return ParsedOverride(
    bundle = knownShapeNormalization.bundle,
    format = format,
    shapeHighlights = knownShapeNormalization.shapeHighlights,
  )
}

fun detectOverrideFormat(rawJson: String): OverrideFormat {
  val trimmed = rawJson.trim()
  if (trimmed.isEmpty() || trimmed == "{}") return OverrideFormat.EMPTY

  return runCatching {
    val jsonObject = JSONObject(trimmed)
    val keys = jsonObject.keys().asSequence().toList()
    val hasFlattenedKeys = keys.any { key -> key.contains('.') || key.contains('[') || key.contains(']') }
    val hasStructuredValues = keys.any { key ->
      when (val value = jsonObject.get(key)) {
        is JSONObject, is JSONArray -> true
        else -> false
      }
    }

    when {
      hasFlattenedKeys && hasStructuredValues -> OverrideFormat.MIXED_UNSUPPORTED
      hasFlattenedKeys -> OverrideFormat.FLATTENED
      else -> OverrideFormat.UNFLATTENED
    }
  }.getOrDefault(OverrideFormat.INVALID)
}

fun formatValueForKey(key: String, value: Any?): String {
  return formatValue(value)
}

fun formatDeliveredValueForKey(key: String, value: Any?): String {
  return formatDeliveredRuntimeValueForKey(key, value)
}

fun formatBundleRedacted(bundle: Bundle, depth: Int = 0): String {
  if (bundle.isEmpty) return "{}"
  return bundleToRedactedJsonObject(bundle).toString(2)
}

fun formatBundleRuntimePreview(bundle: Bundle, depth: Int = 0): String {
  if (bundle.isEmpty) return "{}"
  return renderRuntimeBundleLines(bundle, depth).joinToString("\n")
}

fun mergeBundles(base: Bundle, override: Bundle): Bundle =
  Bundle(base).apply {
    override.keySet().forEach { key ->
      putAllForKey(key, override.valueForKey(key))
    }
  }

private fun formatValue(value: Any?): String =
  when (value) {
    null -> "null"
    is Bundle -> formatBundleRedacted(value)
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatValue(item) }
    is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
    is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
    is ArrayList<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatValue(item) }
    is Parcelable -> if (value is Bundle) formatBundleRedacted(value) else value.toString()
    else -> value.toString()
  }

private fun formatRuntimeValue(value: Any?, depth: Int): String =
  when (value) {
    null -> "null"
    is String -> value
    is Bundle -> formatBundleRuntimePreview(value, depth)
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatRuntimeValue(item, depth + 1) }
    is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
    is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
    is ArrayList<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatRuntimeValue(item, depth + 1) }
    is Parcelable -> if (value is Bundle) formatBundleRuntimePreview(value, depth) else value.toString()
    else -> value.toString()
  }

private fun formatDeliveredRuntimeValue(value: Any?): String =
  when (value) {
    null -> "null"
    is Bundle -> renderRuntimeBundleLines(value, 0).joinToString("\n")
    is Array<*> -> renderRuntimeArrayValueLines(value, 0).joinToString("\n")
    is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
    is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
    is ArrayList<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatRuntimeValue(item, 0) }
    else -> formatRuntimeValue(value, 0)
  }

private fun formatDeliveredRuntimeValueForKey(key: String, value: Any?): String =
  when (value) {
    null -> "null"
    is Array<*> -> renderRuntimeArrayLines(key, value, 0).joinToString("\n")
    is BooleanArray -> buildIndexedScalarLines(key, value.toTypedArray()).joinToString("\n")
    is IntArray -> buildIndexedScalarLines(key, value.toTypedArray()).joinToString("\n")
    is LongArray -> buildIndexedScalarLines(key, value.toTypedArray()).joinToString("\n")
    is FloatArray -> buildIndexedScalarLines(key, value.toTypedArray()).joinToString("\n")
    is DoubleArray -> buildIndexedScalarLines(key, value.toTypedArray()).joinToString("\n")
    else -> formatDeliveredRuntimeValue(value)
  }

private fun buildIndexedScalarLines(key: String, values: Array<*>): List<String> =
  values.mapIndexed { index, item -> "$key[$index]: ${formatRuntimeValue(item, 0)}" }

private fun renderRuntimeBundleLines(bundle: Bundle, depth: Int): List<String> {
  val lines = mutableListOf<String>()
  val indent = "  ".repeat(depth)

  bundle.keySet().sorted().forEach { key ->
    when (val value = bundle.valueForKey(key)) {
      is Bundle -> {
        lines += "$indent$key:"
        lines += renderRuntimeBundleLines(value, depth + 1)
      }
      is Array<*> -> {
        lines += "$indent$key:"
        lines += renderRuntimeArrayLines(key, value, depth + 1)
      }
      is BooleanArray -> {
        lines += "$indent$key:"
        value.forEachIndexed { index, item ->
          lines += "${"  ".repeat(depth + 1)}$key[$index]: $item"
        }
      }
      is IntArray -> {
        lines += "$indent$key:"
        value.forEachIndexed { index, item ->
          lines += "${"  ".repeat(depth + 1)}$key[$index]: $item"
        }
      }
      is LongArray -> {
        lines += "$indent$key:"
        value.forEachIndexed { index, item ->
          lines += "${"  ".repeat(depth + 1)}$key[$index]: $item"
        }
      }
      is FloatArray -> {
        lines += "$indent$key:"
        value.forEachIndexed { index, item ->
          lines += "${"  ".repeat(depth + 1)}$key[$index]: $item"
        }
      }
      is DoubleArray -> {
        lines += "$indent$key:"
        value.forEachIndexed { index, item ->
          lines += "${"  ".repeat(depth + 1)}$key[$index]: $item"
        }
      }
      else -> {
        lines += "$indent$key: ${formatRuntimeValue(value, depth + 1)}"
      }
    }
  }

  return lines
}

private fun renderRuntimeArrayLines(parentKey: String, array: Array<*>, depth: Int): List<String> {
  val lines = mutableListOf<String>()
  val indent = "  ".repeat(depth)

  array.forEachIndexed { index, item ->
    val itemKey = "$parentKey[$index]"
    when (item) {
      is Bundle -> {
        lines += "$indent$itemKey:"
        lines += renderRuntimeBundleLines(item, depth + 1)
      }
      else -> {
        lines += "$indent$itemKey: ${formatRuntimeValue(item, depth + 1)}"
      }
    }
  }

  return lines
}

private fun renderRuntimeArrayValueLines(array: Array<*>, depth: Int): List<String> {
  val lines = mutableListOf<String>()
  val indent = "  ".repeat(depth)

  array.forEachIndexed { index, item ->
    val itemKey = "[$index]"
    when (item) {
      is Bundle -> {
        lines += "$indent$itemKey:"
        lines += renderRuntimeBundleLines(item, depth + 1)
      }
      else -> {
        lines += "$indent$itemKey: ${formatRuntimeValue(item, depth + 1)}"
      }
    }
  }

  return lines
}

private fun formatStructuredValue(value: Any?, depth: Int): String =
  when (value) {
    null -> "null"
    is String -> "\"${escapeJsonString(value)}\""
    is Bundle -> formatBundleRedacted(value, depth)
    is Array<*> -> {
      if (value.isEmpty()) {
        "[]"
      } else {
        val indent = "  ".repeat(depth)
        val childIndent = "  ".repeat(depth + 1)
        value.joinToString(prefix = "[\n", postfix = "\n$indent]", separator = ",\n") { item ->
          "$childIndent${formatStructuredValue(item, depth + 1)}"
        }
      }
    }
    is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
    is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
    is ArrayList<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatStructuredValue(item, depth + 1) }
    else -> value.toString()
  }

private fun bundleToRedactedJsonObject(bundle: Bundle): JSONObject {
  val jsonObject = JSONObject()
  bundle.keySet().sorted().forEach { key ->
    val value = toRedactedJsonValue(bundle.valueForKey(key))
    jsonObject.put(key, value)
  }
  return jsonObject
}

private fun toRedactedJsonValue(value: Any?): Any =
  when (value) {
    null -> JSONObject.NULL
    is Bundle -> bundleToRedactedJsonObject(value)
    is Array<*> -> JSONArray().apply { value.forEach { put(toRedactedJsonValue(it)) } }
    is BooleanArray -> JSONArray().apply { value.forEach { put(it) } }
    is IntArray -> JSONArray().apply { value.forEach { put(it) } }
    is LongArray -> JSONArray().apply { value.forEach { put(it) } }
    is FloatArray -> JSONArray().apply { value.forEach { put(it.toDouble()) } }
    is DoubleArray -> JSONArray().apply { value.forEach { put(it) } }
    is ArrayList<*> -> JSONArray().apply { value.forEach { put(toRedactedJsonValue(it)) } }
    is Parcelable -> if (value is Bundle) bundleToRedactedJsonObject(value) else value.toString()
    else -> value
  }

private fun jsonObjectToBundle(jsonObject: JSONObject, depth: Int = 0): Bundle {
  require(depth <= MAX_JSON_DEPTH) { "JSON nesting exceeds supported depth." }
  return Bundle().apply {
    val keys = jsonObject.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      putJsonValue(key, jsonObject.get(key), depth)
    }
  }
}

private data class RuntimeNormalizationResult(
  val bundle: Bundle,
  val foundFlattenedKeys: Boolean,
  val foundStructuredValues: Boolean,
)

private data class KnownShapeNormalizationResult(
  val bundle: Bundle,
  val shapeHighlights: List<BundleArrayShapeHighlight>,
)

private fun normalizeRuntimeBundle(bundle: Bundle): RuntimeNormalizationResult {
  val normalized = Bundle()
  var foundFlattenedKeys = false
  var foundStructuredValues = false

  bundle.keySet().sorted().forEach { key ->
    val value = bundle.valueForKey(key)
    val keyIsFlattened = key.contains('.') || key.contains('[') || key.contains(']')
    if (keyIsFlattened) {
      foundFlattenedKeys = true
      putFlattenedRuntimeValue(normalized, key, value)
    } else {
      val normalizedValue = normalizeRuntimeValue(value)
      if (value is Bundle || value is Array<*> && value.all { it is Bundle }) {
        foundStructuredValues = true
      }
      normalized.putAllForKey(key, normalizedValue)
      if (normalizedValue is Bundle) {
        val childAnalysis = normalizeRuntimeBundle(normalizedValue)
        normalized.putBundle(key, childAnalysis.bundle)
        foundFlattenedKeys = foundFlattenedKeys || childAnalysis.foundFlattenedKeys
        foundStructuredValues = true
      } else if (normalizedValue is Array<*> && normalizedValue.all { it is Bundle }) {
        val normalizedBundles =
          normalizedValue.map { child ->
            val childAnalysis = normalizeRuntimeBundle(child as Bundle)
            foundFlattenedKeys = foundFlattenedKeys || childAnalysis.foundFlattenedKeys
            foundStructuredValues = true
            childAnalysis.bundle as Parcelable
          }.toTypedArray()
        normalized.putParcelableArray(key, normalizedBundles)
      }
    }
  }

  return RuntimeNormalizationResult(
    bundle = normalized,
    foundFlattenedKeys = foundFlattenedKeys,
    foundStructuredValues = foundStructuredValues,
  )
}

private fun canonicalizeKnownBundleArrayShapes(bundle: Bundle): KnownShapeNormalizationResult {
  val normalized = Bundle()
  val shapeHighlights = mutableListOf<BundleArrayShapeHighlight>()

  bundle.keySet().sorted().forEach { key ->
    val value = bundle.valueForKey(key)
    when {
      value is Bundle -> {
        val child = canonicalizeKnownBundleArrayShapes(value)
        normalized.putBundle(key, child.bundle)
        shapeHighlights += child.shapeHighlights
      }
      value is Array<*> && value.all { it is Bundle } -> {
        val wrapperKey = bundleArrayWrapperKeys[key]
        var sawWrapped = false
        var sawDirect = false
        val normalizedItems =
          value.map { item ->
            val itemBundle = item as Bundle
            val unwrapped =
              if (wrapperKey != null && itemBundle.keySet() == setOf(wrapperKey) && itemBundle.getBundle(wrapperKey) != null) {
                sawWrapped = true
                itemBundle.getBundle(wrapperKey) ?: Bundle()
              } else {
                sawDirect = true
                itemBundle
              }
            val child = canonicalizeKnownBundleArrayShapes(unwrapped)
            shapeHighlights += child.shapeHighlights
            child.bundle as Parcelable
          }.toTypedArray()
        if (wrapperKey != null && (sawWrapped || sawDirect)) {
          val variant =
            when {
              sawWrapped && sawDirect -> BundleArrayShapeVariant.MIXED_DIRECT_AND_WRAPPED
              sawWrapped -> BundleArrayShapeVariant.WRAPPED_ITEM_BUNDLE_KEY
              else -> BundleArrayShapeVariant.DIRECT_ITEM_BUNDLES
            }
          shapeHighlights += BundleArrayShapeHighlight(parentKey = key, wrapperKey = wrapperKey, variant = variant)
        }
        normalized.putParcelableArray(key, normalizedItems)
      }
      else -> normalized.putAllForKey(key, value)
    }
  }

  return KnownShapeNormalizationResult(
    bundle = normalized,
    shapeHighlights = shapeHighlights,
  )
}

private fun normalizeRuntimeValue(value: Any?): Any? =
  when {
    value is Bundle -> normalizeRuntimeBundle(value).bundle
    value is Array<*> && value.all { it is Bundle } ->
      value.map { normalizeRuntimeBundle(it as Bundle).bundle as Parcelable }.toTypedArray()
    else -> value
  }

private fun flattenedJsonObjectToBundle(jsonObject: JSONObject): Bundle {
  val root = Bundle()
  val keys = jsonObject.keys()
  while (keys.hasNext()) {
    val key = keys.next()
    putFlattenedJsonValue(root, key, jsonObject.get(key))
  }
  return root
}

private fun putFlattenedJsonValue(root: Bundle, path: String, value: Any?) {
  val segments = parsePathSegments(path)
  require(segments.isNotEmpty()) { "Invalid flattened key: $path" }
  require(segments.size <= MAX_FLATTENED_SEGMENTS) { "Flattened key path is too deep: $path" }
  putPathValue(root, segments, value)
}

private fun putFlattenedRuntimeValue(root: Bundle, path: String, value: Any?) {
  val segments = parsePathSegments(path)
  require(segments.isNotEmpty()) { "Invalid flattened runtime key: $path" }
  require(segments.size <= MAX_FLATTENED_SEGMENTS) { "Flattened runtime key path is too deep: $path" }
  putRuntimePathValue(root, segments, value)
}

private sealed interface PathSegment {
  data class Key(val value: String) : PathSegment
  data class Index(val value: Int) : PathSegment
}

private fun parsePathSegments(path: String): List<PathSegment> {
  val segments = mutableListOf<PathSegment>()
  var index = 0
  while (index < path.length) {
    when (path[index]) {
      '.' -> index++
      '[' -> {
        val end = path.indexOf(']', startIndex = index)
        require(end > index + 1) { "Invalid array segment in $path" }
        val arrayIndex = path.substring(index + 1, end).toInt()
        require(arrayIndex >= 0) { "Bundle array index must be non-negative." }
        require(arrayIndex <= MAX_BUNDLE_ARRAY_INDEX) { "Bundle array index exceeds supported maximum." }
        segments += PathSegment.Index(arrayIndex)
        index = end + 1
      }
      else -> {
        val start = index
        while (index < path.length && path[index] != '.' && path[index] != '[') {
          index++
        }
        segments += PathSegment.Key(path.substring(start, index))
      }
    }
  }
  return segments
}

private fun putRuntimePathValue(bundle: Bundle, segments: List<PathSegment>, value: Any?) {
  val first = segments.firstOrNull() as? PathSegment.Key ?: error("Flattened runtime path must start with a key")
  if (segments.size == 1) {
    bundle.putAllForKey(first.value, normalizeRuntimeValue(value))
    return
  }

  when (val next = segments[1]) {
    is PathSegment.Key -> {
      val child = bundle.getBundle(first.value) ?: Bundle()
      putRuntimePathValue(child, segments.drop(1), value)
      bundle.putBundle(first.value, child)
    }
    is PathSegment.Index -> {
      val existing = bundle.parcelableArrayForKey(first.value)?.map { (it as? Bundle) ?: Bundle() }?.toMutableList() ?: mutableListOf()
      ensureBundleIndex(existing, next.value)
      putRuntimePathValue(existing[next.value], segments.drop(2), value)
      bundle.putParcelableArray(first.value, existing.toTypedArray())
    }
  }
}

private fun putPathValue(bundle: Bundle, segments: List<PathSegment>, value: Any?) {
  val first = segments.firstOrNull() as? PathSegment.Key ?: error("Flattened path must start with a key")
  if (segments.size == 1) {
    bundle.putJsonValue(first.value, value, 0)
    return
  }

  when (val next = segments[1]) {
    is PathSegment.Key -> {
      val child = bundle.getBundle(first.value) ?: Bundle()
      putPathValue(child, segments.drop(1), value)
      bundle.putBundle(first.value, child)
    }
    is PathSegment.Index -> {
      val existing = bundle.parcelableArrayForKey(first.value)?.map { (it as? Bundle) ?: Bundle() }?.toMutableList() ?: mutableListOf()
      ensureBundleIndex(existing, next.value)
      putPathValue(existing[next.value], segments.drop(2), value)
      bundle.putParcelableArray(first.value, existing.toTypedArray())
    }
  }
}

private fun ensureBundleIndex(list: MutableList<Bundle>, index: Int) {
  while (list.size <= index) {
    require(list.size < MAX_ARRAY_LENGTH) { "Bundle array exceeds supported length." }
    list += Bundle()
  }
}

private fun Bundle.putJsonValue(key: String, value: Any?, depth: Int) {
  when (value) {
    null, JSONObject.NULL -> putString(key, null)
    is Boolean -> putBoolean(key, value)
    is Int -> putInt(key, value)
    is Long -> putLong(key, value)
    is Double -> {
      val intValue = value.toInt()
      if (value == intValue.toDouble()) {
        putInt(key, intValue)
      } else {
        putString(key, value.toString())
      }
    }
    is String -> putString(key, value)
    is JSONObject -> putBundle(key, jsonObjectToBundle(value, depth + 1))
    is JSONArray -> putJsonArray(key, value, depth + 1)
    else -> putString(key, value.toString())
  }
}

private fun Bundle.putJsonArray(key: String, array: JSONArray, depth: Int) {
  require(array.length() <= MAX_ARRAY_LENGTH) { "Array exceeds supported length." }
  if (array.length() == 0) {
    putStringArray(key, emptyArray())
    return
  }

  val first = array.get(0)
  when {
    first is JSONObject -> {
      val bundles = Array(array.length()) { index -> jsonObjectToBundle(array.getJSONObject(index), depth) as Parcelable }
      putParcelableArray(key, bundles)
    }
    else -> {
      val values = Array(array.length()) { index ->
        val item = array.get(index)
        if (item == JSONObject.NULL) "" else item.toString()
      }
      putStringArray(key, values)
    }
  }
}

private fun Bundle.putAllForKey(key: String, value: Any?) {
  when (value) {
    null -> putString(key, null)
    is Boolean -> putBoolean(key, value)
    is Int -> putInt(key, value)
    is Long -> putLong(key, value)
    is Float -> putFloat(key, value)
    is Double -> putDouble(key, value)
    is String -> putString(key, value)
    is Bundle -> putBundle(key, value)
    is Array<*> ->
      when {
        value.all { it is String } -> putStringArray(key, value.map { it as String }.toTypedArray())
        value.all { it is Parcelable } -> putParcelableArray(key, value.map { it as Parcelable }.toTypedArray())
        else -> putStringArray(key, value.map { it?.toString().orEmpty() }.toTypedArray())
      }
    is BooleanArray -> putBooleanArray(key, value)
    is IntArray -> putIntArray(key, value)
    is LongArray -> putLongArray(key, value)
    is FloatArray -> putFloatArray(key, value)
    is DoubleArray -> putDoubleArray(key, value)
    is Parcelable -> putParcelable(key, value)
    else -> putString(key, value.toString())
  }
}
