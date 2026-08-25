package io.github.supermonster003.autojs6.plugin.r8compiler

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class R8CompatibilityCorpusTest(
    private val language: R8CorpusLanguage,
    private val minApi: Int,
) {
    @Test
    fun realR8PreservesTheFiveCompatibilitySurfaces() {
        val receipt = R8CompatibilityCorpusSupport.execute(language, minApi)
        R8CompatibilityCorpusSupport.writeReceipt(receipt)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-minApi-{1}")
        fun cells(): List<Array<Any>> = buildList {
            R8CorpusLanguage.values().forEach { language ->
                (24..36).forEach { minApi -> add(arrayOf(language, minApi)) }
            }
        }
    }
}
