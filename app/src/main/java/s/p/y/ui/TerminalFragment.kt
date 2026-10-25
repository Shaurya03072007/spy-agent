package s.p.y.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import s.p.y.adb.ConnectionState
import s.p.y.databinding.FragmentTerminalBinding

class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: TerminalAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        setupQuickChips()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = TerminalAdapter()
        val layoutManager = LinearLayoutManager(requireContext())
        layoutManager.stackFromEnd = true
        binding.rvTerminal.layoutManager = layoutManager
        binding.rvTerminal.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnRunCommand.setOnClickListener {
            runCurrentCommand()
        }

        binding.etCommandLine.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runCurrentCommand()
                true
            } else {
                false
            }
        }

        binding.btnHistoryUp.setOnClickListener {
            val prev = viewModel.getPreviousHistoryCommand()
            if (prev != null) {
                binding.etCommandLine.setText(prev)
                binding.etCommandLine.setSelection(prev.length)
            }
        }

        binding.btnHistoryDown.setOnClickListener {
            val next = viewModel.getNextHistoryCommand()
            if (next != null) {
                binding.etCommandLine.setText(next)
                binding.etCommandLine.setSelection(next.length)
            }
        }

        binding.btnClearTerminal.setOnClickListener {
            viewModel.clearTerminal()
        }

        binding.btnCopyTerminal.setOnClickListener {
            val lines = viewModel.terminalLines.value
            if (lines.isEmpty()) {
                Toast.makeText(requireContext(), "Terminal is empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val textToCopy = lines.joinToString("\n") { it.text }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Output", textToCopy))
            Toast.makeText(requireContext(), "Copied ${lines.size} lines to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runCurrentCommand() {
        val cmd = binding.etCommandLine.text.toString().trim()
        if (cmd.isNotBlank()) {
            binding.etCommandLine.text.clear()
            viewModel.executeShell(cmd)
        }
    }

    private fun setupQuickChips() {
        binding.chipTcpip5555.setOnClickListener {
            Toast.makeText(requireContext(), "Enabling TCP/IP 5555 mode...", Toast.LENGTH_SHORT).show()
            viewModel.enableTcpip5555 { result ->
                if (result.isSuccess || result.stdout.contains("restarting in TCP mode", ignoreCase = true)) {
                    Toast.makeText(requireContext(), "✔ TCP/IP 5555 Active! Wi-Fi no longer needed.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "TCP/IP output: ${result.combinedOutput.take(60)}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.chipDeviceInfo.setOnClickListener {
            viewModel.executeShell("getprop ro.product.model; getprop ro.build.version.release")
        }

        binding.chipBattery.setOnClickListener {
            viewModel.executeShell("dumpsys battery")
        }

        binding.chipApps.setOnClickListener {
            viewModel.executeShell("pm list packages -3")
        }

        binding.chipScreenSize.setOnClickListener {
            viewModel.executeShell("wm size")
        }

        binding.chipScreenDensity.setOnClickListener {
            viewModel.executeShell("wm density")
        }

        binding.chipTop.setOnClickListener {
            viewModel.executeShell("top -n 1 -m 5")
        }

        binding.chipDisk.setOnClickListener {
            viewModel.executeShell("df -h /data")
        }

        binding.chipLogcat.setOnClickListener {
            viewModel.executeShell("logcat -d -t 30")
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.terminalLines.collect { lines ->
                        adapter.submitList(lines) {
                            if (lines.isNotEmpty()) {
                                binding.rvTerminal.scrollToPosition(lines.size - 1)
                            }
                        }
                    }
                }

                launch {
                    viewModel.connectionState.collect { state ->
                        when (state) {
                            is ConnectionState.Connected -> {
                                binding.tvTerminalTarget.text = "shell@${state.host}:${state.port} [${state.deviceName}] $"
                            }
                            is ConnectionState.Connecting -> {
                                binding.tvTerminalTarget.text = "connecting@${state.host}:${state.port}..."
                            }
                            is ConnectionState.Pairing -> {
                                binding.tvTerminalTarget.text = "pairing@${state.host}:${state.port}..."
                            }
                            else -> {
                                binding.tvTerminalTarget.text = "shell@localhost (Disconnected) $"
                            }
                        }
                    }
                }

                launch {
                    viewModel.isBusy.collect { busy ->
                        binding.btnRunCommand.isEnabled = !busy
                        binding.btnRunCommand.alpha = if (busy) 0.6f else 1.0f
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
