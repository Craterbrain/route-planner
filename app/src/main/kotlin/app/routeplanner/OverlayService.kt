package app.routeplanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import app.routeplanner.ui.badgeColors
import app.routeplanner.ui.badgeText
import app.routeplanner.ui.dp
import app.routeplanner.ui.dpf
import app.routeplanner.ui.label
import app.routeplanner.ui.roundRect
import app.routeplanner.core.splitAddress
import app.routeplanner.ui.totalPackages
import com.google.android.material.R as M
import kotlin.math.abs

/**
 * Floating action button drawn over the navigation app. Collapsed it is a
 * single round button that can be dragged and snaps to the nearest screen
 * edge (position remembered). Tapping it expands a small panel with the
 * current stop (number, address, ID, packages; tap it to open that stop in the app) and Prev / Delivered / Failed / Next.
 * Dragging it to the circle with an X at the bottom centre closes it.
 * Hidden while Route Planner itself is on screen, where the next-stop card
 * does the same job.
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var t: Context
    private var root: LinearLayout? = null
    private lateinit var fab: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var info: LinearLayout
    private lateinit var undoStrip: TextView
    private lateinit var fabIcon: ImageView
    private lateinit var fabNumber: NumberView
    private lateinit var lp: WindowManager.LayoutParams
    private var expanded = false
    private val main = Handler(Looper.getMainLooper())
    private val refresh = {
        // The button belongs to a route in progress: it goes when the route is stopped, finished or cleared.
        if (!inProgress()) stopSelf() else bindInfo()
    }

    private fun inProgress() = SessionStore.session?.let { it.started && !it.done } == true
    private val visibility = { main.post { applyVisibility() }; Unit }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("overlay", "Floating button", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle("Route in progress")
            .setContentText("Floating button is on")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_close), "Stop", stop).build())
            .build()
        ServiceCompat.startForeground(this, 1, n,
            if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        running = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        t = themed() // app theme + the user's Material You colors
        SessionStore.load(this)
        if (!inProgress()) { running = false; stopSelf(); return }
        build()
        SessionStore.observe(refresh)
        visibilityListeners += visibility
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    /** After Delivered from the panel: the panel stays up for five seconds with an Undo, then folds away. */
    private fun showUndo(stopId: String, where: String) {
        undoStrip.text = "Delivered · $where  —  Undo"
        undoStrip.setOnClickListener {
            SessionStore.act(this) { undo(stopId); null }
            main.removeCallbacks(hideUndo)
            undoStrip.visibility = View.GONE
        }
        undoStrip.visibility = View.VISIBLE
        main.removeCallbacks(hideUndo)
        main.postDelayed(hideUndo, 5_000)
    }

    private val hideUndo = Runnable {
        if (::undoStrip.isInitialized) { undoStrip.visibility = View.GONE; setExpanded(false) }
    }

    /** The current stop: number badge and the street address (big), then its ID badge and package count. Tapping opens the app on that stop. */
    private fun bindInfo() {
        info.removeAllViews()
        val s = SessionStore.session
        val c = s?.current
        if (s == null || c == null || s.done) {
            info.addView(t.label(15f).apply { text = if (s?.done == true) "All stops handled" else "No route" })
            return
        }
        fabNumber.value = c.optimizedIndex.toString(); fabNumber.visibility = View.VISIBLE; fabIcon.visibility = View.GONE
        val colors = t.badgeColors(c, true)
        val top = LinearLayout(t).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(t).apply {
            t.appFont()?.let { typeface = it }
            text = badgeText(c); textSize = 15f; gravity = Gravity.CENTER
            t.appFont(bold = true)?.let { typeface = it }
            setTextColor(colors.text)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colors.fill) }
        }, LinearLayout.LayoutParams(t.dp(38), t.dp(38)).apply { marginEnd = t.dp(10) })
        val where = LinearLayout(t).apply { orientation = LinearLayout.VERTICAL }
        where.addView(t.label(21f, bold = true, lines = 2).apply { text = c.address.substringBefore(',') })
        splitAddress(c.address).second.takeIf { it.isNotEmpty() }?.let { city ->
            where.addView(t.label(14f, M.attr.colorOnSurfaceVariant, lines = 1).apply { text = city })
        }
        top.addView(where, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        info.addView(top)
        val meta = LinearLayout(t).apply { gravity = Gravity.CENTER_VERTICAL }
        meta.addView(TextView(t).apply {
            t.appFont()?.let { typeface = it }
            text = "ID ${c.entryNo}"; textSize = 13f
            t.appFont(bold = true)?.let { typeface = it }
            setTextColor(t.color(M.attr.colorOnTertiaryContainer))
            background = t.roundRect(M.attr.colorTertiaryContainer, 10)
            setPadding(t.dp(8), t.dp(2), t.dp(8), t.dp(2))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = t.dp(8) })
        meta.addView(t.label(14f, M.attr.colorOnSurfaceVariant).apply { text = totalPackages(s.finder(c).size) })
        info.addView(meta, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(6); marginStart = t.dp(48) })
    }

    private fun circle(colorAttr: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.color(colorAttr)) }

    private fun action(icon: Int, desc: String, bg: Int, fg: Int, pill: Boolean = false, onClick: () -> Unit) = ImageView(t).apply {
        setImageResource(icon)
        setColorFilter(t.color(fg))
        contentDescription = desc
        background = if (pill) GradientDrawable().apply { cornerRadius = t.dp(24).toFloat(); setColor(t.color(bg)) } else circle(bg)
        val p = t.dp(12)
        setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    private fun build() {
        fab = FrameLayout(t).apply {
            background = circle(M.attr.colorPrimaryContainer)
            elevation = t.dp(6).toFloat()
            contentDescription = "Route Planner floating button"
            fabIcon = ImageView(t).apply {
                setImageResource(R.drawable.ic_navigation)
                setColorFilter(t.color(M.attr.colorOnPrimaryContainer))
            }
            addView(fabIcon, FrameLayout.LayoutParams(t.dp(28), t.dp(28), Gravity.CENTER))
            // Drawn with a Paint of our own: a TextView in this overlay window fell back to the phone's font (where 1 looks like I).
            fabNumber = NumberView(t, t.appFont(bold = true), t.color(M.attr.colorOnPrimaryContainer), t.dpf(22f)).apply { visibility = View.GONE }
            addView(fabNumber, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        info = LinearLayout(t).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(t.dp(6), t.dp(2), t.dp(6), t.dp(10))
            setOnClickListener {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_STOP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                setExpanded(false)
            }
        }
        // Same order as the app's card: Failed on the left, Delivered (wider, so the likelier tap is the bigger one) on the right,
        // with a gap between them and the stop-to-stop arrows so a thumb on a moving phone doesn't hit the wrong one.
        val actions = LinearLayout(t).apply { gravity = Gravity.CENTER }
        fun add(v: View, w: Int = 48, margin: Int = 4) = actions.addView(v, LinearLayout.LayoutParams(t.dp(w), t.dp(48)).apply { setMargins(t.dp(margin), 0, t.dp(margin), 0) })
        add(action(R.drawable.ic_chevron_left, "Previous stop (marks it pending again)", M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer) {
            SessionStore.act(this) { prev(); null }
        }, margin = 2)
        add(View(t), w = 12, margin = 0)
        add(action(R.drawable.ic_close, "Failed", M.attr.colorErrorContainer, M.attr.colorOnErrorContainer) {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_FAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            setExpanded(false)
        }, margin = 3)
        add(action(R.drawable.ic_check, "Delivered", M.attr.colorPrimary, M.attr.colorOnPrimary, pill = true) {
            val s0 = SessionStore.session
            val c = s0?.current
            if (c != null) {
                SessionStore.act(this) { deliver() }
                showUndo(c.id, c.address.substringBefore(','))
            }
        }, w = 84, margin = 3)
        add(View(t), w = 12, margin = 0)
        add(action(R.drawable.ic_chevron_right, "Next stop", M.attr.colorSecondaryContainer, M.attr.colorOnSecondaryContainer) {
            SessionStore.act(this) { next(); null }
        }, margin = 2)
        undoStrip = TextView(t).apply {
            t.appFont()?.let { typeface = it }
            textSize = 14f
            setTextColor(t.color(M.attr.colorPrimary))
            gravity = Gravity.CENTER
            setPadding(t.dp(8), t.dp(10), t.dp(8), t.dp(6))
            visibility = View.GONE
        }
        panel = LinearLayout(t).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(t.dp(8), t.dp(10), t.dp(8), t.dp(10))
            background = GradientDrawable().apply { cornerRadius = t.dp(24).toFloat(); setColor(t.color(M.attr.colorSurfaceContainerHigh)) }
            elevation = t.dp(6).toFloat()
            visibility = View.GONE
            addView(info, LinearLayout.LayoutParams(t.dp(280), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(actions)
            addView(undoStrip, LinearLayout.LayoutParams(t.dp(280), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val r = LinearLayout(t).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = t.dp(6) // room for elevation shadows
            setPadding(pad, pad, pad, pad)
        }
        root = r
        arrange()
        bindInfo()

        val dm = resources.displayMetrics
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or if (prefs.fabRight) Gravity.RIGHT else Gravity.LEFT
            x = 0
            y = prefs.fabY.takeIf { it >= 0 } ?: (dm.heightPixels * 0.35f).toInt()
        }
        clampY()
        fab.setOnTouchListener(DragTouch())
        wm.addView(r, lp)
        applyVisibility()
    }

    /** FAB sits on the screen-edge side; the panel opens toward the middle. */
    private fun arrange() {
        val r = root ?: return
        r.removeAllViews()
        val fabLp = LinearLayout.LayoutParams(t.dp(56), t.dp(56))
        val panelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(t.dp(8), 0, t.dp(8), 0)
        }
        if (prefs.fabRight) { r.addView(panel, panelLp); r.addView(fab, fabLp) }
        else { r.addView(fab, fabLp); r.addView(panel, panelLp) }
    }

    private fun setExpanded(e: Boolean) {
        expanded = e
        panel.visibility = if (e) View.VISIBLE else View.GONE
        if (e) bindInfo()
    }

    private fun applyVisibility() {
        root?.visibility = if (appInForeground) View.GONE else View.VISIBLE
        if (appInForeground) setExpanded(false)
    }

    /** Keeps the button off the status bar and out of the lower screen (keyboard, gesture bar). */
    private fun clampY() {
        val h = resources.displayMetrics.heightPixels
        lp.y = lp.y.coerceIn(t.dp(48), (h * 0.7f).toInt())
    }

    // ---- Drag-to-close target: a circle with an X at the bottom centre, shown while the button is being dragged ----
    private var dismissView: FrameLayout? = null

    private fun showDismissTarget() {
        if (dismissView != null) return
        val v = FrameLayout(t).apply {
            background = circle(M.attr.colorSurfaceContainerHighest)
            elevation = t.dp(6).toFloat()
            addView(ImageView(t).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(t.color(M.attr.colorOnSurface))
            }, FrameLayout.LayoutParams(t.dp(28), t.dp(28), Gravity.CENTER))
        }
        val p = WindowManager.LayoutParams(t.dp(64), t.dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = DISMISS_BOTTOM_DP.let { t.dp(it) } }
        runCatching { wm.addView(v, p); dismissView = v }
    }

    private fun hideDismissTarget() {
        dismissView?.let { runCatching { wm.removeView(it) } }
        dismissView = null
        overTarget = false
    }

    private var overTarget = false

    /** True while the finger is over the close target; the target grows and turns red to say so. */
    private fun updateDismissTarget(rawX: Float, rawY: Float): Boolean {
        val v = dismissView ?: return false
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels - t.dp(DISMISS_BOTTOM_DP + 32)
        val near = Math.hypot((rawX - cx).toDouble(), (rawY - cy).toDouble()) < t.dp(80)
        if (near != overTarget) {
            overTarget = near
            v.background = circle(if (near) M.attr.colorErrorContainer else M.attr.colorSurfaceContainerHighest)
            v.animate().scaleX(if (near) 1.3f else 1f).scaleY(if (near) 1.3f else 1f).setDuration(120).start()
        }
        return near
    }

    private inner class DragTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startY = 0
        private var startX = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val r = root ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; setExpanded(false); showDismissTarget() }
                    if (dragging) {
                        lp.y = startY + dy.toInt()
                        lp.x = (startX + if (prefs.fabRight) -dx.toInt() else dx.toInt()).coerceAtLeast(0)
                        wm.updateViewLayout(r, lp)
                        updateDismissTarget(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) { v.performClick(); setExpanded(!expanded); return true }
                    val closing = overTarget
                    hideDismissTarget()
                    if (closing) { stopSelf(); return true }
                    // Snap to the nearer edge and remember.
                    val right = e.rawX > resources.displayMetrics.widthPixels / 2f
                    if (right != prefs.fabRight) { prefs.fabRight = right; arrange() }
                    lp.gravity = Gravity.TOP or if (right) Gravity.RIGHT else Gravity.LEFT
                    lp.x = 0
                    clampY()
                    prefs.fabY = lp.y
                    wm.updateViewLayout(r, lp)
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; hideDismissTarget() }
            }
            return true
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(hideUndo)
        hideDismissTarget()
        SessionStore.unobserve(refresh)
        visibilityListeners -= visibility
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.routeplanner.STOP_OVERLAY"
        private const val DISMISS_BOTTOM_DP = 56

        /** True while the floating button is up (for the menu toggle). */
        @Volatile var running = false
            private set

        private val visibilityListeners = mutableListOf<() -> Unit>()

        /** Set by MainActivity: the button hides while the app itself is in front. */
        var appInForeground = false
            set(v) { field = v; visibilityListeners.toList().forEach { it() } }

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}

/** A centred number drawn with an explicit typeface. */
class NumberView(ctx: Context, face: android.graphics.Typeface?, color: Int, private val sizePx: Float) : View(ctx) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; this.color = color; textSize = sizePx; textAlign = android.graphics.Paint.Align.CENTER
    }
    var value: String = ""
        set(v) { field = v; invalidate() }

    override fun onDraw(c: android.graphics.Canvas) {
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(value, width / 2f, y, paint)
    }
}
