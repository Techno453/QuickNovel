package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Prompt profiles: system-prompt assembly and how the engine picks the profile. */
class PromptProfileTest {
    private val mapper = jacksonObjectMapper()
    private val langs = listOf("zh", "ja", "ko")

    @Test
    fun defaultProfileKeepsTheStandardPrompt() {
        for (lang in langs) {
            val prompt = PromptTemplates.systemPrompt(lang)
            assertEquals(prompt, PromptTemplates.systemPrompt(lang, PromptProfiles.DEFAULT))
            assertEquals(prompt, PromptTemplates.systemPrompt(lang, PromptProfiles.byId("no-such-profile")))
            // Rule 7 is followed directly by the blank line and the output protocol.
            assertTrue(lang, Regex("""\n7\. [^\n]+\n\nOutput format""").containsMatchIn(prompt))
            assertFalse(lang, prompt.contains("\n8. "))
        }
        assertEquals(PromptTemplates.systemPrompt("zh"), PromptTemplates.SYSTEM_PROMPT)
    }

    @Test
    fun profileRulesAreNumberedAfterTheBaseRulesAndBeforeTheProtocol() {
        val profile = PromptProfiles.GENERAL
        for (lang in langs) {
            val prompt = PromptTemplates.systemPrompt(lang, profile)
            val base = PromptTemplates.systemPrompt(lang)
            val first = PromptTemplates.BASE_RULE_COUNT + 1
            val last = PromptTemplates.BASE_RULE_COUNT + profile.rules.size
            profile.rules.forEachIndexed { i, rule ->
                assertTrue("$lang rule ${first + i}", prompt.contains("\n${first + i}. $rule\n"))
            }
            assertTrue(prompt.indexOf("\n$last. ") < prompt.indexOf("Output format"))
            assertTrue(prompt.indexOf("\n7. ") < prompt.indexOf("\n$first. "))
            assertFalse(prompt.contains("\n${last + 1}. "))
            // Only the rules are added: same head, same protocol.
            assertTrue(prompt.startsWith(base.substringBefore("\nOutput format").trimEnd()))
            assertTrue(prompt.endsWith(base.substringAfter("\nOutput format")))
        }
    }

    @Test
    fun languageRulesApplyOnlyToTheirLanguage() {
        val profile = PromptProfiles.GEMINI
        val jaOnly = profile.languageRules.getValue(TranslationLanguages.JAPANESE).single()
        val n = PromptTemplates.BASE_RULE_COUNT + profile.rules.size + 1
        assertTrue(PromptTemplates.systemPrompt("ja", profile).contains("\n$n. $jaOnly\n"))
        assertFalse(PromptTemplates.systemPrompt("zh", profile).contains(jaOnly))
        assertFalse(PromptTemplates.systemPrompt("ko", profile).contains(jaOnly))
        assertEquals(profile.rules, profile.rulesFor("zh"))
        assertEquals(profile.rules + jaOnly, profile.rulesFor("ja"))
    }

    @Test
    fun profilesAreWellFormed() {
        assertEquals(PromptProfiles.ALL.size, PromptProfiles.ALL.map { it.id }.distinct().size)
        assertTrue(PromptProfiles.DEFAULT.isDefault)
        for (p in PromptProfiles.ALL.filter { !it.isDefault }) {
            // Compact: a profile stays a short list of one-line rules.
            assertTrue(p.id, langs.all { p.rulesFor(it).size <= 12 })
            p.rules.forEach { assertFalse(p.id, it.contains('\n')) }
            assertTrue(p.id, p.label.isNotBlank() && p.description.isNotBlank())
        }
        // Every preset names a real profile and hands it to the engine config.
        for (preset in ModelPresets.ALL) {
            assertTrue(preset.id, PromptProfiles.exists(preset.promptProfile))
            assertEquals(preset.promptProfile, preset.toEngineConfig("k").promptProfile)
        }
    }

    // ---- engine ----

    private class Recorder {
        val bodies = CopyOnWriteArrayList<Pair<String, JsonNode>>()
        private val mapper = jacksonObjectMapper()

        fun systemFor(host: String): String =
            bodies.first { it.first == host }.second.path("messages").first { it.path("role").asText() == "system" }
                .path("content").asText()

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer().also { request.body?.writeTo(it) }
            bodies += request.url.host to mapper.readTree(buffer.readUtf8())
            val content = if (request.url.host == "primary.test" && refusePrimary) {
                "I can't help translate this, as the novel is protected by copyright."
            } else {
                "One.\nTwo.\nThree.\nFour.\n===TERMS===\n[]\n===SUMMARY===\nFour paragraphs."
            }
            val json = mapper.writeValueAsString(
                mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")))
            )
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }).build()

        var refusePrimary = false
    }

    private val source = "第一段。\n第二段。\n第三段。\n第四段。"
    private val primary = EngineConfig(baseUrl = "http://primary.test/v1", model = "primary-model", apiKey = "k1")
    private val generalRule = PromptProfiles.GENERAL.rules.first()

    @Test
    fun engineUsesItsConfigsProfile() = runBlocking {
        val rec = Recorder()
        val engine = TranslationEngine(primary.copy(promptProfile = PromptProfiles.GENERAL_ID), "zh", rec.client)
        assertEquals(PromptProfiles.GENERAL_ID, engine.promptProfileId)
        engine.translateChapter(source, "n", 0, emptyList(), options = TranslationOptions(strictMode = false))
        assertTrue(rec.systemFor("primary.test").contains(generalRule))
    }

    @Test
    fun optionsOverrideTheEngineProfile() = runBlocking {
        val rec = Recorder()
        TranslationEngine(primary, "zh", rec.client).translateChapter(
            source, "n", 0, emptyList(),
            options = TranslationOptions(strictMode = false, promptProfile = PromptProfiles.GENERAL_ID),
        )
        assertTrue(rec.systemFor("primary.test").contains(generalRule))

        val rec2 = Recorder()
        TranslationEngine(primary.copy(promptProfile = PromptProfiles.GENERAL_ID), "zh", rec2.client).translateChapter(
            source, "n", 0, emptyList(),
            options = TranslationOptions(strictMode = false, promptProfile = PromptProfiles.DEFAULT_ID),
        )
        assertEquals(PromptTemplates.systemPrompt("zh"), rec2.systemFor("primary.test"))
    }

    @Test
    fun fallbackKeepsItsOwnProfile() = runBlocking {
        val rec = Recorder().apply { refusePrimary = true }
        val fallback = EngineConfig(
            baseUrl = "http://fallback.test/v1", model = "fallback-model", apiKey = "k2",
            promptProfile = PromptProfiles.DEFAULT_ID,
        )
        val result = TranslationEngine(primary, "zh", rec.client, fallback).translateChapter(
            source, "n", 0, emptyList(),
            options = TranslationOptions(strictMode = false, promptProfile = PromptProfiles.LUNA_ID),
        )
        assertTrue(result.usedFallback)
        assertTrue(rec.systemFor("primary.test").contains(PromptProfiles.LUNA.rules.last()))
        assertEquals(PromptTemplates.systemPrompt("zh"), rec.systemFor("fallback.test"))
    }
}
