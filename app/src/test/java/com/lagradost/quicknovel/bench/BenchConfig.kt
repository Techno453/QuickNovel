package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.TranslationOptions
import java.io.File
import java.util.Properties

internal val benchMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .enable(SerializationFeature.INDENT_OUTPUT)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SampleSpec(
    val id: String,
    val lang: String,
    /** syosetu | kakuyomu | novelpia | quanben | file */
    val source: String,
    val url: String = "",
    val title: String = id,
    val enabled: Boolean = true,
)

/**
 * One model under test. Either fully explicit, or `"preset": "<app preset id>"` (see
 * [ModelPresets]) with any field below given explicitly as an override; [resolved] fills the rest
 * from the preset.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ModelSpec(
    /** Report / result-store id; defaults to the preset's model id. */
    val id: String = "",
    /** App preset to take endpoint, model, extra body, temperature behaviour and prices from. */
    val preset: String? = null,
    /** Model id sent to the API; null = [id]. */
    val model: String? = null,
    val inputPerM: Double = Double.NaN,
    val outputPerM: Double = Double.NaN,
    /** OpenAI reasoning_effort, stepped down automatically if rejected; null = not sent. */
    val reasoningEffort: String? = null,
    /** null = the config's baseUrl (OpenAI). */
    val baseUrl: String? = null,
    /** Name of the API key in the environment / bench.local.properties ("" = by provider, OpenAI by default). */
    val keyName: String = "",
    /** Extra top-level request fields (handed to the engine's extraBody); a null value removes the field. */
    val extraBody: Map<String, Any?> = emptyMap(),
    /** false = never send temperature (engine `sendTemperature`); null = send. */
    val sendTemperature: Boolean? = null,
    val headers: Map<String, String> = emptyMap(),
    /** "max_completion_tokens" or "max_tokens"; null = by provider (OpenAI: max_completion_tokens). */
    val maxTokensParam: String? = null,
    /** Free text shown in the cost table, e.g. "OpenRouter default route (estimate)". */
    val priceNote: String = "",
    /** max output = max(minCompletionTokens, estimated prompt tokens * this). */
    val completionFactor: Double = 2.5,
    val minCompletionTokens: Int = 2048,
    val enabled: Boolean = true,
) {
    val provider: String get() = if (keyName == OPENAI_KEY) "openai" else keyName.removeSuffix("_API_KEY").lowercase()

    /** Model id sent to the API. */
    val apiModel: String get() = model ?: id

    /**
     * Fills unset fields from [preset]: model, base URL, key name (by provider), prices, extra
     * body (explicit entries win), reasoning_effort (so the metering interceptor keeps it),
     * headers and temperature behaviour. Explicit models only get the OpenAI key default.
     */
    fun resolved(): ModelSpec {
        val p = preset?.let { ModelPresets.byId(it) ?: error("Unknown preset '$it' in bench.config.json") }
        if (p == null) {
            require(id.isNotBlank()) { "Model without id or preset in bench.config.json" }
            require(!inputPerM.isNaN() && !outputPerM.isNaN()) { "Model $id: inputPerM / outputPerM missing" }
            return copy(keyName = keyName.ifBlank { OPENAI_KEY })
        }
        val presetBody = p.extraBody + (if (p.sendTemperature) emptyMap() else mapOf("temperature" to null))
        return copy(
            id = id.ifBlank { p.model },
            model = model ?: p.model,
            inputPerM = if (inputPerM.isNaN()) p.priceIn else inputPerM,
            outputPerM = if (outputPerM.isNaN()) p.priceOut else outputPerM,
            reasoningEffort = reasoningEffort ?: p.extraBody["reasoning_effort"] as? String,
            baseUrl = baseUrl ?: p.baseUrl,
            keyName = keyName.ifBlank { keyNameFor(p.provider.id) },
            extraBody = presetBody + extraBody,
            sendTemperature = sendTemperature ?: p.sendTemperature,
            headers = headers.ifEmpty { p.provider.headers },
            priceNote = priceNote.ifBlank { "preset ${p.id}" },
        )
    }

    fun baseUrlOr(default: String): String = baseUrl ?: default

    fun tokensParam(): String = maxTokensParam ?: if (keyName == OPENAI_KEY) "max_completion_tokens" else "max_tokens"

    fun costUsd(promptTokens: Long, completionTokens: Long): Double =
        promptTokens / 1e6 * inputPerM + completionTokens / 1e6 * outputPerM

    companion object {
        const val OPENAI_KEY = "OPENAI_API_KEY"

        /** Environment / bench.local.properties key name for an app provider id. */
        fun keyNameFor(providerId: String): String = when (providerId) {
            "openai" -> OPENAI_KEY
            "google" -> "GEMINI_API_KEY"
            "qwen" -> "DASHSCOPE_API_KEY"
            else -> providerId.uppercase() + "_API_KEY" // OPENROUTER_, DEEPSEEK_, ANTHROPIC_API_KEY
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class JudgeSpec(val enabled: Boolean = true, val model: String = "gpt-6-luna")

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val dryRun: Boolean = false,
    val budgetUsd: Double = 2.50,
    val parallelism: Int = 4,
    val sampleTargetChars: Int = 3000,
    val sampleMinChars: Int = 2500,
    val sampleMaxChars: Int = 3500,
    val chapterChars: Int = 4500,
    val targetUsdPer100Chapters: Double = 3.0,
    val stretchUsdPer100Chapters: Double = 5.0,
    val bibleCallsPer100Chapters: Int = 2,
    val bibleModel: String = "gpt-6-luna",
    /** Before each provider phase, drop at most this many of its most expensive models if the estimate would exceed the cap. */
    val maxDropsPerPhase: Int = 2,
    /** Engine options for translations (the app's defaults: strict mode and story context on). */
    val strictMode: Boolean = true,
    val carryStoryContext: Boolean = true,
    /** The v1 single judge; it is also the panel's first member and the "luna-only" comparison column. */
    val judge: JudgeSpec = JudgeSpec(),
    /** LLM judge panel (see Judge.kt / docs/translation-bench.md). */
    val judges: JudgePanelSpec = JudgePanelSpec(),
    val samples: List<SampleSpec> = emptyList(),
    val models: List<ModelSpec> = emptyList(),
) {
    fun model(id: String): ModelSpec? = models.firstOrNull { it.id == id }

    fun engineOptions(): TranslationOptions = TranslationOptions(strictMode = strictMode, carryStoryContext = carryStoryContext)

    /** Resolves preset references; fails on duplicate ids. */
    fun resolved(): BenchConfig {
        val list = models.map { it.resolved() }
        val dupes = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "Duplicate model ids in bench.config.json: $dupes" }
        return copy(models = list)
    }

    companion object {
        fun loadDefault(): BenchConfig {
            val stream = BenchConfig::class.java.getResourceAsStream("/bench/bench.config.json")
                ?: error("bench/bench.config.json not found on the test classpath")
            return stream.use { benchMapper.readValue<BenchConfig>(it) }.resolved()
        }
    }
}

/**
 * Run-time environment: project root, API keys, overrides. Every override can be given as a
 * JVM system property (bench.*), an environment variable (BENCH_*), or a line in
 * bench.local.properties.
 */
class BenchEnv private constructor(
    val projectRoot: File,
    private val localProps: Properties,
    private val propsFileName: String?,
) {
    val outputRoot: File get() = File(projectRoot, "app/build/translation-bench")
    val cacheDir: File get() = File(outputRoot, "cache")
    val localSamplesDir: File get() = File(projectRoot, "bench/samples")

    /** Key by name (environment first, then bench.local.properties); registered with [Secrets]. */
    fun key(name: String = ModelSpec.OPENAI_KEY): String? =
        (System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() })
            ?.also { Secrets.register(it) }

    fun hasKey(name: String = ModelSpec.OPENAI_KEY): Boolean = key(name) != null

    fun keyDescription(name: String): String {
        val k = key(name) ?: return "$name: none"
        val src = if (System.getenv(name)?.isNotBlank() == true) "env" else (propsFileName ?: "properties")
        return "$name: loaded from $src (${Secrets.mask(k)})"
    }

    /** Looks up sysprop "bench.<name>", env "BENCH_<NAME>", then bench.local.properties (both spellings). */
    fun setting(name: String): String? {
        val envName = "BENCH_" + name.uppercase().replace('.', '_').replace('-', '_')
        return System.getProperty("bench.$name")?.takeIf { it.isNotBlank() }
            ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty(envName)?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("bench.$name")?.takeIf { it.isNotBlank() }
    }

    val enabled: Boolean
        get() = (System.getProperty("bench") ?: System.getenv("BENCH"))?.trim() in setOf("1", "true", "yes")

    /** Applies overrides: models, samples, dryRun, judge, budget, parallelism. */
    fun applyOverrides(base: BenchConfig): BenchConfig {
        var c = base
        setting("models")?.let { list ->
            val wanted = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val known = c.models.associateBy { it.id }
            wanted.forEach { require(it in known) { "Unknown model '$it' (add it to bench.config.json)" } }
            c = c.copy(models = c.models.map { it.copy(enabled = it.id in wanted) })
        }
        setting("samples")?.let { list ->
            val wanted = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            c = c.copy(samples = c.samples.map { it.copy(enabled = it.id in wanted) })
        }
        (setting("dryRun") ?: setting("dry_run"))?.let { c = c.copy(dryRun = it.toBoolean() || it == "1") }
        setting("judge")?.let { c = c.copy(judge = c.judge.copy(enabled = it.toBoolean() || it == "1")) }
        (setting("budget") ?: setting("budget_usd"))?.toDoubleOrNull()?.let { c = c.copy(budgetUsd = it) }
        setting("parallelism")?.toIntOrNull()?.let { c = c.copy(parallelism = it.coerceIn(1, 8)) }
        return c
    }

    companion object {
        private const val PROPS_FILE = "bench.local.properties"

        fun findProjectRoot(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()) return dir
                dir = dir.parentFile
            }
            return File("").absoluteFile
        }

        fun load(): BenchEnv {
            val root = findProjectRoot()
            // bench.local.properties: project root first, then parent directories (a git worktree
            // under .claude/worktrees/ finds the main checkout's file).
            var propsFile: File? = null
            var dir: File? = root
            while (dir != null && propsFile == null) {
                File(dir, PROPS_FILE).takeIf { it.isFile }?.let { propsFile = it }
                dir = dir.parentFile
            }
            val props = Properties()
            propsFile?.inputStream()?.use { props.load(it) } // tolerates blank lines and comments
            return BenchEnv(root, props, propsFile?.name)
        }
    }
}

/** Keeps API keys out of every string the harness writes. */
object Secrets {
    private val secrets = mutableSetOf<String>()
    private val KEY_LIKE = Regex("""sk-[A-Za-z0-9_\-*]{6,}""")

    @Synchronized
    fun register(secret: String) {
        if (secret.length >= 8) secrets.add(secret)
    }

    /** Never reveals any character of the key. */
    fun mask(secret: String): String = "****(${secret.length} chars)"

    /** Removes registered secrets and anything that looks like a key (error bodies may echo partial keys). */
    fun scrub(text: String?): String {
        if (text == null) return ""
        var s: String = text
        synchronized(this) { for (k in secrets) s = s.replace(k, "[REDACTED]") }
        return s.replace(KEY_LIKE, "sk-[REDACTED]")
    }
}
