package com.phonoteka.security

sealed interface SensitiveResultsState {
    data object StepUpRequired : SensitiveResultsState
    data class Loaded(val rows: List<String>) : SensitiveResultsState
}

/**
 * ЛР13: обычные данные доступны без дополнительного шага, чувствительные «результаты» — только при аутентификации младше 5 минут.
 * Загрузчик не вызывается, пока step-up не пройден: сначала обновляется время аутентификации, потом грузятся данные.
 */
class SensitiveResultsGate(
    private val isFresh: () -> Boolean,
    private val loader: () -> List<String>,
    private val audit: (String) -> Unit = {}
) {
    fun open(): SensitiveResultsState {
        if (!isFresh()) { audit("results_stepup_required"); return SensitiveResultsState.StepUpRequired }
        val rows = loader()
        audit("results_opened")
        return SensitiveResultsState.Loaded(rows)
    }

    companion object {
        val ordinaryOrders = listOf("Заказ №1001 · плейлист «Дорога»", "Заказ №1002 · альбом «Джаз»", "Заказ №1003 · подкаст")
        fun mockResults(): List<String> = listOf(
            "Результат №1001: лицензия подтверждена, сумма скрыта в логах",
            "Результат №1002: возврат одобрен",
            "Результат №1003: требуется документ"
        )
    }
}
