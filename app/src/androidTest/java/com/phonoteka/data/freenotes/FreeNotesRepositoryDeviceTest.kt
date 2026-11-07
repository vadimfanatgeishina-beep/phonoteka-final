package com.phonoteka.data.freenotes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** ЛР11 на устройстве: реальная файловая система Android, включая замену файла без java.nio (minSdk 24). */
@RunWith(AndroidJUnit4::class)
class FreeNotesRepositoryDeviceTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "free-notes-${UUID.randomUUID()}")
    private val file = File(root, "notes.json")
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun missingFileLoadsEmpty() = runTest {
        assertEquals(emptyList<FreeNote>(), FreeNotesRepository(file).load())
    }

    @Test fun saveOverwritesExistingFileAndLeavesNoTmp() = runTest {
        val repo = FreeNotesRepository(file)
        repo.save(listOf(FreeNote("a", "Первая", "текст", 1)))
        val second = listOf(FreeNote("a", "Первая", "изменена", 2), FreeNote("b", "Вторая", "", 3))
        repo.save(second) // файл уже существует: проверяется замена через renameTo
        assertEquals(second, FreeNotesRepository(file).load()) // новый экземпляр = перезапуск процесса
        assertFalse(File(root, "notes.json.tmp").exists())
    }

    @Test fun corruptFileIsReportedNotOverwrittenAndCanBeQuarantined() = runTest {
        root.mkdirs(); file.writeText("{not json")
        val repo = FreeNotesRepository(file)
        val error = runCatching { repo.load() }.exceptionOrNull()
        assertTrue(error is FreeNotesException && error.error == FreeNotesError.CORRUPT)
        assertEquals("{not json", file.readText())
        val moved = repo.quarantineCorrupt(42)
        assertNotNull(moved); assertEquals("{not json", moved!!.readText())
        assertFalse(file.exists())
        assertEquals(emptyList<FreeNote>(), repo.load())
    }
}
