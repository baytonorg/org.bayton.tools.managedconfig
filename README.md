# Managed Config Tool

A reference Android app for validating managed configurations (formerly known
as *application restrictions*) delivered by EMM/MDM platforms, and for sending
keyed app state feedback back up to those platforms so EMM-side receipt can be
validated.

It is intended for IT admins, EMM developers, and Android Enterprise
practitioners who need a transparent target app to confirm that:

1. Managed configurations are being delivered correctly to a managed app.
2. The shape of the payload (top-level scalars, bundles, bundle arrays) is
   what the EMM console claims to be sending.
3. Keyed app state feedback (`androidx.enterprise.feedback`) is being sent to
   the EMM as expected, and the EMM can confirm receipt.

## Bundle-array implementation note

`bundle_array` payloads are the main place where real-world EMM behavior
diverges, so this app treats their runtime shape more carefully than the other
managed-config types.

This project treats the following as materially different runtime shapes:

1. **Direct item bundles** (Google iframe style)
   ```json
   {
     "my_bundle_array_key": [
       {
         "my_bool_key_in_bundle_array": true,
         "my_string_key_in_bundle_array": "value"
       }
     ]
   }
   ```
2. **Wrapped item bundle key**
   ```json
   {
     "my_bundle_array_key": [
       {
         "my_bundle_array_item": {
           "my_bool_key_in_bundle_array": true,
           "my_string_key_in_bundle_array": "value"
         }
       }
     ]
   }
   ```

These are **not** equivalent raw structures:

- In the first, the array item itself is the settings bundle.
- In the second, the array item contains an intermediate wrapper bundle key.

Implementation-wise, the app needs to do three things consistently:

1. Show the **raw received** runtime shape honestly.
2. Detect whether array items were delivered **directly** or under a
   **wrapper bundle key**.
3. Normalize both variants to the same resolved form output, while still
   highlighting which variant was received.

> Managed configurations let an IT admin remotely set values that an app reads
> via `RestrictionsManager.getApplicationRestrictions()`. The app declares the
> available keys in an `app_restrictions.xml` file referenced from the
> `<application>` element of its manifest. See Google's
> [Managed configurations](https://developer.android.com/work/managed-configurations)
> guide for the platform contract.

## App details

| Attribute        | Value                              |
| ---------------- | ---------------------------------- |
| Application ID   | `org.bayton.tools.managedconfig`   |
| `minSdk`         | 28 (Android 9.0)                   |
| `targetSdk`      | 37                                 |
| `compileSdk`     | 37                                 |
| Language         | Kotlin (Jetpack Compose UI)        |
| Build system     | Gradle (Kotlin DSL), AGP 9.x       |

The app declares no runtime permissions, has no network code, and exports only
the launcher activity.

## Building

```bash
./gradlew assembleDebug
```

The resulting APK is written to
`app/build/outputs/apk/debug/app-debug.apk` and can be installed on any
device or emulator running API 28 or above:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For unit tests:

```bash
./gradlew testDebugUnitTest
```

For instrumented tests on a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

A coverage report is produced by:

```bash
./gradlew jacocoDebugUnitTestReport
```

There is no signing config in this repository — release builds will be
unsigned. Add your own signing config locally if you need to ship a release
build.

## What the app declares

The app's `app_restrictions.xml` exposes the eight `restrictionType` values
documented by the platform, so a single deployment exercises every shape an
EMM is likely to push:

| Key                    | `restrictionType` | What it tests                                                 |
| ---------------------- | ----------------- | ------------------------------------------------------------- |
| `my_bool_key`          | `bool`            | Boolean delivery and default-value behavior                   |
| `my_string_key`        | `string`          | Free-form string delivery                                     |
| `my_integer_key`       | `integer`         | 32-bit integer delivery                                       |
| `my_choice_key`        | `choice`          | Single-select from `entryValues`                              |
| `my_multiselect_key`   | `multi-select`    | `String[]` selected from `entryValues`                        |
| `my_hidden_key`        | `hidden`          | `TYPE_NULL` payload (single string, not surfaced in admin UI) |
| `my_bundle_key`        | `bundle`          | Nested `Bundle` of keyed values                               |
| `my_bundle_array_key`  | `bundle_array`    | `Parcelable[]` of `Bundle` entries (API 23+)                  |

Refer to Google's [restriction entry types table](https://developer.android.com/work/managed-configurations#define_managed_configurations)
for the canonical type definitions.

The hidden key (`my_hidden_key`) is included so QA can verify that hidden
managed-config payloads survive delivery intact. This app surfaces that value
on screen rather than masking it, because the goal is to validate delivery and
reconstruction rather than treat the sample payloads as secrets.

## What the app shows

The main screen has two tabs:

### `RestrictionsManager` tab

Reflects exactly what
[`RestrictionsManager.getApplicationRestrictions()`](https://developer.android.com/reference/android/content/RestrictionsManager#getApplicationRestrictions())
returned on the most recent refresh. It shows:

- Whether managed config has been received at all
- The timestamp of the latest refresh
- Per-key entries with the received value rendered with its declared type
- A reconstructed JSON view inferred from the runtime Parcelable payload
- The raw runtime `Bundle` / `Parcelable[]` structure before normalization
- The local status of the most recent keyed app state feedback send attempt,
  when managed config is actually present

The app refreshes restrictions in `onResume()` and on the
[`Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED`](https://developer.android.com/reference/android/content/Intent#ACTION_APPLICATION_RESTRICTIONS_CHANGED)
broadcast, in line with the Android documentation's recommended pattern.

### `Local simulation` tab

Lets you paste a JSON payload and inspect it as a completely separate local
simulation, so you can experiment with payload shapes without involving a DPC
or conflating the result with `RestrictionsManager`. Two formats are supported:

- **Unflattened**: standard nested JSON, e.g.
  ```json
  {
    "my_bool_key": true,
    "my_bundle_key": { "my_string_key_in_bundle": "value" },
    "my_bundle_array_key": [{ "my_string_key_in_bundle_array": "item" }]
  }
  ```
- **Flattened**: dot/bracket paths, e.g.
  ```json
  {
    "my_bool_key": true,
    "my_bundle_key.my_string_key_in_bundle": "value",
    "my_bundle_array_key[0].my_string_key_in_bundle_array": "item"
  }
  ```

The two formats cannot be mixed in a single payload — the app rejects mixed
input with an explicit error. Defensive limits apply: 32 KB max input, max
nesting depth 8, max array length 32, max bundle-array index 31.

The simulation tab does **not** merge on top of the real managed config
anymore. It shows only the parsed local payload, its reconstructed JSON view,
and its `Bundle` / `Parcelable[]` representation. To compare real delivery and
local simulation, swipe between the two tabs.

## Testing managed config delivery

The most reliable way to push managed configs at this app is to use Google's
[Test DPC](https://github.com/googlesamples/android-testdpc):

1. Provision a work profile or fully managed device using Test DPC.
2. Install Managed Config Tool inside the managed profile.
3. In Test DPC, open **Manage app restrictions** → select
   `org.bayton.tools.managedconfig`.
4. Edit the values for any of the keys listed above.
5. Tap **Save** in Test DPC. The system fires
   `ACTION_APPLICATION_RESTRICTIONS_CHANGED`; this app refreshes immediately.

EMM/MDM consoles (Android Management API, Microsoft Intune, VMware Workspace
ONE, IBM MaaS360, etc.) all read the schema from
`app_restrictions.xml` and present it in the admin console. Google's
[managed-configurations iframe](https://developers.google.com/android/management/managed-configurations-iframe)
is the canonical EMM UI; if you implement it, this app's schema renders
correctly there too.

If you need to bypass an EMM altogether for an exploratory test, the
**Local simulation** tab covers the same ground without provisioning.

## Keyed app state feedback

The app uses
[`androidx.enterprise.feedback`](https://developer.android.com/reference/androidx/enterprise/feedback/package-summary)
to send keyed app states back to the EMM whenever the managed payload
changes. This follows the
[App Feedback overview](https://developer.android.com/work/app-feedback/overview)
guidance. Every defined key in `app_restrictions.xml` produces a per-key
state on each refresh — keys absent from the managed bundle report
"Managed config not set." with `SEVERITY_INFO`, so the EMM can confirm
end-to-end visibility even when the IT admin hasn't set a value. A
`managed_config_status` summary state is also emitted with the count of
managed keys received.

The app does **not** act as the source of truth for whether an EMM actually
received or stored those states. The in-app status only reflects the local send
attempt. Final confirmation needs to come from the EMM or Android Management
API device report.

The app does **not** report keyed app states when `RestrictionsManager`
returns an empty bundle on a cold unmanaged / no-config run. If the app had
previously reported real managed config and that config is later removed, it
sends one clearing update so stale EMM-side feedback can be resolved.

Each per-key state reports:

- **Key**: the original managed config key
- **Severity**: `SEVERITY_INFO` for healthy delivery, `SEVERITY_ERROR` when
  the runtime type does not match the declared `restrictionType` (for
  example, an `integer` key that arrived as a `String`)
- **Message**: human-readable description of what the app saw
- **Data**: machine-readable token, e.g. `type=bundle_array`

To confirm feedback is being delivered, run an EMM that surfaces keyed app
states (for example Android Management API, where states show up under
`enterprises.devices.get` → `appState`), or follow Google's
[Testing App Feedback](https://developer.android.com/work/app-feedback/testing)
guide to capture states from a fake reporter.

## Project layout

```
app/
  src/main/java/org/bayton/tools/managedconfig/
    MainActivity.kt              — Activity wiring, RestrictionsManager refresh,
                                   broadcast receiver, debug-only intent injection
    ManagedConfigScreen.kt       — Compose UI (two-tab screen, rendering, inputs)
    ManagedConfigSupport.kt      — JSON ↔ Bundle parser, redaction, format
                                   detection, runtime structure rendering
    EnterpriseFeedbackSupport.kt — Keyed app state construction, signature
                                   stability, type validation
  src/main/res/
    xml/app_restrictions.xml     — Managed config schema referenced by the
                                   manifest <meta-data android:name=
                                   "android.content.APP_RESTRICTIONS"/>
  src/test/                      — Robolectric unit tests
  src/androidTest/               — Instrumented tests (UI Automator + JUnit)
```

## Screenshots


## License

[MIT](LICENSE)
