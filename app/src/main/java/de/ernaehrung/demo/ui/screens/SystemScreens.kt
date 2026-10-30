package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.*

@Composable fun SystemScreens(screen:Screen,ui:UiActions){val d=DemoRepository
    when(screen){
        Screen.MORE->{
            Heading(screen.title,"Dein Überblick, an einem Ort.")
            Route("Körper & Training","Messungen, Bewegung und Entwicklung",Icons.Outlined.MonitorHeart,Screen.K01,ui)
            Route("Profil & Ziele","Fiktives Profil und Zielwerte",Icons.Outlined.PersonOutline,Screen.M01,ui)
            Route("Datenqualität","Offene Angaben und Quellen",Icons.Outlined.FactCheck,Screen.M02,ui,Amber)
            Route("Daten & Sicherungen","Ausblick auf lokale Datenhaltung",Icons.Outlined.Storage,Screen.M03,ui)
            Route("ChatGPT-Bereich","Simulierter Export und Planimport",Icons.Outlined.AutoAwesome,Screen.I01,ui,Purple)
            Fold("Demo-Rundgang · alle 33 Ansichten"){Screen.entries.filter{it!=Screen.MORE}.forEach{Go("${it.name} · ${it.title}",it,ui,true)}}
            Hint("Ernährungsgedächtnis · UI-Demo 0.1.0\nAlle Personen, Produkte, Belege und Messungen sind fiktiv. Keine echte Buchung, keine Datenerfassung, keine Cloudverbindung.",Sage)
        }
        Screen.M01->{var form by remember{mutableStateOf(false)}
            Heading("Profil & Ernährungsziele","Deine Richtung, mit flexiblen Leitplanken.")
            Panel(color=Sage){Icon(Icons.Outlined.PersonOutline,null,Modifier.size(36.dp),tint=Forest);Section("Demo-Profil");KV("Ziel",d.goal);Badge("Fiktiv · nicht persönlich bestätigt",Amber)}
            Panel{KV("Ernährungsvorliebe","Pflanzenbetont · Demo");KV("Training","3 Einheiten / Woche · Demo");KV("Allergien & Einschränkungen","Unbekannt");KV("Energieziel","${d.energyGoal} kcal / Tag · Demo");KV("Proteinziel","${d.proteinGoal} g / Tag · Demo")}
            Action("Ziele bearbeiten"){form=true};Action("Mit ChatGPT auswerten",true){ui.demo("Kein ChatGPT-Aufruf. Die nächste Ansicht zeigt nur eine spätere Exportauswahl.",Screen.I01,"Exportvorschau ansehen")};Go("Zur Monatsplanung",Screen.G02,ui,true)
            if(form)FormDialog("Demo-Ziele bearbeiten",listOf("Ziel" to d.goal,"Energie in kcal" to d.energyGoal.toString(),"Protein in g" to d.proteinGoal.toString()),{form=false},{form=false;ui.demo("Die Zielwerte wurden nicht gespeichert. Das Demo-Profil bleibt unverändert.")})
        }
        Screen.M02->{
            Heading(screen.title,"Wissen, was bekannt ist – und was noch fehlt.")
            Link("Nährwerte unvollständig","Naturtofu · Salz, gesättigte Fette und Ballaststoffe unbekannt",Icons.Outlined.FactCheck,Amber,"go_P02"){ui.product(d.tofu.id,Screen.P02)}
            Route("Bonposition ungeklärt","ARTIKEL 03 · Preis vorhanden, Produkt offen",Icons.Outlined.ReceiptLong,Screen.E02,ui,Amber)
            Panel{Section("Lager- und Quellenstatus");KV("Naturtofu","Kühlschrank · nur Demozuordnung");KV("Haltbarkeit","Unbekannt");KV("Quellenprüfung","Nicht erfolgt")}
            Fold("Versionschronik"){KV("DEMO-v1","Aktuelle feste Produktversion");KV("DEMO-v2","Korrekturvorschau · nicht gespeichert");Text("Eine neue Version darf historische Verzehre nicht verändern.",style=MaterialTheme.typography.bodyMedium)}
        }
        Screen.M03->{
            Heading(screen.title,"Ausblick auf eine lokale, klare Datenbasis.")
            Panel(color=Sage){Icon(Icons.Outlined.Storage,null,Modifier.size(36.dp),tint=Forest);Section("Später: lokal mit SQLite / Room");Text("Eine einzige schreibbare Datenquelle. Google Drive wäre ausschließlich Sicherung oder Export.",style=MaterialTheme.typography.bodyLarge);Badge("Noch nicht implementiert",Amber)}
            Hint("Die Demo verwendet nur feste Beispieldaten im App-Code. Keine Datenbank und keine dauerhafte Speicherung.")
            listOf("Backup erstellen","Daten exportieren","Sicherung wiederherstellen").forEach{label->Action(label,true){ui.demo("$label ist eine inaktive Vorschau. Keine Datei wird gelesen oder geschrieben; keine Sicherung wird verändert.")}}
            Panel{KV("Demo-Version","0.1.0");KV("Netzverbindung / Anmeldung","Nicht erforderlich");KV("Produktive Daten","Nicht verbunden")}
        }
        Screen.I01->{val options=listOf("Ernährungsprofil","Aktuelle Vorräte","Rezepte","Preisübersicht","Planungszeitraum","Körperwerte","Trainingsdaten")
            val checked=remember{mutableStateMapOf<String,Boolean>().apply{options.take(5).forEach{put(it,true)}}}
            Heading(screen.title,"Nur ausgewählte, erforderliche Daten.");Hint("Simulation · keine Datei und keine Verbindung zu ChatGPT",Purple)
            Panel{options.forEach{label->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked[label]==true,{checked[label]=it});Text(label,style=MaterialTheme.typography.bodyLarge)}}}
            Panel(color=Purple){Icon(Icons.Outlined.DataObject,null,Modifier.size(36.dp));Section("ernaehrung_demo.json");KV("Datenstand",d.shortDate);KV("Ausgewählte Bereiche","${checked.count{it.value}}");Text("Dateisymbol und Auswahl sind eine Vorschau. Es werden keine Dateien geschrieben oder versendet.",style=MaterialTheme.typography.bodyMedium)}
            Action("Exportvorschau ansehen"){ui.show(Notice("{\n  \"schema_version\": \"demo-0.1\",\n  \"is_demo\": true,\n  \"selected_sections\": ${checked.count{it.value}}\n}\n\nNur angezeigter Text; kein Export.",title="Simulierte JSON-Vorschau"))}
            Go("Weiter zum simulierten Planimport",Screen.I02,ui)
        }
        Screen.I02->{var selected by remember{mutableStateOf(false)}
            Heading(screen.title,"Ein KI-Vorschlag bleibt zunächst ein Vorschlag.");Action("Demo-Datei auswählen"){selected=true}
            if(selected)Badge("Feste Demo-Datei ausgewählt",Purple)
            Panel(color=Purple){Icon(Icons.Outlined.Description,null,Modifier.size(36.dp));Section("demo_plan.json","Feste Vorschau · 14. April–13. Mai 2026");KV("Zeitraum","30 Tage");KV("Meal-Prep-Portionen","12 geplant");KV("Produktbedarf","8 Positionen vorgeschlagen");KV("Rezepte","6 Referenzen vorgeschlagen");Badge("KI-Vorschlag – noch nicht übernommen",Purple)}
            Hint("Keine echten Dateien werden gelesen. Die Vorschau enthält absichtlich Konflikte und erweitert den Katalog nicht.");Go("Vorschlag prüfen",Screen.I03,ui)
        }
        Screen.I03->{
            Heading(screen.title,"Feste Prüfergebnisse · keine echte Validierung")
            Panel{Badge("Schema formal gültig · simuliert");KV("Bekannte Produkt-ID",d.tofu.id);KV("Bestand im Vorschlag","Veraltet · vor aktuellem Datenstand");KV("Produktversion","DEMO-v9 unbekannt");KV("Rezepte","3 von 6 Referenzen unbekannt");KV("Gefrierplatz","Kapazität fehlt · nicht prüfbar")}
            Hint("Offene Konflikte verhindern eine echte Übernahme. Ein Plan wäre später niemals eine Einkaufs- oder Verzehrbuchung.")
            Action("Konflikte ansehen",true){ui.show(Notice("1. Veralteter Bestand.\n2. Unbekannte Produktversion und Rezeptreferenzen.\n3. Fehlende Gefrierkapazität.\n\nDer Vorschlag wurde nicht übernommen.",title="3 Konfliktgruppen · Simulation"))}
            Action("Plan übernehmen"){ui.demo("Die Übernahme ist nicht aktiv; die Konflikte wurden nicht behoben. Die Monatsplanung bleibt unverändert.",Screen.G02,"Monatsplanung ansehen")}
            Go("Monatsplanung ansehen",Screen.G02,ui,true);Go("Abbrechen",Screen.I02,ui,true)
        }
        else->Unit
    }
}
