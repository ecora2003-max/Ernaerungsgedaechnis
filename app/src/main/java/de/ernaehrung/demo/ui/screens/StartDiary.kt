package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.*

@Composable fun StartDiary(screen:Screen,selection:Selection,ui:UiActions){val d=DemoRepository
    when(screen){
        Screen.S01->{
            Heading("Dein Alltag.\nIm Blick.","Einfach planen. Bewusst genießen.",d.date)
            Panel(color=Sage){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Metric("${d.products.size}","Produkte",Modifier.weight(1f));Metric(de(d.product("DEMO-CURRY-001").quantity),"Portionen",Modifier.weight(1f),"Meal Prep");Metric("2","Offen",Modifier.weight(1f),"Prüfungen")
            }}
            Route("Körper & Training","${de(d.measurements.last().weight.toDouble())} kg · letzte Messung heute",Icons.Outlined.MonitorHeart,Screen.K01,ui)
            Route("Heute","1 Mahlzeit erfasst · Tagesbilanz offen",Icons.Outlined.Restaurant,Screen.S02,ui)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Tile("Bestände","Was ist da?",Icons.Outlined.Inventory2,Screen.L02,ui,Modifier.weight(1f));Tile("Produkte","Dein Katalog",Icons.Outlined.Eco,Screen.L01,ui,Modifier.weight(1f))}
            Route("Scan","Produkt oder Kassenbon ansehen",Icons.Outlined.QrCodeScanner,Screen.P01,ui)
            Route("Planung","Deine Woche & Meal Prep",Icons.Outlined.CalendarMonth,Screen.G01,ui,Blue)
            Route("Einkaufsliste","3 Produkte · geschätzt ${money(d.shoppingCost)}",Icons.Outlined.ShoppingCart,Screen.H02,ui,Amber)
            Section("Heute – deine Ernährung im Blick");SummaryCard()
            Route("Hinweise & Aufgaben","2 Prüfungen und 3 Planungsimpulse",Icons.Outlined.Notifications,Screen.S03,ui,Amber)
        }
        Screen.S02,Screen.T01->{
            Heading(screen.title,d.date);SummaryCard()
            if(screen==Screen.T01)Hint("Die heutige Planung bleibt eine feste Vorschau. Die Auswahl im Wochenplan wird nicht gespeichert.",Blue)
            Section("Tatsächlich gegessen","Ein bestätigter, fiktiver Beispieleintrag");d.eaten.forEach{MealCard(it)}
            Section("Geplant","Diese Mahlzeiten zählen noch nicht zur Bilanz.");d.planned.forEach{MealCard(it)}
            Go("Mahlzeit erfassen",Screen.T02,ui);Go("Tagesbilanz ansehen",Screen.T03,ui,true)
        }
        Screen.S03->{
            Heading(screen.title,"Kleine Schritte für einen klaren Überblick.");Badge("2 offene Prüfungen",Amber)
            Link("Produkt prüfen","Naturtofu · Nährwertangaben ergänzen",Icons.Outlined.FactCheck,Amber,"go_P02"){ui.product(d.tofu.id,Screen.P02)}
            Route("Neue Körpermessung","Messbedingungen sind noch offen",Icons.Outlined.MonitorWeight,Screen.K01,ui,Amber)
            Section("Planungsimpulse")
            Link("Meal Prep vorbereiten","${d.recipes[0].portions} Portionen Linsencurry · nur geplant",Icons.Outlined.SoupKitchen,tag="go_R03"){ui.recipe(d.recipes[0].id,Screen.R03)}
            Route("Einkauf bald benötigt","Naturtofu fehlen 150 g für die Planung",Icons.Outlined.ShoppingCart,Screen.H02,ui)
            Route("Planung anpassen","Lagerplatz im Wochenplan prüfen",Icons.Outlined.CalendarMonth,Screen.G01,ui,Blue)
        }
        Screen.T02->{
            var chosen by remember{mutableStateOf(selection.productId)};var amount by remember{mutableStateOf("50")};var unit by remember{mutableStateOf("g")};var charge by remember{mutableStateOf("Nicht ausgewählt")}
            val p=d.product(chosen)
            Heading(screen.title,"Eine Eingabemaske, noch keine Buchung.")
            Picker("Produkt aus deinem Katalog",d.products.map{it.name},p.name){n->chosen=d.products.first{it.name==n}.id;charge="Nicht ausgewählt";ui.selectProduct(chosen)}
            Field("Tatsächlich gegessene Menge",amount,{amount=it},"z. B. 50")
            Picker("Einheit",listOf("g","ml","Portionen"),unit){unit=it}
            Picker("Charge · optional",listOf("Nicht ausgewählt","${p.id}-LOT-01 · ${p.location}"),charge){charge=it}
            if(chosen==d.tofu.id&&amount.replace(',','.').toDoubleOrNull()==50.0&&unit=="g")Panel(color=Sage){
                Section("Feste Nährwertvorschau","50 g Bio Naturtofu · Demo")
                Row(horizontalArrangement=Arrangement.spacedBy(20.dp)){Metric("≈ 63","kcal",Modifier.weight(1f));Metric("6,5 g","Protein",Modifier.weight(1f))}
                Text("Fett 3,5 g · Kohlenhydrate 1 g",style=MaterialTheme.typography.bodyMedium)
            } else Hint("Für diese Eingabe ist keine Nährwertberechnung implementiert. Werte bleiben unbekannt, nicht 0.",Sage)
            Action("Verzehr bestätigen"){
                val valid=amount.replace(',','.').toDoubleOrNull()?.let{it.isFinite()&&it>0}==true
                if(!valid)ui.show(Notice("Bitte gib eine konkrete positive Menge und passende Einheit an. Es wurde nichts erfasst.",title="Menge noch unklar"))
                else ui.demo("Es wurde kein Verzehr gespeichert und kein Vorrat vermindert. Die Tagesbilanz enthält weiterhin nur das feste Demo-Frühstück.",Screen.T03,"Tagesbilanz ansehen")
            }
            Go("Tagesbilanz ansehen",Screen.T03,ui,true);Action("Charge ansehen",true,"go_L03"){ui.product(p.id,Screen.L03)}
            Hint("g, ml und Portionen sind nicht automatisch austauschbar. Eine echte App müsste Produkt und Mengenumrechnung prüfen.")
        }
        Screen.T03->{
            Heading(screen.title,d.date);Badge("Noch nicht vollständig erfasst",Amber);SummaryCard()
            Panel{Section("Makronährstoffe","Nur das tatsächlich erfasste Demo-Frühstück");KV("Protein",value(d.dayNutrition.protein));KV("Fett",value(d.dayNutrition.fat));KV("Kohlenhydrate",value(d.dayNutrition.carbs))}
            Fold("Weitere Nährstoffe"){KV("Zucker",value(d.dayNutrition.sugar));KV("Ballaststoffe",value(d.dayNutrition.fiber));KV("Salz",value(d.dayNutrition.salt));KV("Vitamine & Mineralstoffe","Unbekannt")}
            Section("Verzehrhistorie");d.eaten.forEach{MealCard(it)}
            Action("Eintrag korrigieren",true){ui.demo("Es wird keine Korrektur gespeichert. Später wären Änderungen mit ihrem Ursprung und Datum nachvollziehbar.",Screen.T02,"Eingabemaske ansehen")}
            Go("Zur heutigen Übersicht",Screen.S02,ui,true);Hint("Geplantes zählt nicht zur Bilanz. Ein unvollständiger Tag ist kein Tag mit 0 kcal.",Sage)
        }
        else->Unit
    }
}
