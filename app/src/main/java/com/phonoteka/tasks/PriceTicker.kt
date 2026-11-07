package com.phonoteka.tasks

import java.util.concurrent.CopyOnWriteArrayList

/**
 * ЛР9, модуль 2: внешний источник с подпиской. Экран подписывается через DisposableEffect
 * и обязан отписаться в onDispose. listenerCount нужен для эксперимента с утечкой.
 */
class PriceTicker {
    // List, не Set: каждая незакрытая подписка — отдельная утечка, даже с тем же лямбда-объектом
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    val listenerCount: Int get() = listeners.size

    fun subscribe(listener: (Int) -> Unit) { listeners += listener }
    fun unsubscribe(listener: (Int) -> Unit) { listeners.remove(listener) }
    fun emit(price: Int) { listeners.forEach { it(price) } }
}

/** Аналитика фильтра: должна получать ровно одно событие на изменение фильтра. */
class FilterAnalytics {
    private val _events = mutableListOf<String>()
    val events: List<String> @Synchronized get() = _events.toList()
    @Synchronized fun filterChanged(filter: String) { _events += filter }
}
