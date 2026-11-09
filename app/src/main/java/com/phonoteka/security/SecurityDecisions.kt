package com.phonoteka.security

/**
 * ЛР13/14/15: точки, где поведение ЗАВИСИТ от режима сборки.
 *
 * Каждая функция возвращает НЕ «как устроено», а «что разрешено сейчас».
 * Все места принятия решений обязаны спрашивать здесь — иначе различие между
 * сборками останется только надписью в интерфейсе.
 *
 * Уязвимая сборка ослабляет проверки НАМЕРЕННО и ЛОКАЛЬНО (только фиктивные данные),
 * чтобы на закрытом учебном стенде можно было показать атаку.
 */
object SecurityDecisions {

    /**
     * Одноразовость учебного QR-билета.
     * Уязвимая сборка разрешает повторное сканирование — это и есть демонстрируемая ошибка.
     */
    fun qrTicketSingleUse(): Boolean = SecurityProfile.enforceQrTicketSingleUse

    /**
     * Одноразовость кода восстановления/Authenticator.
     * Уязвимая сборка допускает повторное применение уже использованного кода.
     */
    fun otpSingleUse(): Boolean = SecurityProfile.enforceOtpSingleUse

    /**
     * Требовать step-up перед чувствительной операцией.
     * Уязвимая сборка пропускает операцию без подтверждения.
     */
    fun requireStepUp(): Boolean = SecurityProfile.enforceStepUp

    /**
     * Требовать свежую аутентификацию для операций с библиотекой.
     */
    fun requireFreshAuthForLibrary(): Boolean = SecurityProfile.requireFreshAuthForLibrary

    /**
     * Требовать SPKI-pinning и fail-closed при несовпадении пина.
     */
    fun requirePinning(): Boolean = SecurityProfile.enforceCertificatePinning

    /**
     * Пояснение для интерфейса: почему операция прошла или была отклонена.
     * Сборки показывают РАЗНЫЙ текст в одном и том же сценарии.
     */
    fun stepUpExplanation(): String = if (requireStepUp())
        "Операция требует подтверждения (step-up): PIN или биометрия."
    else
        "Учебная уязвимая сборка: подтверждение отключено намеренно."

    fun freshAuthExplanation(): String = if (requireFreshAuthForLibrary())
        "Требуется свежая аутентификация (вход не старше 5 минут)."
    else
        "Учебная уязвимая сборка: свежая аутентификация не проверяется."

    fun qrExplanation(): String = if (qrTicketSingleUse())
        "Билет одноразовый: повторное сканирование отклоняется."
    else
        "Учебная уязвимая сборка: билет можно просканировать повторно."

    fun otpExplanation(): String = if (otpSingleUse())
        "Код одноразовый: повторное применение отклоняется."
    else
        "Учебная уязвимая сборка: код можно применить повторно."
}
