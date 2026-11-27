package com.carriez.flutter_hbb

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ffi.FFI

/**
 * FrontPOS Support: et lille rundt FP-maerke oeverst til hoejre, KUN mens en supporter er forbundet
 * (27. nov 2026). Erstatter ikke RustDesks svaevende vindue, men er det, personalet ser under en fjernstyring:
 * blaat FP-maerke med en groen, svagt pulserende prik. Tryk -> lille boks med hvem der er paa og "Afbryd".
 * Kan traekkes; placeringen huskes. Kraever "Vis oven paa andre apps" (SYSTEM_ALERT_WINDOW), som appen har.
 */
object FrontPosSessionBadge {
    private const val TAG = "FrontPosBadge"
    private const val PREFS = "frontpos_badge"
    private const val BLUE = 0xFF12224C.toInt()
    private const val GREEN = 0xFF2ECC71.toInt()

    private val main = Handler(Looper.getMainLooper())
    // Info : forbindelses-id -> visningsnavn (supporterens navn eller ID).
    private val clients = LinkedHashMap<Int, String>()
    private var badge: BadgeView? = null
    private var badgeParams: WindowManager.LayoutParams? = null
    private var panel: View? = null

    fun onClientAuthorized(context: Context, id: Int, name: String) = main.post {
        clients[id] = name
        refresh(context.applicationContext)
    }

    fun onClientRemoved(context: Context, id: Int) = main.post {
        clients.remove(id)
        refresh(context.applicationContext)
    }

    private fun refresh(ctx: Context) {
        if (clients.isEmpty()) {
            hidePanel(ctx)
            badge?.let { runCatching { wm(ctx).removeView(it) } }
            badge?.stopPulse()
            badge = null
            return
        }
        if (badge == null) show(ctx)
        if (panel != null) { hidePanel(ctx); showPanel(ctx) }
    }

    private fun wm(ctx: Context) = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun show(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "Maa ikke vise oven paa andre apps; supportikonet vises ikke.")
            return
        }
        val size = dp(ctx, 52)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val view = BadgeView(ctx)
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("x", dp(ctx, 12))
            y = prefs.getInt("y", dp(ctx, 60))
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        // Note : Gravity END -> x maales fra hoejre kant.
                        params.x = (startX - dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        runCatching { wm(ctx).updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    else if (panel == null) showPanel(ctx) else hidePanel(ctx)
                }
            }
            true
        }
        try {
            wm(ctx).addView(view, params)
            badge = view
            badgeParams = params
            view.startPulse()
        } catch (e: Exception) {
            Log.w(TAG, "Supportikonet kunne ikke vises: $e")
        }
    }

    private fun showPanel(ctx: Context) {
        val p = badgeParams ?: return
        val pad = dp(ctx, 14)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(ctx, 14).toFloat(); setStroke(dp(ctx, 1), 0x22000000) }
            elevation = dp(ctx, 8).toFloat()
        }
        box.addView(TextView(ctx).apply {
            text = "FrontPOS Support er forbundet"
            setTextColor(BLUE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(ctx).apply {
            text = clients.values.joinToString("\n") { "Supporter: $it" }
            setTextColor(0xFF555555.toInt()); textSize = 14f
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 10))
        })
        box.addView(Button(ctx).apply {
            text = "Afbryd"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFFC0392B.toInt()); cornerRadius = dp(ctx, 10).toFloat() }
            setOnClickListener {
                clients.keys.toList().forEach { id -> runCatching { FFI.frontposCloseConnection(id) } }
                hidePanel(ctx)
            }
        })
        val params = WindowManager.LayoutParams(
            dp(ctx, 260), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = p.x
            y = p.y + p.height + dp(ctx, 6)
        }
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) hidePanel(ctx)
            false
        }
        try { wm(ctx).addView(box, params); panel = box } catch (e: Exception) { Log.w(TAG, "Boksen kunne ikke vises: $e") }
    }

    private fun hidePanel(ctx: Context) {
        panel?.let { runCatching { wm(ctx).removeView(it) } }
        panel = null
    }

    /** Det runde FP-maerke: blaa cirkel, hvidt "FP", groen prik der pulserer svagt. */
    private class BadgeView(ctx: Context) : View(ctx) {
        private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN }
        private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var pulse = 1f
        private var animator: ValueAnimator? = null

        init { elevation = 6 * ctx.resources.displayMetrics.density }

        fun startPulse() {
            animator = ValueAnimator.ofFloat(0.45f, 1f).apply {
                duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stopPulse() { animator?.cancel(); animator = null }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = minOf(w, h) / 2f - 2f
            canvas.drawCircle(w / 2, h / 2, r, circle)
            ring.strokeWidth = r * 0.08f
            canvas.drawCircle(w / 2, h / 2, r - ring.strokeWidth / 2, ring)
            text.textSize = r * 0.8f
            canvas.drawText("FP", w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text)
            val dr = r * 0.28f
            val cx = w / 2 + r * 0.68f; val cy = h / 2 + r * 0.68f
            canvas.drawCircle(cx, cy, dr + r * 0.06f, dotRing)
            dot.alpha = (255 * pulse).toInt()
            canvas.drawOval(RectF(cx - dr, cy - dr, cx + dr, cy + dr), dot)
        }
    }
}
