package org.techwithkaushik.formSnap

import org.junit.Assert.assertEquals
import org.junit.Test
import org.techwithkaushik.formSnap.pipeline.DetectionKind

class OutputFileNamingTest {
    @Test
    fun createsExpectedPhotoAndSignatureNames() {
        assertEquals("Arvind-Kaushik-photo.jpg", OutputFileNaming.desiredName(DetectionKind.PHOTO, "Arvind Kaushik"))
        assertEquals("Arvind-Kaushik-sign.png", OutputFileNaming.desiredName(DetectionKind.SIGNATURE, "Arvind Kaushik"))
    }

    @Test
    fun stripsUnsafeFilenameCharactersAndUsesFallbackForBlankNames() {
        assertEquals("AB-photo.jpg", OutputFileNaming.desiredName(DetectionKind.PHOTO, "A/B:*?"))
        assertEquals("FormSnap-sign.png", OutputFileNaming.desiredName(DetectionKind.SIGNATURE, "  "))
    }

    @Test
    fun addsCollisionSuffixBeforeExtension() {
        assertEquals("Arvind-photo_1.jpg", OutputFileNaming.withSuffix("Arvind-photo.jpg", 1))
        assertEquals("signature_2.png", OutputFileNaming.withSuffix("signature.png", 2))
    }
}
