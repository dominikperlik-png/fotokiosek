package cz.fotokiosek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Velikost výstupu podle modelu a rozpoznání chyby „model tuhle velikost neumí“. */
class GeminiClientTest {

    @Test
    fun imageSizePerModel() {
        assertEquals("2K", GeminiClient.imageSizeFor("gemini-3.1-flash-image"))
        assertEquals("2K", GeminiClient.imageSizeFor(" models/gemini-nano-banana-2.1 "))
        assertEquals("1K", GeminiClient.imageSizeFor("gemini-3.1-flash-lite-image"))
        assertEquals("2K", GeminiClient.imageSizeFor("gemini-3-pro-image"))
        assertEquals(null, GeminiClient.imageSizeFor("gemini-2.5-flash-image"))
        assertEquals("2K", GeminiClient.imageSizeFor("gemini-9-neznamy-model")) // neznámý: zkusit 2K, pojistka případně bez
    }

    @Test
    fun recognisesImageSizeErrors() {
        // Skutečná hláška z automatu (2026-11-25).
        assertTrue(GeminiClient.isImageSizeError(400, """{"error":{"code":400,"message":"Image size 2K is not supported for this model","status":"INVALID_ARGUMENT"}}"""))
        assertTrue(GeminiClient.isImageSizeError(400, """{"error":{"message":"Invalid value at 'generation_config.image_config.imageSize'"}}"""))
        assertTrue(GeminiClient.isImageSizeError(400, """{"error":{"message":"Invalid value for image_size"}}"""))
        assertFalse(GeminiClient.isImageSizeError(400, """{"error":{"message":"API key not valid"}}"""))
        assertFalse(GeminiClient.isImageSizeError(500, "image size"))
    }
}
