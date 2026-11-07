package com.phonoteka.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.material3.MaterialTheme
import com.phonoteka.labs.InMemoryLab7Store
import com.phonoteka.labs.Lab1FirstScreen
import com.phonoteka.labs.Lab4StateScreen
import com.phonoteka.labs.Lab6App
import com.phonoteka.labs.Lab7ErrorType
import com.phonoteka.labs.Lab7Exception
import com.phonoteka.labs.Lab7Item
import com.phonoteka.labs.Lab7ListScreen
import com.phonoteka.labs.Lab7ListViewModel
import com.phonoteka.labs.Lab7Source
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class Labs1to7UiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lab1CounterIncrements() {
        rule.setContent { MaterialTheme { Lab1FirstScreen() } }
        rule.onNodeWithTag("lab1Button").performClick().performClick()
        rule.onNodeWithText("Нажато: 2").assertIsDisplayed()
    }

    @Test fun lab4StateHoisting() {
        rule.setContent { Lab4StateScreen() }
        rule.onNodeWithTag("lab4Search").performTextInput("abc")
        rule.onNodeWithTag("lab4SearchCount").assertTextContains("Символов: 3")
        rule.onNodeWithText("Очистить").performClick()
        rule.onNodeWithTag("lab4SearchCount").assertTextContains("Символов: 0")
        rule.onNodeWithText("+1").performClick()
        rule.onNodeWithTag("lab4Count").assertTextContains("Счётчик: 1")
    }

    /** ЛР6: Главная → Список → Детали → Назад → Назад → Главная. */
    @Test fun lab6HomeListDetailsBackHome() {
        rule.setContent { Lab6App() }
        rule.onNodeWithTag("lab6ToList").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab6Item_3")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("lab6Item_3").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab6DetailsTitle")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("lab6DetailsTitle").assertTextContains("Статья №3")
        rule.onNodeWithTag("lab6Back").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab6List")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("lab6Back").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab6ToList")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun lab6DeepLinkUnknownIdShowsNotFound() {
        rule.setContent { Lab6App(deepLinkItemId = "999") }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab6NotFound")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Элемент не найден").assertIsDisplayed()
    }

    /** ЛР7: ошибка → подождать backoff → повторить → успех. */
    @Test fun lab7ErrorWaitRetrySuccess() {
        val calls = AtomicInteger(0)
        val source = Lab7Source { attempt ->
            if (calls.incrementAndGet() <= 2) throw Lab7Exception(Lab7ErrorType.NO_NETWORK)
            listOf(Lab7Item(1, "Трек OK #$attempt"))
        }
        val vm = Lab7ListViewModel(source, InMemoryLab7Store(), log = {})
        rule.setContent { MaterialTheme { Lab7ListScreen(vm) } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab7Retry")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("lab7ErrorMessage").assertTextContains("Нет сети", substring = true)
        rule.onNodeWithTag("lab7Retry").performClick() // 1-й повтор сразу → снова ошибка, далее backoff 1 с
        rule.waitUntil(5_000) { calls.get() == 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("lab7Retry").assertIsNotEnabled()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("lab7Retry").assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag("lab7Retry").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("lab7Item_1")).fetchSemanticsNodes().isNotEmpty() }
    }
}
