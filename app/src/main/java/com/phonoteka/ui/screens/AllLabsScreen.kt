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

    const val TITLE = "Объединённая Фонотека — все 16 лабораторных"
    const val SUMMARY = "Одно приложение «Фонотека». Лабораторные сценарии открываются В КОНТЕКСТЕ разделов " +
        "(Главная, Каталог, Заметки, Профиль, Журнал), а не как отдельные приложения."

    val labs = listOf(
        Lab(1, "Первый Compose-экран", "Заставка, приветствие, счётчик, @Preview", "Главная (старт приложения); Инструменты интерфейса → ЛР1; labs/Labs1to4.kt"),
        Lab(2, "Базовая вёрстка", "Text/Column/Row/Box и модификаторы", "Инструменты интерфейса → ЛР2; labs/Labs1to4.kt"),
        Lab(3, "Лейауты", "Профиль, карточка товара, 70/30, адаптивный главный экран", "Инструменты интерфейса → ЛР3; Профиль → Редактор профиля"),
        Lab(4, "Состояние", "Счётчик, поиск, карточки, форма, remember/rememberSaveable/ViewModel", "Инструменты интерфейса → ЛР4"),
        Lab(5, "Списки", "LazyColumn, пагинация, фильтр, debounce", "Инструменты интерфейса → ЛР5; Каталог → поиск"),
        Lab(6, "Навигация", "4 экрана, аргументы, deep link", "Инструменты интерфейса → ЛР6; labs/Lab6Navigation.kt, ui/Navigation.kt"),
        Lab(7, "Загрузка, ошибки, кэш", "Loading/Success/Error, backoff, кэш", "Инструменты интерфейса → ЛР7; labs/Lab7Loading.kt"),
        Lab(8, "Material 3, компоненты, доступность", "Дизайн-система, AppButton/LoadingButton, форма профиля, тема", "Профиль → Редактор профиля; Настройки → Тема; DESIGN_SYSTEM.md"),
        Lab(9, "Анимации, эффекты, UI-тесты, производительность", "Трекер задач (60 задач), анимации, автосохранение, замеры", "Главная → Трекер задач (ЛР9); LAB9_PERFORMANCE.md"),
        Lab(10, "Сеть", "Каталог постов (jsonplaceholder), детали, повтор запроса, кэш", "Каталог → Посты (общий тип контента ленты); ui/screens/Lab10CatalogScreen.kt"),
        Lab(11, "Файлы", "Заметки, вложения-картинки, автосохранение, JSON-экспорт, зашифрованный архив", "Заметки → Заметки треков / Свободные заметки / Архив; data/notes/NoteArchive.kt"),
        Lab(12, "Модель угроз", "Только документы: активы, STRIDE, планы атак, меры", "THREAT_MODEL_LAB12.md, ATTACK_PLANS_LAB12.md, LAB12_MODEL_V2/V3.md (в приложении экрана нет)"),
        Lab(13, "Аутентификация", "Локальный mock-вход, PIN, биометрия, восстановление, step-up и TOTP Authenticator (6 цифр / 60 секунд)", "Профиль → Вход и PIN; Журнал → Результаты ЛР13; реального аккаунт-сервера в этой сборке нет"),
        Lab(14, "Контроль доступа", "Mock-роли и scopes, QR-приглашение к треку, системные разрешения и аудит; повтор QR разрешается только в уязвимом варианте", "Профиль → Доступ и QR (ЛР14); камера открывается по запросу; PERMISSIONS_LAB14.md"),
        Lab(15, "Безопасная передача", "Локальный TLS/WSS-стенд, SPKI-проверки, HMAC-подписанные кадры, защита от повторов и смена пользователя", "Журнал → Живой WSS (ЛР15); это собственный стенд, не перехват внешнего Wi-Fi-трафика; LAB15_PROTOCOL.md"),
        Lab(16, "Аудит и hardening", "R8/shrink, политики backup/cleartext, безопасный журнал, диагностические RASP-сигналы и статические правила Semgrep", "Журнал → Режим сборки и отличия; документы и скрипты — AUDIT_REPORT.md, tools/semgrep-security.yml; RASP-сигналы диагностические, не блокировка")
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
            com.phonoteka.ui.components.AppBackTextButton(onBack)
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
