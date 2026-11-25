package com.example.lightrhythm

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class AppUpdateTest {
    @Before fun requiresUpdatablePackage() {
        assumeTrue("Update tests apply to published builds", isUpdatableBuild)
    }

    private val apk = "downloaded APK test bytes".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun metadata() = AppUpdateMetadata(
        BuildConfig.APPLICATION_ID, 100123, "1.0.preview.123",
        "https://example.com/LightRhythm.apk", sha, apk.size.toLong(), 26, "测试",
    )

    @Test fun onlyHigherVersionOffersUpdate() {
        assertTrue(metadata().isNewerThan(100122))
        assertFalse(metadata().isNewerThan(100123))
        assertFalse(metadata().isNewerThan(100124))
    }

    @Test fun rejectsUntrustedOrInvalidManifestFields() {
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(applicationId = "other.app") }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(versionCode = 0) }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(apkUrl = "http://example.com/app.apk") }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(apkUrl = "https://user:password@example.com/app.apk") }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(sha256 = "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(sizeBytes = MAX_UPDATE_BYTES + 1) }
    }

    @Test fun rejectsUpdatesFromTheOtherChannel() {
        val otherChannel = if (BuildConfig.APPLICATION_ID.endsWith(".preview")) {
            "com.example.lightrhythm"
        } else {
            "com.example.lightrhythm.preview"
        }
        assertThrows(IllegalArgumentException::class.java) { metadata().copy(applicationId = otherChannel) }
    }

    @Test fun verifiesFullDownloadAndReportsCompletion() {
        val output = ByteArrayOutputStream()
        var progress = -1
        copyVerifiedUpdate(ByteArrayInputStream(apk), output, metadata()) { progress = it }
        assertArrayEquals(apk, output.toByteArray())
        assertEquals(100, progress)
    }

    @Test fun rejectsCorruptionEvenWhenLengthMatches() {
        val corrupt = apk.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate(ByteArrayInputStream(corrupt), ByteArrayOutputStream(), metadata()) {}
        }
    }

    @Test fun rejectsTruncatedDownload() {
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate(ByteArrayInputStream(apk.copyOf(apk.size - 1)), ByteArrayOutputStream(), metadata()) {}
        }
    }

    @Test fun rejectsOversizedDownloadBeforeWritingExtraBytes() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            copyVerifiedUpdate(ByteArrayInputStream(apk + byteArrayOf(1)), output, metadata()) {}
        }
        assertTrue(output.size() <= apk.size)
    }
}
