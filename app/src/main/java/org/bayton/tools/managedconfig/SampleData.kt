package org.bayton.tools.managedconfig

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

internal val sampleOverrideJson =
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

internal val invalidSampleOverrideJson =
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
        "my_bundle_array_item": {
          "my_bool_key_in_bundle_array": true,
          "my_string_key_in_bundle_array": "array-item-1",
          "my_choice_key_in_bundle_array": "another"
        }
      },
      {
        "my_bundle_array_item": {
          "my_bool_key_in_bundle_array": false,
          "my_string_key_in_bundle_array": "array-item-2",
          "my_choice_key_in_bundle_array": ""
        }
      }
    ]
  }
  """.trimIndent()
