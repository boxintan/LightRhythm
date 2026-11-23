package com.example.lightrhythm

import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Does not download, install, or request permissions. Uses the installed APK as a fixture. */
class AppUpdateArchiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = AppUpdateRepository(context)
    @Suppress("DEPRECATION")
    private val info = context.packageManager.getPackageInfo(context.packageName, 0)
    private val metadata by lazy { AppUpdateMetadata(context.packageName, PackageInfoCompat.getLongVersionCode(info),
        info.versionName ?: "test", "https://example.com/app.apk", "0".repeat(64), 1, 26, "") }

    @Before fun requiresUpdatablePackage() {
        assumeTrue("Update archive validation applies to the team's normal app ID",
            context.packageName == UPDATABLE_APPLICATION_ID)
    }

    @Test fun acceptsInstalledApkWithMatchingPackageVersionAndSigner() {
        repository.verifyArchive(File(context.applicationInfo.sourceDir), metadata)
    }

    @Test fun rejectsPackageVersionDifferentFromManifest() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.verifyArchive(File(context.applicationInfo.sourceDir), metadata.copy(versionCode = metadata.versionCode + 1))
        }
    }

    @Test fun rejectsAnotherApplicationApk() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.verifyArchive(File(instrumentation.context.applicationInfo.sourceDir), metadata)
        }
    }
}
