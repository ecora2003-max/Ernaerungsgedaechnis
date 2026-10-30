package de.ernaehrung.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import de.ernaehrung.demo.navigation.*
import de.ernaehrung.demo.ui.*
import de.ernaehrung.demo.ui.screens.*

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
    setContent{DemoTheme{DemoApp()}}
}}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DemoApp(start:Screen=Screen.S01){
    val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val current=Screen.from(entry?.destination?.route)
    var product by rememberSaveable{mutableStateOf("DEMO-TOFU-001")};var recipe by rememberSaveable{mutableStateOf("DEMO-R01")};var day by rememberSaveable{mutableIntStateOf(1)}
    var notice by remember{mutableStateOf<Notice?>(null)}
    val ui=UiActions({nav.navigate(it.name){launchSingleTop=true}},{notice=it},{product=it},{recipe=it},{day=it})
    val compact=LocalConfiguration.current.screenWidthDp<340
    Scaffold(modifier=Modifier.imePadding(),containerColor=Warm,topBar={Column{
        CenterAlignedTopAppBar(title={Text("Ernährungsgedächtnis",fontSize=16.sp,fontWeight=FontWeight.SemiBold)},
            navigationIcon={if(current!=Screen.S01)IconButton({if(!nav.popBackStack())ui.go(Screen.S01)},Modifier.testTag("back")){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Zurück")}
                else Icon(Icons.Outlined.Spa,null,Modifier.padding(start=18.dp).size(24.dp),tint=Forest)},
            actions={Text(current.name,Modifier.padding(end=16.dp),style=MaterialTheme.typography.labelSmall,color=Muted)},colors=TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor=Warm))
        Row(Modifier.fillMaxWidth().background(Sage).padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
            Icon(Icons.Outlined.Science,null,Modifier.size(14.dp),tint=Forest);Text("UI-Demo · fiktive Daten · ohne Speicherung",fontSize=11.sp,color=Forest)
        }
    }},bottomBar={Surface(color=Color.White){Row(Modifier.fillMaxWidth().navigationBarsPadding(),verticalAlignment=Alignment.CenterVertically){
        MainTab.entries.forEach{tab->val selected=current.tab==tab
            Column(Modifier.weight(1f).heightIn(min=76.dp).selectable(selected,onClick={nav.navigate(tab.root){popUpTo(nav.graph.findStartDestination().id){saveState=true};launchSingleTop=true}},role=Role.Tab).testTag("tab_${tab.name}").padding(vertical=10.dp,horizontal=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
                Surface(color=if(selected)Sage else Color.Transparent,shape=RoundedCornerShape(20.dp)){Icon(when(tab){MainTab.Start->Icons.Outlined.Home;MainTab.Stock->Icons.Outlined.Inventory2;MainTab.Scan->Icons.Outlined.QrCodeScanner;MainTab.Plan->Icons.Outlined.CalendarMonth;MainTab.More->Icons.Outlined.MoreHoriz},null,Modifier.padding(horizontal=14.dp,vertical=4.dp).size(23.dp),tint=if(selected)Forest else Muted)}
                Text(tab.label,fontSize=if(compact)10.sp else 11.sp,lineHeight=14.sp,letterSpacing=0.sp,maxLines=1,color=if(selected)Forest else Muted)
            }
        }
    }}}){padding->
        NavHost(nav,startDestination=start.name,modifier=Modifier.padding(padding)){
            Screen.entries.forEach{screen->composable(screen.name){
                Column(Modifier.fillMaxSize().testTag("screen_${screen.name}").verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    val selection=Selection(product,recipe,day)
                    when(screen){
                        Screen.S01,Screen.S02,Screen.S03,Screen.T01,Screen.T02,Screen.T03->StartDiary(screen,selection,ui)
                        Screen.P01,Screen.P02,Screen.P03,Screen.L01,Screen.L02,Screen.L03,Screen.E01,Screen.E02,Screen.E03->ProductsReceipts(screen,selection,ui)
                        Screen.R01,Screen.R02,Screen.R03,Screen.G01,Screen.G02,Screen.G03,Screen.H01,Screen.H02,Screen.H03->Planning(screen,selection,ui)
                        Screen.K01,Screen.K02,Screen.K03->BodyTraining(screen,ui)
                        Screen.I01,Screen.I02,Screen.I03,Screen.M01,Screen.M02,Screen.M03,Screen.MORE->SystemScreens(screen,ui)
                    };Spacer(Modifier.height(8.dp))
                }
            }}
        }
    }
    notice?.let{n->AlertDialog(onDismissRequest={notice=null},modifier=Modifier.testTag("demo_dialog"),icon={Icon(Icons.Outlined.Science,null,tint=Forest)},title={Text(n.title)},text={Text(n.text)},
        confirmButton={TextButton(onClick={notice=null;n.destination?.let(ui.go)},modifier=Modifier.testTag("dialog_confirm")){Text(if(n.destination==null)"Verstanden" else n.button)}},
        dismissButton={if(n.destination!=null)TextButton({notice=null}){Text("Schließen")}},containerColor=Warm,shape=RoundedCornerShape(22.dp))}
}
