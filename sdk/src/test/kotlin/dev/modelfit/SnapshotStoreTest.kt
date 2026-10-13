package dev.modelfit

import dev.modelfit.RecommendationSource
import dev.modelfit.cache.SnapshotStore
import dev.modelfit.device.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnapshotStoreTest {

    @Test
    fun `test offline snapshot evaluation with bundled production data`() {
        val snapshotFile = File("src/main/assets/modelfit/snapshot.json")
        assertTrue("Snapshot file must exist in assets", snapshotFile.exists())
        val json = snapshotFile.readText(Charsets.UTF_8)

        // Mock device matching user's phone (Snapdragon 7s Gen 2 / sm7435 with 8GB RAM)
        val device = DeviceInfo(
            soc = "SM7435",
            socNormalized = "sm7435",
            manufacturer = "realme",
            model = "RMX3987",
            totalRamMb = 7435,
            availRamMb = 3840,
            cpuCores = 8,
            apiLevel = 34,
            abi = "arm64-v8a"
        )

        // Using reflection or a package-private method, let's test evaluateSnapshot directly
        val storeClass = SnapshotStore::class.java
        val method = storeClass.getDeclaredMethod(
            "evaluateSnapshot",
            String::class.java,
            DeviceInfo::class.java,
            Profile::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true

        val dummyStore = org.mockito.Mockito.mock(SnapshotStore::class.java)
        // Or invoke on a new instance constructed with null context if we bypass constructor or mock context
        val mockContext = org.mockito.Mockito.mock(android.content.Context::class.java)
        val store = SnapshotStore(mockContext)

        val result = method.invoke(store, json, device, Profile.Balanced, 170, 100, 3, true) as dev.modelfit.Recommendation

        assertNotNull(result)
        assertEquals(RecommendationSource.SNAPSHOT, result.source)
        assertEquals("exact", result.scope.match)
        assertEquals("sm7435", result.scope.scopeKey)
        assertTrue("Recommendations should contain items", result.items.isNotEmpty())

        val topModel = result.items.first()
        println("Top offline model: ${topModel.modelId} with score ${topModel.score} and decode TPS ${topModel.expected.decodeTps}")
        assertTrue("Top model score should be positive", topModel.score > 0.0)
        assertTrue("Decode speed should be > 5 tok/s", topModel.expected.decodeTps >= 5.0)
    }
}
