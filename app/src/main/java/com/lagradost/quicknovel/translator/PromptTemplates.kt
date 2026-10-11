package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.db.GlossaryTerm

object PromptTemplates {
    val SYSTEM_PROMPT = """
You are an expert translator specializing in Chinese web novels (xianxia, wuxia, xuanhuan). Translate naturally into readable English while preserving the original atmosphere. Keep terms like "qi" and "dantian" when appropriate. Use glossary terms EXACTLY as specified.
""".trimIndent()

    fun buildUserPrompt(chunk: String, relevantTerms: List<GlossaryTerm>): String {
        val sb = StringBuilder()

        if (relevantTerms.isNotEmpty()) {
            sb.appendLine("## Required Terminology (use these EXACT translations):")
            for (term in relevantTerms) {
                sb.appendLine("「${term.chinese}」→ ${term.english} [${term.category}]")
            }
            sb.appendLine()
        }

        sb.appendLine("## Instructions:")
        sb.appendLine("- Use the glossary terms EXACTLY as specified — this is critical for consistency")
        sb.appendLine("- If you encounter a NEW proper noun (name, place, technique) not in the glossary, mark it as: [NEW: 中文原文 → your suggested translation]")
        sb.appendLine("- Translate naturally, avoid overly literal translations")
        sb.appendLine("- Preserve paragraph structure")
        sb.appendLine()
        sb.appendLine("## Chinese Text:")
        sb.appendLine(chunk)
        sb.appendLine()
        sb.append("## Translation:")

        return sb.toString()
    }
}
