package com.phonoteka.ui

/**
 * Единая карта маршрутов.
 *
 * Лабораторные сценарии больше не являются отдельными «приложениями» внутри
 * настроек: каждый из них принадлежит конкретному разделу Фонотеки.
 * Карта переводит старые адреса настроек в (раздел, маршрут), поэтому любой
 * пункт меню открывает экран в его естественном контексте, а не «лабораторию».
 */
object AppRoutes {
    data class Target(val section: MainSection, val route: String)

    private val byLegacyRoute: Map<String, Target> = mapOf(
        // Профиль открывается из настроек.
        "settings/profile" to Target(MainSection.SETTINGS, "profile/edit"),
        // ЛР9 — трекер задач и окно измерения анимаций.
        "settings/tasks" to Target(MainSection.HOME, "tasks"),
        "settings/lab9-measure" to Target(MainSection.HOME, "settings/lab9-measure"),
        // ЛР10 — пост-каталог как тип контента общей ленты.
        "settings/lab10" to Target(MainSection.CATALOG, "catalog/posts"),
        "settings/lab10/post/{id}" to Target(MainSection.CATALOG, "catalog/posts"),
        // ЛР11 — заметки и резервные копии в разделе «Заметки».
        "settings/lab11" to Target(MainSection.NOTES, "notes/backup"),
        "settings/lab11/free-notes" to Target(MainSection.NOTES, "notes/free"),
        "settings/lab11/notes" to Target(MainSection.NOTES, "notes/track"),
        // ЛР13 — вход, PIN, биометрия и восстановление в разделе «Профиль».
        "settings/lab13" to Target(MainSection.SETTINGS, "profile/auth"),
        "settings/lab13/results" to Target(MainSection.SETTINGS, "profile/auth/results"),
        // ЛР14 — QR-проход и роли в разделе «Профиль».
        "settings/lab14" to Target(MainSection.SETTINGS, "profile/access"),
        // ЛР15 — живой сетевой канал в разделе «Журнал».
        "settings/lab15" to Target(MainSection.AUDIT, "journal/live"),
        // ЛР1–7 — инструменты интерфейса (компоненты дизайн-системы).
        "settings/labs1to7" to Target(MainSection.HOME, "tools/labs1to7")
    )

    fun resolve(legacyRoute: String): Target? = byLegacyRoute[legacyRoute]

    /** Раздел, которому принадлежит маршрут, если он известен глобально. */
    fun sectionOf(route: String): MainSection? = when {
        route.startsWith("profile") -> MainSection.SETTINGS
        route.startsWith("notes") -> MainSection.NOTES
        route.startsWith("catalog") -> MainSection.CATALOG
        route.startsWith("journal") -> MainSection.AUDIT
        else -> null
    }
}
