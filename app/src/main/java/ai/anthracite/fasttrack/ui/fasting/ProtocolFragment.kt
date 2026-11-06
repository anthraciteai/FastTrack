package ai.anthracite.fasttrack.ui.fasting

import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.databinding.FragmentProtocolBinding
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

/** The single place a user configures their fasting window. The Fasting tab is read-only. */
class ProtocolFragment : Fragment() {

    private var _binding: FragmentProtocolBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FastingViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProtocolBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        buildChips()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedProtocol.collect { protocol ->
                    syncChipSelection(protocol)
                    binding.protocolName.text = protocol.displayName
                    binding.protocolSubtitle.text = protocol.subtitle
                }
            }
        }
    }

    private fun buildChips() {
        binding.protocolChips.removeAllViews()
        FastingProtocol.entries.forEach { protocol ->
            val chip = Chip(requireContext()).apply {
                text = protocol.displayName
                isCheckable = true
                isCheckedIconVisible = true
                tag = protocol
                setOnClickListener { viewModel.selectProtocol(protocol) }
            }
            binding.protocolChips.addView(chip)
        }
    }

    private fun syncChipSelection(protocol: FastingProtocol) {
        for (i in 0 until binding.protocolChips.childCount) {
            val chip = binding.protocolChips.getChildAt(i) as Chip
            chip.isChecked = chip.tag == protocol
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
