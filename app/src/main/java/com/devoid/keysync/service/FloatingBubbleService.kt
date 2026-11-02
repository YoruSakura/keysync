package com.devoid.keysync.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.res.Configuration
import android.content.Intent
import android.graphics.PixelFormat
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.devoid.keysync.MainActivity
import com.devoid.keysync.R
import com.devoid.keysync.model.DraggableItemType
import com.devoid.keysync.ui.overlay.FloatingBubble
import com.devoid.keysync.ui.overlay.ItemsContainer
import com.devoid.keysync.ui.overlay.MumuOverlay
import com.devoid.keysync.ui.overlay.MumuLayoutCalibration
import com.devoid.keysync.ui.overlay.MenuItems
import com.devoid.keysync.ui.overlay.ServiceLifecycleOwner
import com.devoid.keysync.ui.overlay.SettingsLayout
import com.devoid.keysync.ui.theme.KeySyncTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt


@AndroidEntryPoint
class FloatingBubbleService : Service() {
    private val TAG = "FloatingBubbleService"
    private lateinit var floatingBubbleLP: WindowManager.LayoutParams
    private lateinit var itemsContainerLP: WindowManager.LayoutParams

    @Inject
    lateinit var stateManager: Lazy<FloatingWindowStateManager>

    companion object {
        const val INTENT_EXTRA_PACKAGE = "launchedPackageName"
        private const val NOTIFICATION_CHANNEL_ID = "channel1"
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_RETRY_DELAY_MILLIS = 100L
        private const val CAPTURE_WATCHDOG_DELAY_MILLIS = 250L
        private const val APP_LAUNCH_FOCUS_GRACE_MILLIS = 2500L
        private const val FOREGROUND_CHECK_INTERVAL_MILLIS = 150L
    }

    private val lifecycleOwner = ServiceLifecycleOwner()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var containerView: PointerCaptureFrameLayout? = null
    private var floatingBubbleView: ComposeView? = null
    private var initialized = false
    private var inputCaptureSuppressedForAppLaunch = false
    private var launchedTargetPackage: String? = null
    private var mappingTargetIsActive = false
    private var foregroundCheckInFlight = false
    private var foregroundCheckGeneration = 0L
    private var windowFocusChangeListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restoreInputCaptureRunnable = Runnable { restoreInputCapture() }
    private val finishAppLaunchGraceRunnable = Runnable { finishAppLaunchFocusGrace() }
    private val foregroundCheckRunnable = Runnable { checkTargetForeground() }
    private val inputCaptureRecovery by lazy {
        InputCaptureRecoveryController(
            onInterrupted = { stateManager.get().onInputCaptureInterrupted() },
            onRestored = { stateManager.get().onInputCaptureRestored() }
        )
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val localizedContext = ContextCompat.getContextForLanguage(this)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            localizedContext.getString(R.string.overlay_service_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val launchedPackageName = intent?.getStringExtra(INTENT_EXTRA_PACKAGE)
        launchedPackageName?.takeIf(String::isNotBlank)?.let {
            launchedTargetPackage = it
            foregroundCheckGeneration++
            beginAppLaunchFocusGrace()
            stateManager.get().loadButtonsConfig(it)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).apply {
            setSmallIcon(R.drawable.key_sync)
            setContentTitle(localizedContext.getString(R.string.app_name))
            setContentText(
                localizedContext.getString(
                    R.string.overlay_service_running,
                    localizedContext.getString(R.string.app_name)
                )
            )
        }.build()
        startForeground(NOTIFICATION_ID, notification)

        if (!initialized) {
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.INITIALIZED) {
                lifecycleOwner.performRestore(null)
            }
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            init()
            initialized = true
        }
        return START_NOT_STICKY
    }

    private fun init() {

        floatingBubbleLP = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        floatingBubbleLP.gravity = Gravity.START or Gravity.TOP

        itemsContainerLP = WindowManager.LayoutParams()
        itemsContainerLP.copyFrom(floatingBubbleLP)

        containerView =
            getItemsContainerView { id ->
                stateManager.get().removeItem(id)
            }

        floatingBubbleView = getFloatingBubbleView(
            onCLick = {
                stateManager.get().onFloatingBubbleClick()
            },
            onAddItem = { itemType ->
                Log.i(TAG, "init: $itemType")
                stateManager.get().addNewItem(itemType)
            }
        )
        scope.launch {
            stateManager.get().isBubbleExpanded.collect { expanded ->
                itemsContainerLP.apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                }
                if (expanded) {
                    stateManager.get().stopInputCapture()
                    containerView?.removeCallbacks(restoreInputCaptureRunnable)
                    inputCaptureRecovery.reset()
                    itemsContainerLP.flags =
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    stateManager.get().windowManager.updateViewLayout(
                        containerView,
                        itemsContainerLP
                    )
                    containerView?.clearFocus()
                    containerView?.releasePointerCapture()
                    stateManager.get().clearActivePointers()
                } else {
                    inputCaptureRecovery.reset()
                    itemsContainerLP.flags = collapsedOverlayFlags()
                    stateManager.get().windowManager.updateViewLayout(
                        containerView,
                        itemsContainerLP
                    )
                    if (!inputCaptureSuppressedForAppLaunch) {
                        stateManager.get().startInputCapture()
                        scheduleInputCaptureRestore(delayMillis = 0)
                    }
                }
            }
        }
        scope.launch {
            stateManager.get().rootInputCaptureActive.collect { active ->
                val view = containerView ?: return@collect
                if (stateManager.get().isBubbleExpanded.value || !view.isAttachedToWindow) {
                    return@collect
                }

                itemsContainerLP.flags = collapsedOverlayFlags()
                runCatching {
                    stateManager.get().windowManager.updateViewLayout(view, itemsContainerLP)
                }.onFailure { Log.w(TAG, "Unable to update root capture overlay state", it) }

                if (active) {
                    updateMappingNotification(R.string.overlay_service_root_active)
                    view.removeCallbacks(restoreInputCaptureRunnable)
                    inputCaptureRecovery.reset()
                    view.releasePointerCapture()
                    view.clearFocus()
                } else if (
                    !inputCaptureSuppressedForAppLaunch &&
                    stateManager.get().overlayInputCaptureAllowed.value
                ) {
                    inputCaptureRecovery.reset()
                    scheduleInputCaptureRestore(delayMillis = 0L)
                }
            }
        }
        scope.launch {
            stateManager.get().overlayInputCaptureAllowed.collect { allowed ->
                if (allowed && !stateManager.get().isBubbleExpanded.value) {
                    updateMappingNotification(R.string.overlay_service_shizuku_active)
                    scheduleInputCaptureRestore(delayMillis = 0L)
                } else if (!allowed) {
                    containerView?.removeCallbacks(restoreInputCaptureRunnable)
                    containerView?.releasePointerCapture()
                    containerView?.clearFocus()
                    if (
                        !inputCaptureSuppressedForAppLaunch &&
                        !stateManager.get().isBubbleExpanded.value
                    ) {
                        stateManager.get().startInputCapture()
                    }
                }
            }
        }
        scope.launch {
            stateManager.get().mappingFailure.filterNotNull().collect { failure ->
                val message = when (failure) {
                    FloatingWindowStateManager.MappingFailure.ROOT_CONNECTION_LOST -> {
                        R.string.mapping_stopped_root_lost
                    }
                    FloatingWindowStateManager.MappingFailure.ROOT_CAPTURE_FAILED -> {
                        R.string.mapping_stopped_root_capture_failed
                    }
                    FloatingWindowStateManager.MappingFailure.INPUT_INJECTION_FAILED -> {
                        R.string.mapping_stopped_injection_failed
                    }
                }
                Toast.makeText(
                    ContextCompat.getContextForLanguage(this@FloatingBubbleService),
                    message,
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
            }
        }
        stateManager.get().windowManager.addView(containerView, itemsContainerLP)
        floatingBubbleLP.y = 200
        stateManager.get().windowManager.addView(floatingBubbleView, floatingBubbleLP)
        stateManager.get().updateDisplayBounds()
        scheduleInputCaptureRestore(delayMillis = 1000)
    }

    private fun scheduleInputCaptureRestore(delayMillis: Long = CAPTURE_RETRY_DELAY_MILLIS) {
        if (
            inputCaptureSuppressedForAppLaunch ||
            stateManager.get().rootInputCaptureActive.value ||
            !stateManager.get().overlayInputCaptureAllowed.value
        ) return
        val view = containerView ?: return
        view.removeCallbacks(restoreInputCaptureRunnable)
        view.postDelayed(restoreInputCaptureRunnable, delayMillis)
    }

    private fun updateMappingNotification(contentText: Int) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val localizedContext = ContextCompat.getContextForLanguage(this)
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.key_sync)
            .setContentTitle(localizedContext.getString(R.string.app_name))
            .setContentText(localizedContext.getString(contentText))
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun restoreInputCapture() {
        val view = containerView ?: return
        if (
            inputCaptureSuppressedForAppLaunch ||
            stateManager.get().rootInputCaptureActive.value ||
            !stateManager.get().overlayInputCaptureAllowed.value ||
            !view.isAttachedToWindow ||
            stateManager.get().isBubbleExpanded.value
        ) return

        val hasCompleteCapture =
            view.hasWindowFocus() && view.hasFocus() && view.hasPointerCapture()
        if (hasCompleteCapture) {
            inputCaptureRecovery.reportAvailable()
            val nextCheck = if (inputCaptureRecovery.isInterrupted()) {
                CAPTURE_RETRY_DELAY_MILLIS
            } else {
                CAPTURE_WATCHDOG_DELAY_MILLIS
            }
            scheduleInputCaptureRestore(nextCheck)
            return
        }

        inputCaptureRecovery.reportUnavailable()

        if (!view.hasWindowFocus()) {
            // A real screen touch transfers focus to the game. Re-register the
            // touch-through window as focusable before requesting capture again.
            val focusableFlags =
                itemsContainerLP.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            itemsContainerLP.flags =
                focusableFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching {
                stateManager.get().windowManager.updateViewLayout(view, itemsContainerLP)
            }.onFailure { Log.w(TAG, "Unable to release overlay focus", it) }
            itemsContainerLP.flags = focusableFlags
            runCatching {
                stateManager.get().windowManager.updateViewLayout(view, itemsContainerLP)
            }.onFailure { Log.w(TAG, "Unable to restore overlay focusability", it) }
        }

        if (!view.hasFocus()) view.requestFocus()
        view.post {
            if (
                !stateManager.get().isBubbleExpanded.value &&
                view.hasWindowFocus() &&
                !view.hasPointerCapture()
            ) {
                runCatching { view.requestPointerCapture() }
                    .onFailure { Log.w(TAG, "Unable to restore pointer capture", it) }
            }
        }
        scheduleInputCaptureRestore()
    }

    /**
     * A focusable full-screen overlay can pre-empt the first window of an app
     * launched from KeySync. Some renderers never recover that surface and stay
     * black. Keep the overlay visible but non-focusable during the task switch;
     * mapping capture is enabled after the target has had time to draw.
     */
    private fun beginAppLaunchFocusGrace() {
        inputCaptureSuppressedForAppLaunch = true
        setMappingTargetActive(false)
        stateManager.get().stopInputCapture()
        mainHandler.removeCallbacks(foregroundCheckRunnable)
        mainHandler.removeCallbacks(finishAppLaunchGraceRunnable)
        mainHandler.postDelayed(
            finishAppLaunchGraceRunnable,
            APP_LAUNCH_FOCUS_GRACE_MILLIS
        )

        val view = containerView ?: return
        view.removeCallbacks(restoreInputCaptureRunnable)
        inputCaptureRecovery.reset()
        view.releasePointerCapture()
        view.clearFocus()
        if (!stateManager.get().isBubbleExpanded.value && view.isAttachedToWindow) {
            itemsContainerLP.flags = collapsedOverlayFlags()
            runCatching {
                stateManager.get().windowManager.updateViewLayout(view, itemsContainerLP)
            }.onFailure { Log.w(TAG, "Unable to suspend overlay focus for app launch", it) }
        }
    }

    private fun finishAppLaunchFocusGrace() {
        inputCaptureSuppressedForAppLaunch = false
        val view = containerView
        if (
            view != null &&
            view.isAttachedToWindow &&
            !stateManager.get().isBubbleExpanded.value
        ) {
            stateManager.get().updateDisplayBounds()
            itemsContainerLP.flags = collapsedOverlayFlags()
            runCatching {
                stateManager.get().windowManager.updateViewLayout(view, itemsContainerLP)
            }.onFailure {
                Log.w(TAG, "Unable to finish overlay app-launch grace", it)
            }
            inputCaptureRecovery.reset()
        }
        scheduleForegroundCheck(delayMillis = 0L)
    }

    private fun scheduleForegroundCheck(
        delayMillis: Long = FOREGROUND_CHECK_INTERVAL_MILLIS
    ) {
        if (inputCaptureSuppressedForAppLaunch || launchedTargetPackage == null) return
        mainHandler.removeCallbacks(foregroundCheckRunnable)
        mainHandler.postDelayed(foregroundCheckRunnable, delayMillis)
    }

    private fun checkTargetForeground() {
        if (foregroundCheckInFlight || inputCaptureSuppressedForAppLaunch) {
            scheduleForegroundCheck()
            return
        }
        val targetPackage = launchedTargetPackage ?: return
        val generation = foregroundCheckGeneration
        foregroundCheckInFlight = true
        scope.launch(Dispatchers.IO) {
            val focusedPackage = stateManager.get().getFocusedPackageName()
            mainHandler.post {
                foregroundCheckInFlight = false
                if (
                    !initialized ||
                    generation != foregroundCheckGeneration ||
                    targetPackage != launchedTargetPackage
                ) return@post

                setMappingTargetActive(focusedPackage == targetPackage)
                scheduleForegroundCheck()
            }
        }
    }

    private fun setMappingTargetActive(active: Boolean) {
        if (mappingTargetIsActive == active) return
        mappingTargetIsActive = active
        stateManager.get().setMappingTargetActive(active)

        val view = containerView ?: return
        view.removeCallbacks(restoreInputCaptureRunnable)
        inputCaptureRecovery.reset()
        itemsContainerLP.flags = collapsedOverlayFlags()
        runCatching {
            stateManager.get().windowManager.updateViewLayout(view, itemsContainerLP)
        }.onFailure { Log.w(TAG, "Unable to update foreground mapping state", it) }

        if (!active) {
            view.releasePointerCapture()
            view.clearFocus()
            updateMappingNotification(R.string.overlay_service_target_paused)
            return
        }

        stateManager.get().updateDisplayBounds()
        if (!stateManager.get().isBubbleExpanded.value) {
            stateManager.get().startInputCapture()
            scheduleInputCaptureRestore(delayMillis = 0L)
        }
    }

    private fun collapsedOverlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            if (
                inputCaptureSuppressedForAppLaunch ||
                stateManager.get().rootInputCaptureActive.value ||
                !stateManager.get().overlayInputCaptureAllowed.value
            ) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                0
            }


    private fun getFloatingBubbleView(
        onCLick: () -> Unit,
        onAddItem: (DraggableItemType) -> Unit
    ): ComposeView {
        val composeView = ComposeView(ContextCompat.getContextForLanguage(this))
        composeView.setContent {
            FloatingBubbleLayout(
                onAddItem = onAddItem,
                onBubbleClick = onCLick
            )
        }
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        return composeView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun getItemsContainerView(
        onRemove: (Int) -> Unit
    ): PointerCaptureFrameLayout {
        val localizedContext = ContextCompat.getContextForLanguage(this)
        val rootView = PointerCaptureFrameLayout(localizedContext)
        val composeView = ComposeView(localizedContext)
        composeView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        composeView.setContent {
            KeySyncTheme {
                val draggableItems by stateManager.get().containerItems.collectAsState()
                val itemsContainerOpacity by stateManager.get().overlayOpacity.collectAsState()
                val isBubbleExpanded by stateManager.get().isBubbleExpanded.collectAsState()
                val isShootingMode by stateManager.get().isShootingMode.collectAsState()
                val pointerOffset = stateManager.get().pointerOffset
                val keysConfig by stateManager.get().keysConfig.collectAsState()
                val mumuProfile by stateManager.get().mumuProfile.collectAsState()
                Box {
                    val mappingTargetActive by stateManager.get()
                        .mappingTargetActive.collectAsState()
                    if (mappingTargetActive && !isBubbleExpanded && !isShootingMode) {
                        Image(///mouse pointer
                            modifier = Modifier.graphicsLayer {
                                // Read cursor state during the layer phase so
                                // movement does not recompose or relayout the
                                // complete full-screen overlay.
                                translationX = pointerOffset.value.x
                                translationY = pointerOffset.value.y
                            },
                            painter = painterResource(R.drawable.mouse),
                            contentDescription = stringResource(R.string.mouse_pointer)
                        )
                    }
                    if (!isBubbleExpanded && (!mappingTargetActive || itemsContainerOpacity == 0f))
                        return@Box//do not compose if user set overlay opacity to 0
                    val profile = mumuProfile
                    if (profile != null && isBubbleExpanded) {
                        val positions by stateManager.get().mumuLayoutEdits.collectAsState()
                        val saving by stateManager.get().mumuLayoutSaving.collectAsState()
                        MumuLayoutCalibration(
                            profile = profile,
                            positions = positions,
                            saving = saving,
                            onMove = stateManager.get()::moveMumuTarget,
                            onSave = stateManager.get()::onFloatingBubbleClick,
                            onCancel = stateManager.get()::cancelMumuLayout
                        )
                    } else if (profile != null) {
                        MumuOverlay(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (isBubbleExpanded) 1f else itemsContainerOpacity),
                            profile = profile,
                            isAiming = isShootingMode
                        )
                    } else {
                        ItemsContainer(
                            Modifier.alpha(if (isBubbleExpanded) 1f else itemsContainerOpacity),
                            appConfig = keysConfig,
                            containerItems = draggableItems,
                            onRemove = onRemove,
                        )
                    }
                }
            }
        }
        rootView.addView(composeView)
        rootView.setViewTreeLifecycleOwner(lifecycleOwner)
        rootView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        rootView.isFocusable = true
        rootView.isClickable = true
        rootView.isFocusableInTouchMode = true
        rootView.addOnLayoutChangeListener { _, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                stateManager.get().updateDisplayBounds(width, height)
            }
        }
        val focusChangeListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (
                !inputCaptureSuppressedForAppLaunch &&
                stateManager.get().overlayInputCaptureAllowed.value &&
                !stateManager.get().rootInputCaptureActive.value &&
                !stateManager.get().isBubbleExpanded.value
            ) {
                if (!hasFocus) inputCaptureRecovery.reportUnavailable()
                scheduleInputCaptureRestore(
                    if (hasFocus) 0L else CAPTURE_RETRY_DELAY_MILLIS
                )
            }
        }
        windowFocusChangeListener = focusChangeListener
        rootView.viewTreeObserver.addOnWindowFocusChangeListener(focusChangeListener)
        rootView.onPointerCaptureChanged = { hasCapture ->
            if (
                !inputCaptureSuppressedForAppLaunch &&
                stateManager.get().overlayInputCaptureAllowed.value &&
                !stateManager.get().rootInputCaptureActive.value &&
                !stateManager.get().isBubbleExpanded.value
            ) {
                if (hasCapture) {
                    inputCaptureRecovery.reportAvailable()
                    scheduleInputCaptureRestore(delayMillis = CAPTURE_RETRY_DELAY_MILLIS)
                } else {
                    inputCaptureRecovery.reportUnavailable()
                    scheduleInputCaptureRestore(delayMillis = 0L)
                }
            }
        }
        rootView.post {
            rootView.setOnCapturedPointerListener { _, motionEvent ->
                stateManager.get().onMouseEvent(motionEvent)
            }
        }
        rootView.setOnKeyListener { _, _, event ->
            stateManager.get().onKeyEvent(event)
        }
        return rootView
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!initialized) return
        containerView?.post { stateManager.get().updateDisplayBounds() }
    }

    override fun onDestroy() {
        initialized = false
        foregroundCheckGeneration++
        foregroundCheckInFlight = false
        mainHandler.removeCallbacks(finishAppLaunchGraceRunnable)
        mainHandler.removeCallbacks(foregroundCheckRunnable)
        containerView?.removeCallbacks(restoreInputCaptureRunnable)
        inputCaptureRecovery.reset()
        windowFocusChangeListener?.let { listener ->
            containerView?.viewTreeObserver
                ?.takeIf { it.isAlive }
                ?.removeOnWindowFocusChangeListener(listener)
        }
        windowFocusChangeListener = null
        containerView?.onPointerCaptureChanged = null
        removeAllViews()
        stateManager.get().onDestroy()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun removeAllViews() {
        containerView?.let {
            stateManager.get().windowManager.removeViewImmediate(it)
        }
        floatingBubbleView?.let {
            stateManager.get().windowManager.removeViewImmediate(it)
        }
    }

    @Composable
    private fun FloatingBubbleLayout(
        modifier: Modifier = Modifier,
        onAddItem: (DraggableItemType) -> Unit,
        onBubbleClick: () -> Unit
    ) {
        var opacity by remember { mutableFloatStateOf(0.5f) }
        val isExpanded by stateManager.get().isBubbleExpanded.collectAsState()
        val mumuProfile by stateManager.get().mumuProfile.collectAsState()
        var itemsMenuVisible by remember { mutableStateOf(false) }
        var settingsLayoutVisible by remember { mutableStateOf(false) }
        LaunchedEffect(isExpanded) {
            opacity = if (isExpanded) 1f else 0.5f
            itemsMenuVisible = isExpanded && itemsMenuVisible
            settingsLayoutVisible = isExpanded && settingsLayoutVisible
        }
        Box(
            modifier = modifier
                .alpha(opacity)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            opacity = 1.0f
                        }, onDragEnd = {
                            if (!isExpanded) opacity = 0.5f
                            if (floatingBubbleLP.x < baseContext.resources.displayMetrics.widthPixels / 2) {
                                floatingBubbleLP.x = 0
                                stateManager.get().windowManager.updateViewLayout(
                                    floatingBubbleView,
                                    floatingBubbleLP
                                )
                            } else {
                                floatingBubbleLP.x =
                                    baseContext.resources.displayMetrics.widthPixels - (floatingBubbleView?.width
                                        ?: 0)
                                stateManager.get().windowManager.updateViewLayout(
                                    floatingBubbleView,
                                    floatingBubbleLP
                                )
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        floatingBubbleLP.x += dragAmount.x.roundToInt()
                        floatingBubbleLP.y += dragAmount.y.roundToInt()
                        stateManager.get().windowManager.updateViewLayout(
                            floatingBubbleView,
                            floatingBubbleLP
                        )
                    }
                }
        ) {
            ConstraintLayout {
                val (background, bubbleLayout, otherLayout, bubble) = createRefs()
                Box(
                    modifier = Modifier
                        .constrainAs(background) {
                            start.linkTo(bubble.start)
                            top.linkTo(bubble.top)
                            end.linkTo(bubble.end)
                            bottom.linkTo(bubbleLayout.bottom)
                            height = Dimension.fillToConstraints
                            width = Dimension.fillToConstraints
                        }
                        .background(color = MaterialTheme.colorScheme.surface, CircleShape)
                )
                FloatingBubble(
                    modifier = Modifier
                        .size(50.dp)
                        .constrainAs(bubble) { },
                    expanded = isExpanded, onClick = onBubbleClick
                )
                AnimatedVisibility(
                    visible = isExpanded,
                    Modifier.constrainAs(bubbleLayout) {
                        top.linkTo(bubble.bottom)
                        end.linkTo(bubble.end)
                    }) {
                    Column {
                        if (mumuProfile == null) {
                            IconButton(onClick = {
                                itemsMenuVisible = !itemsMenuVisible
                                settingsLayoutVisible = false
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = stringResource(R.string.add_item)
                                )
                            }
                        }

                        IconButton(onClick = {
                            settingsLayoutVisible = !settingsLayoutVisible
                            itemsMenuVisible = false
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = (itemsMenuVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                        }) {
                    MenuItems(onItemClick = {
                        onAddItem(it)
                        itemsMenuVisible = false
                    })
                }
                AnimatedVisibility(
                    visible = (settingsLayoutVisible),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .constrainAs(otherLayout) {
                            start.linkTo(bubble.end)
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            end.linkTo(parent.end)
                            width = Dimension.preferredWrapContent
                        }) {
                    val pointerSensitivity by stateManager.get().pointerSensitivity.collectAsState()
                    val overlayOpacity by stateManager.get().overlayOpacity.collectAsState()
                    SettingsLayout(
                        pointerSensitivity = pointerSensitivity,
                        overlayOpacity = overlayOpacity,
                        onOpacityChange = { stateManager.get().overlayOpacity.value = it },
                        onPointerSensChange = { stateManager.get().pointerSensitivity.value = it },
                        onAdvancedSettingsClick = {
                            val intent = Intent(baseContext, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                action = MainActivity.INTENT_ACTION_SETTINGS
                            }
                            baseContext.startActivity(intent)
                            stateManager.get().onFloatingBubbleClick()
                        },
                        onCloseOverlayClick = {
                            stopSelf()
                        })
                }
            }
        }
    }
}


@Preview
@Composable
fun PreviewFloatingBubble() {
    Box(
        modifier = Modifier
    ) {
        ConstraintLayout {
            val (background, bubbleLayout, otherLayout, bubble) = createRefs()
            Box(
                modifier = Modifier
                    .constrainAs(background) {
                        start.linkTo(bubble.start)
                        top.linkTo(bubble.top)
                        end.linkTo(bubble.end)
                        bottom.linkTo(bubbleLayout.bottom)
                        height = Dimension.fillToConstraints
                        width = Dimension.fillToConstraints
                    }
                    .background(color = MaterialTheme.colorScheme.surface, CircleShape)
            )
            FloatingBubble(
                modifier = Modifier
                    .size(50.dp)
                    .constrainAs(bubble) { },
                expanded = true, onClick = { })
            AnimatedVisibility(
                visible = true,
                Modifier.constrainAs(bubbleLayout) {
                    top.linkTo(bubble.bottom)
                    end.linkTo(bubble.end)
                }) {
                Column {
                    IconButton(onClick = {
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.add_item)
                        )
                    }

                    IconButton(onClick = {
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = (false),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .constrainAs(otherLayout) {
                        start.linkTo(bubble.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                    }) {
                MenuItems(onItemClick = { })
            }
            AnimatedVisibility(
                visible = (true),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .constrainAs(otherLayout) {
                        start.linkTo(bubble.end)
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        end.linkTo(parent.end)
                        width = Dimension.preferredWrapContent
                    }) {
                SettingsLayout(
                    onAdvancedSettingsClick = {},
                    onPointerSensChange = {},
                    onOpacityChange = { },
                    onCloseOverlayClick = {}
                )
            }
        }
    }
}
