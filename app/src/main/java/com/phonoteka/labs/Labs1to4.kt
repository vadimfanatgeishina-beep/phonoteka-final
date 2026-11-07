package com.phonoteka.labs

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// ============================== ЛР1 ==============================

/** ЛР1: первый Compose-экран — приветствие, счётчик и кнопка (состояние переживает поворот). */
@Composable
fun Lab1FirstScreen(modifier: Modifier = Modifier) {
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Привет, Jetpack Compose!", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Фонотека · ЛР1", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { count++ }, modifier = Modifier.testTag("lab1Button")) {
            Text("Нажато: $count")
        }
    }
}

@Preview(showBackground = true, name = "ЛР1 FirstScreen")
@Composable
private fun Lab1FirstScreenPreview() { MaterialTheme { Lab1FirstScreen() } }

// ============================== ЛР2 ==============================

/** ЛР2: базовые composable (Text, Column, Row, Box) и модификаторы padding/fillMaxWidth/align/clickable. */
@Composable
fun Lab2BasicsScreen(modifier: Modifier = Modifier) {
    var clicks by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Фонотека", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Ваша музыкальная коллекция", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth().testTag("lab2Button")) {
            Text("Начать слушать")
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Row: слева")
            Text("Row: справа")
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                .clickable { clicks++ }
                .testTag("lab2Box")
        ) {
            Text("Box: верх-лево", Modifier.align(Alignment.TopStart).padding(8.dp))
            Text("Нажмите на Box", Modifier.align(Alignment.Center))
            Text("кликов: $clicks", Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

@Preview(showBackground = true, name = "ЛР2 Basics")
@Composable
private fun Lab2Preview() { MaterialTheme { Lab2BasicsScreen() } }

// ============================== ЛР3 ==============================

/** ЛР3: вкладки «Профиль», «Карточка товара», «Две колонки 70/30». */
@Composable
fun Lab3LayoutsScreen(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Профиль", "Товар", "70/30")
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> Lab3ProfileScreen(Modifier.weight(1f))
            1 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductCard("Виниловая пластинка «OK Computer»", "2 990 ₽")
                ProductCard("Наушники студийные", "7 490 ₽")
            }
            else -> TwoColumnLayout(Modifier.weight(1f))
        }
    }
}

/**
 * ЛР3, задание 1: аватар 64dp по центру, жирное имя, серый статус, два счётчика со Spacer(weight),
 * «Подписаться» (fillMaxWidth, 16dp) и «Сообщение» через 8dp, нижняя панель из 3 иконок (SpaceEvenly).
 * BoxWithConstraints: при ширине > 600dp аватар и имя располагаются в Row.
 */
@Composable
fun Lab3ProfileScreen(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > 600.dp
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                if (wide) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("lab3HeaderRow"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileAvatar()
                        Spacer(Modifier.width(16.dp))
                        ProfileName(Alignment.Start)
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().testTag("lab3HeaderColumn"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ProfileAvatar()
                        Spacer(Modifier.height(8.dp))
                        ProfileName(Alignment.CenterHorizontally)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                    ProfileCounter("Подписчики", "1 204")
                    Spacer(Modifier.weight(1f))
                    ProfileCounter("Подписки", "87")
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Подписаться")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Сообщение")
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("lab3BottomBar"),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = {}) { Icon(Icons.Filled.Home, contentDescription = "Главная") }
                IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = "Поиск") }
                IconButton(onClick = {}) { Icon(Icons.Filled.Person, contentDescription = "Профиль") }
            }
        }
    }
}

@Composable
private fun ProfileAvatar() {
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.MusicNote, contentDescription = "Аватар") }
}

@Composable
private fun ProfileName(alignment: Alignment.Horizontal) {
    Column(horizontalAlignment = alignment) {
        Text("Monty Rounds", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text("Сейчас слушает: Radiohead", color = Color.Gray)
    }
}

@Composable
private fun ProfileCounter(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

/** ЛР3, задание 2: адаптивная карточка товара — узко: Column, широко (>400dp): Row. */
@Composable
fun ProductCard(title: String, price: String, modifier: Modifier = Modifier, onBuy: () -> Unit = {}) {
    Card(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val image = @Composable {
                Box(
                    Modifier.size(if (maxWidth > 400.dp) 120.dp else 160.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.MusicNote, contentDescription = "Изображение товара") }
            }
            val info = @Composable {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(price, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onBuy) { Text("Купить") }
                }
            }
            if (maxWidth > 400.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) { image(); Spacer(Modifier.width(12.dp)); info() }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    image(); Spacer(Modifier.height(8.dp)); info()
                }
            }
        }
    }
}

/** ЛР3, задание 2: две колонки 70% / 30% через weight. */
@Composable
fun TwoColumnLayout(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier.weight(0.7f).fillMaxSize()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                .padding(12.dp).testTag("lab3Col70")
        ) {
            Text("Основной контент (70%)", fontWeight = FontWeight.Bold)
            Text("Список альбомов и треков")
        }
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier.weight(0.3f).fillMaxSize()
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                .padding(12.dp).testTag("lab3Col30")
        ) {
            Text("Панель (30%)", fontWeight = FontWeight.Bold)
            Text("Фильтры")
        }
    }
}

@Preview(showBackground = true, widthDp = 360, name = "ЛР3 профиль узкий")
@Composable
private fun Lab3NarrowPreview() { MaterialTheme { Lab3ProfileScreen() } }

@Preview(showBackground = true, widthDp = 700, name = "ЛР3 профиль широкий")
@Composable
private fun Lab3WidePreview() { MaterialTheme { Lab3ProfileScreen() } }

// ============================== ЛР4 ==============================

/**
 * ЛР4: состояние. Переключатель темы хранится на уровне экрана и применяется ко всему экрану.
 */
@Composable
fun Lab4StateScreen(modifier: Modifier = Modifier) {
    var darkTheme by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (darkTheme) "Тёмная тема" else "Светлая тема", Modifier.weight(1f))
                    Switch(checked = darkTheme, onCheckedChange = { darkTheme = it },
                        modifier = Modifier.testTag("lab4ThemeSwitch"))
                }
                CounterSection()
                SearchSection()
                Text("Раскрывающиеся карточки", style = MaterialTheme.typography.titleMedium)
                (1..5).forEach { i ->
                    ExpandableCard(title = "Альбом №$i", body = "Описание альбома №$i: треклист, год, жанр.")
                }
                FormSection()
                CheckboxSection()
            }
        }
    }
}

/** ЛР4, задание 1: счётчик +1 / −1 / Сброс. */
@Composable
fun CounterSection() {
    var count by rememberSaveable { mutableIntStateOf(0) }
    Column {
        Text("Счётчик: $count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("lab4Count"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { count++ }) { Text("+1") }
            Button(onClick = { count-- }) { Text("−1") }
            OutlinedButton(onClick = { count = 0 }) { Text("Сброс") }
        }
    }
}

/** ЛР4, задание 2: stateless SearchBar — состояние поднято в родителя. */
@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Поиск") },
        singleLine = true,
        modifier = modifier.fillMaxWidth().testTag("lab4Search")
    )
}

@Composable
fun SearchSection() {
    var query by rememberSaveable { mutableStateOf("") }
    Column {
        SearchBar(query = query, onQueryChange = { query = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Символов: ${query.length}", Modifier.weight(1f).testTag("lab4SearchCount"))
            TextButton(onClick = { query = "" }) { Text("Очистить") }
        }
    }
}

/** ЛР4, задание 3: карточка с собственным локальным состоянием expanded. */
@Composable
fun ExpandableCard(title: String, body: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }.testTag("lab4Card_$title")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть")
            }
            if (expanded) Text(body, Modifier.padding(top = 8.dp))
        }
    }
}

/** ЛР4, задание 4: форма — поле + «Отправить», затем отображение введённого значения. */
@Composable
fun FormSection() {
    var input by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Любимый исполнитель") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("lab4FormField"))
        Button(onClick = { submitted = input.trim(); input = "" }, enabled = input.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp)) { Text("Отправить") }
        submitted?.let { Text("Отправлено: $it", Modifier.testTag("lab4FormResult")) }
    }
}

/** ЛР4, задание 4: список чекбоксов со счётчиком выбранных. */
@Composable
fun CheckboxSection() {
    val genres = remember { listOf("Рок", "Джаз", "Поп", "Классика", "Электроника") }
    var checked by rememberSaveable { mutableStateOf(List(genres.size) { false }) }
    Column {
        Text("Выбрано: ${checked.count { it }}", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("lab4CheckedCount"))
        genres.forEachIndexed { i, g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked[i], onCheckedChange = { v ->
                    checked = ArrayList(checked).also { it[i] = v }
                }, modifier = Modifier.testTag("lab4Check_$i"))
                Text(g, textAlign = TextAlign.Start)
            }
        }
    }
}

@Preview(showBackground = true, name = "ЛР4 State")
@Composable
private fun Lab4Preview() { Lab4StateScreen() }
