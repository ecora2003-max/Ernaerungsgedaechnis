package de.ernaehrung.demo.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.*

@Composable fun ProductsReceipts(screen:Screen,s:Selection,ui:UiActions){val d=DemoRepository;val p=d.product(s.productId)
    when(screen){
        Screen.P01->{var manual by remember{mutableStateOf(false)}
            Heading(screen.title,"Einmal kennen. Immer wiederfinden.")
            Surface(color=Color(0xFF253E32),shape=RoundedCornerShape(22.dp)){
                Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Badge("Kamera nicht aktiv")
                    Surface(color=Color.Transparent,shape=RoundedCornerShape(18.dp),border=BorderStroke(2.dp,Color(0xFFAFC595))){Box(Modifier.size(184.dp),contentAlignment=Alignment.Center){FoodArt("tofu",Modifier.size(140.dp))}}
                    Text("${d.tofu.name} · ${d.tofu.pack}",style=MaterialTheme.typography.titleMedium,color=Color.White)
                    Text("${d.tofu.id} · kein echter Barcode",style=MaterialTheme.typography.bodyMedium,color=Color(0xFFDCE6D7))
                }
            }
            Action("Demo-Produkt ansehen",tag="go_P02"){ui.product(d.tofu.id,Screen.P02)}
            Go("Produkt suchen",Screen.L01,ui,true);Action("Manuell anlegen",true){manual=true}
            Route("Kassenbon erfassen","Zum simulierten Einkaufsablauf",Icons.Outlined.ReceiptLong,Screen.E01,ui,Amber)
            Hint("Das Scanfenster ist eine Gestaltungsvorschau. Es öffnet keine Kamera und erkennt keine Codes.",Sage)
            if(manual)FormDialog("Produkt anlegen · Vorschau",listOf("Produktname" to "","Marke" to "","Packungsgröße" to ""),{manual=false},{manual=false;ui.demo("Es wurde kein neues Produkt gespeichert. Die ausgefüllte Beispielmaske ist separat erreichbar.",Screen.P03,"Beispielmaske ansehen")})
        }
        Screen.P02->{
            Heading(screen.title,"Herkunft und offene Angaben im Blick behalten.")
            Panel{Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically){FoodArt(p.kind,Modifier.size(80.dp));Column(Modifier.weight(1f)){
                Text(p.name,style=MaterialTheme.typography.titleLarge);Text("${p.brand} · ${p.pack}",style=MaterialTheme.typography.bodyMedium,color=Muted);Text(p.id,style=MaterialTheme.typography.labelSmall,color=Muted)
            }};Badge("Quelle nicht bestätigt",Amber);Text("Open Food Facts – simuliertes Quellenbeispiel. Kein Abruf erfolgt.",style=MaterialTheme.typography.bodyMedium,color=Muted)}
            Panel{Section("Nährwerte je 100 g","Ausschließlich Demonstrationswerte");Nutrients(p.nutrition)}
            Fold("Weitere Nährstoffe"){KV("Gesättigte Fettsäuren",value(p.nutrition.saturated));KV("Zucker",value(p.nutrition.sugar));KV("Ballaststoffe",value(p.nutrition.fiber));KV("Salz",value(p.nutrition.salt));KV("Vitamine & Mineralstoffe","Unbekannt")}
            Fold("Zutaten, Allergene & Herkunft"){KV("Zutaten",p.ingredients);KV("Allergene",p.allergens);KV("Produktversion",p.version);Hint("Keine reale Allergie- oder Etikettenprüfung erfolgt.")}
            Go("Angaben prüfen & ergänzen",Screen.P03,ui);Go("Verzehr ansehen",Screen.T02,ui,true)
            Hint("Produkt speichern erhöht keinen Bestand. Auch bei Bestand 0 bleiben die Produktinformationen im Katalog.",Sage)
        }
        Screen.P03->{
            Heading(screen.title,"Bearbeitbare Korrekturvorschau");Badge("Etikett bestätigt · nur simuliert")
            Hint("Das Etikett wurde nicht geprüft. Der Status zeigt ausschließlich das geplante Design.")
            listOf("Produktname" to p.name,"Packungsgröße" to p.pack,"Energie je 100 g in kcal" to p.nutrition.kcal?.let{de(it)}.orEmpty(),"Protein je 100 g in g" to p.nutrition.protein?.let{de(it)}.orEmpty(),"Fett je 100 g in g" to p.nutrition.fat?.let{de(it)}.orEmpty(),"Kohlenhydrate je 100 g in g" to p.nutrition.carbs?.let{de(it)}.orEmpty()).forEach{(label,initial)->var text by remember(p.id,label){mutableStateOf(initial)};Field(label,text,{text=it},"Unbekannt")}
            Fold("Weitere Angaben bearbeiten"){listOf("Gesättigte Fettsäuren","Zucker","Ballaststoffe","Salz","Zutaten","Allergene","Hersteller").forEach{label->var text by remember{mutableStateOf("")};Field(label,text,{text=it},"Unbekannt")}}
            Action("Korrektur speichern"){ui.demo("Die Korrektur wurde nicht gespeichert. Version ${p.version} und alle Bestände bleiben unverändert.")}
            Action("Produkt übernehmen"){ui.demo("Es wird kein Produkt angelegt und kein Vorrat erhöht.",Screen.L01,"Produktkatalog ansehen")}
            Go("Produktkatalog ansehen",Screen.L01,ui,true);Go("Jetzt Einkauf erfassen",Screen.E01,ui,true)
        }
        Screen.L01->{var query by remember{mutableStateOf("")};var filter by remember{mutableStateOf("Alle")}
            Heading(screen.title,"${d.products.size} bekannte Demo-Produkte · unabhängig vom Vorrat")
            Field("Produkt suchen",query,{query=it});Choices(listOf("Alle","Auf Lager","Bestand 0"),filter){filter=it}
            val products=d.products.filter{(it.name+it.brand+it.id).contains(query,true)&&when(filter){"Auf Lager"->it.inStock;"Bestand 0"->!it.inStock;else->true}}
            Text("${products.size} Produkte",style=MaterialTheme.typography.labelSmall,color=Muted)
            if(products.isEmpty())Hint("Kein Demo-Produkt gefunden. Versuche einen anderen Suchbegriff.",Sage)
            products.forEach{v->ProductRow(v,"${v.brand} · ${v.pack}",if(v.inStock)"Auf Lager · ${v.amount}" else "Bestand 0 · Produkt bleibt bekannt"){ui.product(v.id,Screen.P02)}}
            Go("Lagerbestand ansehen",Screen.L02,ui,true)
        }
        Screen.L02->{var filter by remember{mutableStateOf("Alle")}
            Heading(screen.title,"Was schon da ist, bleibt im Blick.");Choices(listOf("Alle","Kühlschrank","Vorrat","Gefrierfach"),filter){filter=it}
            d.products.filter{it.inStock&&(filter=="Alle"||it.location==filter)}.forEach{v->ProductRow(v,"${v.amount} · ${v.location}",v.check){ui.product(v.id,Screen.L03)}}
            Action("Bestand korrigieren",true){ui.demo("Die Vorschau erzeugt keine Korrekturbewegung und ändert keine Menge.",Screen.L03,"Chargenansicht öffnen")}
            Go("Produktkatalog",Screen.L01,ui,true);Go("Bedarf prüfen",Screen.H01,ui);Go("Einkauf erfassen",Screen.E01,ui,true)
        }
        Screen.L03->{
            Heading(screen.title,p.name)
            Panel(color=Sage){Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){FoodArt(p.kind,Modifier.size(80.dp));Metric(p.amount,"Verfügbar · Demo",Modifier.weight(1f))}
                KV("Produktversion",p.version);KV("Chargenkennung","${p.id}-LOT-01");KV("Einkauf / Herstellung",p.date);KV("Lagerort",p.location);KV("MHD / Verbrauchsdatum","Unbekannt");KV("Prüfstatus",p.check)}
            Section("Chronik","Unveränderliche Beispielereignisse")
            Panel{KV(p.date,"${if(p.kind=="curry")"Produktion" else "Einkauf"} · Zugang ${p.amount}");Text("Keine weiteren Bewegungen in dieser Demo-Charge. Die Eingabemasken ändern diese Chronik nicht.",style=MaterialTheme.typography.bodyMedium,color=Muted)}
            Go("Verbrauch erfassen",Screen.T02,ui)
            Action("Umlagern",true){ui.demo("Es wird keine Charge verschoben. ${p.location} bleibt als Demo-Lagerort sichtbar.")}
            Action("Korrigieren",true){ui.demo("Keine Korrekturbewegung erzeugt. ${p.amount} bleiben unverändert.")};Go("Produktinformationen",Screen.P02,ui,true)
        }
        Screen.E01->{
            Heading(screen.title,"Ein Beleg. Alle Positionen im Blick.")
            Panel(color=Amber.copy(alpha=.6f)){Section("DEMO-MARKT","MUSTERBELEG · ${d.shortDate}");HorizontalDivider();d.receipt.forEach{KV(it.original,money(it.price))};HorizontalDivider();KV("Summe",money(d.receipt.sumOf{it.price}),true);Text(d.receiptId,style=MaterialTheme.typography.labelSmall,color=Muted)}
            Action("Bon fotografieren"){ui.demo("Keine Kamera und keine Texterkennung aktiv. Du kannst den vorbereiteten Musterbeleg prüfen.",Screen.E02,"Musterbeleg prüfen")}
            Action("Position manuell hinzufügen",true){ui.demo("Die nächste Ansicht zeigt bearbeitbare Vorschauzeilen. Es werden keine Positionen angelegt.",Screen.E02,"Eingabemaske ansehen")}
            Go("Demo-Beleg prüfen",Screen.E02,ui);Hint("Texterkennung würde später nur Vorschläge liefern. Jede Zuordnung müsste geprüft werden.")
        }
        Screen.E02->{
            Heading(screen.title,"2 Positionen vorbereitet · 1 Position offen")
            d.receipt.forEachIndexed{i,line->var name by remember{mutableStateOf(line.original)};var amount by remember{mutableStateOf(line.amount)}
                Panel{Text("POSITION ${i+1}",style=MaterialTheme.typography.labelSmall,color=Muted);Field("Originalbezeichnung",name,{name=it});Field("Menge · Vorschau",amount,{amount=it});KV("Preis erfasst",money(line.price))
                    if(line.id!=null){val v=d.product(line.id);KV("Zuordnung",v.name);Badge("Produkt erkannt · Demo");KV("Lagerzuordnung",v.location);Action("Zuordnung ansehen",true){ui.product(v.id,Screen.P02)}}
                    else{Badge("Lagerzuordnung offen",Amber);Text("Kein Produkt zugeordnet. Der Preis bleibt sichtbar; diese Position erzeugt keinen Vorrat.",style=MaterialTheme.typography.bodyMedium);Action("Produkt zuordnen",true){ui.demo("Die Position bleibt offen; eine echte Zuordnung ist nicht implementiert.",Screen.L01,"Katalog durchsuchen")}}
                }
            }
            Hint("Die Felder sind flüchtige Eingabevorschauen. Die nächste Ansicht zeigt den festen Musterbeleg; Änderungen werden nicht übernommen.",Sage);Go("Weiter zur Übersicht",Screen.E03,ui)
        }
        Screen.E03->{
            Heading(screen.title,"Prüfen, bevor später etwas gebucht wird.");Badge("2 Positionen vorbereitet, 1 offen",Amber)
            Panel{KV("Händler",d.merchant);KV("Datum",d.shortDate);KV("Belegkennung",d.receiptId);KV("Belegsumme",money(d.receipt.sumOf{it.price}),true)}
            Panel{Section("Vorbereitete Zuordnungen");d.receipt.filter{it.id!=null}.forEach{l->val v=d.product(l.id!!);KV(v.name,"${l.amount} · ${v.location}")};Hint("ARTIKEL 03 · ${money(d.receipt.last().price)} · Zuordnung offen")}
            Action("Beleg speichern"){ui.demo("Der Musterbeleg wird nicht gespeichert. Es gibt keine dauerhafte Beleg- oder Preisbuchung.")}
            Action("Bestätigte Vorräte buchen"){ui.demo("Es wurde nichts gebucht. Alle Vorräte bleiben unverändert; die offene Position bleibt offen.",Screen.L02,"Bestände ansehen")}
            Go("Bestände ansehen",Screen.L02,ui,true);Go("Preise & Budget ansehen",Screen.H03,ui,true)
        }
        else->Unit
    }
}
