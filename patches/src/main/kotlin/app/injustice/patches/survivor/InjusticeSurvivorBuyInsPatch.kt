package app.injustice.patches.survivor

import app.morphe.patcher.patch.rawResourcePatch
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE
import java.io.RandomAccessFile

// The Survivor buy-in gate is native (see analysis/injustice/notes/survivor-buyins.md) —
// no DEX surface exists. This patch edits the shipped libInjusticeGAU.so in place, with
// per-ABI offset tables: APKPure ships both a 32-bit (armeabi-v7a) and a 64-bit
// (arm64-v8a) build of 3.5.1, and their binaries differ completely. The two gate
// functions are byte-identical in shape on both; only offsets and encodings change.
// PT_LOAD for these regions is 1:1, so virtual address == file offset (verified per ABI).

private const val ARM64_LIB = "lib/arm64-v8a/libInjusticeGAU.so"
private const val ARM32_LIB = "lib/armeabi-v7a/libInjusticeGAU.so"

// The library must at least contain the byte range this patch edits (largest offset + 12).
private const val MIN_LIB_BYTES = 0x1e375dcL + 8

private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private class Site(
    val offset: Long,
    val original: ByteArray,
    val patch: ByteArray,
    val what: String,
)

private class Abi(val libPath: String, val skips: Site, val gate: Site)

/**
 * Per-ABI patch sites. Both ABIs patch the same two getters:
 *
 *   UPlayerSaveData::GetNumSurvivorCooldownSkips() — reads the buy-in counter field
 *   UPlayerSaveData::IsSurvivalModeCooldownInEffect() — reads flag bit 0 of the
 *     survivor flag byte (the re-entry cooldown gate)
 *
 * Originals and replacements (self-checked against the binary at apply time):
 *
 *   arm64-v8a (64-bit APK, verified against SHA-checked A8 build):
 *     GetNumSurvivorCooldownSkips   @ 0x1e375dc  ldr w0, [x0, #0x494]; ret
 *       -> mov w0, #1000; ret
 *     IsSurvivalModeCooldownInEffect @ 0x1e32220  ldrb w0, [x0, #0x221]; and w0, w0, #1; ret
 *       -> mov w0, #0; ret; nop
 *
 *   armeabi-v7a (32-bit APK):
 *     GetNumSurvivorCooldownSkips   @ 0x18fdb8c  ldr r0, [r0, #0x400]; bx lr
 *       -> movw r0, #1000; bx lr
 *     IsSurvivalModeCooldownInEffect @ 0x18f7fc0  ldrb r1, [r0, #0x1c1]; and r0, r1, #1; bx lr
 *       -> mov r0, #0; bx lr; nop
 */
private val ABIS = listOf(
    Abi(
        ARM64_LIB,
        Site(0x1e375dcL, hex("0094 44b9 c003 5fd6"), hex("007d 8052 c003 5fd6"), "GetNumSurvivorCooldownSkips"),
        Site(0x1e32220L, hex("0084 4839 0000 0012 c003 5fd6"), hex("0000 8052 c003 5fd6 1f20 03d5"), "IsSurvivalModeCooldownInEffect"),
    ),
    Abi(
        ARM32_LIB,
        Site(0x18fdb8cL, hex("0004 90e5 1eff 2fe1"), hex("e803 00e3 1eff 2fe1"), "GetNumSurvivorCooldownSkips"),
        Site(0x18f7fc0L, hex("c101 d0e5 0100 00e2 1eff 2fe1"), hex("0000 a0e3 1eff 2fe1 0000 a0e1"), "IsSurvivalModeCooldownInEffect"),
    ),
)

/**
 * Injustice: Gods Among Us — Survivor Buy-Ins
 *
 * Makes Survivor mode entries unlimited: the re-entry cooldown can never engage and the
 * buy-in counter always reads 1000.
 *
 * Mechanism (native analysis of libInjusticeGAU.so, see
 * analysis/injustice/notes/survivor-buyins.md): after a Survivor run the save data gets a
 * re-entry cooldown — flag bit 0 of the survivor flag byte, read by
 * UPlayerSaveData::IsSurvivalModeCooldownInEffect(). While it is set, re-entry requires
 * waiting out the timer (USurvivorPopup shows it), spending a buy-in — the
 * "Survivor cooldown skip" counter, read by GetNumSurvivorCooldownSkips() — or paying
 * (analytics: LogSurvivorPurchaseCooldown / Swrve OnBuyIn). When the timer expires the
 * flag is cleared AND the skip counter is zeroed — which is why buy-ins run out.
 *
 * The patch replaces the two read-only getters (each 2-3 instructions) with constants:
 * the gate always answers "no cooldown" (unlimited immediate re-entries) and the counter
 * always answers 1000 (immune to the expiry wipe). Both are read-only accessors; the run
 * state itself is still written normally by the game. The patch refuses to touch a
 * binary whose bytes don't match the verified originals.
 *
 * Applies to both APK builds of 3.5.1: the 64-bit arm64-v8a APK (what current devices
 * install) and the legacy 32-bit armeabi-v7a APK — the ABI is detected from the entries
 * present in the input.
 *
 * Risk: LOW — WBID cloud-save sync is server-authoritative. Note this patch modifies the
 * native lib, so it is applied as a raw resource patch (not bytecode) — the same
 * CLI/Manager flow applies.
 */
@Suppress("unused")
val injusticeSurvivorBuyInsPatch = rawResourcePatch(
    name = "Injustice Survivor Buy-Ins",
    description = "Unlimited Survivor mode entries: no re-entry cooldown and 1000 buy-ins.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        val abi = ABIS.firstOrNull { candidate ->
            runCatching {
                val f = get(candidate.libPath, true)
                f.isFile && f.length() >= MIN_LIB_BYTES
            }.getOrDefault(false)
        } ?: throw IllegalStateException(
            "libInjusticeGAU.so is not present in the APK being patched " +
                "(looked for ${ABIS.joinToString(" and ") { it.libPath }}). " +
                "This patch needs an input that contains the native library — on split " +
                "installs the libraries live in a separate split; patch the single APK " +
                "file instead of the installed app. (Desktop morphe-cli builds of this " +
                "patch are verified working for both APK variants.)"
        )

        RandomAccessFile(get(abi.libPath, true), "rw").use { f ->
            fun apply(site: Site) {
                val current = ByteArray(site.original.size)
                f.seek(site.offset)
                f.readFully(current)
                check(current.contentEquals(site.original)) {
                    "${site.what}: unexpected bytes at 0x${site.offset.toString(16)} in " +
                        "${abi.libPath} — got ${current.joinToString("") { "%02x".format(it) }}, " +
                        "want ${site.original.joinToString("") { "%02x".format(it) }} " +
                        "(binary is not the supported 3.5.1 build?)"
                }
                f.seek(site.offset)
                f.write(site.patch)
            }

            apply(abi.skips)
            apply(abi.gate)
        }
    }
}
