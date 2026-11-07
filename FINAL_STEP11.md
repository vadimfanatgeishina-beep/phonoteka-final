# Этап 11 — закрытие сокета ЛР15 при смене пользователя, скрытие секретов в логах

## Смена пользователя → закрытие сокета
- `security/PrincipalChangeSignal.kt` (main): счётчик-сигнал «пользователь сменился или вышел» и объединение нескольких StateFlow в поток событий смены (начальные значения сменой не считаются).
- `PhonotekaApp.principalChanges`; `invalidateProtectedSession()` теперь подаёт сигнал. Она уже вызывается при блокировке в `MainActivity`.
- `Lab13MockAuthScreen`: выход из учебной сессии тоже подаёт сигнал (в `finally`, даже при ошибке очистки).
- debug `Lab15LiveScreen`: подписка на сигнал и на смену роли (`roleSession.changes`) вызывает `socket.onPrincipalChanged()` → сокет закрывается с `USER_CHANGED`, привязка к пользователю сбрасывается.
- Тест `PrincipalChangeSignalTest.kt` — 2 JVM-теста (открытие экрана не закрывает сокет; блокировка и смена роли закрывают; повтор той же роли — нет).

## Логи
- debug `Lab10HttpClient`: `redactHeader` для `Authorization`, `Cookie`, `Set-Cookie`, `X-Lab-Ticket`. Сейчас уровень BASIC и заголовки и так не пишутся; это защита на случай повышения уровня. В release логгера нет.
- Клиент ЛР15 (`LocalMockTlsClient`) логгер не использует.

## Не проверено
- Kotlin не компилировался, тесты не запускались (нет Gradle/Android SDK).
