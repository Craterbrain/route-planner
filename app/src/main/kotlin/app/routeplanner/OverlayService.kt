package app.routeplanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat

/**
 * Draw-over-other-apps action bar: [<] (Delivered) [Fail] [>] floating on top of
 * Google Maps / Waze so the driver never has to switch back to this app.
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bar: LinearLayout? = null
    private var label: TextView? = null
    private val refresh = { label?.text = describe() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("overlay", "Action bar", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Route in progress")
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        SessionStore.load(this)
        addBar()
        SessionStore.observe(refresh)
    }

    private fun describe(): String {
        val s = SessionStore.session ?: return "No route"
        val c = s.current ?: return "Done"
        return "#${c.optimizedIndex} ${c.address.substringBefore(',')}"
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(36, 28, 36, 28)
        background = GradientDrawable().apply { cornerRadius = 40f; setColor(color) }
        setOnClickListener { onClick() }
    }

    private fun addBar() {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply { cornerRadius = 48f; setColor(0xE6202124.toInt()) }
        }
        label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; text = describe()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gap = (6 * density).toInt()
        fun add(v: View) = row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { setMargins(gap, gap, gap, gap) })
        add(button("◄", 0xFF5F6368.toInt()) { SessionStore.act(this) { prev(); null } })
        add(button("✓", 0xFF1E8E3E.toInt()) { SessionStore.act(this) { deliver() } })
        add(button("✗", 0xFFD93025.toInt()) {
            startActivity(Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_FAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        add(button("►", 0xFF5F6368.toInt()) { SessionStore.act(this) { next(); null } })
        root.addView(label)
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (96 * density).toInt() }

        // Drag by the label so button taps stay unambiguous.
        var startY = 0f; var startOffset = 0
        label!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startOffset = lp.y }
                MotionEvent.ACTION_MOVE -> { lp.y = startOffset - (e.rawY - startY).toInt(); wm.updateViewLayout(root, lp) }
            }
            true
        }
        wm.addView(root, lp)
        bar = root
    }

    override fun onDestroy() {
        SessionStore.unobserve(refresh)
        bar?.let { wm.removeView(it) }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}
