package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.fitness.*
import de.ernaehrung.demo.ui.Forest
import java.time.LocalDate

@Composable
internal fun BodyRawScatter(series: BodyRawSeries, start: LocalDate?, end: LocalDate?) {
    val plot = remember(series, start, end) { BodyPlotProjection.project(series, start, end) }
    val suffix = "${series.measure.metric.name}_${series.measure.name.orEmpty()}_${series.measure.unit}"
    val colors = listOf(Forest, Color(0xFF945312), Color(0xFF395AA6), Color(0xFF85458E), Color(0xFF536776), Color(0xFF8B443D))
    Text("Rohmesspunkte · ${plot.points.size} darstellbar · ${plot.unknownCount} unbekannt",
        Modifier.testTag("body_plot_count_$suffix"), style = MaterialTheme.typography.titleSmall)
    if (plot.unplottableCount > 0) Text("${plot.unplottableCount} Originaltexte haben keine darstellbare Dezimalposition. Sie bleiben in der Liste erhalten.")
    if (plot.points.isNotEmpty()) {
        Text("${plot.first} bis ${plot.last} · Wertebereich ${plot.minimum?.stripTrailingZeros()?.toPlainString()} bis ${plot.maximum?.stripTrailingZeros()?.toPlainString()} ${series.measure.unit}")
        Canvas(Modifier.fillMaxWidth().height(176.dp).testTag("body_plot_$suffix").semantics {
            contentDescription = "${plot.points.size} einzelne Rohmesspunkte, ${plot.unknownCount} unbekannte Werte, ${plot.points.count { it.excluded }} ausgeschlossene Messpunkte weiterhin sichtbar. Keine Verbindung oder Glättung."
        }) {
            val inset = 8.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(1f)
            val height = (size.height - 2 * inset).coerceAtLeast(1f)
            val grid = Color(0xFFCCD4CE)
            drawLine(grid, Offset(inset, inset), Offset(inset, inset + height))
            drawLine(grid, Offset(inset, inset + height), Offset(inset + width, inset + height))
            plot.points.forEach { point ->
                val center = Offset(inset + point.x * width, inset + (1f - point.yFromBottom) * height)
                val color = colors[point.sourceIndex % colors.size]
                val radius = 4.dp.toPx()
                if (point.excluded) {
                    drawLine(color, center - Offset(radius, radius), center + Offset(radius, radius), 2.dp.toPx())
                    drawLine(color, center + Offset(-radius, radius), center + Offset(radius, -radius), 2.dp.toPx())
                } else drawCircle(color, radius, center)
            }
        }
        plot.sources.take(colors.size).forEachIndexed { index, source ->
            Text("● ${source.method.label} · ${source.methodDetail ?: "keine weitere Methodendefinition"} · ${source.device ?: "Gerät unbekannt"} · ${source.model ?: "Modell unbekannt"} · ${source.quality.label} · ${source.conditions ?: "Bedingungen unbekannt"}",
                color = colors[index % colors.size], style = MaterialTheme.typography.bodySmall)
        }
        if (plot.sources.size > colors.size) Text("${plot.sources.size - colors.size} weitere Methoden-/Geräte-/Bedingungsgruppen; Farben wiederholen sich. Alle genauen Angaben bleiben in den Originalzeilen erreichbar.", style = MaterialTheme.typography.bodySmall)
        Text("Einzelne Punkte sind Messungen, Kreuze bleiben als ausgeschlossene Rohwerte sichtbar. Gleiche Tage werden nicht gemittelt; überlagerte Punkte behalten ihre Originalzeilen. Lücken bleiben leer. Methoden, Geräte und Bedingungen sind pro Originalzeile angegeben; Messwerte werden durch die Darstellung nicht vergleichbar erklärt.", style = MaterialTheme.typography.bodySmall)
    } else Text("Ohne bekannten darstellbaren Wert wird keine Nullkurve gezeichnet.")
    Text("Darstellung ${BodyPlotProjection.VERSION}; die exakten Werte und Bedingungen stehen darunter.", style = MaterialTheme.typography.labelSmall)
}
