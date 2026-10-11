package com.lagradost.quicknovel.ui.glossary

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.FragmentGlossaryBinding
import com.lagradost.quicknovel.translator.db.GlossaryTerm

class GlossaryFragment : Fragment() {
    lateinit var binding: FragmentGlossaryBinding
    private val viewModel: GlossaryViewModel by activityViewModels()

    companion object {
        fun newInstance(novelId: String, novelName: String): Bundle = Bundle().apply {
            putString("novel_id", novelId)
            putString("novel_name", novelName)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentGlossaryBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val novelId = arguments?.getString("novel_id") ?: return
        val novelName = arguments?.getString("novel_name") ?: "Novel"
        viewModel.init(novelId)

        val adapter = GlossaryAdapter(
            onDelete = { term -> confirmDelete(term) },
            onEdit = { term ->
                AddTermDialogFragment.newInstance(term)
                    .show(childFragmentManager, "edit_term")
            }
        )

        binding.glossaryList.layoutManager = LinearLayoutManager(requireContext())
        binding.glossaryList.adapter = adapter

        viewModel.terms.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.glossaryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.pendingCount.observe(viewLifecycleOwner) { count ->
            binding.pendingTermsBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
            binding.pendingTermsBadge.text = count.toString()
        }

        binding.addTermFab.setOnClickListener {
            AddTermDialogFragment.newInstance()
                .show(childFragmentManager, "add_term")
        }

        binding.pendingTermsBtn.setOnClickListener {
            PendingTermsDialogFragment()
                .show(childFragmentManager, "pending_terms")
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_glossary, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_import_preset -> {
                        val count = viewModel.loadPreset(requireContext())
                        if (count >= 0) {
                            Toast.makeText(
                                requireContext(),
                                "Imported $count common cultivation terms",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    R.id.action_export_json -> {
                        val json = viewModel.exportJson()
                        // Show in a dialog for the user to copy
                        AlertDialog.Builder(requireContext())
                            .setTitle("Export Glossary JSON")
                            .setMessage(json)
                            .setPositiveButton("OK", null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun confirmDelete(term: GlossaryTerm) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Term")
            .setMessage("Delete \"${term.source} → ${term.english}\"?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteTerm(term) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
