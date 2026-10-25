package s.p.y.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import s.p.y.databinding.FragmentToolboxBinding

class ToolboxFragment : Fragment() {

    private var _binding: FragmentToolboxBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private var selectedPermission = "android.permission.WRITE_SECURE_SETTINGS"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentToolboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupStealthAndSecurity()
        setupPermissionChips()
        setupListeners()
    }

    private fun setupStealthAndSecurity() {
        binding.btnConfigureAppLinks.setOnClickListener {
            s.p.y.security.SecurityManager.openLinkHandlingSettings(requireContext())
            Toast.makeText(
                requireContext(),
                "Set 'Open supported links' to Always to auto-launch from browser.",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnTestBrowserLaunch.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://a.p.y")).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Launch error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        updatePinStatusUI()
        binding.btnSetPin.setOnClickListener {
            showSetPinDialog()
        }

        binding.btnRemovePin.setOnClickListener {
            showRemovePinDialog()
        }

        updateDeviceAdminUI()
        binding.btnToggleDeviceAdmin.setOnClickListener {
            if (s.p.y.security.SecurityManager.isDeviceAdminActive(requireContext())) {
                s.p.y.security.SecurityManager.removeDeviceAdmin(requireContext())
                updateDeviceAdminUI()
                Toast.makeText(requireContext(), "Uninstall Protection Deactivated", Toast.LENGTH_SHORT).show()
            } else {
                val intent = s.p.y.security.SecurityManager.createDeviceAdminIntent(requireContext())
                startActivity(intent)
            }
        }
    }

    private fun updatePinStatusUI() {
        val hasPin = s.p.y.security.SecurityManager.isPinEnabled(requireContext())
        if (hasPin) {
            binding.btnSetPin.text = "🔒 Change PIN"
            binding.btnRemovePin.visibility = View.VISIBLE
        } else {
            binding.btnSetPin.text = "🔒 Set 4-Digit PIN"
            binding.btnRemovePin.visibility = View.GONE
        }
    }

    private fun updateDeviceAdminUI() {
        val isAdmin = s.p.y.security.SecurityManager.isDeviceAdminActive(requireContext())
        if (isAdmin) {
            binding.btnToggleDeviceAdmin.text = "🛡️ Uninstall Protection: ACTIVE (Tap to Deactivate)"
            binding.btnToggleDeviceAdmin.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), s.p.y.R.color.accent_green))
        } else {
            binding.btnToggleDeviceAdmin.text = "🛡️ Activate Uninstall Protection (Device Admin)"
            binding.btnToggleDeviceAdmin.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), s.p.y.R.color.accent_cyan))
        }
    }

    private fun showSetPinDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "Enter 4 digits"
            setPadding(40, 30, 40, 30)
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Set Security PIN")
            .setMessage("Enter a 4-digit PIN required to unlock the app when opened:")
            .setView(input)
            .setPositiveButton("Save PIN") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 4) {
                    s.p.y.security.SecurityManager.setPin(requireContext(), pin)
                    updatePinStatusUI()
                    Toast.makeText(requireContext(), "✔ 4-Digit PIN Protection Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "PIN must be exactly 4 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemovePinDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "Current 4-digit PIN"
            setPadding(40, 30, 40, 30)
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Disable PIN Lock")
            .setMessage("Enter current PIN to disable:")
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val pin = input.text.toString().trim()
                if (s.p.y.security.SecurityManager.verifyPin(requireContext(), pin)) {
                    s.p.y.security.SecurityManager.disablePin(requireContext())
                    updatePinStatusUI()
                    Toast.makeText(requireContext(), "PIN Lock Disabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupPermissionChips() {
        binding.chipPermSecureSettings.setOnClickListener {
            selectedPermission = "android.permission.WRITE_SECURE_SETTINGS"
            Toast.makeText(requireContext(), "Selected: WRITE_SECURE_SETTINGS", Toast.LENGTH_SHORT).show()
        }

        binding.chipPermDump.setOnClickListener {
            selectedPermission = "android.permission.DUMP"
            Toast.makeText(requireContext(), "Selected: DUMP", Toast.LENGTH_SHORT).show()
        }

        binding.chipPermUsageStats.setOnClickListener {
            selectedPermission = "android.permission.PACKAGE_USAGE_STATS"
            Toast.makeText(requireContext(), "Selected: PACKAGE_USAGE_STATS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            val pkg = binding.etTargetPackage.text.toString().trim()
            if (pkg.isBlank()) {
                binding.etTargetPackage.error = "Package name required"
                return@setOnClickListener
            }

            val command = "pm grant $pkg $selectedPermission"
            viewModel.executeShell(command) { result ->
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Granted $selectedPermission to $pkg", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Failed: ${result.combinedOutput}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnGetDisplay.setOnClickListener {
            viewModel.executeShell("wm size; wm density")
            Toast.makeText(requireContext(), "Display info fetched (see Terminal)", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetDisplay.setOnClickListener {
            viewModel.executeShell("wm size reset; wm density reset")
            Toast.makeText(requireContext(), "Display settings reset to default", Toast.LENGTH_SHORT).show()
        }

        binding.btnScreenshot.setOnClickListener {
            val fileName = "screenshot_${System.currentTimeMillis()}.png"
            val path = "/sdcard/Download/$fileName"
            viewModel.executeShell("screencap -p $path") { result ->
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "Screenshot saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "Screenshot failed: ${result.combinedOutput}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDumpsysBattery.setOnClickListener {
            viewModel.executeShell("dumpsys battery")
            Toast.makeText(requireContext(), "Battery status fetched (see Terminal)", Toast.LENGTH_SHORT).show()
        }

        binding.btnToolboxTcpip5555.setOnClickListener {
            Toast.makeText(requireContext(), "Switching to TCP/IP 5555 mode...", Toast.LENGTH_SHORT).show()
            viewModel.enableTcpip5555 { result ->
                if (result.isSuccess || result.stdout.contains("restarting in TCP mode", ignoreCase = true)) {
                    Toast.makeText(requireContext(), "✔ TCP/IP 5555 Active! Wi-Fi is no longer required.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(requireContext(), "TCP/IP output: ${result.combinedOutput.take(80)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnToolboxUsbMode.setOnClickListener {
            viewModel.switchUsb { result ->
                Toast.makeText(requireContext(), "Switched adbd to USB mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
