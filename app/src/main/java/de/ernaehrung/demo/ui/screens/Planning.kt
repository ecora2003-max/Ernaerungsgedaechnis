package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.*

@Composable fun Planning(screen:Screen,s:Selection,ui:UiActions){val d=DemoRepository;val r=d.recipe(s.recipeId)
    when(screen){
        Screen.R01->{var filter by remember{mutableStateOf("Alle")}
            Heading(screen.title,"Weniger überlegen. Gut vorbereitet sein.");Choices(listOf("Alle","Mit Vorräten kochbar","Meal Prep","Favoriten"),filter){filter=it}
            d.recipes.filter{when(filter){"Mit Vorräten kochbar"->it.ingredients.all{v->v.available};"Meal Prep"->it.mealPrep;"Favoriten"->it.favorite;else->true}}.forEach{v->
                Panel(Modifier.testTag("recipe_${v.id}").clickable{ui.recipe(v.id,Screen.R02)}){
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){FoodArt(v.kind,Modifier.size(84.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(v.name,style=MaterialTheme.typography.titleLarge);Text("${v.time} min · ${v.portions} Portionen",style=MaterialTheme.typography.bodyMedium,color=Muted);Text("≈ ${v.protein} g Protein / Portion",style=MaterialTheme.typography.bodyMedium,color=Forest)
                    }}
                    val missing=v.ingredients.count{!it.available};Badge(if(missing==0)"Mit Vorräten kochbar" else "$missing Zutat fehlt",if(missing==0)Sage else Amber)
                    Text("Rezept ansehen →",style=MaterialTheme.typography.labelLarge,color=Forest)
                }
            };Hint("Rezeptwerte und Vorratsabgleich sind vorbereitete Beispiele. Es wird nichts gekocht oder verbraucht.",Sage)
        }
        Screen.R02->{
            Heading(r.name,"Ein Rezept, das in deinen Alltag passt.")
            Panel(color=Sage){Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){FoodArt(r.kind,Modifier.size(110.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("${r.time} Minuten",style=MaterialTheme.typography.titleLarge);Text("${r.portions} Portionen · ${r.portionWeight} je Portion",style=MaterialTheme.typography.bodyMedium);Badge("Demo-Rezept · geschätzt")
            }};KV("Nährwertvorschau je Portion","${r.kcal} kcal · ${r.protein} g Protein")}
            Section("Zutaten & Vorratsabgleich");Panel{r.ingredients.forEach{i->val p=d.product(i.productId)
                Column(Modifier.fillMaxWidth().clickable{ui.product(p.id,Screen.P02)},verticalArrangement=Arrangement.spacedBy(4.dp)){
                    KV(p.name,i.amount,true);Text("${if(i.available)"Vorhanden" else "Fehlt"} · ${p.id}",style=MaterialTheme.typography.labelSmall,color=Muted)
                }
            }}
            Section("So geht’s");Panel{r.steps.forEachIndexed{i,step->Text("${i+1}.  $step",style=MaterialTheme.typography.bodyLarge)}}
            Go("Für Meal Prep planen",Screen.R03,ui);Go("Zutatenbedarf prüfen",Screen.H01,ui,true);Go("Zur Wochenplanung",Screen.G01,ui,true)
        }
        Screen.R03->{var portions by remember{mutableStateOf("")};var storage by remember{mutableStateOf("Gefrierfach")}
            Heading(screen.title,r.name)
            Panel(color=Blue){Badge("Geplant",Blue);KV("Geplante Portionen","${r.portions}",true);KV("Portionsgewicht",r.portionWeight);Text("Diese Planung hat keine Zutaten verbraucht.",style=MaterialTheme.typography.bodyMedium)}
            Field("Tatsächlich hergestellte Portionen",portions,{portions=it},"Noch nicht angegeben")
            Picker("Geplanter Lagerort",listOf("Kühlschrank","Gefrierfach"),storage){storage=it}
            Panel{KV("Haltbarkeit","Unbekannt");KV("Lebensmittelsicherheit","Noch nicht geprüft");KV("Platzcheck","Kapazität unbekannt · Prüfung offen")}
            Hint("Vor einer echten Produktion müssten Zutaten, sichere Abkühlung, Haltbarkeit und Lagerplatz geprüft werden.")
            Action("Produktion bestätigen"){ui.demo("Es wurde keine Produktion gebucht. Zutaten und vorhandene Curry-Portionen bleiben unverändert.",Screen.L02,"Bestände ansehen")}
            Action("Nur als Plan speichern",true){ui.demo("Die Planung wird nicht gespeichert. Es entstehen weder Verzehre noch neue Chargen.")};Go("Bestände ansehen",Screen.L02,ui,true)
        }
        Screen.G01->{
            Heading(screen.title,"13.–19. April 2026 · feste Beispielwoche","Vorausschauend, mit Spielraum")
            Choices(listOf("Woche","Monat"),"Woche"){if(it=="Monat")ui.go(Screen.G02)}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                d.days.forEachIndexed{i,label->FilterChip(s.day==i,{ui.selectDay(i)},label={Column(horizontalAlignment=Alignment.CenterHorizontally){Text(label);Text(d.dates[i].take(6))}},modifier=Modifier.heightIn(min=60.dp).testTag("day_$i"))}
            }
            Section("${d.days[s.day]}, ${d.dates[s.day]}","Alle folgenden Mahlzeiten sind nur geplant.")
            Panel(color=Blue){d.dailyPlan.forEachIndexed{i,m->KV("${m.time} · ${listOf("Frühstück","Mittagessen","Abendessen")[i]}","${m.name} · 1 Portion")};KV("Snack","Noch nicht festgelegt");KV("Geplantes Protein","ca. ${d.dailyPlan.sumOf{it.protein}} g · unvollständig")}
            Go("Geplanten Tag ansehen",Screen.G03,ui,true)
            Panel{Section("Diese Woche vorbereiten","Linsencurry · ${d.recipes[0].portions} Portionen geplant");Text("Vorhanden: ${d.product("DEMO-CURRY-001").amount} im Gefrierfach. Vor neuer Produktion den Bedarf abgleichen.",style=MaterialTheme.typography.bodyMedium,color=Muted);Go("Rezepte & Meal Prep",Screen.R01,ui,true)}
            Go("Bedarf prüfen",Screen.H01,ui);Go("Monatsplanung ansehen",Screen.G02,ui,true);Go("Mit ChatGPT planen",Screen.I01,ui,true)
        }
        Screen.G02->{
            Heading(screen.title,"Vier übersichtliche Wochen · 13. April–10. Mai 2026");Choices(listOf("Woche","Monat"),"Monat"){if(it=="Woche")ui.go(Screen.G01)}
            d.weeks.forEachIndexed{i,w->Panel{Text("${w.name} · ${w.dates}",style=MaterialTheme.typography.titleMedium);Badge(w.status,if(w.status=="Anpassung nötig")Amber else Blue);Section(w.focus);KV("Vorbereitung",w.portions);KV("Kosten geschätzt",w.cost);KV("Gefrierbedarf",w.portions)
                if(i==0)Go("Beispielwoche öffnen",Screen.G01,ui,true) else Action("Wochenvorschau ansehen",true){ui.demo("${w.name} besitzt eine kompakte Vorschaukarte. Die detaillierte Demo-Woche ist der 13.–19. April.",Screen.G01,"Beispielwoche öffnen")}
            }}
            Action("Plan aktualisieren",true){ui.demo("Bestände, Budget und Platz wurden nicht neu berechnet. Die Karten bleiben feste Beispiele.")};Go("Mit ChatGPT planen",Screen.I01,ui)
        }
        Screen.G03->{
            Heading(screen.title,"${d.days[s.day]}, ${d.dates[s.day]}");Badge("Nur geplant",Blue)
            d.dailyPlan.forEachIndexed{i,m->Panel{Section("${m.time} · ${m.name}","1 Portion · ca. ${m.kcal} kcal / ${m.protein} g Protein")
                when(i){0->{KV("Haferflocken 50 g","Vorhanden");KV("Sojajoghurt 150 g","Für Einkauf benötigt")};1->KV("DEMO-CURRY-001","Vorhanden · ${d.product("DEMO-CURRY-001").amount}");else->{KV("Naturtofu 200 g","Vorhanden");KV("TK-Gemüse 200 g","Vorhanden");Action("Naturtofu ansehen",true){ui.product(d.tofu.id,Screen.P02)}}}
            }}
            Panel{KV("Snack","Noch nicht festgelegt")};Hint("Die Bedarfsansicht ist ein fester Auszug für vier Produkte. Ein vollständiger automatischer Wochenabgleich ist nicht implementiert.")
            Go("Als heutige Planung anzeigen",Screen.T01,ui)
        }
        Screen.H01->{
            Heading(screen.title,"Bedarfsauszug · 4 ausgewählte Produkte des Wochenplans")
            d.needs.forEach{n->val p=d.product(n.id);Panel{
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){FoodArt(p.kind);Text(p.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)}
                KV("Benötigt","${de(n.required)} g");KV("Verfügbar",p.amount);KV("Fehlmenge","${de(n.missing)} g",true)
                Badge(when{n.missing==0.0->"Vorhanden";p.inStock->"Teilweise";else->"Fehlt"},if(n.missing>0)Amber else Sage)
            }}
            Hint("Vorberechnete Beispiele. Kein automatischer Einkaufszwang und keine echte Bedarfsberechnung.",Sage);Go("Einkaufsliste ansehen",Screen.H02,ui)
        }
        Screen.H02->{val checked=remember{mutableStateMapOf<String,Boolean>()}
            Heading(screen.title,"Nur Fehlmengen aus dem Demo-Bedarfsauszug")
            d.shopping.forEach{n->val p=d.product(n.id);Panel{
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Checkbox(checked[p.id]==true,{checked[p.id]=it},Modifier.testTag("check_${p.id}"));Column(Modifier.weight(1f)){Text(p.name,style=MaterialTheme.typography.titleMedium);Text(n.packs,style=MaterialTheme.typography.bodyMedium,color=Muted)};Text(money(n.cost),style=MaterialTheme.typography.labelLarge)}
                if(checked[p.id]==true)Text("Abgehakt · noch nicht als Einkauf erfasst",style=MaterialTheme.typography.labelSmall,color=Forest)
            }}
            Panel(color=Sage){KV("Geschätzte Gesamtkosten",money(d.shoppingCost),true);Text("Für die ganze Liste · keine Abbuchung",style=MaterialTheme.typography.bodyMedium,color=Muted)}
            Hint("Abhaken organisiert nur die Liste. Es erhöht keinen Vorrat und erfasst keinen Beleg.");Go("Einkauf erfassen",Screen.E01,ui);Go("Preise & Budget",Screen.H03,ui,true)
        }
        Screen.H03->{
            Heading(screen.title,"Plan und bestätigte Demo-Historie getrennt")
            Panel(color=Sage){Metric(money(d.remaining),"Nach Plan noch verfügbar");KV("Fiktives Wochenbudget",money(d.budget));KV("Bestätigte Historie · Demo",money(d.spent));KV("Geplanter Einkauf · Schätzung",money(d.shoppingCost))}
            Panel{Section("Naturtofu · ${d.tofu.pack}","Fiktiver Preisverlauf · Demo-Markt");d.prices.forEach{(date,cost)->KV(date,money(cost))};KV("Letzter Grundpreis","12,45 €/kg");Text("Die neue Bonvorschau ist kein zusätzlicher bestätigter Kauf.",style=MaterialTheme.typography.bodyMedium,color=Muted)}
            Panel{Section("Planungsschätzungen");d.shopping.forEach{KV(d.product(it.id).name,money(it.cost))}}
            Hint("Alle Preise sind erfunden. Keine Budgetbuchung und kein externer Preisabruf.",Sage);Go("Zur Wochenplanung",Screen.G01,ui)
        }
        else->Unit
    }
}
