package com.phonoteka.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.phonoteka.diagnostics.RecordRecomposition
import com.phonoteka.tasks.AUTOSAVE_DELAY_MS
import com.phonoteka.tasks.ComputationCounter
import com.phonoteka.tasks.FilterAnalytics
import com.phonoteka.tasks.FilterMode
import com.phonoteka.tasks.PriceTicker
import com.phonoteka.tasks.SCROLL_TOP_THRESHOLD
import com.phonoteka.tasks.SEARCH_DEBOUNCE_MS
import com.phonoteka.tasks.SaveState
import com.phonoteka.tasks.TaskItem
import com.phonoteka.tasks.TaskOps
import com.phonoteka.viewmodel.TaskTrackerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

object TaskTrackerTags {
    const val SEARCH = "searchInput"
    const val NEW_TASK = "newTaskInput"
    const val ADD = "addTaskButton"
    const val SAVE = "saveButton"
    const val TICK = "tickButton"
    const val LIST = "taskList"
    const val SCROLL_TOP = "scrollTopButton"
    fun row(id: Long) = "taskRow_$id"
}

/**
 * ЛР9: «Трекер задач». Отдельный экран, не список треков.
 * Параметры экспериментов (filterMode, leakListeners, naiveAnalytics) по умолчанию —
 * правильные варианты; антипримеры включаются только в debug-панели или тесте.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalComposeUiApi::class)
@Composable
fun TaskTrackerScreen(
    viewModel: TaskTrackerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ticker: PriceTicker = remember { PriceTicker() },
    analytics: FilterAnalytics = remember { FilterAnalytics() },
    filterCounter: ComputationCounter = remember { ComputationCounter() },
    showExperiments: Boolean = com.phonoteka.BuildConfig.DEBUG
) {
    RecordRecomposition("TaskTrackerScreen")
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Модуль 2: первичная загрузка — ровно один раз на вход в композицию.
    LaunchedEffect(Unit) { viewModel.load() }

    var query by rememberSaveable { mutableStateOf("") }
    var appliedQuery by rememberSaveable { mutableStateOf("") }
    var newTitle by rememberSaveable { mutableStateOf("") }
    var newTitleError by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(0) }
    var price by remember { mutableIntStateOf(0) }
    var filterMode by remember { mutableStateOf(FilterMode.DERIVED) }
    var leakListeners by remember { mutableStateOf(false) }
    var naiveAnalytics by remember { mutableStateOf(false) }

    // Модуль 2: debounce поиска. Новый ключ отменяет предыдущий запуск.
    val applySearch by rememberUpdatedState<(String) -> Unit> { appliedQuery = it }
    LaunchedEffect(query) {
        Log.d("DEBOUNCE", "start '${query.take(20)}'")
        try {
            delay(SEARCH_DEBOUNCE_MS)
            Log.d("DEBOUNCE", "apply '${query.take(20)}'")
            applySearch(query)
        } catch (e: CancellationException) {
            Log.d("DEBOUNCE", "cancel '${query.take(20)}'")
            throw e
        }
    }

    // Модуль 2: автосохранение по changeToken, с задержкой — не на каждый тап.
    val autosave by rememberUpdatedState<() -> Unit> { viewModel.autosave() }
    LaunchedEffect(state.changeToken) {
        if (state.changeToken == 0) return@LaunchedEffect
        delay(AUTOSAVE_DELAY_MS)
        autosave()
    }

    // Модуль 2: аналитика — один раз на изменение фильтра.
    if (naiveAnalytics) {
        analytics.filterChanged(appliedQuery) // АНТИПРИМЕР: на каждую рекомпозицию
    } else {
        LaunchedEffect(appliedQuery) { analytics.filterChanged(appliedQuery) }
    }

    // Модуль 2: DisposableEffect + onDispose. В режиме утечки ключ зависит от tick,
    // поэтому каждое нажатие «Тик» перезапускает эффект без отписки.
    DisposableEffect(ticker, if (leakListeners) tick else -1) {
        val listener: (Int) -> Unit = { price = it }
        ticker.subscribe(listener)
        Log.d("TICKER", "subscribe, listeners=${ticker.listenerCount}")
        onDispose {
            if (!leakListeners) ticker.unsubscribe(listener)
            Log.d("TICKER", "dispose, listeners=${ticker.listenerCount}")
        }
    }

    // Модуль 2: секундный таймер только в STARTED.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, ticker) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(1_000)
                seconds++
                ticker.emit(100 + seconds % 10)
            }
        }
    }

    val listState = rememberLazyListState()
    // Модуль 2: скролл через snapshotFlow.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(250)
            .collect { Log.d("SCROLL", "firstVisible=$it") }
    }
    // Модуль 5: второй derivedStateOf — порог кнопки «Наверх».
    val showScrollTop by remember { derivedStateOf { listState.firstVisibleItemIndex > SCROLL_TOP_THRESHOLD } }

    // Модуль 5: три варианта фильтрации.
    val tasksState = rememberUpdatedState(state.tasks)
    val queryState = rememberUpdatedState(appliedQuery)
    val derivedVisible = remember { derivedStateOf { filterCounter.increment(); TaskOps.filter(tasksState.value, queryState.value) } }
    val visible: List<TaskItem> = when (filterMode) {
        FilterMode.NAIVE -> { filterCounter.increment(); TaskOps.filter(state.tasks, appliedQuery) }
        // НЕВЕРНО: tasks не в ключе -> после добавления/переключения список устаревает.
        FilterMode.STALE_REMEMBER -> remember(appliedQuery) { filterCounter.increment(); TaskOps.filter(state.tasks, appliedQuery) }
        FilterMode.DERIVED -> derivedVisible.value
    }

    val onToggle = remember(viewModel) { { id: Long -> viewModel.toggle(id) } }
    val onRemove = remember(viewModel) { { id: Long -> viewModel.remove(id) } }
    val onMoveUp = remember(viewModel) { { id: Long -> viewModel.moveUp(id) } }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text("Трекер задач · ЛР9") },
                navigationIcon = {
                    com.phonoteka.ui.components.AppBackIconButton(onBack)
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = showScrollTop, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), label = "scrollTopVisibility") {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.testTag(TaskTrackerTags.SCROLL_TOP)
                ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Наверх списка") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Поиск") },
                modifier = Modifier.fillMaxWidth().testTag(TaskTrackerTags.SEARCH)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it; newTitleError = false },
                    singleLine = true, isError = newTitleError,
                    label = { Text("Новая задача") },
                    supportingText = if (newTitleError) { { Text("Введите 1–80 символов") } } else null,
                    modifier = Modifier.weight(1f).testTag(TaskTrackerTags.NEW_TASK)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { if (viewModel.add(newTitle)) newTitle = "" else newTitleError = true },
                    modifier = Modifier.testTag(TaskTrackerTags.ADD)
                ) { Text("Добавить") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SaveButton(state.saveState, onClick = viewModel::save)
                TextButton(onClick = { tick++ }, modifier = Modifier.testTag(TaskTrackerTags.TICK)) { Text("Тик: $tick") }
                Text("⏱ $seconds с · цена $price", style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            if (showExperiments) {
                ExperimentPanel(
                    filterMode = filterMode, onFilterMode = { filterMode = it; filterCounter.reset() },
                    leakListeners = leakListeners, onLeak = { leakListeners = it },
                    naiveAnalytics = naiveAnalytics, onNaiveAnalytics = { naiveAnalytics = it },
                    stats = "фильтрований=${filterCounter.value} · слушателей=${ticker.listenerCount} · событий аналитики=${analytics.events.size} · load()=${viewModel.loadCalls}"
                )
            }
            TaskList(visible, listState, onToggle, onRemove, onMoveUp, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TaskList(
    tasks: List<TaskItem>,
    listState: LazyListState,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().testTag(TaskTrackerTags.LIST),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tasks, key = { it.id }, contentType = { "task" }) { task ->
            TaskRow(task = task, onToggle = onToggle, onRemove = onRemove, onMoveUp = onMoveUp)
        }
    }
}

@Composable
fun TaskRow(
    task: TaskItem,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    RecordRecomposition("TaskRow")
    androidx.compose.runtime.SideEffect { Log.d("RECOMP", "TaskRow id=${task.id}") }
    // Состояние строки привязано к key=id: переживает удаление/перестановку соседей.
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (task.done) scheme.secondaryContainer else scheme.surfaceVariant, tween(250), label = "taskRowBackground")
    val textColor by animateColorAsState(
        if (task.done) scheme.onSecondaryContainer else scheme.onSurfaceVariant, tween(250), label = "taskRowTextColor")
    val startPadding by animateDpAsState(if (task.done) 20.dp else 12.dp, tween(250), label = "taskRowPadding")
    val contentAlpha by animateFloatAsState(if (task.done) 0.7f else 1f, tween(250), label = "taskRowAlpha")
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "taskRowArrowRotation")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TaskTrackerTags.row(task.id))
            .background(background, MaterialTheme.shapes.small)
            .selectable(selected = task.done, role = Role.Checkbox, onClick = { onToggle(task.id) })
            .semantics { selected = task.done; stateDescription = if (task.done) "Выполнена" else "Не выполнена" }
            .animateContentSize(tween(250))
            .padding(start = startPadding, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Визуальный чекбокс: переключение выполняет строка.
            Checkbox(checked = task.done, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).alpha(contentAlpha)) {
                Text(task.title, color = textColor, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null)
                if (task.done) Text("✓ Выполнена", color = textColor, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Default.ExpandMore, modifier = Modifier.rotate(arrowRotation),
                    contentDescription = if (expanded) "Скрыть подробности" else "Показать подробности")
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(200)),
            label = "taskRowDetails"
        ) {
            Column(Modifier.padding(start = 40.dp, bottom = 4.dp)) {
                Text(task.details, style = MaterialTheme.typography.bodyMedium, color = textColor)
                Row {
                    TextButton(onClick = { onMoveUp(task.id) }) { Text("Выше") }
                    TextButton(onClick = { onRemove(task.id) }) { Text("Удалить") }
                }
            }
        }
    }
}

/** updateTransition: ширина, скругление и цвет меняются синхронно. */
@Composable
private fun SaveButton(saveState: SaveState, onClick: () -> Unit) {
    val transition = updateTransition(saveState, label = "saveButtonTransition")
    val width by transition.animateDp({ tween(250) }, label = "saveButtonWidth") {
        when (it) { SaveState.IDLE -> 132.dp; SaveState.SAVING -> 172.dp; SaveState.SAVED -> 148.dp; SaveState.ERROR -> 148.dp }
    }
    val corner by transition.animateDp({ tween(250) }, label = "saveButtonCorner") {
        if (it == SaveState.SAVING) 24.dp else 8.dp
    }
    val scheme = MaterialTheme.colorScheme
    val success = com.phonoteka.ui.theme.LocalExtendedColors.current.success
    val color by transition.animateColor({ tween(250) }, label = "saveButtonColor") {
        when (it) { SaveState.IDLE -> scheme.primary; SaveState.SAVING -> scheme.tertiary; SaveState.SAVED -> success; SaveState.ERROR -> scheme.error }
    }
    val label = when (saveState) {
        SaveState.IDLE -> "Сохранить"; SaveState.SAVING -> "Сохранение"
        SaveState.SAVED -> "✓ Сохранено"; SaveState.ERROR -> "Повторить"
    }
    Box(
        modifier = Modifier
            .width(width)
            .height(48.dp)
            .background(color, RoundedCornerShape(corner))
            .selectable(selected = false, enabled = saveState != SaveState.SAVING, role = Role.Button, onClick = onClick)
            .testTag(TaskTrackerTags.SAVE)
            .semantics {
                contentDescription = "Сохранить задачи"
                stateDescription = when (saveState) {
                    SaveState.IDLE -> "Готово"; SaveState.SAVING -> "Сохранение"
                    SaveState.SAVED -> "Сохранено"; SaveState.ERROR -> "Ошибка"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = scheme.onPrimary)
            if (saveState == SaveState.SAVING) { Spacer(Modifier.width(6.dp)); SavingDots() }
        }
    }
}

/** Три пульсирующие точки со смещением старта. Живут только пока находятся в композиции. */
@Composable
private fun SavingDots() {
    val infinite = rememberInfiniteTransition(label = "savingDots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val dotAlpha by infinite.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse, StartOffset(index * 150)),
                label = "savingDot$index"
            )
            Box(Modifier.size(6.dp).alpha(dotAlpha).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
        }
    }
}

@Composable
private fun ExperimentPanel(
    filterMode: FilterMode, onFilterMode: (FilterMode) -> Unit,
    leakListeners: Boolean, onLeak: (Boolean) -> Unit,
    naiveAnalytics: Boolean, onNaiveAnalytics: (Boolean) -> Unit,
    stats: String
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("Эксперименты (debug)", style = MaterialTheme.typography.labelMedium)
        Row {
            FilterMode.entries.forEach { mode ->
                TextButton(onClick = { onFilterMode(mode) }, modifier = Modifier.semantics { selected = mode == filterMode }) {
                    Text((if (mode == filterMode) "● " else "") + mode.name)
                }
            }
        }
        Row {
            TextButton(onClick = { onLeak(!leakListeners) }) { Text(if (leakListeners) "Утечка: ВКЛ" else "Утечка: выкл") }
            TextButton(onClick = { onNaiveAnalytics(!naiveAnalytics) }) { Text(if (naiveAnalytics) "Аналитика: наивная" else "Аналитика: эффект") }
        }
        Text(stats, style = MaterialTheme.typography.bodySmall)
    }
}
