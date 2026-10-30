package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.*

@Composable fun BodyTabs(current:Screen,ui:UiActions){val routes=listOf(Screen.K01,Screen.K02,Screen.K03);val compact=LocalConfiguration.current.screenWidthDp<340
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        listOf("Körperwerte","Training","Entwicklung").forEachIndexed{i,label->FilterChip(current==routes[i],{ui.go(routes[i])},
            label={Text(label,Modifier.fillMaxWidth(),fontSize=if(compact)10.sp else 12.sp,letterSpacing=0.sp,maxLines=1,textAlign=TextAlign.Center)},
            modifier=Modifier.weight(1f).heightIn(min=48.dp),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Forest,selectedLabelColor=Color.White))}
    }
}
@Composable fun BodyTraining(screen:Screen,ui:UiActions){val d=DemoRepository
    Heading("Körper & Training",when(screen){Screen.K01->"Deine Werte, mit Ruhe betrachtet.";Screen.K02->"Bewegung, bewusst eingeordnet.";else->"Ein Verlauf ist mehr als ein einzelner Wert."})
    BodyTabs(screen,ui)
    when(screen){
        Screen.K01->{var form by remember{mutableStateOf(false)};val last=d.measurements.last()
            Panel(color=Sage){Text("Letzte Demo-Messung · ${d.shortDate}",style=MaterialTheme.typography.labelSmall,color=Muted);Metric("${de(last.weight.toDouble())} kg","Körpergewicht");HorizontalDivider(color=Line)
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Metric("${de(last.fat.toDouble())} %","Körperfett",Modifier.weight(1f),"Geschätzt");Metric("${de(last.muscle.toDouble())} kg","Muskelmasse",Modifier.weight(1f),"Geschätzt")}}
            Action("Neue Messung"){form=true}
            Panel{Section("Gewicht · 14 Tage");Chart(d.measurements.map{it.weight},d.weightCurve,d.measurements.map{it.day})}
            Go("Entwicklung ansehen",Screen.K03,ui,true)
            Section("Letzte Messungen","Fiktive Beispielwaage · Messbedingungen unbekannt")
            Panel{d.measurements.reversed().forEach{m->KV("${m.day.toString().padStart(2,'0')}.04.2026","${de(m.weight.toDouble())} kg · ${de(m.fat.toDouble())} %")}}
            Hint("Alle Körperwerte sind erfunden. Körperfett und Muskelmasse sind Geräte-Schätzwerte, keine bestätigten Körperzusammensetzungen.")
            if(form)FormDialog("Neue Demo-Messung",listOf("Datum" to d.shortDate,"Gewicht in kg" to "","Körperfett in % · optional" to "","Muskelmasse in kg · optional" to "","Gerät · optional" to "","Messbedingungen · optional" to ""),{form=false},{form=false;ui.demo("Es wurde keine Messung gespeichert und kein Verlauf verändert.")})
        }
        Screen.K02->{var filter by remember{mutableStateOf("Alle")};var form by remember{mutableStateOf(false)}
            Action("Einheit eintragen"){form=true};Choices(listOf("Alle","Krafttraining","Radfahren","Spaziergang"),filter){filter=it}
            d.activities.filter{filter=="Alle"||it.name==filter}.forEach{a->Panel{Section(a.name,"${a.date} · ${a.minutes} min · ${a.intensity}");KV("Aktive Sportkalorien",a.calories?.let{"$it kcal · Uhrschätzung"}?:"Unbekannt");Badge("Fiktive Einheit")}}
            Hint("Uhrkalorien sind ungenaue Messschätzungen. Sie verändern in der Demo keine Ernährungsziele.")
            if(form)FormDialog("Demo-Einheit",listOf("Sportart" to "Krafttraining","Datum" to d.shortDate,"Dauer in Minuten" to "","Intensität · optional" to "","Aktive Uhrkalorien · optional" to ""),{form=false},{form=false;ui.demo("Es wurde keine Trainingseinheit gespeichert. Sportkalorien und Tagesziele wurden nicht berechnet.")})
        }
        Screen.K03->{var metric by remember{mutableStateOf("Gewicht")};var period by remember{mutableStateOf("30 Tage")}
            Choices(listOf("Gewicht","Fettmasse","Muskelmasse"),metric){metric=it};Choices(listOf("7 Tage","30 Tage","90 Tage","1 Jahr"),period){period=it}
            val points=if(period=="7 Tage")d.measurements.filter{it.day>=8}else d.measurements
            val values=points.map{when(metric){"Fettmasse"->it.fatMass;"Muskelmasse"->it.muscle;else->it.weight}}
            val curve=when(metric){"Fettmasse"->d.fatCurve;"Muskelmasse"->d.muscleCurve;else->d.weightCurve}.takeLast(points.size)
            Panel{Section("$metric · $period","Verfügbare Demodaten: ${points.first().day}.–14. April 2026");Chart(values,curve,points.map{it.day})}
            Panel(color=Sage){Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Metric("${de((values.last()-values.first()).toDouble())} kg","Veränderung",Modifier.weight(1f));Metric("${de(values.last().toDouble())} kg","Aktueller Wert",Modifier.weight(1f))}
                KV("Trend","Nur vorbereitete Beispielkurve");KV("Datenqualität","${points.size} fiktive Messungen · nicht bewertet")}
            Hint("Die Zeitraumwahl erfindet keine Messpunkte. Glättung, Regression und Kalorienkalibrierung sind nicht implementiert.")
            if(metric!="Gewicht")Hint("Fett- und Muskelmasse sind vorbereitete Geräte-Schätzwerte.",Blue)
        }
        else->Unit
    }
}
