package org.autojs.plugin.r8compiler.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R8RulePolicyTest {
    @Test fun callerSuppliedAdmissionLimitsCannotExceedProtocolCeilings() {
        expectContractFailure {
            R8RuleAdmissionLimits(
                17, 32, 256L * 1024, 2L * 1024 * 1024,
                4_096, 16_384, 16 * 1024,
            )
        }
    }

    @Test fun aggregateSnapshotsCallerCollectionExactlyOnce() {
        val values = listOf(R8InputRole.KEEP_RULES to R8Fixtures.keepBytes)
        var iterations = 0
        val singleUse = object : AbstractCollection<Pair<R8InputRole, ByteArray>>() {
            override val size: Int = values.size
            override fun iterator(): Iterator<Pair<R8InputRole, ByteArray>> {
                check(++iterations == 1) { "Caller collection was iterated more than once" }
                return values.iterator()
            }
        }
        R8RulePolicy.validateAggregate(singleUse)
        assertEquals(1, iterations)
    }

    @Test fun aggregateBoundsHostileCollectionAtProtocolMaximumPlusOne() {
        var nextCalls = 0
        val endless = object : AbstractCollection<Pair<R8InputRole, ByteArray>>() {
            override val size: Int get() = throw AssertionError("Collection size must not be trusted")
            override fun iterator(): Iterator<Pair<R8InputRole, ByteArray>> =
                object : Iterator<Pair<R8InputRole, ByteArray>> {
                    override fun hasNext() = true
                    override fun next(): Pair<R8InputRole, ByteArray> {
                        nextCalls++
                        if (nextCalls > 49) throw AssertionError("Policy iterated past the protocol limit plus one")
                        return R8InputRole.KEEP_RULES to R8Fixtures.keepBytes
                    }
                }
        }

        val failure = expectContractFailure { R8RulePolicy.validateAggregate(endless) }

        assertTrue(failure is R8ContractException)
        assertEquals(R8ContractViolation.INVALID_VALUE, (failure as R8ContractException).violation)
        assertEquals(49, nextCalls)
    }

    @Test fun multilineKeepBlockIsAccepted() {
        val summary = R8RulePolicy.validateFile(R8Fixtures.keepBytes, requireKeepDirective = true)
        assertTrue(summary.keepDirectiveCount > 0)
        assertTrue(summary.lineCount > 1)
    }

    @Test fun allowlistedDiagnosticsAndAttributeRulesAreAccepted() {
        val bytes = "-keepattributes Signature,*Annotation*\n-dontwarn sample.optional.**\n-dontnote sample.internal.**\n".toByteArray()
        val summary = R8RulePolicy.validateFile(bytes, requireKeepDirective = false)
        assertEquals(3, summary.directiveCount)
    }

    @Test fun filesystemAndOutputDirectivesAreRejected() {
        listOf(
            "@rules.pro\n",
            "-include rules.pro\n",
            "-basedirectory .\n",
            "-injars input.jar\n",
            "-outjars output.jar\n",
            "-libraryjars android.jar\n",
            "-applymapping mapping.txt\n",
            "-printmapping mapping.txt\n",
            "-printseeds seeds.txt\n",
            "-printusage usage.txt\n",
            "-obfuscationdictionary words.txt\n",
        ).forEach { hostile -> expectContractFailure { R8RulePolicy.validateFile(hostile.toByteArray(), false) } }
    }

    @Test fun profileControlDirectivesAreRejected() {
        listOf(
            "-dontshrink\n",
            "-dontoptimize\n",
            "-dontobfuscate\n",
            "-forceprocessing\n",
            "-optimizationpasses 5\n",
            "-target 1.8\n",
            "-repackageclasses x\n",
            "-allowaccessmodification\n",
            "-assumenosideeffects class X {}\n",
        ).forEach { hostile -> expectContractFailure { R8RulePolicy.validateFile(hostile.toByteArray(), false) } }
    }

    @Test fun commentDecoysCannotReviveDeniedDirectives() {
        val bytes = "# -keep class Hidden {}\n/* -keep class AlsoHidden {} */\n-dontobfuscate\n".toByteArray()
        expectContractFailure { R8RulePolicy.validateFile(bytes, false) }
    }

    @Test fun semicolonAndAdjacentSecondDirectiveSmugglingAreRejected() {
        listOf(
            "-keep class X {} ;-dontobfuscate\n",
            "-keep class X {}-include x\n",
            "-keep class X {} @x\n",
        ).forEach { hostile -> expectContractFailure { R8RulePolicy.validateFile(hostile.toByteArray(), true) } }
    }

    @Test fun strictUtf8LfAndFinalNewlineAreRequired() {
        listOf(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + R8Fixtures.keepBytes,
            "-keep class X {}\r\n".toByteArray(),
            "-keep class X {}".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte()),
            byteArrayOf('-'.code.toByte(), 'k'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte(), 'p'.code.toByte(), 0, '\n'.code.toByte()),
        ).forEach { hostile -> expectContractFailure { R8RulePolicy.validateFile(hostile, true) } }
    }

    @Test fun keepRoleMustSupplyMeaningfulKeepItself() {
        val diagnosticsOnly = "-dontwarn sample.**\n".toByteArray()
        expectContractFailure {
            R8RulePolicy.validateAggregate(
                listOf(
                    R8InputRole.KEEP_RULES to diagnosticsOnly,
                    R8InputRole.CONSUMER_RULES to "-keep class ConsumerOnly {}\n".toByteArray(),
                ),
            )
        }
    }

    @Test fun providerSpecificLineAndAggregateLimitsAreEnforced() {
        val caps = R8Fixtures.capabilities(
            limits = R8Fixtures.limits(
                maxRuleFileBytes = 128,
                maxTotalRuleBytes = 128,
                maxRuleLinesPerFile = 2,
                maxTotalRuleLines = 2,
                maxRuleLineBytes = 64,
            ),
        )
        expectContractFailure {
            R8CompilerValidation.validateExtractedRules(
                listOf(R8InputRole.KEEP_RULES to R8Fixtures.keepBytes),
                caps,
            )
        }
    }

    @Test fun unbalancedBracesAndContinuationsAreRejected() {
        listOf(
            "-keep class X {\n",
            "-keep class X }\n",
            "-keep class X \\\n",
        ).forEach { hostile -> expectContractFailure { R8RulePolicy.validateFile(hostile.toByteArray(), true) } }
    }
}
