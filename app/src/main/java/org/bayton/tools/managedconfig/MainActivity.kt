package org.bayton.tools.managedconfig

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.collectLatest
import org.bayton.tools.managedconfig.theme.BaytonManagedConfigTheme

class MainActivity : ComponentActivity() {
  private val managedConfigViewModel: ManagedConfigViewModel by viewModels {
    viewModelFactory {
      initializer {
        ManagedConfigViewModel(
          managedConfigRepository = AndroidManagedConfigRepository(applicationContext),
          installedAppsRepository = AndroidInstalledAppsRepository(applicationContext),
          keyedAppStatesPublisher = AndroidKeyedAppStatesPublisher(applicationContext),
          currentPackageName = packageName,
          isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
          savedStateHandle = createSavedStateHandle(),
        )
      }
    }
  }
  private var receiverRegistered = false

  private val restrictionsReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        managedConfigViewModel.refreshRestrictions("Restrictions changed broadcast")
      }
    }

  private val packageChangedReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        managedConfigViewModel.refreshImportedAppsAndSelectedSchema(
          changedPackageName = intent?.data?.schemeSpecificPart,
          source = "Package changed broadcast",
        )
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.auto(LIGHT_SYSTEM_BAR_SCRIM, DARK_SYSTEM_BAR_SCRIM),
      navigationBarStyle = SystemBarStyle.auto(LIGHT_SYSTEM_BAR_SCRIM, DARK_SYSTEM_BAR_SCRIM),
    )

    managedConfigViewModel.initialize()
    consumeManagedConfigIntent(intent)

    setContent {
      val uiState by managedConfigViewModel.uiState.collectAsStateWithLifecycle()

      LaunchedEffect(Unit) {
        managedConfigViewModel.uiEvents.collectLatest { event ->
          when (event) {
            is ManagedConfigUiEvent.ToastMessage ->
              Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
          }
        }
      }

      BaytonManagedConfigTheme {
        ManagedConfigScreen(
          uiState = uiState,
          knownConfigs = managedConfigDefinitions,
          onEditorValueChange = managedConfigViewModel::updateEditorJson,
          onApplyLocalOverride = managedConfigViewModel::applyCurrentEditor,
          onClearLocalOverride = managedConfigViewModel::clearEditor,
          onLoadSampleOverride = managedConfigViewModel::loadSampleOverride,
          onLoadInvalidSampleOverride = managedConfigViewModel::loadInvalidSampleOverride,
          onLoadEmmPayload = managedConfigViewModel::loadEmmPayloadIntoEditor,
          onSelectImportedSchemaPackage = managedConfigViewModel::selectImportedSchemaPackage,
        )
      }
    }
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
      ContextCompat.registerReceiver(
        this,
        packageChangedReceiver,
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_CHANGED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addDataScheme("package")
        },
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
      receiverRegistered = true
    }
  }

  override fun onResume() {
    super.onResume()
    managedConfigViewModel.refreshRestrictions("Activity resume")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    consumeManagedConfigIntent(intent)
  }

  override fun onStop() {
    if (receiverRegistered) {
      unregisterReceiver(restrictionsReceiver)
      unregisterReceiver(packageChangedReceiver)
      receiverRegistered = false
    }
    super.onStop()
  }

  private fun consumeManagedConfigIntent(intent: Intent?) {
    val json = intent?.getStringExtra(EXTRA_MANAGED_CONFIG_JSON) ?: return
    intent.removeExtra(EXTRA_MANAGED_CONFIG_JSON)
    managedConfigViewModel.applyIncomingManagedConfigJson(json)
  }
}

internal const val EXTRA_MANAGED_CONFIG_JSON = "managed_config_json"
internal const val EMPTY_MANAGED_CONFIG_SIGNATURE = "__empty_managed_config__"
private const val LIGHT_SYSTEM_BAR_SCRIM = 0xFFF7F7F7.toInt()
private const val DARK_SYSTEM_BAR_SCRIM = 0xFF141218.toInt()
