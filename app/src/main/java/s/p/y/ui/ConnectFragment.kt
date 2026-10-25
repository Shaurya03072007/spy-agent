package s.p.y.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import s.p.y.R
import s.p.y.adb.ConnectionState
import s.p.y.databinding.FragmentConnectBinding
import s.p.y.service.FloatingPairService
import s.p.y.service.PairingNotificationReceiver

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDefaults()
        setupListeners()
        observeState()
    }

    private fun setupDefaults() {
        val lastHost = viewModel.adbManager.getLastHost()
        val lastPort = viewModel.adbManager.getLastPort()
        val lastPairPort = viewModel.adbManager.getLastPairPort()

        binding.etConnectHost.setText(lastHost)
        if (lastPort > 0) {
            binding.etConnectPort.setText(lastPort.toString())
        }
        if (lastPairPort > 0) {
            binding.etPairPort.setText(lastPairPort.toString())
        }

        binding.tvAndroidVersion.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ADB Engine"
    }

    private fun setupListeners() {
        // 1-Click Split Screen Mode
        binding.btn1ClickSplit.setOnClickListener {
            launchSplitScreen()
        }

        // 1-Click Floating Overlay Window
        binding.btn1ClickFloating.setOnClickListener {
            launchFloatingWindow()
        }

        // 1-Click Notification Mode
        binding.btn1ClickNotification.setOnClickListener {
            launchNotificationMode()
        }

        // Direct Settings
        binding.btnOpenDevSettings.setOnClickListener {
            openDeveloperSettings()
        }

        // Pair button
        binding.btnPair.setOnClickListener {
            performPair(autoConnect = false)
        }

        // Pair & Auto-Connect button
        binding.btnPairAndConnect.setOnClickListener {
            performPair(autoConnect = true)
        }

        // Connect button
        binding.btnConnect.setOnClickListener {
            performConnect()
        }

        // Quick Connect Offline Port 5555
        binding.btnConnectOffline5555.setOnClickListener {
            binding.etConnectHost.setText("127.0.0.1")
            binding.etConnectPort.setText("5555")
            viewModel.connect("127.0.0.1", 5555) { result ->
                if (result.isSuccess || result.stdout.contains("connected to", ignoreCase = true)) {
                    Toast.makeText(requireContext(), "🎉 Connected to 127.0.0.1:5555! Offline ADB ready.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Connect 5555 failed: ${result.combinedOutput.take(100)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Run 'adb tcpip 5555'
        binding.btnSwitchTcpip5555.setOnClickListener {
            Toast.makeText(requireContext(), "Running 'adb tcpip 5555'...", Toast.LENGTH_SHORT).show()
            viewModel.enableTcpip5555 { result ->
                if (result.isSuccess || result.stdout.contains("restarting in TCP mode", ignoreCase = true)) {
                    Toast.makeText(requireContext(), "✔ Restarted in TCP/IP 5555 mode! You can now use ADB without Wi-Fi.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "tcpip 5555 output: ${result.combinedOutput.take(80)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Disconnect button
        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
            Toast.makeText(requireContext(), "Disconnected", Toast.LENGTH_SHORT).show()
        }

        // Refresh devices
        binding.btnRefreshDevices.setOnClickListener {
            viewModel.refreshDevices()
        }
    }

    private fun launchSplitScreen() {
        try {
            val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            startActivity(devIntent)
            Toast.makeText(
                requireContext(),
                "Launching in Multi-Window! On Samsung: You can also tap Recents (|||) -> Tap Spy icon -> 'Open in split screen'",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            openDeveloperSettings()
        }
    }

    private fun launchFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
            AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
                .setTitle("Allow Floating Pairing Window")
                .setMessage(
                    "To enter the pairing code without leaving Settings (and without Android cancelling it), " +
                    "Spy needs permission to display a floating helper window over other apps."
                )
                .setPositiveButton("Grant Permission") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            FloatingPairService.start(requireContext())
            openDeveloperSettings()
            Toast.makeText(
                requireContext(),
                "🪟 Floating window active! Drag it over Wireless Debugging and pair effortlessly.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchNotificationMode() {
        val pairPort = binding.etPairPort.text.toString().toIntOrNull()
            ?: viewModel.discoveredPairPort.value
        val connectPort = binding.etConnectPort.text.toString().toIntOrNull()
            ?: viewModel.discoveredConnectPort.value

        PairingNotificationReceiver.showPairingNotification(requireContext(), pairPort, connectPort)
        openDeveloperSettings()

        Toast.makeText(
            requireContext(),
            "🔔 Notification posted! In Wireless Debugging, tap 'Pair with code', then pull down status bar to enter code.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performPair(autoConnect: Boolean) {
        val host = binding.etConnectHost.text.toString().trim().ifBlank { "127.0.0.1" }
        val portStr = binding.etPairPort.text.toString().trim()
        val code = binding.etPairCode.text.toString().trim()

        if (portStr.isBlank()) {
            binding.etPairPort.error = "Pairing port required"
            return
        }
        if (code.isBlank() || code.length < 6) {
            binding.etPairCode.error = "6-digit code required"
            return
        }

        val port = portStr.toIntOrNull() ?: 0
        if (port <= 0 || port > 65535) {
            binding.etPairPort.error = "Invalid port (1-65535)"
            return
        }

        viewModel.pair(host, port, code) { result ->
            if (result.isSuccess || result.stdout.contains("Successfully paired", ignoreCase = true)) {
                Toast.makeText(requireContext(), "✔ Successfully paired to $host:$port!", Toast.LENGTH_SHORT).show()

                if (autoConnect) {
                    val connectPortStr = binding.etConnectPort.text.toString().trim()
                    val connectPort = connectPortStr.toIntOrNull()
                        ?: viewModel.discoveredConnectPort.value
                        ?: 5555

                    viewModel.connect(host, connectPort) { connResult ->
                        if (connResult.isSuccess || connResult.stdout.contains("connected to", ignoreCase = true)) {
                            Toast.makeText(requireContext(), "🎉 Connected to Wireless ADB on port $connectPort!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(requireContext(), "Paired! Please verify connect port: ${connResult.combinedOutput.take(80)}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), "❌ Pairing Failed: ${result.combinedOutput.take(120)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performConnect() {
        val host = binding.etConnectHost.text.toString().trim().ifBlank { "127.0.0.1" }
        val portStr = binding.etConnectPort.text.toString().trim()

        if (portStr.isBlank()) {
            binding.etConnectPort.error = "Connect port required"
            return
        }

        val port = portStr.toIntOrNull() ?: 0
        if (port <= 0 || port > 65535) {
            binding.etConnectPort.error = "Invalid port (1-65535)"
            return
        }

        viewModel.connect(host, port) { result ->
            if (result.isSuccess || result.stdout.contains("connected to", ignoreCase = true)) {
                Toast.makeText(requireContext(), "Connected to $host:$port", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Connect Failed: ${result.combinedOutput.take(100)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        when (state) {
                            is ConnectionState.Connected -> {
                                binding.btnConnect.isEnabled = false
                                binding.btnDisconnect.isEnabled = true
                                binding.btnPair.isEnabled = false
                                binding.btnPairAndConnect.isEnabled = false
                            }
                            is ConnectionState.Pairing, is ConnectionState.Connecting -> {
                                binding.btnConnect.isEnabled = false
                                binding.btnPair.isEnabled = false
                                binding.btnPairAndConnect.isEnabled = false
                                binding.btnDisconnect.isEnabled = false
                            }
                            else -> {
                                binding.btnConnect.isEnabled = true
                                binding.btnPair.isEnabled = true
                                binding.btnPairAndConnect.isEnabled = true
                                binding.btnDisconnect.isEnabled = false
                            }
                        }
                    }
                }

                launch {
                    viewModel.isBusy.collect { busy ->
                        val alpha = if (busy) 0.6f else 1.0f
                        binding.btnPair.alpha = alpha
                        binding.btnPairAndConnect.alpha = alpha
                        binding.btnConnect.alpha = alpha
                    }
                }

                launch {
                    viewModel.connectedDevices.collect { devices ->
                        if (devices.isEmpty()) {
                            binding.tvDevicesList.text = "No devices currently attached.\nTip: Tap 'Connect' once paired."
                        } else {
                            binding.tvDevicesList.text = devices.joinToString("\n")
                        }
                    }
                }

                // Discovered Connect Port (safely show chip and never overwrite user's typing)
                launch {
                    viewModel.discoveredConnectPort.collect { port ->
                        if (port != null && port > 0) {
                            binding.tvLocalIp.text = "Host: 127.0.0.1 (Detected :$port)"
                            binding.tvDetectedConnectPort.text = "⚡ Detected: $port [Tap to use]"
                            binding.tvDetectedConnectPort.visibility = View.VISIBLE
                            binding.tvDetectedConnectPort.setOnClickListener {
                                binding.etConnectPort.setText(port.toString())
                            }

                            // Only prefill if user hasn't typed anything yet and isn't focused
                            if (binding.etConnectPort.text.isNullOrBlank() && !binding.etConnectPort.hasFocus()) {
                                binding.etConnectPort.setText(port.toString())
                            }
                        } else {
                            binding.tvDetectedConnectPort.visibility = View.GONE
                        }
                    }
                }

                // Discovered Pair Port (safely show chip and never overwrite user's typing)
                launch {
                    viewModel.discoveredPairPort.collect { port ->
                        if (port != null && port > 0) {
                            binding.tvDetectedPairPort.text = "⚡ Detected: $port [Tap to use]"
                            binding.tvDetectedPairPort.visibility = View.VISIBLE
                            binding.tvDetectedPairPort.setOnClickListener {
                                binding.etPairPort.setText(port.toString())
                            }

                            // Only prefill if user hasn't typed anything yet and isn't focused
                            if (binding.etPairPort.text.isNullOrBlank() && !binding.etPairPort.hasFocus()) {
                                binding.etPairPort.setText(port.toString())
                            }
                        } else {
                            binding.tvDetectedPairPort.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startDnsScan()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopDnsScan()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
