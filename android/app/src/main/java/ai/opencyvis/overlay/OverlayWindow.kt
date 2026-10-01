package ai.opencyvis.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.opencyvis.R
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult

/**
 * Floating overlay: chat-head (D-style, corner) ⇄ island (A-style, centered top).
 *
 * Minimized = gradient chat-head ball in top-right corner, draggable.
 * Expanded  = Dynamic Island capsule centered at top (task name + status + stop).
 *
 * Tap chat-head → expand to island.
 * Tap island body → return to app, collapse to chat-head.
 * WaitingForUser → auto-expand to island.
 */
class OverlayWindow(private val context: Context) {

    interface Callback {
        fun onReturnToApp()
        /** 双击胶囊：请求进入黑屏挂机遮罩 */
        fun onPillDoubleTap() {}
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val config = ConfigRepository(context)
    private val debugMode = config.debugMode

    // Island (expanded) view — Dynamic Island style
    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillDot: View? = null
    private var pillStep: TextView? = null
    // 胶囊背景中表示已执行步数的灰色 clip 段
    private var pillProgressClip: ClipDrawable? = null

    // Chat-head (minimized) view — gradient ball
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private var chatHead: ImageView? = null

    var callback: Callback? = null
    var maxSteps: Int = 100
        set(value) {
            field = value
            // 分母变化后按当前步数重算进度
            updateProgress(currentStep)
        }

    var currentInstruction: String = ""

    // Latest per-step status text (same as chat area). Null until the first step result arrives.
    private var lastStepText: String? = null

    private var isExpanded = false
    private var isPrepared = false
    private var isAttached = false
    private var currentStep: Int = 0
    private var _currentState: AgentState = AgentState.Idle()

    private val dragSlop: Int = (10 * context.resources.displayMetrics.density).toInt()

    // 胶囊手势：单击收起 + 双击进入黑屏挂机遮罩。
    // onSingleTapConfirmed 需等待双击超时窗口（约 300ms），单击收起会略有延迟。
    private val pillGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            setExpanded(false)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            callback?.onPillDoubleTap()
            return true
        }
    })
    private var glowAnimator: ObjectAnimator? = null
    private var dotAnimator: ObjectAnimator? = null
    private var progressBlinkAnimator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { setExpanded(false) }

    internal var attachCount: Int = 0
        private set
    internal var detachCount: Int = 0
        private set

    private val colorIdle = 0xFF888888.toInt()
    private val colorRunning = 0xFF4ADE80.toInt()
    private val colorWaiting = 0xFFFA8C16.toInt()
    private val colorPaused = 0xFFFFCBA1.toInt()
    private val colorError = 0xFFEF4444.toInt()
    private val colorSuccess = 0xFF4CAF50.toInt()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    fun prepare() {
        if (isPrepared) return

        val inflater = LayoutInflater.from(context)

        // Island view (expanded) — centered at top
        pillView = inflater.inflate(R.layout.overlay_window, null)
        pillDot = pillView?.findViewById(R.id.pill_dot)
        pillStep = pillView?.findViewById(R.id.pill_step)

        // Overlay windows can never gain focus, so select the TextView to keep marquee scrolling
        pillStep?.isSelected = true

        val pillRoot = pillView?.findViewById<LinearLayout>(R.id.pill_root)
        pillRoot?.setOnTouchListener { _, event ->
            pillGestureDetector.onTouchEvent(event)
        }
        // 胶囊背景（bg_pill_progress）中表示已执行步数的灰色 clip 段
        pillProgressClip = (pillRoot?.background as? LayerDrawable)
            ?.findDrawableByLayerId(R.id.pill_progress_clip) as? ClipDrawable

        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80
        }

        // Chat-head view (minimized) — corner ball
        minimizedView = inflater.inflate(R.layout.overlay_minimized, null)
        chatHead = minimizedView?.findViewById(R.id.chat_head)

        minimizedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 120
        }

        chatHead?.setOnClickListener {
            if (_currentState is AgentState.Paused) {
                callback?.onReturnToApp()
            } else {
                setExpanded(true)
            }
        }
        chatHead?.setOnLongClickListener {
            callback?.onReturnToApp()
            setExpanded(false)
            true
        }
        setupDragging(chatHead, minimizedParams!!)

        applyStateColors()
        startGlowAnimation()
        isPrepared = true
        Log.d(TAG, "prepare() done")
    }

    fun attach() {
        if (!isPrepared) {
            Log.w(TAG, "attach() called before prepare(); ignoring")
            return
        }
        if (isAttached) return
        applyStateColors()
        refreshPillText()

        val view = if (isExpanded) pillView else minimizedView
        val params = if (isExpanded) pillParams else minimizedParams
        try {
            if (view != null && params != null && !view.isAttachedToWindow) {
                applyKeepScreenOnFlag(params)
                windowManager.addView(view, params)
                attachCount++
                // Entry animation
                if (isExpanded) {
                    // Island: scale from small + fade
                    view.scaleX = 0.5f
                    view.scaleY = 0.5f
                    view.alpha = 0f
                    view.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    // Chat-head: bounce in
                    view.scaleX = 0f
                    view.scaleY = 0f
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(400)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
                Log.d(TAG, "attach() added ${if (isExpanded) "island" else "chatHead"}")
            }
            isAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "attach: addView failed: ${e.message}")
        }
        // 胶囊可见后，按当前步数同步背景进度与闪烁状态
        if (isExpanded) updateProgress(currentStep)
    }

    fun detach() {
        if (!isPrepared) return
        if (!isAttached) return
        try {
            pillView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        try {
            minimizedView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        isAttached = false
        detachCount++
        Log.d(TAG, "detach() done")
    }

    fun dismiss() {
        handler.removeCallbacks(autoCollapseRunnable)
        glowAnimator?.cancel()
        dotAnimator?.cancel()
        progressBlinkAnimator?.cancel()
        progressBlinkAnimator = null
        detach()
        pillView = null
        minimizedView = null
        isPrepared = false
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        handler.removeCallbacks(autoCollapseRunnable)
        if (expanded && _currentState !is AgentState.WaitingForUser
            && _currentState !is AgentState.WaitingForHandoff) {
            val collapseMs = autoCollapseMs()
            if (collapseMs > 0) handler.postDelayed(autoCollapseRunnable, collapseMs)
        }
        if (!isAttached) return

        val newView = if (expanded) pillView else minimizedView
        val newParams = if (expanded) pillParams else minimizedParams
        val oldView = if (expanded) minimizedView else pillView
        try {
            oldView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExpanded remove old: $e")
        }
        try {
            if (newView != null && newParams != null && !newView.isAttachedToWindow) {
                applyKeepScreenOnFlag(newParams)
                windowManager.addView(newView, newParams)
                // Transition animation
                if (expanded) {
                    newView.scaleX = 0.5f
                    newView.scaleY = 0.5f
                    newView.alpha = 0f
                    newView.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(250)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    newView.scaleX = 0f
                    newView.scaleY = 0f
                    newView.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExpanded add new: $e")
        }
        // 展开后同步背景进度与闪烁状态
        if (expanded) updateProgress(currentStep)
    }

    /** 读取用户配置的自动收起时长（毫秒）；负值表示永不自动收起。每次展开时重新读取，设置变更下次展开生效。 */
    private fun autoCollapseMs(): Long {
        val seconds = config.overlayAutoCollapseSeconds
        return if (seconds > 0) seconds * 1000L else -1L
    }

    /** 任务运行期间保持屏幕常亮（独立设置）：根据配置给悬浮窗窗口加/清 FLAG_KEEP_SCREEN_ON。
     *  悬浮窗只在任务活跃时显示，且配置变更必然发生在 App 前台（悬浮窗已隐藏），
     *  因此在每次 addView 前应用即可拿到最新配置。 */
    private fun applyKeepScreenOnFlag(params: WindowManager.LayoutParams) {
        if (config.keepScreenOnWhileRunning) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
    }

    fun isAttachedForTest(): Boolean = isAttached
    fun isExpandedForTest(): Boolean = isExpanded
    fun isPreparedForTest(): Boolean = isPrepared

    // ── State updates ──────────────────────────────────────────────────────

    fun updateState(state: AgentState) {
        Log.d(TAG, "updateState: $state isAttached=$isAttached isExpanded=$isExpanded")
        _currentState = state
        when (state) {
            is AgentState.Running -> {
                updateProgress(state.step)
                if (debugMode) pillStep?.text = "step ${state.step} / $maxSteps"
                // Normal mode: keep the previous step text until the next StepResult arrives
            }
            is AgentState.WaitingForUser -> {
                pillStep?.text = context.getString(R.string.overlay_waiting_answer)
                setExpanded(true)
            }
            is AgentState.WaitingForHandoff -> {
                pillStep?.text = context.getString(R.string.overlay_waiting_handoff)
                setExpanded(true)
            }
            is AgentState.Paused -> {
                pillStep?.text = if (debugMode) context.getString(R.string.overlay_paused_takeover) else context.getString(R.string.overlay_paused)
            }
            is AgentState.Idle -> {
                val idle = state as AgentState.Idle
                if (idle.resultMessage != null) {
                    pillStep?.text = context.getString(R.string.overlay_done)
                    lastStepText = null
                }
            }
            is AgentState.Error -> {
                pillStep?.text = context.getString(R.string.overlay_failed)
                lastStepText = null
            }
            else -> {}
        }
        // 非运行态（等待/暂停/结束）停止闪烁警示
        if (state !is AgentState.Running) stopProgressBlink()
        applyStateColors()
    }

    fun addStepResult(result: StepResult) {
        updateProgress(result.step)
        if (debugMode) {
            pillStep?.text = "step ${result.step} / $maxSteps"
        } else {
            // Same real-time status text as the chat area: thought, fallback to detail
            lastStepText = result.thought.ifBlank { result.detail }
            pillStep?.text = lastStepText
        }
    }

    private fun applyStateColors() {
        val color = when (_currentState) {
            is AgentState.Running -> colorRunning
            is AgentState.WaitingForUser -> colorWaiting
            is AgentState.WaitingForHandoff -> colorWaiting
            is AgentState.Paused -> colorPaused
            is AgentState.Error -> colorError
            is AgentState.Idle -> if ((_currentState as AgentState.Idle).resultMessage != null) colorSuccess else colorIdle
            else -> colorIdle
        }
        (pillDot?.background as? GradientDrawable)?.setColor(color)
    }

    /** 按当前步数更新胶囊背景中的灰色进度段（clip level 0–10000）。比例 clamp 到 [0,1]，超出最大步数不溢出。 */
    private fun updateProgress(step: Int) {
        currentStep = step
        val max = maxSteps
        val ratio = if (max > 0) (step.toFloat() / max).coerceIn(0f, 1f) else 0f
        pillProgressClip?.level = (ratio * 10000).toInt()

        if (_currentState is AgentState.Running && ratio >= PROGRESS_BLINK_THRESHOLD) {
            startProgressBlink()
        } else {
            stopProgressBlink()
        }
    }

    /** 进度达到阈值时，已执行灰色段在灰色与胶囊原背景色之间来回切换，模拟闪烁警示。胶囊不可见时不启动。 */
    private fun startProgressBlink() {
        if (progressBlinkAnimator != null) return
        if (pillView?.isAttachedToWindow != true) return
        progressBlinkAnimator = ValueAnimator.ofArgb(PROGRESS_FILL_COLOR, PROGRESS_BLINK_TO_COLOR).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                (pillProgressClip?.drawable as? GradientDrawable)?.setColor(anim.animatedValue as Int)
            }
            start()
        }
    }

    private fun stopProgressBlink() {
        progressBlinkAnimator?.cancel()
        progressBlinkAnimator = null
        // 恢复灰色填充
        (pillProgressClip?.drawable as? GradientDrawable)?.setColor(PROGRESS_FILL_COLOR)
    }

    private fun refreshPillText() {
        when (val s = _currentState) {
            is AgentState.Running -> pillStep?.text = if (debugMode) "step ${s.step} / $maxSteps" else (lastStepText ?: context.getString(R.string.overlay_running))
            is AgentState.WaitingForUser -> pillStep?.text = context.getString(R.string.overlay_waiting_answer)
            is AgentState.WaitingForHandoff -> pillStep?.text = context.getString(R.string.overlay_waiting_handoff)
            is AgentState.Paused -> pillStep?.text = if (debugMode) context.getString(R.string.overlay_paused_takeover) else context.getString(R.string.overlay_paused)
            else -> {}
        }
    }

    private fun startGlowAnimation() {
        glowAnimator = ObjectAnimator.ofFloat(chatHead, "alpha", 1f, 0.65f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        dotAnimator = ObjectAnimator.ofFloat(pillDot, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ── Drag handling ──────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging(view: View?, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging) {
                        if (dx * dx + dy * dy > dragSlop * dragSlop) isDragging = true
                        else return@setOnTouchListener false
                    }
                    params.x = initialX - dx
                    params.y = initialY + dy
                    try {
                        if (v.isAttachedToWindow) {
                            windowManager.updateViewLayout(v, params)
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "OverlayWindow"
        /** 已执行步数的灰色填充色，需与 bg_pill_progress.xml 中保持一致 */
        private val PROGRESS_FILL_COLOR = 0xFFB8B8B8.toInt()
        /** 胶囊原背景色（surface_island），闪烁时与灰色来回切换 */
        private val PROGRESS_BLINK_TO_COLOR = 0xF0FFFFFF.toInt()
        /** 进度达到该比例时开始闪烁警示 */
        private const val PROGRESS_BLINK_THRESHOLD = 0.9f
    }
}
