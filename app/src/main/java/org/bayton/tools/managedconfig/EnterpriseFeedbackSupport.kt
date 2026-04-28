package org.bayton.tools.managedconfig

import android.os.Bundle
import androidx.enterprise.feedback.KeyedAppState

fun buildManagedConfigFeedbackStates(
  managedRestrictions: Bundle,
  definitions: List<ManagedConfigDefinition>,
): Set<KeyedAppState> {
  val states = linkedSetOf<KeyedAppState>()
  val managedKeys = managedRestrictions.keySet()

  states +=
    KeyedAppState
      .builder()
      .setKey("managed_config_status")
      .setSeverity(KeyedAppState.SEVERITY_INFO)
      .setMessage(
        if (managedKeys.isEmpty()) {
          "No managed configuration currently set."
        } else {
          "Received ${managedKeys.size} managed keys across ${definitions.size} supported features."
        },
      ).setData("managed_key_count=${managedKeys.size}")
      .build()

  definitions.forEach { definition ->
    states += buildStateForDefinition(definition, managedRestrictions)
  }

  return states
}

fun feedbackSignature(managedRestrictions: Bundle): String = canonicalBundleSignature(managedRestrictions)

private fun buildStateForDefinition(
  definition: ManagedConfigDefinition,
  managedRestrictions: Bundle,
): KeyedAppState {
  if (!managedRestrictions.containsKey(definition.key)) {
    return KeyedAppState
      .builder()
      .setKey(definition.key)
      .setSeverity(KeyedAppState.SEVERITY_INFO)
      .setMessage("Managed config not set.")
      .setData("present=false")
      .build()
  }

  val value = managedRestrictions.valueForKey(definition.key)
  val validationError = validateManagedValue(definition.type, value)
  return if (validationError == null) {
    KeyedAppState
      .builder()
      .setKey(definition.key)
      .setSeverity(KeyedAppState.SEVERITY_INFO)
      .setMessage("Managed config received and rendered.")
      .setData("present=true;type=${definition.type.label}")
      .build()
  } else {
    KeyedAppState
      .builder()
      .setKey(definition.key)
      .setSeverity(KeyedAppState.SEVERITY_ERROR)
      .setMessage(validationError)
      .setData("present=true;type=${definition.type.label}")
      .build()
  }
}

private fun validateManagedValue(type: ManagedConfigValueType, value: Any?): String? =
  when (type) {
    ManagedConfigValueType.BOOL ->
      if (value is Boolean) null else typeMismatch(type, value)
    ManagedConfigValueType.STRING,
    ManagedConfigValueType.CHOICE,
    ManagedConfigValueType.HIDDEN ->
      if (value is String) null else typeMismatch(type, value)
    ManagedConfigValueType.INTEGER ->
      if (value is Int) null else typeMismatch(type, value)
    ManagedConfigValueType.MULTISELECT ->
      if (value is Array<*> && value.all { it is String }) null else typeMismatch(type, value)
    ManagedConfigValueType.BUNDLE ->
      if (value is Bundle) null else typeMismatch(type, value)
    ManagedConfigValueType.BUNDLE_ARRAY ->
      if (value is Array<*> && value.all { it is Bundle }) null else typeMismatch(type, value)
  }

private fun typeMismatch(
  expectedType: ManagedConfigValueType,
  value: Any?,
): String = "Expected ${expectedType.label} but received ${runtimeTypeName(value)}."

private fun runtimeTypeName(value: Any?): String =
  when (value) {
    null -> "null"
    is Array<*> -> "Array"
    is BooleanArray -> "BooleanArray"
    is IntArray -> "IntArray"
    is LongArray -> "LongArray"
    is FloatArray -> "FloatArray"
    is DoubleArray -> "DoubleArray"
    else -> value::class.java.simpleName
  }

private fun canonicalBundleSignature(bundle: Bundle): String =
  buildString {
    appendBundleSignature(this, bundle)
  }

private fun appendBundleSignature(builder: StringBuilder, bundle: Bundle) {
  builder.append('{')
  bundle.keySet().sorted().forEachIndexed { index, key ->
    if (index > 0) builder.append(',')
    builder.append(key)
    builder.append('=')
    appendValueSignature(builder, bundle.valueForKey(key))
  }
  builder.append('}')
}

private fun appendValueSignature(builder: StringBuilder, value: Any?) {
  when (value) {
    null -> builder.append("null")
    is String -> builder.append("string:").append(value)
    is Boolean -> builder.append("bool:").append(value)
    is Int -> builder.append("int:").append(value)
    is Long -> builder.append("long:").append(value)
    is Float -> builder.append("float:").append(value)
    is Double -> builder.append("double:").append(value)
    is Bundle -> {
      builder.append("bundle:")
      appendBundleSignature(builder, value)
    }
    is Array<*> -> {
      builder.append("array[")
      value.forEachIndexed { index, item ->
        if (index > 0) builder.append(',')
        appendValueSignature(builder, item)
      }
      builder.append(']')
    }
    is BooleanArray -> builder.append("booleanArray:").append(value.joinToString(","))
    is IntArray -> builder.append("intArray:").append(value.joinToString(","))
    is LongArray -> builder.append("longArray:").append(value.joinToString(","))
    is FloatArray -> builder.append("floatArray:").append(value.joinToString(","))
    is DoubleArray -> builder.append("doubleArray:").append(value.joinToString(","))
    else -> builder.append("other:").append(value.toString())
  }
}
