package dev.modelfit

import dev.modelfit.device.SocNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class SocNormalizerTest {

    @Test
    fun `test qualcomm soc normalization`() {
        assertEquals("sm7435", SocNormalizer.normalizeSoc("SM7435"))
        assertEquals("sm7435", SocNormalizer.normalizeSoc("SM7435-AB"))
        assertEquals("sm8650", SocNormalizer.normalizeSoc("sm8650_pro"))
        assertEquals("sdm845", SocNormalizer.normalizeSoc("Qualcomm Technologies, Inc SDM845"))
        assertEquals("msm8998", SocNormalizer.normalizeSoc("msm8998"))
    }

    @Test
    fun `test mediatek soc normalization`() {
        assertEquals("mt6893", SocNormalizer.normalizeSoc("MT6893"))
        assertEquals("mt6765", SocNormalizer.normalizeSoc("mt6765v/ca"))
    }

    @Test
    fun `test exynos soc normalization`() {
        assertEquals("s5e9925", SocNormalizer.normalizeSoc("s5e9925"))
        assertEquals("exynos2200", SocNormalizer.normalizeSoc("Exynos 2200"))
    }

    @Test
    fun `test tensor soc normalization`() {
        assertEquals("gs201", SocNormalizer.normalizeSoc("GS201"))
        assertEquals("tensor_g3", SocNormalizer.normalizeSoc("tensor_g3"))
    }

    @Test
    fun `test empty and unknown fallbacks`() {
        assertEquals("unknown", SocNormalizer.normalizeSoc(null))
        assertEquals("unknown", SocNormalizer.normalizeSoc(""))
        assertEquals("unknown", SocNormalizer.normalizeSoc("   "))
        assertEquals("ranchu", SocNormalizer.normalizeSoc("ranchu"))
    }

    @Test
    fun `test ram tier categorization`() {
        assertEquals(4, SocNormalizer.ramTier(3800))
        assertEquals(4, SocNormalizer.ramTier(4096))
        assertEquals(6, SocNormalizer.ramTier(5800))
        assertEquals(8, SocNormalizer.ramTier(7435)) // realme 12 Pro (8GB phone reports ~7.4GB)
        assertEquals(12, SocNormalizer.ramTier(11500))
        assertEquals(16, SocNormalizer.ramTier(15500))
    }
}
