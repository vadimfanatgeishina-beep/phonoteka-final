package com.phonoteka.viewmodel
import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsPersistenceTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    @Test(timeout = 15000) fun themeAndForceDarkSurviveNewStoreAndViewModel() = runTest {
        val file = File(temporary.root, "owned-theme.preferences_pb")
        val app = Mockito.mock(Application::class.java)
        val firstJob = SupervisorJob()
        val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
        val first = SettingsViewModel(app, firstStore)
        val owner = ViewModelStore().apply { put("vm", first) }
        try {
            first.setThemeMode(ThemeMode.DARK); first.setForceDarkContent(true); first.setGestureOnlyBack(true)
            val written = first.state.first { it.themeMode == ThemeMode.DARK && it.forceDarkContent && it.gestureOnlyBack }
            assertTrue(written.forceDarkContent)
        } finally { owner.clear(); firstJob.cancelAndJoin() }
        val secondJob = SupervisorJob()
        val secondStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file })
        val second = SettingsViewModel(app, secondStore)
        val secondOwner = ViewModelStore().apply { put("vm", second) }
        try {
            val restored = second.state.first { it.themeMode == ThemeMode.DARK && it.forceDarkContent && it.gestureOnlyBack }
            assertEquals(ThemeMode.DARK, restored.themeMode)
            assertTrue(restored.gestureOnlyBack)
            second.setThemeMode(ThemeMode.SYSTEM)
            assertEquals(ThemeMode.SYSTEM, second.state.first { it.themeMode == ThemeMode.SYSTEM && it.forceDarkContent }.themeMode)
        } finally { secondOwner.clear(); secondJob.cancelAndJoin() }
    }
}
