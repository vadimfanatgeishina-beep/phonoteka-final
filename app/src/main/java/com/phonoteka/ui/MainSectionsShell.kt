package com.phonoteka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Основные разделы объединённой Фонотеки.
 *
 * Разделы — это НЕ лабораторные приложения: это части одного музыкального
 * приложения. Лабораторные сценарии живут внутри этих разделов (в профиле,
 * в треке, в заметке, в журнале), а не как отдельные точки входа.
 */
enum class MainSection(
    val title: String,
    val icon: ImageVector
) {
    HOME("Главная", Icons.Filled.Home),
    CATALOG("Каталог", Icons.Filled.LibraryMusic),
    NOTES("Заметки", Icons.Filled.NoteAlt),
    PROFILE("Профиль", Icons.Filled.Person),
    AUDIT("Журнал", Icons.Filled.Security),
    SETTINGS("Настройки", Icons.Filled.Settings);

    companion object {
        val ORDER: List<MainSection> = listOf(CATALOG, NOTES, HOME, SETTINGS, AUDIT)
    }
}

/**
 * Общий корпус приложения: горизонтальные свайпы между основными разделами
 * и нижняя панель с заметной кнопкой «Домой».
 *
 * Свайпы реализованы через [HorizontalPager], поэтому они не перехватывают:
 *  - вертикальную прокрутку списков (LazyColumn) — направления перпендикулярны;
 *  - ввод текста и горизонтальные элементы — вложенная прокрутка приоритетнее.
 */
@Composable
fun MainSectionsShell(
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
    onHomeClicked: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (MainSection) -> Unit
) {
    val sections = MainSection.ORDER
    val pagerState = rememberPagerState(initialPage = selectedSection) { sections.size }

    // Внешнее переключение (кнопка «Домой», выбор вкладки) двигает пейджер.
    LaunchedEffect(selectedSection) {
        if (pagerState.currentPage != selectedSection) pagerState.scrollToPage(selectedSection)
    }
    // Свайп пользователем — обновляет внешнее состояние раздела.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { onSectionSelected(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxSize().testTag("mainSectionsPager"),
            beyondViewportPageCount = 1
        ) { page ->
            content(sections[page])
        }

        MainSectionsBar(
            current = sections.getOrNull(pagerState.currentPage) ?: MainSection.HOME,
            onHome = onHomeClicked,
            onSelect = { section ->
                val index = sections.indexOf(section)
                if (index >= 0 && index != pagerState.currentPage) onSectionSelected(index)
            }
        )
    }
}

/**
 * Нижняя панель разделов. «Домой» находится по центру:
 * она всегда возвращает именно на главную Фонотеку, из любого раздела.
 */
@Composable
private fun MainSectionsBar(
    current: MainSection,
    onHome: () -> Unit,
    onSelect: (MainSection) -> Unit
) {
    NavigationBar(
        modifier = Modifier.testTag("mainSectionsBar"),
        tonalElevation = 3.dp
    ) {
        MainSection.ORDER.forEach { section ->
            NavigationBarItem(
                selected = current == section,
                onClick = { if (section == MainSection.HOME) onHome() else onSelect(section) },
                icon = { Icon(section.icon, contentDescription = null) },
                label = { Text(if (section == MainSection.HOME) "Домой" else section.title,
                    style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("nav_${section.name.lowercase()}")
            )
        }
    }
}

/** Заголовок раздела с пояснением, чем этот раздел является в общей Фонотеке. */
@Composable
fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
