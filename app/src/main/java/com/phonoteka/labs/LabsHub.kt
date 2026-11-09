package com.phonoteka.labs

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ЛР6: приём deep link app://phonoteka/details/{itemId}. MainActivity передаёт сюда intent.data,
 * главный граф открывает раздел «ЛР1–7», а [Lab6App] строит стек Главная → Список → Детали.
 * Интент-фильтр объявлен только в debug-манифесте, чтобы не расширять внешнюю поверхность release (ЛР14).
 */
object LabDeepLinks {
    private val _pendingDetailsId = MutableStateFlow<String?>(null)
    val pendingDetailsId: StateFlow<String?> = _pendingDetailsId.asStateFlow()

    /** Возвращает true, если uri — наш deep link. */
    fun offer(uri: Uri?): Boolean {
        val id = parseDetailsId(uri?.scheme, uri?.host, uri?.pathSegments) ?: return false
        _pendingDetailsId.value = id
        return true
    }

    fun consume() { _pendingDetailsId.value = null }

    /** Чистая функция для unit-тестов: app://phonoteka/details/{id} → id. */
    fun parseDetailsId(scheme: String?, host: String?, segments: List<String>?): String? {
        if (scheme != "app" || host != "phonoteka") return null
        val s = segments ?: return null
        return if (s.size == 2 && s[0] == "details" && s[1].isNotBlank()) s[1] else null
    }
}

private val labs = listOf(
    "lab1" to "ЛР1 · Первый экран и счётчик",
    "lab2" to "ЛР2 · Text/Column/Row/Box и модификаторы",
    "lab3" to "ЛР3 · Профиль, карточка товара, 70/30",
    "lab4" to "ЛР4 · Состояние (счётчик, поиск, карточки, форма)",
    "lab5" to "ЛР5 · LazyColumn, пагинация, фильтр, debounce",
    "lab6" to "ЛР6 · Навигация из 4 экранов + deep link",
    "lab7" to "ЛР7 · Loading/Success/Error, backoff, кэш"
)

/** Раздел «ЛР1–7»: собственный NavHost, каждая лаба — отдельный экран. */
@Composable
fun LabsHubScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val pending by LabDeepLinks.pendingDetailsId.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending != null && nav.currentDestination?.route != "lab6") nav.navigate("lab6") { launchSingleTop = true }
    }
    NavHost(navController = nav, startDestination = "hub", modifier = modifier.fillMaxSize()) {
        composable("hub") {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.phonoteka.ui.components.AppBackTextButton(onExit)
                    Text("Лабораторные 1–7", style = MaterialTheme.typography.titleLarge)
                }
                labs.forEach { (route, title) ->
                    Button(onClick = { nav.navigate(route) }, modifier = Modifier.fillMaxWidth().testTag("hub_$route")) { Text(title) }
                }
            }
        }
        composable("lab1") { LabFrame("ЛР1", nav::popBackStack) { Lab1FirstScreen() } }
        composable("lab2") { LabFrame("ЛР2", nav::popBackStack) { Lab2BasicsScreen() } }
        composable("lab3") { LabFrame("ЛР3", nav::popBackStack) { Lab3LayoutsScreen() } }
        composable("lab4") { LabFrame("ЛР4", nav::popBackStack) { Lab4StateScreen() } }
        composable("lab5") { LabFrame("ЛР5", nav::popBackStack) { Lab5ListScreen() } }
        composable("lab6") {
            Lab6App(
                deepLinkItemId = pending,
                onDeepLinkConsumed = LabDeepLinks::consume,
                onExit = { nav.popBackStack() }
            )
        }
        composable("lab7") {
            val ctx = LocalContext.current
            val vm: Lab7ListViewModel = viewModel(factory = Lab7ListViewModel.factory(ctx))
            LabFrame("ЛР7", nav::popBackStack) { Lab7ListScreen(vm) }
        }
    }
}

@Composable
private fun LabFrame(title: String, onBack: () -> Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.phonoteka.ui.components.AppBackTextButton({ onBack() }, modifier = Modifier.testTag("labFrameBack"))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
