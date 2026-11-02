package com.devoid.keysync

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.input.InputManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devoid.keysync.data.local.DataStoreManager
import com.devoid.keysync.data.local.MappingBackupCodec
import com.devoid.keysync.data.local.MumuProfileLibrary
import com.devoid.keysync.domain.touch.MumuProfileCodec
import com.devoid.keysync.domain.touch.TouchDebugRegistry
import kotlinx.serialization.json.*
import com.devoid.keysync.data.external.RootInputConnection
import com.devoid.keysync.model.AppConfig
import com.devoid.keysync.model.DraggableItem
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.model.InputMode
import com.devoid.keysync.service.FloatingBubbleService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject


@HiltViewModel
class MainActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreManager: DataStoreManager
) :
    ViewModel(), Shizuku.OnRequestPermissionResultListener {
    private val TAG = "MainActivityViewModel"
    private val SHIZUKU_REQ_CODE = 10
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shizukuBinder: IBinder? = null
    private val _inputBackendState =
        MutableStateFlow<UiState>(UiState.RootUnavailable)
    val inputBackendState = _inputBackendState.asStateFlow()
    val rootConnectionStatus = RootInputConnection.status
    val rootCaptureStatus = RootInputConnection.captureStatus

    private val _addedPackages = MutableStateFlow<List<String>>(listOf())
    val addedPackages = _addedPackages.asStateFlow()
    private val _mumuLibraries = MutableStateFlow<Map<String, MumuProfileLibrary>>(emptyMap())
    val mumuLibraries = _mumuLibraries.asStateFlow()

    private val _appConfig = MutableStateFlow(AppConfig.Default)
    val appConfig = _appConfig.asStateFlow()


    private val _connectedDevices = MutableStateFlow<Map<Int, String>>(hashMapOf())
    val connectedDevices = _connectedDevices.asStateFlow()

    init {
        viewModelScope.launch { dataStoreManager.getMumuLibraries().collect { _mumuLibraries.value = it } }
        Shizuku.addRequestPermissionResultListener(this)
        Shizuku.addBinderReceivedListener {
            shizukuBinder = Shizuku.getBinder()
            refreshInputBackendState()
        }
        Shizuku.addBinderDeadListener {
            shizukuBinder = null
            refreshInputBackendState()
        }
        getShizukuBinder()
        viewModelScope.launch {
            RootInputConnection.status.collect {
                refreshInputBackendState()
            }
        }
        getConnectedExternalDevices()
        viewModelScope.launch {
            _addedPackages.value = dataStoreManager.getList(DataStoreManager.ADDED_PACKAGES).first()
            _appConfig.value = dataStoreManager.getKeyConfig(DataStoreManager.KEYS_CONFIG).first()
            refreshInputBackendState()
        }
    }

    fun saveKeyConfig(newAppConfig: AppConfig) {
        val oldAppConfig = _appConfig.value
        val newFireKeyCode = newAppConfig.fireKeyCode
        val newShootingModeKeyCode = newAppConfig.shootingModeKeyCode
        val newScopeKeyCode = newAppConfig.scopeKeyCode
        viewModelScope.launch {
            if (newFireKeyCode != oldAppConfig.fireKeyCode ||
                newShootingModeKeyCode != oldAppConfig.shootingModeKeyCode ||
                newScopeKeyCode != oldAppConfig.scopeKeyCode
            ) {
                dataStoreManager.getButtonsConfigKeys().first().forEach {
                    val buttonsConfig = dataStoreManager.getButtons(it).first().toMutableList()
                    buttonsConfig.forEachIndexed { index, value ->
                        if (value is DraggableItem.FixedKey) {
                            val newKeyCode = when (value.type) {
                                DraggableItemType.FIRE -> newFireKeyCode
                                DraggableItemType.SHOOTING_MODE -> {
                                    newShootingModeKeyCode
                                }
                                DraggableItemType.SCOPE -> newScopeKeyCode
                                else -> null
                            }
                            if (newKeyCode != null) {
                                buttonsConfig[index] = value.copy(keyCode = newKeyCode)
                            }
                        }
                    }
                    dataStoreManager.save(it, buttonsConfig)
                }
            }
            dataStoreManager.save(DataStoreManager.KEYS_CONFIG, newAppConfig)
            _appConfig.value = newAppConfig
            if (
                newAppConfig.inputMode == InputMode.ROOT &&
                newAppConfig.inputMode != oldAppConfig.inputMode
            ) {
                RootInputConnection.retry(context)
            }
            refreshInputBackendState()
            if (newAppConfig.inputMode != oldAppConfig.inputMode) {
                // A privilege backend change is a session boundary. Stop any
                // existing mapping instead of changing backend under live pointers.
                context.stopService(Intent(context, FloatingBubbleService::class.java))
            }
        }
    }

    fun exportMappingBackup(uri: Uri, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching {
                val text = MappingBackupCodec.encode(dataStoreManager.createMappingBackup())
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            withContext(Dispatchers.Main) { onFinished(success) }
        }
    }

    fun exportTouchDiagnostics(uri: Uri, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching {
                val json = Json { prettyPrint = true; encodeDefaults = true }
                val report = buildJsonObject {
                    put("version", BuildConfig.VERSION_NAME)
                    put("engine", if (BuildConfig.TOUCH_CORE) "Framework Touch Core" else "Legacy")
                    put("inputMode", _appConfig.value.inputMode.name)
                    put("moveWindowMillis", BuildConfig.TOUCH_MOVE_WINDOW_MILLIS)
                    put("rootConnection", RootInputConnection.status.value.name)
                    put("capture", RootInputConnection.captureStatus.value.toString())
                    put("scope", "Last 256 app-side queue/backend samples only. Includes encoding and Binder return, NOT physical-to-game latency or visual response.")
                    put("touch", TouchDebugRegistry.snapshot()?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                }
                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                    it.write(json.encodeToString(report).toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            withContext(Dispatchers.Main) { onFinished(success) }
        }
    }

    fun importMappingBackup(uri: Uri, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching {
                val backup = requireNotNull(context.contentResolver.openInputStream(uri)).use {
                    MappingBackupCodec.read(it)
                }
                dataStoreManager.restoreMappingBackup(backup)
                withContext(Dispatchers.Main) { context.stopService(Intent(context, FloatingBubbleService::class.java)) }
                _addedPackages.value = dataStoreManager.getList(DataStoreManager.ADDED_PACKAGES).first()
                _appConfig.value = backup.keySettings.applyTo(_appConfig.value)
            }.isSuccess
            withContext(Dispatchers.Main) {
                refreshInputBackendState()
                onFinished(success)
            }
        }
    }

    fun suggestMumuProfileName(uri: Uri, packageName: String): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).substringBeforeLast('.').removePrefix("$packageName-") else "MuMu"
        } ?: "MuMu"
    }.getOrDefault("MuMu").take(MumuProfileLibrary.MAX_NAME_LENGTH)

    fun importMumuProfile(uri: Uri, packageName: String, name: String, onFinished: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val error = runCatching {
                require(BuildConfig.TOUCH_CORE) { "MuMu profiles need Touch Core" }
                val text = requireNotNull(context.contentResolver.openInputStream(uri)).use(MumuProfileCodec::read)
                require(packageName in _addedPackages.value)
                withContext(Dispatchers.Main) { context.stopService(Intent(context, FloatingBubbleService::class.java)) }
                dataStoreManager.saveMumuProfile(packageName, name, text)
            }.exceptionOrNull()?.message
            withContext(Dispatchers.Main) { onFinished(error) }
        }
    }

    fun selectMumuProfile(packageName: String, id: String?) {
        viewModelScope.launch {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
            dataStoreManager.selectMumuProfile(packageName, id)
        }
    }

    fun renameMumuProfile(packageName: String, id: String, name: String) {
        viewModelScope.launch { dataStoreManager.renameMumuProfile(packageName, id, name) }
    }

    fun deleteMumuProfile(packageName: String, id: String) {
        viewModelScope.launch {
            if (_mumuLibraries.value[packageName]?.selectedId == id) {
                context.stopService(Intent(context, FloatingBubbleService::class.java))
            }
            dataStoreManager.deleteMumuProfile(packageName, id)
        }
    }

    fun removePackages(packages: Collection<Int>) {
        viewModelScope.launch {
            _addedPackages.value = _addedPackages.value.filterIndexed { index, packageName ->
                if (index !in packages) {
                    true
                } else {
                    if (appConfig.value.deleteDataOnRemove) {
                        dataStoreManager.remove(DataStoreManager.getButtonsConfigKey(packageName))
                        dataStoreManager.removeMumuProfiles(packageName)
                    }
                    false
                }
            }
            dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, _addedPackages.value)
        }
    }

    fun addPackage(packages: String) {
        if (_addedPackages.value.contains(packages))
            return
        _addedPackages.value = _addedPackages.value.plus(packages)
        viewModelScope.launch {
            dataStoreManager.saveList(DataStoreManager.ADDED_PACKAGES, _addedPackages.value)
        }
    }

    fun getInstalledPackages(): List<PackageInfo> {
        return context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { it.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
    }

    fun getPackageLabel(packageName: String): String {
        try {
            val packageInfo = context.packageManager.getApplicationInfo(packageName, 0)
            return context.packageManager.getApplicationLabel(packageInfo).toString()
        }catch (e:Exception){
            return ContextCompat.getContextForLanguage(context)
                .getString(R.string.app_not_found)
        }
    }

    private fun getConnectedExternalDevices() {
        val inputManager = context.getSystemService(INPUT_SERVICE) as InputManager
        val deviceIds = inputManager.inputDeviceIds
        val devices = HashMap<Int, String>()
        deviceIds.forEach { deviceId ->
            val device = inputManager.getInputDevice(deviceId)
            device?.let {
                if (it.isExternal) {
                    devices[it.id] = getDeviceName(it)
                }
            }
        }
        _connectedDevices.value = devices
        inputManager.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId)
                device?.let {
                    if (it.isExternal) {
                        _connectedDevices.value =
                            _connectedDevices.value.plus(it.id to getDeviceName(it))
                    }
                }
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                _connectedDevices.value = _connectedDevices.value.minus(deviceId)

            }

            override fun onInputDeviceChanged(deviceId: Int) {
                val device = inputManager.getInputDevice(deviceId)
                device?.let {
                    if (it.isExternal) {
                        _connectedDevices.value =
                            _connectedDevices.value.plus(it.id to getDeviceName(it))
                    }
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun getDeviceName(inputDevice: InputDevice): String {
        return inputDevice.name.takeIf { name -> name.isNotEmpty() } ?: when (inputDevice.sources) {
            InputDevice.SOURCE_MOUSE -> localizedString(R.string.device_mouse)
            InputDevice.SOURCE_KEYBOARD -> localizedString(R.string.device_keyboard)
            else -> localizedString(R.string.device_unknown)
        }

    }

    private fun localizedString(resourceId: Int): String =
        ContextCompat.getContextForLanguage(context).getString(resourceId)

    suspend fun getPackageIcon(packageName: String): Drawable {
        return withContext(Dispatchers.IO) {
            try {
                return@withContext   context.packageManager.getApplicationIcon(packageName)
            }catch (e:Exception){
                return@withContext AppCompatResources.getDrawable(context, R.drawable.apk_document)!!
            }
        }
    }

    private fun getShizukuBinder() {
        shizukuBinder = Shizuku.getBinder()
        refreshInputBackendState()
    }

    private fun refreshInputBackendState() {
        _inputBackendState.value = when (_appConfig.value.inputMode) {
            InputMode.ROOT -> if (RootInputConnection.isReady()) {
                UiState.RootRunning
            } else {
                UiState.RootUnavailable
            }
            InputMode.SHIZUKU -> if (shizukuBinder != null) {
                UiState.ShizukuRunning
            } else {
                UiState.ShizukuUnavailable
            }
        }
    }

    fun retryRootConnection() {
        RootInputConnection.retry(context)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            shizukuBinder = Shizuku.getBinder()
            refreshInputBackendState()
        }
    }

    fun requestShizukuPermission(): Boolean {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return false
        }
        // Request the permission
        Shizuku.requestPermission(SHIZUKU_REQ_CODE)
        return true
    }

    fun openGithub() {
        val url = "https://github.com/aka-munan/keysync"
        val i = Intent(Intent.ACTION_VIEW)
        i.setData(Uri.parse(url))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    sealed interface UiState {
        data object RootUnavailable : UiState
        data object RootRunning : UiState
        data object ShizukuUnavailable : UiState
        data object ShizukuRunning : UiState
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(this)
        Log.i("MainActivityViewModel", "onCleared: main viewModelCleared ")
    }

    fun launchPackage(packageName: String, packageManager: PackageManager) {
        try {
            val packageIntent =
                packageManager.getLaunchIntentForPackage(packageName) ?: run {
                    Log.e(TAG, "No launch intent found for $packageName")
                    return
                }

            // Tell an existing overlay to release input focus before Android
            // starts the target activity. The service also uses this command to
            // load the selected mapping while the application draws its first
            // window.
            launchFloatingBubbleService(packageName)

            // Preserve the launcher's normal task flags. REORDER_TO_FRONT can
            // expose a stale activity surface from an existing task, which is
            // especially visible as a permanent black screen in games.
            packageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mainHandler.postDelayed(
                {
                    runCatching { context.startActivity(packageIntent) }
                        .onFailure { Log.e(TAG, "failed to launch package", it) }
                },
                OVERLAY_FOCUS_RELEASE_DELAY_MILLIS
            )
        }catch (e:Exception){
            //failed to launch package
            Log.e(TAG,"failed to launch package",e)
        }
    }
    private fun launchFloatingBubbleService(packageName: String) {
        val serviceIntent = Intent(
            context,
            FloatingBubbleService::class.java
        )
        serviceIntent.putExtra(FloatingBubbleService.INTENT_EXTRA_PACKAGE, packageName)
        context.startForegroundService(serviceIntent)
    }

    private companion object {
        const val OVERLAY_FOCUS_RELEASE_DELAY_MILLIS = 250L
    }
}
