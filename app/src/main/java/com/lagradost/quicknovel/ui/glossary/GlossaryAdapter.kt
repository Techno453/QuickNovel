package com.lagradost.quicknovel.ui.glossary

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.quicknovel.databinding.ItemGlossaryTermBinding
import com.lagradost.quicknovel.translator.TermAliases
import com.lagradost.quicknovel.translator.db.GlossaryTerm

class GlossaryAdapter(
    private val onDelete: (GlossaryTerm) -> Unit,
    private val onEdit: (GlossaryTerm) -> Unit
) : ListAdapter<GlossaryTerm, GlossaryAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GlossaryTerm>() {
            override fun areItemsTheSame(old: GlossaryTerm, new: GlossaryTerm) = old.id == new.id
            override fun areContentsTheSame(old: GlossaryTerm, new: GlossaryTerm) = old == new
        }
    }

    inner class ViewHolder(val binding: ItemGlossaryTermBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(term: GlossaryTerm) {
            val ctx = binding.root.context
            binding.termChinese.text = term.source
            binding.termEnglish.text = term.english
            binding.termCategory.text = AddTermDialogFragment.categoryLabel(ctx, GlossaryTerm.normalizeCategory(term.category))

            binding.termGender.visibility = if (term.isCharacter) View.VISIBLE else View.GONE
            binding.termGender.text = GlossaryTerm.genderBadge(term.gender)

            binding.termAuto.visibility = if (term.autoAdded && !term.locked) View.VISIBLE else View.GONE
            binding.termLock.visibility = if (term.locked) View.VISIBLE else View.GONE

            val aliases = TermAliases.parse(term.aliases)
            val preview = listOfNotNull(
                term.effectiveNotes.takeIf { it.isNotBlank() },
                aliases.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "aka ")
            ).joinToString(" · ")
            binding.termDescription.text = preview
            binding.termDescription.visibility = if (preview.isEmpty()) View.GONE else View.VISIBLE

            binding.root.setOnLongClickListener {
                onDelete(term)
                true
            }
            binding.root.setOnClickListener {
                onEdit(term)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGlossaryTermBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
