package s.p.y.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import s.p.y.R
import s.p.y.adb.AdbDnsScanner
import s.p.y.adb.AdbManager
import s.p.y.databinding.LayoutFloatingPairBinding
import s.p.y.ui.MainActivity

class FloatingPairService : Service() {

    companion object {
        private const val TAG = "FloatingPairService"
        private const val CHANNEL_ID = "spy_floating_service"
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, FloatingPairService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPairService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var binding: LayoutFloatingPairBinding? = null

    private lateinit var adbManager: AdbManager
    private lateinit var dnsScanner: AdbDnsScanner

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        adbManager = AdbManager.getInstance(applicationContext)
        dnsScanner = AdbDnsScanner(applicationContext)

        createNotificationChannel()
        startForeground(NOTIF_ID, createForegroundNotification())

        setupFloatingView()
        observeDns()
        dnsScanner.startDiscovery()
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        val viewBinding = LayoutFloatingPairBinding.inflate(inflater)
        binding = viewBinding
        floatingView = viewBinding.root

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 180
        }

        // Dragging logic
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        viewBinding.layoutFloatingHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        // Close button
        viewBinding.btnFloatingClose.setOnClickListener {
            stopSelf()
        }

        // Pair button
        viewBinding.btnFloatingPair.setOnClickListener {
            val pairPortStr = viewBinding.etFloatingPairPort.text.toString().trim()
            val codeStr = viewBinding.etFloatingPairCode.text.toString().trim()
            val connectPortStr = viewBinding.etFloatingConnectPort.text.toString().trim()

            val pairPort = pairPortStr.toIntOrNull()
            if (pairPort == null || pairPort !in 1..65535) {
                viewBinding.tvFloatingStatus.text = "⚠️ Enter a valid pairing port!"
                viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_red))
                return@setOnClickListener
            }

            if (codeStr.length < 6) {
                viewBinding.tvFloatingStatus.text = "⚠️ Enter 6-digit pairing code!"
                viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_red))
                return@setOnClickListener
            }

            viewBinding.btnFloatingPair.isEnabled = false
            viewBinding.tvFloatingStatus.text = "⏳ Pairing with 127.0.0.1:$pairPort..."
            viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_amber))

            serviceScope.launch {
                val pairResult = adbManager.pair("127.0.0.1", pairPort, codeStr)
                if (pairResult.isSuccess || pairResult.stdout.contains("Successfully paired", ignoreCase = true)) {
                    viewBinding.tvFloatingStatus.text = "✔ Paired! Connecting..."
                    viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_green))

                    val connectPort = connectPortStr.toIntOrNull()
                        ?: dnsScanner.discoveredConnectPort.value
                        ?: 5555

                    val connectResult = adbManager.connect("127.0.0.1", connectPort)
                    if (connectResult.isSuccess || connectResult.stdout.contains("connected to", ignoreCase = true)) {
                        viewBinding.tvFloatingStatus.text = "🎉 CONNECTED TO ADB! Returning to Spy..."
                        viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_green))

                        delay(1200)
                        openMainActivity()
                        stopSelf()
                    } else {
                        viewBinding.tvFloatingStatus.text = "✔ Paired! Connect manually in app on port $connectPort"
                        viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_amber))
                        viewBinding.btnFloatingPair.isEnabled = true
                    }
                } else {
                    viewBinding.tvFloatingStatus.text = "❌ Failed: ${pairResult.stdout.take(60)}"
                    viewBinding.tvFloatingStatus.setTextColor(getColor(R.color.accent_red))
                    viewBinding.btnFloatingPair.isEnabled = true
                }
            }
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
            stopSelf()
        }
    }

    private fun observeDns() {
        serviceScope.launch {
            dnsScanner.discoveredPairPort.collect { port ->
                binding?.let { b ->
                    if (port != null && port > 0 && b.etFloatingPairPort.text.isNullOrBlank() && !b.etFloatingPairPort.hasFocus()) {
                        b.etFloatingPairPort.setText(port.toString())
                        b.tvFloatingStatus.text = "Detected Pair Port: $port"
                        b.tvFloatingStatus.setTextColor(getColor(R.color.accent_cyan))
                    }
                }
            }
        }

        serviceScope.launch {
            dnsScanner.discoveredConnectPort.collect { port ->
                binding?.let { b ->
                    if (port != null && port > 0 && b.etFloatingConnectPort.text.isNullOrBlank() && !b.etFloatingConnectPort.hasFocus()) {
                        b.etFloatingConnectPort.setText(port.toString())
                    }
                }
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spy ADB Floating Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating pairing overlay window"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spy ADB Pairing Helper Active")
            .setContentText("Floating window is visible. Drag and enter pairing code.")
            .setSmallIcon(R.drawable.ic_wifi_adb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        dnsScanner.stopDiscovery()
        serviceScope.cancel()
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        floatingView = null
        binding = null
    }
}
