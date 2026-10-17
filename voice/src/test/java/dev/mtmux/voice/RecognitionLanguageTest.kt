package dev.mtmux.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionLanguageTest {
    @Test fun automaticUsesWhisperDetection() = assertEquals("auto", recognitionLanguage(true, "zh-CN"))
    @Test fun chineseInterfaceUsesChinese() = assertEquals("zh", recognitionLanguage(false, "zh-CN"))
    @Test fun defaultEnglishResourcesUseEnglish() {
        assertEquals("en", recognitionLanguage(false, "en-US"))
        assertEquals("en", recognitionLanguage(false, "fr-FR"))
    }
    @Test fun silenceGateRejectsSilenceAndKeepsSpeech() {
        org.junit.Assert.assertFalse(hasAudibleSpeech(FloatArray(16000)))
        org.junit.Assert.assertFalse(hasAudibleSpeech(FloatArray(1000){0.5f}))
        org.junit.Assert.assertTrue(hasAudibleSpeech(FloatArray(16000){if(it%100<50) 0.01f else -0.01f}))
    }
}
