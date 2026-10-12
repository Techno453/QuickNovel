package com.lagradost.quicknovel.ui.glossary

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.FragmentGlossaryBinding
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import com.lagradost.quicknovel.ui.translate.AnalysisProgress
import com.lagradost.quicknovel.ui.translate.AnalysisState
import com.lagradost.quicknovel.ui.translate.TranslationForegroundService
import kotlinx.coroutines.launch

class GlossaryFragment : Fragment() {
    lateinit var binding: FragmentGlossaryBinding
    private val viewModel: GlossaryViewModel by activityViewModels()

    /** JSON waiting for the "save as" picker. */
    private var pendingExport: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri == null) return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(ctx, R.string.glossary_export_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, getString(R.string.glossary_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            showExportDialog(json)
        }
    }

    private val analysisListener: (AnalysisProgress) -> Unit = { p ->
        view?.post { renderAnalysis(p) }
    }

    companion object {
        /** Filter chip values: null = all, "unreviewed" = auto-added & not locked, else a category. */
        private const val FILTER_UNREVIEWED = "__unreviewed__"

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

        setupFilterChips()
        binding.glossarySearch.doOnTextChanged { text, _, _, _ ->
            viewModel.setFilter((viewModel.filter.value ?: GlossaryFilter()).copy(query = text?.toString().orEmpty()))
        }

        viewModel.visibleTerms.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val all = viewModel.terms.value.orEmpty()
            binding.glossaryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.glossaryEmpty.setText(if (all.isEmpty()) R.string.glossary_empty else R.string.glossary_empty_filtered)
            val reviewing = viewModel.filter.value?.unreviewedOnly == true
            binding.reviewBar.visibility = if (reviewing && list.isNotEmpty()) View.VISIBLE else View.GONE
            binding.reviewBarText.text = resources.getQuantityString(R.plurals.new_terms_to_review, list.size, list.size)
        }

        binding.lockAllShown.setOnClickListener {
            val shown = viewModel.visibleTerms.value.orEmpty().filter { !it.locked }
            viewModel.lockTerms(shown)
            Toast.makeText(requireContext(), getString(R.string.glossary_locked_count, shown.size), Toast.LENGTH_SHORT).show()
        }

        viewModel.pendingCount.observe(viewLifecycleOwner) { count ->
            binding.pendingTermsBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
            binding.pendingTermsBadge.text = count.toString()
        }

        viewModel.renameOffer.observe(viewLifecycleOwner) { offer ->
            offer ?: return@observe
            viewModel.clearRenameOffer()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.rename_apply_title)
                .setMessage(getString(R.string.rename_apply_message, offer.oldEnglish, offer.chapterCount, offer.newEnglish))
                .setPositiveButton(getString(R.string.rename_apply_yes, offer.chapterCount)) { _, _ ->
                    val appCtx = requireContext().applicationContext
                    viewLifecycleOwner.lifecycleScope.launch {
                        val changed = viewModel.applyRename(offer)
                        Toast.makeText(appCtx, appCtx.getString(R.string.rename_applied, changed), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.rename_apply_no, null)
                .show()
        }

        binding.addTermFab.setOnClickListener {
            AddTermDialogFragment.newInstance()
                .show(childFragmentManager, "add_term")
        }

        binding.pendingTermsBtn.setOnClickListener {
            viewModel.loadPendingTerms()
            PendingTermsDialogFragment()
                .show(childFragmentManager, "pending_terms")
        }

        binding.analysisCancel.setOnClickListener {
            TranslationForegroundService.cancelTranslation(requireContext())
        }
        AnalysisState.lastProgress?.takeIf { it.novelId == novelId }?.let { renderAnalysis(it) }
        AnalysisState.addListener(analysisListener)

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_glossary, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_analyze_novel -> {
                        AnalyzeNovelDialogFragment.newInstance(viewModel.novelId)
                            .show(childFragmentManager, "analyze_novel")
                        true
                    }
                    R.id.action_translation_style -> {
                        TranslationStyleDialogFragment().show(childFragmentManager, "translation_style")
                        true
                    }
                    R.id.action_review_auto -> {
                        selectFilter(FILTER_UNREVIEWED)
                        true
                    }
                    R.id.action_import_preset -> {
                        val count = viewModel.loadPreset(requireContext())
                        if (count >= 0) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.glossary_presets_loaded, count),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    R.id.action_export_json -> {
                        exportJson()
                        true
                    }
                    R.id.action_import_json -> {
                        importFromClipboard()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        AnalysisState.removeListener(analysisListener)
        super.onDestroyView()
    }

    private fun renderAnalysis(p: AnalysisProgress) {
        if (!::binding.isInitialized || p.novelId != viewModel.novelId) return
        if (p.finished) {
            binding.analysisStatus.visibility = View.GONE
            val ctx = context ?: return
            val msg = p.error ?: p.message
            if (msg.isNotBlank()) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            viewModel.loadContext()
        } else {
            binding.analysisStatus.visibility = View.VISIBLE
            binding.analysisStatusText.text = p.message
        }
    }

    private fun setupFilterChips() {
        val ctx = requireContext()
        val group = binding.glossaryFilterChips
        group.removeAllViews()
        val filters: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to getString(R.string.glossary_filter_all)) +
                GlossaryTerm.CATEGORIES.map { it to AddTermDialogFragment.categoryLabel(ctx, it) } +
                (FILTER_UNREVIEWED to getString(R.string.glossary_filter_unreviewed))
        val current = viewModel.filter.value ?: GlossaryFilter()
        val currentKey = if (current.unreviewedOnly) FILTER_UNREVIEWED else current.category
        for ((key, label) in filters) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                tag = key ?: ""
            }
            group.addView(chip)
            if (key == currentKey) group.check(chip.id)
        }
        group.setOnCheckedStateChangeListener { g, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            applyFilterKey((chip.tag as? String)?.ifEmpty { null })
        }
    }

    private fun applyFilterKey(key: String?) {
        val f = viewModel.filter.value ?: GlossaryFilter()
        viewModel.setFilter(
            when (key) {
                FILTER_UNREVIEWED -> f.copy(category = null, unreviewedOnly = true)
                else -> f.copy(category = key, unreviewedOnly = false)
            }
        )
    }

    private fun selectFilter(key: String?) {
        val group = binding.glossaryFilterChips
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            if ((chip.tag as? String)?.ifEmpty { null } == key) {
                group.check(chip.id)
                return
            }
        }
        applyFilterKey(key)
    }

    private fun exportJson() {
        viewLifecycleOwner.lifecycleScope.launch {
            val json = viewModel.exportJson()
            val name = (arguments?.getString("novel_name") ?: "glossary")
                .replace(Regex("""[\\/:*?"<>|]"""), "_").take(60) + "_story_bible.json"
            pendingExport = json
            try {
                exportLauncher.launch(name)
            } catch (e: ActivityNotFoundException) {
                pendingExport = null
                showExportDialog(json)
            }
        }
    }

    /** Fallback when no document picker is available: show the JSON with a Copy button. */
    private fun showExportDialog(json: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.glossary_export_title)
            .setMessage(json)
            .setPositiveButton(R.string.glossary_export_copy) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("story_bible.json", json))
                Toast.makeText(ctx, R.string.glossary_export_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFromClipboard() {
        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(ctx, R.string.glossary_import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val imported = try {
            viewModel.parseImport(text)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(ctx, getString(R.string.glossary_import_invalid, e.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.glossary_import_title)
            .setMessage(getString(R.string.glossary_import_confirm, imported.terms.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val appCtx = ctx.applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val count = viewModel.applyImport(imported)
                    Toast.makeText(appCtx, appCtx.getString(R.string.glossary_import_done, count), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(term: GlossaryTerm) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.glossary_delete_title)
            .setMessage(getString(R.string.glossary_delete_message, term.source, term.english))
            .setPositiveButton(R.string.glossary_delete) { _, _ -> viewModel.deleteTerm(term) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
