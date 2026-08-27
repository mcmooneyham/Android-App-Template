package com.mattmooneyham.base.android.flows

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the FileProvider grant for the export zip: the zip lives in
 * cacheDir, so log_export_paths.xml must carry a matching cache-path
 * entry. A wrong grant throws here exactly as the share sheet would
 * crash on tap in production.
 */
class LogExportProviderTest {

    @Test
    fun exportZipIsShareableThroughTheLogExportProvider() {
        val targetContext = InstrumentationRegistry
            .getInstrumentation().targetContext
        val logManager = appComponent.logManager
        logManager.info("export-provider-marker")

        val exportPath =
            runBlocking { logManager.writeExportSnapshot() }

        assertNotNull("a logged app must have an export", exportPath)
        FileProvider.getUriForFile(
            targetContext,
            "${targetContext.packageName}.logexport",
            File(exportPath!!),
        )
    }
}
