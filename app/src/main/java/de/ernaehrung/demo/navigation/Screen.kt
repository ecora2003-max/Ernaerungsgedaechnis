package de.ernaehrung.demo.navigation

enum class MainTab(val label:String,val root:String) { Start("Start","S01"),Stock("Bestände","L02"),Scan("Scan","P01"),Plan("Planung","G01"),More("Mehr","MORE") }
enum class Screen(val title:String,val tab:MainTab) {
    S01("Dein Alltag. Im Blick.",MainTab.Start),S02("Heute im Überblick",MainTab.Start),S03("Hinweise & Aufgaben",MainTab.Start),
    P01("Produkt erfassen",MainTab.Scan),P02("Produkt prüfen",MainTab.Scan),P03("Angaben ergänzen",MainTab.Scan),
    L01("Dein Produktkatalog",MainTab.Stock),L02("Deine Bestände",MainTab.Stock),L03("Charge & Bewegungen",MainTab.Stock),
    E01("Einkauf erfassen",MainTab.Scan),E02("Einkauf prüfen",MainTab.Scan),E03("Einkauf vorbereiten",MainTab.Scan),
    T01("Deine Ernährung heute",MainTab.Start),T02("Verzehr erfassen",MainTab.Start),T03("Deine Tagesbilanz",MainTab.Start),
    R01("Rezepte für deinen Alltag",MainTab.Plan),R02("Rezeptdetails",MainTab.Plan),R03("Meal Prep durchführen",MainTab.Plan),
    G01("Deine Woche",MainTab.Plan),G02("Dein Monatsplan",MainTab.Plan),G03("Geplanter Tag",MainTab.Plan),
    H01("Was fehlt noch?",MainTab.Plan),H02("Deine Einkaufsliste",MainTab.Plan),H03("Preise & Budget",MainTab.Plan),
    I01("Für ChatGPT vorbereiten",MainTab.More),I02("Planvorschau laden",MainTab.More),I03("Vorschlag prüfen",MainTab.More),
    M01("Profil & Ziele",MainTab.More),M02("Datenqualität",MainTab.More),M03("Daten & Sicherungen",MainTab.More),
    K01("Deine Körperwerte",MainTab.More),K02("Sport & Aktivität",MainTab.More),K03("Deine Entwicklung",MainTab.More),MORE("Mehr Möglichkeiten",MainTab.More);
    companion object { fun from(id:String?)=entries.find{it.name==id}?:S01 }
}
data class Selection(val productId:String="DEMO-TOFU-001",val recipeId:String="DEMO-R01",val day:Int=1)
data class Notice(val text:String,val destination:Screen?=null,val button:String="Vorschau ansehen",val title:String="Demo – Funktion noch nicht aktiv")
data class UiActions(val go:(Screen)->Unit,val show:(Notice)->Unit,val selectProduct:(String)->Unit,val selectRecipe:(String)->Unit,val selectDay:(Int)->Unit) {
    fun demo(text:String,destination:Screen?=null,button:String="Vorschau ansehen")=show(Notice(text,destination,button))
    fun product(id:String,target:Screen){selectProduct(id);go(target)}
    fun recipe(id:String,target:Screen){selectRecipe(id);go(target)}
}
