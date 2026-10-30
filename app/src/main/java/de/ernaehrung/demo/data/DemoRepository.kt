package de.ernaehrung.demo.data

import java.util.Locale

fun de(v:Double)=if(v%1.0==0.0)v.toInt().toString() else String.format(Locale.GERMANY,"%.1f",v)
fun money(c:Int)=String.format(Locale.GERMANY,"%.2f €",c/100.0)
fun value(v:Double?,unit:String="g")=v?.let{"${de(it)} $unit"}?:"Unbekannt"
data class Nutrition(val kcal:Double?,val protein:Double?,val fat:Double?,val carbs:Double?,val saturated:Double?=null,val sugar:Double?=null,val fiber:Double?=null,val salt:Double?=null)
data class Product(val id:String,val name:String,val pack:String,val kind:String,val quantity:Double,val unit:String,val location:String,val nutrition:Nutrition,
    val ingredients:String,val allergens:String,val date:String,val check:String,val brand:String="Demo-Marke",val version:String="DEMO-v1") {
    val amount get()=if(unit=="g"&&quantity>=1000)"${de(quantity/1000)} kg" else "${de(quantity)} $unit"
    val inStock get()=quantity>0
}
data class Ingredient(val productId:String,val amount:String,val available:Boolean)
data class Recipe(val id:String,val name:String,val time:Int,val portions:Int,val protein:Int,val kcal:Int,val kind:String,val favorite:Boolean,val mealPrep:Boolean,
    val ingredients:List<Ingredient>,val steps:List<String>,val portionWeight:String)
data class Meal(val name:String,val time:String,val kcal:Int,val protein:Int,val planned:Boolean,val kind:String)
data class Need(val id:String,val required:Double,val missing:Double,val packs:String,val cost:Int)
data class ReceiptLine(val original:String,val id:String?,val amount:String,val price:Int)
data class Measurement(val day:Int,val weight:Float,val fat:Float,val fatMass:Float,val muscle:Float)
data class Activity(val name:String,val date:String,val minutes:Int,val calories:Int?,val intensity:String)
data class Week(val name:String,val dates:String,val focus:String,val portions:String,val cost:String,val status:String)

/** Immutable, fictional fixtures. No mutation, database, file, clock or network API. */
object DemoRepository {
    const val date="14. April 2026"
    const val shortDate="14.04.2026"
    const val energyGoal=2000
    const val proteinGoal=100
    const val goal="Body Recomposition / Muskelaufbau"
    val products=listOf(
        Product("DEMO-TOFU-001","Bio Naturtofu","200 g","tofu",400.0,"g","Kühlschrank",Nutrition(125.0,13.0,7.0,2.0,sugar=.5),"Sojabohnen, Wasser, Gerinnungsmittel · Beispiel","Soja · fiktive Angabe","12.04.2026","MHD unbekannt · prüfen"),
        Product("DEMO-OATS-001","Haferflocken","500 g","oats",1800.0,"g","Vorrat",Nutrition(370.0,13.0,7.0,59.0,1.2,1.0,10.0,.01),"Hafer · Beispiel","Unbekannt","10.04.2026","Prüfstatus offen"),
        Product("DEMO-CHICK-001","Kichererbsen","240 g Abtropfgewicht","can",0.0,"g","Vorrat",Nutrition(120.0,6.4,2.0,17.0,fiber=5.5),"Kichererbsen, Wasser · Beispiel","Unbekannt","08.04.2026","Kein Bestand"),
        Product("DEMO-TOMATO-001","Passierte Tomaten","500 g","tomato",500.0,"g","Vorrat",Nutrition(30.0,1.4,.2,4.5,sugar=4.0),"Tomaten · Beispiel","Unbekannt","10.04.2026","Prüfstatus offen"),
        Product("DEMO-LENTIL-001","Rote Linsen","500 g","lentil",300.0,"g","Vorrat",Nutrition(340.0,24.0,1.5,49.0,fiber=11.0),"Rote Linsen · Beispiel","Unbekannt","10.04.2026","Prüfstatus offen"),
        Product("DEMO-YOGURT-001","Sojajoghurt","400 g","yogurt",0.0,"g","Kühlschrank",Nutrition(45.0,4.0,2.3,2.0),"Unbekannt","Unbekannt","12.04.2026","Kein Bestand"),
        Product("DEMO-VEG-001","TK-Gemüse","750 g","veg",450.0,"g","Gefrierfach",Nutrition(50.0,2.5,.5,7.0),"Gemüsemischung · Beispiel","Unbekannt","10.04.2026","Lagerprüfung offen"),
        Product("DEMO-CURRY-001","Linsencurry","1 Portion ≈ 350 g","curry",6.0,"Portionen","Gefrierfach",Nutrition(null,null,null,null),"Rezept DEMO-R01","Unbekannt","13.04.2026","Haltbarkeit unbekannt · prüfen",brand="Demo-Meal-Prep")
    )
    fun product(id:String)=products.first{it.id==id}
    val tofu get()=products.first()
    val recipes=listOf(
        Recipe("DEMO-R01","Linsencurry",35,6,18,410,"curry",true,true,
            listOf(Ingredient("DEMO-LENTIL-001","300 g",true),Ingredient("DEMO-TOMATO-001","500 g",true),Ingredient("DEMO-CHICK-001","480 g",false),Ingredient("DEMO-VEG-001","450 g",true)),
            listOf("Gemüse vorbereiten und anschwitzen.","Linsen, Tomaten und Wasser zugeben und garen.","Kichererbsen ergänzen und abschmecken.","Auf sechs Portionen verteilen. Sichere Abkühlung und Lagerung gesondert prüfen."),"ca. 350 g"),
        Recipe("DEMO-R02","Overnight Oats",10,1,20,420,"oats",true,true,
            listOf(Ingredient("DEMO-OATS-001","50 g",true),Ingredient("DEMO-YOGURT-001","150 g",false)),
            listOf("Haferflocken mit Sojajoghurt mischen.","Abgedeckt kühl stellen; weitere Toppings sind eine Rezeptidee."),"ca. 250 g"),
        Recipe("DEMO-R03","Tofu-Gemüse-Pfanne",25,2,28,520,"veg",false,false,
            listOf(Ingredient("DEMO-TOFU-001","400 g",true),Ingredient("DEMO-VEG-001","400 g",true)),
            listOf("Tofu würfeln und anbraten.","Gemüse zugeben und vollständig garen.","In zwei Portionen aufteilen."),"ca. 400 g")
    )
    fun recipe(id:String)=recipes.first{it.id==id}
    val dayNutrition=Nutrition(420.0,20.0,12.0,54.0,sugar=14.0,fiber=8.0)
    val eaten=listOf(Meal(recipes[1].name,"07:45",420,20,false,"oats"))
    val planned=listOf(Meal(recipes[0].name,"12:30",410,18,true,"curry"),Meal(recipes[2].name,"19:00",520,28,true,"veg"))
    val dailyPlan=listOf(Meal(recipes[1].name,"08:00",420,20,true,"oats"))+planned
    val needs=listOf(Need("DEMO-TOFU-001",550.0,150.0,"1 × 200 g",249),Need("DEMO-OATS-001",500.0,0.0,"Kein Bedarf",0),Need("DEMO-CHICK-001",480.0,480.0,"2 × 240 g Abtropfgewicht",238),Need("DEMO-TOMATO-001",1500.0,1000.0,"2 × 500 g",298))
    val shopping get()=needs.filter{it.missing>0}
    val shoppingCost get()=shopping.sumOf{it.cost}
    const val budget=6000
    const val spent=2480
    val remaining get()=budget-spent-shoppingCost
    val receipt=listOf(ReceiptLine("BIO NATURTOFU",tofu.id,"1 × 200 g",249),ReceiptLine("KICHERERBSEN","DEMO-CHICK-001","1 × 240 g Abtropfgewicht",119),ReceiptLine("ARTIKEL 03",null,"Menge unbekannt",129))
    const val merchant="Demo-Markt · Filiale Beispielstadt"
    const val receiptId="DEMO-BON-20260414-001"
    val prices=listOf("02.04.2026" to 229,"08.04.2026" to 239,"12.04.2026" to 249)
    val measurements=listOf(Measurement(1,81f,20.8f,16.85f,34.2f),Measurement(3,80.8f,20.6f,16.64f,34.3f),Measurement(5,80.9f,20.4f,16.50f,34.2f),Measurement(7,80.5f,20.5f,16.50f,34.3f),Measurement(9,80.6f,20.2f,16.28f,34.4f),Measurement(11,80.4f,20f,16.08f,34.4f),Measurement(12,80.3f,19.9f,15.98f,34.5f),Measurement(14,80.2f,19.8f,15.88f,34.5f))
    // Prepared example curves, not calculated regressions.
    val weightCurve=listOf(80.95f,80.82f,80.73f,80.61f,80.50f,80.39f,80.31f,80.22f)
    val fatCurve=listOf(16.80f,16.66f,16.56f,16.45f,16.30f,16.15f,16.01f,15.91f)
    val muscleCurve=listOf(34.22f,34.25f,34.27f,34.31f,34.37f,34.42f,34.47f,34.49f)
    val activities=listOf(Activity("Krafttraining",shortDate,60,420,"Mittel"),Activity("Radfahren","12.04.2026",45,280,"Mittel"),Activity("Spaziergang","11.04.2026",30,null,"Leicht"))
    val weeks=listOf(Week("Woche 1","13.–19. April","Linsencurry & Oats","6 Portionen","ca. 32 €","Geplant"),Week("Woche 2","20.–26. April","Tofu & Gemüse","4 Portionen","ca. 35 €","Anpassung nötig"),Week("Woche 3","27. April–03. Mai","Curry & Hülsenfrüchte","6 Portionen","ca. 31 €","Geplant"),Week("Woche 4","04.–10. Mai","Flexible Vorratswoche","Unbekannt","Unbekannt","Offen"))
    val days=listOf("Mo","Di","Mi","Do","Fr","Sa","So")
    val dates=(13..19).map{"$it.04.2026"}
}
