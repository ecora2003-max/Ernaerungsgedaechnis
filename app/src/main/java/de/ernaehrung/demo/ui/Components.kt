package de.ernaehrung.demo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.ernaehrung.demo.data.*
import de.ernaehrung.demo.navigation.*

@Composable fun Heading(title:String,subtitle:String?=null,eyebrow:String?=null) {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
    eyebrow?.let{Text(it.uppercase(),style=MaterialTheme.typography.labelSmall,color=Forest,letterSpacing=1.sp)}
    Text(title,style=MaterialTheme.typography.headlineLarge)
    subtitle?.let{Text(it,style=MaterialTheme.typography.bodyMedium,color=Muted)}
}}
@Composable fun Panel(modifier:Modifier=Modifier,color:Color=Color.White,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),color=color,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Line.copy(alpha=.7f))) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable fun Section(title:String,subtitle:String?=null) {Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(title,style=MaterialTheme.typography.titleLarge);subtitle?.let{Text(it,style=MaterialTheme.typography.bodyMedium,color=Muted)}
}}
@Composable fun Badge(text:String,color:Color=Sage) {Surface(color=color,shape=RoundedCornerShape(7.dp)){
    Text(text,Modifier.padding(horizontal=9.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=Ink)
}}
@Composable fun Hint(text:String,color:Color=Amber) {Surface(color=color,shape=RoundedCornerShape(12.dp)){
    Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Icon(Icons.Outlined.Info,null,Modifier.size(18.dp),tint=Muted);Text(text,style=MaterialTheme.typography.bodyMedium)
    }
}}
@Composable fun Action(label:String,secondary:Boolean=false,tag:String=label,onClick:()->Unit) {
    val m=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag(tag)
    if(secondary) OutlinedButton(onClick,m,shape=RoundedCornerShape(12.dp)){Text(label,Modifier.padding(vertical=3.dp))}
    else Button(onClick,m,shape=RoundedCornerShape(12.dp)){Text(label,Modifier.padding(vertical=3.dp))}
}
@Composable fun Go(label:String,target:Screen,ui:UiActions,secondary:Boolean=false)=Action(label,secondary,"go_${target.name}"){ui.go(target)}
@Composable fun Link(title:String,detail:String,icon:ImageVector,color:Color=Sage,tag:String=title,onClick:()->Unit) {
    Surface(onClick,Modifier.fillMaxWidth().testTag(tag),color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Line)){
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(color=color,shape=RoundedCornerShape(12.dp)){Icon(icon,null,Modifier.padding(12.dp).size(22.dp),tint=Forest)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodyMedium,color=Muted)
            }
            Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint=Muted)
        }
    }
}
@Composable fun Route(title:String,detail:String,icon:ImageVector,target:Screen,ui:UiActions,color:Color=Sage)=Link(title,detail,icon,color,"go_${target.name}"){ui.go(target)}
@Composable fun Tile(title:String,detail:String,icon:ImageVector,target:Screen,ui:UiActions,modifier:Modifier=Modifier){
    Surface(onClick={ui.go(target)},modifier=modifier.testTag("go_${target.name}"),color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Line)){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Icon(icon,null,Modifier.size(26.dp),tint=Forest);Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodyMedium,color=Muted)
        }
    }
}
@Composable fun Metric(value:String,label:String,modifier:Modifier=Modifier,foot:String?=null){Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
    Text(value,style=MaterialTheme.typography.headlineMedium,color=Forest);Text(label,style=MaterialTheme.typography.bodyMedium)
    foot?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=Muted)}
}}
@Composable fun KV(label:String,value:String,bold:Boolean=false){
    val stack=LocalConfiguration.current.screenWidthDp<340 && LocalDensity.current.fontScale>1.1f
    if(stack)Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
        Text(label,style=MaterialTheme.typography.bodyMedium,color=Muted);Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=if(bold)FontWeight.SemiBold else FontWeight.Normal)
    } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=Muted)
        Text(value,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,fontWeight=if(bold)FontWeight.SemiBold else FontWeight.Normal)
    }
}
@Composable fun Choices(options:List<String>,selected:String,onSelect:(String)->Unit){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        options.forEach{FilterChip(selected==it,{onSelect(it)},label={Text(it)},modifier=Modifier.heightIn(min=48.dp),colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Forest,selectedLabelColor=Color.White))}
    }
}
@Composable fun Fold(title:String,content:@Composable ColumnScope.()->Unit){var open by remember{mutableStateOf(false)}
    Panel{
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{open=!open},verticalAlignment=Alignment.CenterVertically){
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Icon(if(open)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(open)"Zuklappen" else "Aufklappen")
        };if(open)content()
    }
}
@Composable fun Field(label:String,text:String,onChange:(String)->Unit,placeholder:String=""){
    OutlinedTextField(text,onChange,Modifier.fillMaxWidth(),label={Text(label)},placeholder={Text(placeholder)},singleLine=true,shape=RoundedCornerShape(12.dp))
}
@Composable fun Picker(label:String,options:List<String>,selected:String,onSelect:(String)->Unit){var open by remember{mutableStateOf(false)}
    Box{
        OutlinedButton({open=true},Modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(12.dp)){
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(label,style=MaterialTheme.typography.labelSmall,color=Muted);Text(selected,style=MaterialTheme.typography.bodyLarge)};Icon(Icons.Outlined.ExpandMore,null)
        }
        DropdownMenu(open,{open=false}){options.forEach{DropdownMenuItem(text={Text(it)},onClick={onSelect(it);open=false})}}
    }
}
/** Bounded form dialog: fixed viewport and a scrolling body avoid intrinsic-height loops. */
@Composable fun FormDialog(title:String,fields:List<Pair<String,String>>,onClose:()->Unit,onSave:()->Unit){
    val height=(LocalConfiguration.current.screenHeightDp*.80f).dp
    Dialog(onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.padding(18.dp).widthIn(max=540.dp).fillMaxWidth().height(height).testTag("form_dialog"),shape=RoundedCornerShape(22.dp),color=Warm){
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(title,style=MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("Nur fiktive Eingaben. Leere Werte bleiben unbekannt; nichts wird gespeichert.",style=MaterialTheme.typography.bodyMedium)
                    fields.forEach{(label,initial)->var text by remember(label){mutableStateOf(initial)};Field(label,text,{text=it},"Unbekannt")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClose){Text("Abbrechen")};TextButton(onSave){Text("Speichern · Demo")}}
            }
        }
    }
}
@Composable fun FoodArt(kind:String,modifier:Modifier=Modifier){Canvas(modifier.size(60.dp)){
    val w=size.width;val h=size.height
    drawRoundRect(if(kind=="tomato")Color(0xFFF8E8DF) else Sage,cornerRadius=CornerRadius(w*.23f))
    when(kind){
        "curry","veg"->{drawOval(Color(0xFFFFFCF3),Offset(w*.13f,h*.25f),Size(w*.74f,h*.50f));drawOval(Color(0xFFD9AA55),Offset(w*.20f,h*.31f),Size(w*.60f,h*.35f))
            listOf(.32f to .43f,.58f to .40f,.47f to .54f,.70f to .51f,.33f to .57f).forEachIndexed{i,p->drawCircle(if(i%2==0)Color(0xFF557C42) else Color(0xFFE69A4B),w*.08f,Offset(w*p.first,h*p.second))}}
        "oats","lentil"->{drawRoundRect(Color(0xFFDDC9A7),Offset(w*.27f,h*.21f),Size(w*.46f,h*.60f),CornerRadius(w*.08f));drawRoundRect(Forest,Offset(w*.26f,h*.17f),Size(w*.48f,h*.12f),CornerRadius(w*.04f));drawRect(Color(0xFFFFFCF2),Offset(w*.30f,h*.40f),Size(w*.40f,h*.22f));repeat(5){i->drawOval(Color(0xFFC19455),Offset(w*(.36f+i%3*.09f),h*(.44f+i/3*.07f)),Size(w*.07f,h*.04f))}}
        "can","tomato","yogurt"->{val c=if(kind=="tomato")Color(0xFFBD5D45) else Color(0xFF789367);drawRoundRect(c,Offset(w*.25f,h*.20f),Size(w*.50f,h*.61f),CornerRadius(w*.06f));drawOval(Color(0xFFC9D0C5),Offset(w*.25f,h*.16f),Size(w*.50f,h*.14f));drawRect(Color(0xFFFDFBF1),Offset(w*.26f,h*.40f),Size(w*.48f,h*.22f));drawCircle(c,w*.08f,Offset(w*.5f,h*.51f))}
        else->{drawRoundRect(Color(0xFFE8DDC1),Offset(w*.17f,h*.25f),Size(w*.66f,h*.51f),CornerRadius(w*.06f));drawRect(Color(0xFFFFFCF4),Offset(w*.25f,h*.28f),Size(w*.50f,h*.42f));drawOval(Forest,Offset(w*.42f,h*.34f),Size(w*.16f,h*.12f));drawLine(Forest,Offset(w*.34f,h*.53f),Offset(w*.66f,h*.53f),w*.04f);drawLine(Forest.copy(alpha=.4f),Offset(w*.40f,h*.61f),Offset(w*.60f,h*.61f),w*.025f)}
    }
}}
@Composable fun ProductRow(p:Product,detail:String,status:String,onClick:()->Unit){
    Surface(onClick,Modifier.fillMaxWidth().testTag("product_${p.id}"),color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Line)){
        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
            FoodArt(p.kind);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(p.name,style=MaterialTheme.typography.titleMedium);Text(detail,style=MaterialTheme.typography.bodyMedium,color=Muted);Text(status,style=MaterialTheme.typography.labelSmall,color=Forest)
            };Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp),tint=Muted)
        }
    }
}
@Composable fun MealCard(m:Meal){Panel(color=if(m.planned)Blue.copy(alpha=.5f) else Color.White){
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){FoodArt(m.kind);Column(Modifier.weight(1f)){
        Text(m.name,style=MaterialTheme.typography.titleMedium);Text("${m.time} · ${m.kcal} kcal · ${m.protein} g Protein",style=MaterialTheme.typography.bodyMedium,color=Muted)
    }};Badge(if(m.planned)"Geplant" else "Tatsächlich erfasst · Demo",if(m.planned)Blue else Sage)
}}
@Composable fun SummaryCard(){val d=DemoRepository;Panel(color=Sage){
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Metric("${d.eaten.sumOf{it.kcal}}","kcal",Modifier.weight(1f),"von ${d.energyGoal}")
        Metric("${d.eaten.sumOf{it.protein}} g","Protein",Modifier.weight(1f),"von ${d.proteinGoal} g")
        Metric("${d.eaten.size} / ${d.eaten.size+d.planned.size}","Erfasst",Modifier.weight(1f),"Mahlzeiten")
    }
    LinearProgressIndicator(progress={d.eaten.sumOf{it.kcal}.toFloat()/d.energyGoal},modifier=Modifier.fillMaxWidth().height(5.dp),color=Forest,trackColor=Color.White)
    Text("Bisher erfasst · Demo-Zielwerte",style=MaterialTheme.typography.labelSmall,color=Muted)
}}
@Composable fun Nutrients(n:Nutrition){KV("Energie",value(n.kcal,"kcal"),true);KV("Protein",value(n.protein));KV("Fett",value(n.fat));KV("Kohlenhydrate",value(n.carbs))}
@Composable fun Chart(values:List<Float>,smooth:List<Float>,days:List<Int>){
    val min=(values+smooth).minOrNull()!!-.15f;val max=(values+smooth).maxOrNull()!!+.15f
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("${de(max.toDouble())} kg · Demo-Messreihe",style=MaterialTheme.typography.labelSmall,color=Muted)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics{contentDescription="${values.size} fiktive Rohmesspunkte; gestrichelte vorbereitete Beispielkurve"}){
            val pad=8.dp.toPx()
            fun pt(i:Int,v:Float)=Offset(pad+(days[i]-days.first()).toFloat()/(days.last()-days.first())*(size.width-2*pad),size.height-pad-(v-min)/(max-min)*(size.height-2*pad))
            repeat(4){i->val y=pad+i*(size.height-2*pad)/3;drawLine(Line,Offset(0f,y),Offset(size.width,y),1.dp.toPx())}
            val path=Path();smooth.forEachIndexed{i,v->val p=pt(i,v);if(i==0)path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y)}
            drawPath(path,Color(0xFF879A69),style=Stroke(2.5.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(10f,7f))))
            values.forEachIndexed{i,v->drawCircle(Color.White,5.dp.toPx(),pt(i,v));drawCircle(Forest,3.dp.toPx(),pt(i,v))}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${days.first()}.04.",style=MaterialTheme.typography.labelSmall);Text("${days.last()}.04.",style=MaterialTheme.typography.labelSmall)}
        Text("● Rohmesspunkte     ┄ vorbereitete Beispielkurve",style=MaterialTheme.typography.labelSmall,color=Muted)
    }
}
