package ai.opencyvis

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.overlay.BlackoutOverlay
import ai.opencyvis.overlay.OverlayWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Hosts the slim floating overlay (chat-head ⇄ pill).
 *
 * Visibility rule (single source of truth): the overlay is attached to
 * the WindowManager iff
 *   (OpenCyvis is in background)  AND  (agent is Running or WaitingForUser
 *   or Paused-while-takeover).
 *
 * All visibility transitions go through `evaluateVisibility()` so foreground/
 * background flips, agent state changes, and engine swaps converge to the
 * same single decision. We never call `WindowManager.addView` from anywhere
 * else — `OverlayWindow.updateState()` only refreshes cached text/colors.
 *
 * Routes the user back to their last-foreground Activity (ControlPanel or
 * ViewActivity) when they tap the pill body or the heads-up notification.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        @Volatile var lastForegroundActivityClass: Class<out Activity>? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayWindow: OverlayWindow? = null
    private var agentService: AgentService? = null
    private var bound = false
    private var engineSubscriptionJob: Job? = null
    private var stateCollectionJob: Job? = null
    private var stepResultCollectionJob: Job? = null
    private var isAppInForeground = false

    // 黑屏挂机遮罩（双击胶囊进入）
    private var blackoutOverlay: BlackoutOverlay? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    // 当前处于前台的 Activity（弱引用防泄漏），用于"任务运行时保持常亮"给其 window 加/清 flag
    private var currentActivity = WeakReference<Activity>(null)

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            isAppInForeground = true
            Log.d(TAG, "App foreground")
            evaluateVisibility()
        }

        override fun onStop(owner: LifecycleOwner) {
            isAppInForeground = false
            Log.d(TAG, "App background")
            evaluateVisibility()
        }
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            currentActivity = WeakReference(a)
            if (a.javaClass.`package`?.name?.startsWith("ai.opencyvis") == true) {
                lastForegroundActivityClass = a.javaClass
                Log.d(TAG, "lastForegroundActivity = ${a.javaClass.simpleName}")
            }
            applyKeepScreenOn(a)
        }
        override fun onActivityPaused(a: Activity) {
            if (currentActivity.get() === a) currentActivity.clear()
        }
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AgentService.AgentBinder)?.getService()
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")
            subscribeToEngineFlow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            engineSubscriptionJob?.cancel()
            stateCollectionJob?.cancel()
            stepResultCollectionJob?.cancel()
            evaluateVisibility()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OverlayService created")

        val config = ConfigRepository(this)
        overlayWindow = OverlayWindow(this).apply {
            maxSteps = config.maxSteps
            callback = object : OverlayWindow.Callback {
                override fun onReturnToApp() {
                    val cls = lastForegroundActivityClass
                        ?: ai.opencyvis.ui.ControlPanelActivity::class.java
                    val intent = Intent(this@OverlayService, cls).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        if (agentService?.displayState == AgentService.DisplayState.TAKEOVER) {
                            putExtra(ai.opencyvis.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
                        }
                    }
                    startActivity(intent)
                }

                override fun onPillDoubleTap() {
                    enterBlackout()
                }
            }
            // Inflate the views and wire callbacks, but DO NOT attach to the
            // WindowManager yet — `evaluateVisibility()` is the only path
            // that decides whether we should be visible.
            prepare()
        }

        application.registerActivityLifecycleCallbacks(activityCallbacks)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        val intent = Intent(this, AgentService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        exitBlackout()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        overlayWindow?.dismiss()
        overlayWindow = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        Log.i(TAG, "OverlayService destroyed")
        super.onDestroy()
    }

    /**
     * Subscribe to AgentService.engineFlow. Each time the engine instance
     * changes, re-bind state/stepResult collectors against the new flow
     * objects (since `engineFlow` swaps both `stateFlow` and
     * `stepResultFlow` underneath).
     */
    private fun subscribeToEngineFlow() {
        engineSubscriptionJob?.cancel()
        val service = agentService ?: return
        engineSubscriptionJob = scope.launch {
            service.engineFlow.collect { engine ->
                if (engine != null) {
                    val cfg = ConfigRepository(this@OverlayService)
                    overlayWindow?.maxSteps = cfg.maxSteps
                    overlayWindow?.currentInstruction = service.currentInstruction
                    collectStateUpdates()
                } else {
                    stateCollectionJob?.cancel()
                    stepResultCollectionJob?.cancel()
                }
                evaluateVisibility()
            }
        }
    }

    private fun collectStateUpdates() {
        stateCollectionJob?.cancel()
        stepResultCollectionJob?.cancel()

        stateCollectionJob = agentService?.stateFlow?.let { stateFlow ->
            scope.launch {
                var wasRunning = false
                stateFlow.collect { state ->
                    overlayWindow?.updateState(state)
                    applyKeepScreenOn(currentActivity.get())
                    if (state is AgentState.Running) wasRunning = true
                    if (wasRunning && (state is AgentState.Idle || state is AgentState.Error)) {
                        // 任务结束：按配置同步退出黑屏遮罩
                        if (blackoutOverlay != null &&
                            ConfigRepository(this@OverlayService).overlayDimDismissOnTaskEnd
                        ) {
                            exitBlackout()
                        }
                        kotlinx.coroutines.delay(3000)
                        wasRunning = false
                    }
                    evaluateVisibility()
                }
            }
        }

        stepResultCollectionJob = agentService?.stepResultFlow?.let { resultFlow ->
            scope.launch {
                resultFlow.collect { result ->
                    overlayWindow?.addStepResult(result)
                }
            }
        }
    }

    /**
     * Single source of truth for whether the overlay should be on screen.
     * Combines: (1) is OpenCyvis in the background, (2) is the agent in an
     * "active" state (Running / WaitingForUser / Paused-takeover).
     */
    private fun evaluateVisibility() {
        // 黑屏遮罩显示期间不显示胶囊，避免任务状态变化导致胶囊浮到遮罩之上
        if (blackoutOverlay != null) {
            overlayWindow?.detach()
            return
        }
        val service = agentService
        val state = service?.stateFlow?.value
        val active = service?.isOverlayActiveState(state) == true
        val shouldShow = !isAppInForeground && active
        Log.d(TAG, "evaluateVisibility: fg=$isAppInForeground active=$active → show=$shouldShow")
        if (shouldShow) overlayWindow?.attach() else overlayWindow?.detach()
    }

    // ── 黑屏挂机遮罩（双击胶囊进入） ───────────────────────────────────────

    private fun enterBlackout() {
        val config = ConfigRepository(this)
        if (!config.overlayDimEnabled) return
        if (blackoutOverlay != null) return
        val overlay = BlackoutOverlay(
            this,
            config.overlayDimExitTaps,
            config.overlayDimKeepScreenOn
        ) { exitBlackout() }
        blackoutOverlay = overlay
        overlay.show()
        registerScreenOffReceiver()
        overlayWindow?.detach()
        Log.i(TAG, "Blackout entered")
    }

    /** 幂等：退出遮罩、注销锁屏监听，并让 evaluateVisibility 恢复胶囊显隐。 */
    private fun exitBlackout() {
        val overlay = blackoutOverlay ?: return
        blackoutOverlay = null
        unregisterScreenOffReceiver()
        overlay.dismiss()
        evaluateVisibility()
        Log.i(TAG, "Blackout exited")
    }

    /** 锁屏（或系统超时熄屏）时退出黑屏遮罩，解锁后看到正常界面。仅在遮罩期间注册。 */
    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) exitBlackout()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenOffReceiver = receiver
    }

    private fun unregisterScreenOffReceiver() {
        val receiver = screenOffReceiver ?: return
        screenOffReceiver = null
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ── 任务运行时保持屏幕常亮（独立设置） ─────────────────────────────────

    /** 任务活跃且配置开启时给前台 Activity 加 FLAG_KEEP_SCREEN_ON，否则清除。
     *  在 onActivityResumed 与任务状态变化时调用。 */
    private fun applyKeepScreenOn(activity: Activity?) {
        val target = activity ?: return
        val state = agentService?.stateFlow?.value
        val active = agentService?.isOverlayActiveState(state) == true
        val keepOn = ConfigRepository(this).keepScreenOnWhileRunning && active
        if (keepOn) {
            target.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            target.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
