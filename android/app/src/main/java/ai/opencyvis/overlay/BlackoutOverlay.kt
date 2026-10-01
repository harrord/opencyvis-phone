package ai.opencyvis.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import ai.opencyvis.R

/**
 * 全屏黑色挂机遮罩：任务执行时双击胶囊进入。
 *
 * - 窗口级最低亮度（screenBrightness），移除窗口后系统亮度自动恢复，无需权限
 * - 可选 FLAG_KEEP_SCREEN_ON 保持屏幕常亮，遮罩移除后自动失效
 * - 1.5 秒时间窗内连续点击 [exitTaps] 次退出；锁屏退出由外部（OverlayService）调用 [dismiss]
 * - 遮罩上以白色文字常驻提示退出方式
 */
class BlackoutOverlay(
    private val context: Context,
    private val exitTaps: Int,
    private val keepScreenOn: Boolean,
    private val onDismiss: () -> Unit
) {

    companion object {
        private const val TAG = "BlackoutOverlay"
        /** 连续点击计数的时间窗（毫秒），超时未续点则清零重计 */
        private const val TAP_WINDOW_MS = 1500L
        /** 窗口级最低亮度。0f 在部分 ROM 上会触发关屏流程，0.01f 更稳 */
        private const val MIN_BRIGHTNESS = 0.01f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var tapCount = 0
    private var hasDismissed = false
    private val tapResetRunnable = Runnable { tapCount = 0 }

    val isShowing: Boolean
        get() = rootView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return

        val root = FrameLayout(context).apply { setBackgroundColor(0xFF000000.toInt()) }
        val hint = TextView(context).apply {
            text = context.getString(R.string.blackout_exit_hint, exitTaps)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.setOnClickListener { registerTap() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = MIN_BRIGHTNESS
        }

        try {
            windowManager.addView(root, params)
            rootView = root
            Log.d(TAG, "show() exitTaps=$exitTaps keepScreenOn=$keepScreenOn")
        } catch (e: Exception) {
            Log.w(TAG, "show failed: ${e.message}")
            hasDismissed = true
            onDismiss()
        }
    }

    private fun registerTap() {
        if (hasDismissed) return
        handler.removeCallbacks(tapResetRunnable)
        tapCount++
        if (tapCount >= exitTaps) {
            dismiss()
        } else {
            handler.postDelayed(tapResetRunnable, TAP_WINDOW_MS)
        }
    }

    /** 幂等：只会真正移除一次窗口，并最多回调一次 [onDismiss]。 */
    fun dismiss() {
        if (hasDismissed) return
        hasDismissed = true
        handler.removeCallbacks(tapResetRunnable)
        tapCount = 0
        val view = rootView
        rootView = null
        if (view != null) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.w(TAG, "dismiss: ${e.message}")
            }
        }
        Log.d(TAG, "dismiss()")
        onDismiss()
    }
}
