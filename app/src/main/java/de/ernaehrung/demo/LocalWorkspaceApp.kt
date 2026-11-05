package de.ernaehrung.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import de.ernaehrung.demo.ui.*
import de.ernaehrung.demo.ui.screens.LocalDataContent
import de.ernaehrung.demo.ui.screens.rememberLocalDataViewModel

private enum class WorkspacePage(val title: String, val section: String? = null) {
    HOME("Start"), STOCK("Lager", "Lager"), SCAN("Scan", "Erkennung"),
    PLAN("Planung", "Planung"), MORE("Mehr"), CATALOG("Katalog", "Katalog"),
    KITCHEN("Rezepte & Küche", "Küche"), SHOPPING("Einkaufsvorschläge", "Einkauf"),
    PURCHASES("Bezahlte Käufe", "Käufe"), NUTRITION("Ernährung", "Ernährung"), HISTORY("Chronik", "Chronik");
    val tab get() = when (this) {
        HOME, NUTRITION -> HOME
        STOCK, CATALOG -> STOCK
        SCAN -> SCAN
        PLAN -> PLAN
        else -> MORE
    }
}

/** The default application uses one real Room-backed model across all sections.
 * The immutable design tour remains an explicit opt-in, outside this data flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalWorkspaceApp() {
    val model = rememberLocalDataViewModel()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = WorkspacePage.entries.firstOrNull { it.name == entry?.destination?.route } ?: WorkspacePage.HOME
    var demo by rememberSaveable { mutableStateOf(false) }
    if (demo) {
        BackHandler { demo = false }
        DemoApp()
        return
    }
    fun go(page: WorkspacePage) { nav.navigate(page.name) { launchSingleTop = true } }
    val tabs = listOf(WorkspacePage.HOME, WorkspacePage.STOCK, WorkspacePage.SCAN, WorkspacePage.PLAN, WorkspacePage.MORE)
    val icons: List<ImageVector> = listOf(Icons.Outlined.Home, Icons.Outlined.Inventory2,
        Icons.Outlined.QrCodeScanner, Icons.Outlined.CalendarMonth, Icons.Outlined.MoreHoriz)
    Scaffold(containerColor = Warm, modifier = Modifier.imePadding(), topBar = {
        Column {
            CenterAlignedTopAppBar(title = { Text("Ernährungsgedächtnis") }, navigationIcon = {
                if (current != WorkspacePage.HOME) IconButton({ if (!nav.popBackStack()) go(WorkspacePage.HOME) },
                    Modifier.testTag("workspace_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
            }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Warm))
            Text("Entwicklungsversion · lokale Testdaten", Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = Forest)
        }
    }, bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { index, page ->
                NavigationBarItem(selected = current.tab == page, onClick = {
                    nav.navigate(page.name) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true; restoreState = true
                    }
                }, icon = { Icon(icons[index], null) }, label = { Text(page.title) },
                    modifier = Modifier.testTag("workspace_tab_${page.name}"))
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = WorkspacePage.HOME.name, modifier = Modifier.padding(padding)) {
            WorkspacePage.entries.forEach { page -> composable(page.name) {
                Column(Modifier.fillMaxSize().testTag("workspace_page_${page.name}")
                    .verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (page.section != null) {
                        LocalDataContent(model, page.section, showSectionNavigation = false,
                            onOpenPurchases = { go(WorkspacePage.PURCHASES) })
                    } else if (page == WorkspacePage.MORE) {
                        Heading("Deine Arbeitsbereiche", "Alle Einträge bleiben lokal auf diesem Gerät.")
                        listOf(WorkspacePage.CATALOG, WorkspacePage.KITCHEN, WorkspacePage.SHOPPING,
                            WorkspacePage.PURCHASES, WorkspacePage.NUTRITION, WorkspacePage.HISTORY).forEach { target ->
                            Action(target.title, true, "workspace_go_${target.name}") { go(target) }
                        }
                        Action("Demo-Rundgang öffnen", true, "workspace_demo") { demo = true }
                        Text("Der Rundgang zeigt feste Beispiele. Eigene lokale Einträge werden dort nicht dargestellt.")
                    } else {
                        val state by model.state.collectAsStateWithLifecycle()
                        val daily by model.daily.collectAsStateWithLifecycle()
                        Heading("Dein Alltag. Im Blick.", "Deine tatsächlich erfassten Daten")
                        state.error?.let { Hint(it) }
                        daily.error?.let { Hint(it) }
                        if (state.loading) CircularProgressIndicator() else Panel {
                            Text("Bekannte Produkte: ${state.catalog.size}", Modifier.testTag("workspace_catalog_count"))
                            KV("Chargen mit Bestand", state.batches.count { it.stockAmount > 0 }.toString())
                            KV("Verzehre am ${daily.date}", daily.report?.meals?.size?.toString()
                                ?: if (daily.error != null) "Unbekannt" else "Wird geladen")
                        }
                        listOf(WorkspacePage.NUTRITION, WorkspacePage.STOCK, WorkspacePage.SCAN,
                            WorkspacePage.KITCHEN, WorkspacePage.PLAN, WorkspacePage.SHOPPING,
                            WorkspacePage.PURCHASES, WorkspacePage.CATALOG).forEach { target ->
                            Action(target.title, true, "workspace_go_${target.name}") { go(target) }
                        }
                        Hint("Nur Testdaten eingeben. Sie bleiben nach Neustart erhalten. Vorschläge und Pläne sind keine Einkäufe oder Verzehre.", Sage)
                    }
                }
            } }
        }
    }
}
