package com.lagradost.quicknovel.ui.translate

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.SpendKind
import com.lagradost.quicknovel.translator.SpendLedger
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.SpendRow
import com.lagradost.quicknovel.translator.db.SpendEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Date

/**
 * "Spending": totals for today / this month / all time, per novel (top 10 + rest) and per model,
 * the last 50 entries and "clear history". Opened from Settings → AI Translation.
 */
@Composable
fun SpendDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Readable line length on wide screens (unfolded foldables, tablets).
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()) {
                SpendScreen(onClose = onDismiss, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize())
            }
        }
    }
}

@Composable
fun SpendScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<SpendLedger.Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        if (reload == 0) BatchScheduler.pollNow(context) // results of overnight batches show up here
        try {
            summary = SpendLedger.summary(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not read the spend history"
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Spending", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        val s = summary
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            s == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> SpendContent(context, s, onClear = { confirmClear = true })
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear spend history?") },
            text = { Text("Deletes every recorded call. The monthly cap then starts again from \$0 for this month. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        SpendLedger.clear(context)
                        reload++
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SpendContent(context: Context, s: SpendLedger.Summary, onClear: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                TotalCard("Today", SpendPricing.format(s.todayUsd), null, Modifier.weight(1f))
                TotalCard(
                    "This month", SpendPricing.format(s.monthUsd),
                    if (s.monthlyCapUsd > 0) "of ${SpendPricing.format(s.monthlyCapUsd)} cap" else "no cap",
                    Modifier.weight(1f),
                )
                TotalCard("All time", SpendPricing.format(s.allTimeUsd), null, Modifier.weight(1f))
            }
        }
        item {
            val notes = buildList {
                if (s.perJobCapUsd > 0) add("Per-job cap: ${SpendPricing.format(s.perJobCapUsd)}.")
                if (s.unpricedCalls > 0) add("${s.unpricedCalls} call(s) used a model with no known price and count as \$0.")
                add("Costs are computed from each reply's token usage and the model's list price (overnight batches at 50%).")
            }
            Text(notes.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Section("By novel") }
        if (s.byNovel.isEmpty()) item { Empty() }
        items(s.byNovel) { GroupRow(it) }
        item { Section("By model") }
        if (s.byModel.isEmpty()) item { Empty() }
        items(s.byModel) { GroupRow(it) }
        item { Section("Last ${s.latest.size} calls") }
        if (s.latest.isEmpty()) item { Empty() }
        items(s.latest, key = { it.id }) { EntryRow(context, it) }
        item {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onClear, enabled = s.latest.isNotEmpty()) { Text("Clear history") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TotalCard(label: String, value: String, detail: String?, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Empty() {
    Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
}

/** "Biqu/斗破苍穹" → "斗破苍穹 (Biqu)". */
private fun novelLabel(novelId: String): String {
    val slash = novelId.indexOf('/')
    return if (slash > 0) "${novelId.substring(slash + 1)} (${novelId.substring(0, slash)})" else novelId
}

@Composable
private fun GroupRow(row: SpendRow) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(novelLabel(row.name), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${row.calls} calls", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        Text(SpendPricing.format(row.usd), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EntryRow(context: Context, e: SpendEntry) {
    val time = remember(e.timestamp) {
        DateFormat.getMediumDateFormat(context).format(Date(e.timestamp)) + " " +
            DateFormat.getTimeFormat(context).format(Date(e.timestamp))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SpendKind.fromId(e.kind).label + (e.chapterNum?.let { " · ch. ${it + 1}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Text(if (e.priced) SpendPricing.format(e.usd) else "unpriced", fontWeight = FontWeight.Medium)
        }
        Text(
            listOfNotNull(
                time,
                e.model,
                e.novelId?.let { novelLabel(it) },
                "${CostEstimator.formatTokens(e.promptTokens)} in / ${CostEstimator.formatTokens(e.completionTokens)} out",
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
