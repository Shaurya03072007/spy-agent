package s.p.y.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import s.p.y.R
import s.p.y.adb.ConnectionState
import s.p.y.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var isUnlocked = false
    private val enteredPin = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Automatically hide app icon from launcher on startup
        s.p.y.security.SecurityManager.autoHideOnStart(this)

        setupViewPager()
        setupListeners()
        setupPinLock()
        observeConnectionState()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val data = intent.dataString ?: "a.p.y"
            Toast.makeText(this, "🌐 Opened via Browser Launch: $data", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPinLockState()
    }

    override fun onStop() {
        super.onStop()
        // Re-lock app when placed in background
        if (s.p.y.security.SecurityManager.isPinEnabled(this)) {
            isUnlocked = false
        }
    }

    private fun checkPinLockState() {
        val hasPin = s.p.y.security.SecurityManager.isPinEnabled(this)
        if (hasPin && !isUnlocked) {
            binding.pinLockOverlay.pinLockRoot.visibility = android.view.View.VISIBLE
            binding.pinLockOverlay.pinLockRoot.alpha = 1f
            enteredPin.clear()
            updatePinDotsUI()
        } else {
            binding.pinLockOverlay.pinLockRoot.visibility = android.view.View.GONE
        }
    }

    private fun setupPinLock() {
        val overlay = binding.pinLockOverlay
        val numberButtons = listOf(
            overlay.btnKey0 to "0",
            overlay.btnKey1 to "1",
            overlay.btnKey2 to "2",
            overlay.btnKey3 to "3",
            overlay.btnKey4 to "4",
            overlay.btnKey5 to "5",
            overlay.btnKey6 to "6",
            overlay.btnKey7 to "7",
            overlay.btnKey8 to "8",
            overlay.btnKey9 to "9"
        )

        for ((btn, digit) in numberButtons) {
            btn.setOnClickListener {
                if (enteredPin.length < 4) {
                    enteredPin.append(digit)
                    updatePinDotsUI()
                    if (enteredPin.length == 4) {
                        verifyEnteredPin()
                    }
                }
            }
        }

        overlay.btnKeyBackspace.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin.deleteCharAt(enteredPin.length - 1)
                updatePinDotsUI()
            }
        }

        overlay.btnKeyClear.setOnClickListener {
            enteredPin.clear()
            updatePinDotsUI()
        }
    }

    private fun updatePinDotsUI() {
        val overlay = binding.pinLockOverlay
        val dots = listOf(overlay.dot1, overlay.dot2, overlay.dot3, overlay.dot4)
        for (i in dots.indices) {
            if (i < enteredPin.length) {
                dots[i].setBackgroundResource(R.drawable.bg_pin_dot_filled)
            } else {
                dots[i].setBackgroundResource(R.drawable.bg_pin_dot)
            }
        }
    }

    private fun verifyEnteredPin() {
        val entered = enteredPin.toString()
        if (s.p.y.security.SecurityManager.verifyPin(this, entered)) {
            isUnlocked = true
            binding.pinLockOverlay.pinLockRoot.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.pinLockOverlay.pinLockRoot.visibility = android.view.View.GONE
                    binding.pinLockOverlay.pinLockRoot.alpha = 1f
                }
            Toast.makeText(this, "✔ ADB Workspace Unlocked", Toast.LENGTH_SHORT).show()
        } else {
            // Vibrate and shake on incorrect PIN
            try {
                val vibrator = getSystemService(android.os.Vibrator::class.java)
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}

            binding.pinLockOverlay.layoutPinDots.animate()
                .translationX(20f)
                .setDuration(50)
                .withEndAction {
                    binding.pinLockOverlay.layoutPinDots.animate()
                        .translationX(-20f)
                        .setDuration(50)
                        .withEndAction {
                            binding.pinLockOverlay.layoutPinDots.translationX = 0f
                            enteredPin.clear()
                            updatePinDotsUI()
                        }
                }
            Toast.makeText(this, "Incorrect Security PIN", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupViewPager() {
        val pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 2
        binding.viewPager.isUserInputEnabled = false

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val itemId = when (position) {
                    0 -> R.id.nav_connect
                    1 -> R.id.nav_terminal
                    2 -> R.id.nav_toolbox
                    else -> R.id.nav_connect
                }
                if (binding.bottomNav.selectedItemId != itemId) {
                    binding.bottomNav.selectedItemId = itemId
                }
            }
        })

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> {
                    binding.viewPager.currentItem = 0
                    true
                }
                R.id.nav_terminal -> {
                    binding.viewPager.currentItem = 1
                    true
                }
                R.id.nav_toolbox -> {
                    binding.viewPager.currentItem = 2
                    true
                }
                else -> false
            }
        }
    }

    private fun setupListeners() {
        binding.btnRestartServer.setOnClickListener {
            Toast.makeText(this, "Restarting ADB Server daemon...", Toast.LENGTH_SHORT).show()
            viewModel.restartAdbServer()
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connectionState.collect { state ->
                    updateStatusUI(state)
                }
            }
        }
    }

    private fun updateStatusUI(state: ConnectionState) {
        val green = ContextCompat.getColor(this, R.color.accent_green)
        val amber = ContextCompat.getColor(this, R.color.accent_amber)
        val red = ContextCompat.getColor(this, R.color.accent_red)

        when (state) {
            is ConnectionState.Connected -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.tvStatus.setTextColor(green)
                binding.dotStatus.backgroundTintList = ColorStateList.valueOf(green)
                binding.tvDeviceTarget.text = "${state.host}:${state.port} (${state.deviceName})"
            }
            is ConnectionState.Connecting -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.tvStatus.setTextColor(amber)
                binding.dotStatus.backgroundTintList = ColorStateList.valueOf(amber)
                binding.tvDeviceTarget.text = "Connecting to ${state.host}:${state.port}..."
            }
            is ConnectionState.Pairing -> {
                binding.tvStatus.text = getString(R.string.status_pairing)
                binding.tvStatus.setTextColor(amber)
                binding.dotStatus.backgroundTintList = ColorStateList.valueOf(amber)
                binding.tvDeviceTarget.text = "Pairing with ${state.host}:${state.port}..."
            }
            is ConnectionState.Disconnected -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.tvStatus.setTextColor(red)
                binding.dotStatus.backgroundTintList = ColorStateList.valueOf(red)
                binding.tvDeviceTarget.text = "127.0.0.1 (Local ADB)"
            }
            is ConnectionState.Error -> {
                binding.tvStatus.text = "ERROR"
                binding.tvStatus.setTextColor(red)
                binding.dotStatus.backgroundTintList = ColorStateList.valueOf(red)
                binding.tvDeviceTarget.text = state.message.take(40)
            }
        }
    }
}
