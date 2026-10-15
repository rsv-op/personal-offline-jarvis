package com.example.voiceassistant.aimatrix

import com.example.voiceassistant.TestFiles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model that ships inside the APK: does it actually route phrases on the phone? */
class AiMatrixClassifierTest {

    private val classifier: AiMatrixClassifier by lazy {
        AiMatrixClassifier.fromJson(TestFiles.aiMatrixAsset().readText())
    }

    @Test
    fun `bundled model loads with the expected shape`() {
        assertEquals(AiMatrixFeaturizer.BUCKETS, classifier.buckets)
        assertTrue("Hidden layer should stay small", classifier.hiddenSize in 8..128)
        assertTrue(
            "Expected the six device intents plus UNKNOWN",
            classifier.labels.containsAll(
                listOf(
                    "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "ALARM_SET",
                    "TIMER_SET", "DELETE_FILE", "DEVICE_STATUS", "UNKNOWN"
                )
            )
        )
    }

    @Test
    fun `phrases route to the right action on device`() {
        assertEquals("FLASHLIGHT_ON", classifier.predict("turn on the flashlight").label)
        assertEquals("FLASHLIGHT_ON", classifier.predict("switch the torch on").label)
        assertEquals("FLASHLIGHT_OFF", classifier.predict("switch the torch off").label)
        assertEquals("FLASHLIGHT_OFF", classifier.predict("turn my flashlight off").label)
        assertEquals("ALARM_SET", classifier.predict("set an alarm for seven").label)
        assertEquals("TIMER_SET", classifier.predict("set a timer for five minutes").label)
        assertEquals("DEVICE_STATUS", classifier.predict("how much battery is left").label)
        assertEquals("UNKNOWN", classifier.predict("what is the capital of france").label)
    }

    @Test
    fun `probabilities form a distribution`() {
        val probabilities = classifier.probabilities("wake me up at six")
        assertEquals(classifier.labels.size, probabilities.size)
        var sum = 0f
        for (value in probabilities) {
            assertTrue("Probabilities must be within [0, 1]", value in 0f..1f)
            sum += value
        }
        assertEquals(1f, sum, 1e-4f)
    }

    @Test
    fun `ranked scores are ordered most likely first`() {
        val ranked = classifier.ranked("turn off the torch")
        assertTrue(ranked.size >= 2)
        assertTrue(ranked[0].second >= ranked[1].second)
        assertEquals("FLASHLIGHT_OFF", ranked.first().first)
    }

    @Test
    fun `metadata describes how the model was trained`() {
        val metadata = classifier.metadata
        assertNotNull("The exported model must carry training metadata", metadata)
        assertTrue(metadata!!.optInt("trainingExamples") > 100)
        assertTrue("Held-out accuracy should stay above 90%", metadata.optDouble("holdoutAccuracy") >= 0.9)
    }

    @Test
    fun `a stale asset is rejected instead of silently mis-routing`() {
        val raw = JSONObject(TestFiles.aiMatrixAsset().readText())

        val wrongFeatureVersion = JSONObject(raw.toString()).put("featureVersion", 999)
        val featureError = assertThrows(IllegalArgumentException::class.java) {
            AiMatrixClassifier.fromJson(wrongFeatureVersion)
        }
        assertTrue(featureError.message.orEmpty().contains("feature version"))

        val wrongFormatVersion = JSONObject(raw.toString()).put("formatVersion", 99)
        assertThrows(IllegalArgumentException::class.java) {
            AiMatrixClassifier.fromJson(wrongFormatVersion)
        }

        assertThrows(IllegalArgumentException::class.java) {
            AiMatrixClassifier.fromJson(JSONObject().put("format", "something-else"))
        }
    }

    @Test
    fun `confidence reflects certainty`() {
        val sure = classifier.predict("turn on the flashlight").confidence
        val unsure = classifier.predict("banana helicopter").confidence
        assertTrue("An in-domain phrase should be confident", sure > 0.5f)
        assertTrue("A nonsense phrase should not be confident", unsure <= sure)
    }

    @Test
    fun `sparse scoring equals dense scoring`() {
        val text = "jarvis could you please turn the torch on"
        val dense = classifier.probabilities(text)
        val sparse = classifier.probabilities(AiMatrixFeaturizer.termFrequencies(text))
        for (index in dense.indices) {
            assertEquals(dense[index].toDouble(), sparse[index].toDouble(), 1e-5)
        }
    }
}
