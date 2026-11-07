package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Финальная версия: сводка «какая лаба где» по всем 16 лабам. Открывается из ⋮ → «О версии» на главном экране. */
object AllLabsInfo {
    data class Lab(val number: Int, val title: String, val whatDone: String, val where: String)

    const val TITLE = "Финальная версия — все 16 лабораторных"
    const val SUMMARY = "Одно приложение «Фонотека», в котором собраны требования всех лаб 1–16. Отдельные версии v1…v16 показывают каждую лабу по отдельности."

    val labs = listOf(
        Lab(1, "Первый Compose-экран", "Заставка, приветствие, счётчик, @Preview", "Настройки → Лабораторные 1–7 → ЛР1; labs/Labs1to4.kt"),
        Lab(2, "Базовая вёрстка", "Text/Column/Row/Box и модификаторы", "Лабораторные 1–7 → ЛР2; labs/Labs1to4.kt"),
        Lab(3, "Лейауты", "Профиль, карточка товара, 70/30, адаптивный главный экран", "Лабораторные 1–7 → ЛР3; HomeScreen (BoxWithConstraints)"),
        Lab(4, "Состояние", "Счётчик, поиск, карточки, форма, remember/rememberSaveable/ViewModel", "Лабораторные 1–7 → ЛР4"),
        Lab(5, "Списки", "LazyColumn, пагинация, фильтр, debounce", "Лабораторные 1–7 → ЛР5; список треков"),
        Lab(6, "Навигация", "4 экрана, аргументы, deep link", "Лабораторные 1–7 → ЛР6; labs/Lab6Navigation.kt, ui/Navigation.kt"),
        Lab(7, "Загрузка, ошибки, кэш", "Loading/Success/Error, backoff, кэш", "Лабораторные 1–7 → ЛР7; labs/Lab7Loading.kt"),
        Lab(8, "Material 3, компоненты, доступность", "Дизайн-система, AppButton/LoadingButton, форма профиля, тема", "Настройки → Мой профиль · ЛР8; Тема; DESIGN_SYSTEM.md"),
        Lab(9, "Анимации, эффекты, UI-тесты, производительность", "Трекер задач (60 задач), анимации, автосохранение, замеры", "Настройки → Трекер задач · ЛР9; LAB9_PERFORMANCE.md"),
        Lab(10, "Сеть", "Каталог постов (jsonplaceholder), детали, повтор запроса, кэш", "Настройки → Сетевой каталог · ЛР10"),
        Lab(11, "Файлы", "Заметки, автосохранение, импорт/экспорт JSON, резервная копия", "Настройки → Заметки · ЛР11 и Резервная копия · ЛР11"),
        Lab(12, "Модель угроз", "Только документы: активы, STRIDE, планы атак, меры", "THREAT_MODEL_LAB12.md, ATTACK_PLANS_LAB12.md, LAB12_MODEL_V2/V3.md (в приложении экрана нет)"),
        Lab(13, "Аутентификация", "Вход, блокировка после ошибок, токены, повторное подтверждение для результатов", "Настройки → Вход и токены · ЛР13; Заказы и результаты · ЛР13"),
        Lab(14, "Контроль доступа", "Роли, scopes, приглашения/QR, разрешения, журнал аудита", "Настройки → Доступ и аудит · ЛР14; PERMISSIONS_LAB14.md"),
        Lab(15, "Безопасная передача", "HTTPS, пиннинг, подпись HMAC, защита от повторов, WSS", "Настройки → Локальный WSS · ЛР15; LAB15_PROTOCOL.md"),
        Lab(16, "Аудит и hardening", "R8/shrink, бэкап выключен, буфер с автоочисткой, RASP, Semgrep, отчёт аудита", "Заметки → «Скопировать» (буфер); Доступ и аудит (RASP); AUDIT_REPORT.md, tools/semgrep-security.yml"),
    )
}

/** Кнопка ⋮ в правом верхнем углу главного экрана. */
@Composable
fun BoxScope.AllLabsMenu(onAbout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp)) {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Меню") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("О версии") }, onClick = { expanded = false; onAbout() })
        }
    }
}

/** Экран «О версии»: какая лаба что сделано и где её показать. */
@Composable
fun AllLabsScreen(onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(AllLabsInfo.TITLE, style = MaterialTheme.typography.titleLarge)
            Text(AllLabsInfo.SUMMARY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(AllLabsInfo.labs, key = { it.number }) { lab ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("ЛР${lab.number} — ${lab.title}", style = MaterialTheme.typography.titleSmall)
                    Text("Что сделано: " + lab.whatDone, style = MaterialTheme.typography.bodyMedium)
                    Text("Где: " + lab.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
