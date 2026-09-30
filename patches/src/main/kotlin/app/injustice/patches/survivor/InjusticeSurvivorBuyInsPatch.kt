package app.injustice.patches.survivor

import app.morphe.patcher.patch.rawResourcePatch
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE
import java.io.RandomAccessFile

// Smali/DEX has no Survivor surface — the mode is native (see
// analysis/injustice/notes/survivor-buyins.md). This patch edits the shipped
// lib/armeabi-v7a/libInjusticeGAU.so (3.5.1, SHA-256 80df4725…523a32) in place.
// PT_LOAD for this region is 1:1, so virtual address == file offset (verified).

private const val LIB_PATH = "lib/armeabi-v7a/libInjusticeGAU.so"

// UPlayerSaveData::GetNumSurvivorCooldownSkips() @ 0x18fdb8c, 8 bytes
private const val SKIPS_GETTER_OFFSET = 0x18fdb8cL
// UPlayerSaveData::IsSurvivalModeCooldownInEffect() @ 0x18f7fc0, 12 bytes
private const val COOLDOWN_GATE_OFFSET = 0x18f7fc0L

// The library must at least contain the byte range this patch edits.
private const val MIN_LIB_BYTES = COOLDOWN_GATE_OFFSET + 12

private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

// Original instructions — the patch refuses to touch a binary that doesn't match these.
//   ldr  r0, [r0, #0x400]   ; NumSurvivorCooldownSkips field
//   bx   lr
private val SKIPS_GETTER_ORIGINAL = hex("000490e5 1eff2fe1")
//   ldrb r1, [r0, #0x1c1]   ; survivor flag byte (bit 0 = cooldown in effect)
//   and  r0, r1, #1
//   bx   lr
private val COOLDOWN_GATE_ORIGINAL = hex("c101d0e5 010000e2 1eff2fe1")

// Replacement for the skips getter: movw r0, #1000 ; bx lr
private val SKIPS_GETTER_PATCH = hex("e80300e3 1eff2fe1")
// Replacement for the gate: mov r0, #0 ; bx lr ; nop  (cooldown never in effect)
private val COOLDOWN_GATE_PATCH = hex("0000a0e3 1eff2fe1 0000a0e1")

/**
 * Injustice: Gods Among Us — Survivor Buy-Ins
 *
 * Makes Survivor mode entries unlimited: the re-entry cooldown can never engage and the
 * buy-in counter always reads 1000.
 *
 * Mechanism (native analysis of libInjusticeGAU.so, see
 * analysis/injustice/notes/survivor-buyins.md): after a Survivor run the save data gets a
 * re-entry cooldown — flag bit 0 of the byte at UPlayerSaveData+0x1c1, read by
 * UPlayerSaveData::IsSurvivalModeCooldownInEffect(). While it is set, re-entry requires
 * waiting out the timer (USurvivorPopup shows it), spending a buy-in — the
 * "Survivor cooldown skip" counter at UPlayerSaveData+0x400, read by
 * GetNumSurvivorCooldownSkips() — or paying (analytics: LogSurvivorPurchaseCooldown /
 * Swrve OnBuyIn). When the timer expires, GetSurvivalModeCooldownTimerPercentage() clears
 * the flag AND zeroes the skip counter — which is why buying/skipping can run out.
 *
 * Two 8/12-byte in-place instruction replacements (self-checked against the original
 * bytes; aborts on a mismatch):
 *
 *   GetNumSurvivorCooldownSkips @ 0x18fdb8c:
 *     ldr r0, [r0, #0x400]  ->  movw r0, #1000
 *     bx  lr                ->  bx lr
 *     (always reports 1000 buy-ins; immune to the expiry wipe since the field is ignored)
 *
 *   IsSurvivalModeCooldownInEffect @ 0x18f7fc0:
 *     ldrb r1, [r0, #0x1c1] ->  mov r0, #0
 *     and  r0, r1, #1       ->  bx lr
 *     bx  lr                ->  nop
 *     (cooldown never in effect: unlimited immediate re-entries, no payment needed)
 *
 * Risk: LOW — both are pure getters used as read-only gates; the run state itself is
 * still written normally by the game. Same caveat as the repo's other Injustice patches:
 * WBID cloud-save sync is server-authoritative. Note this patch modifies the native lib,
 * so it is applied as a raw resource patch (not bytecode) — the same CLI/Manager flow
 * applies.
 */
@Suppress("unused")
val injusticeSurvivorBuyInsPatch = rawResourcePatch(
    name = "Injustice Survivor Buy-Ins",
    description = "Unlimited Survivor mode entries: no re-entry cooldown and 1000 buy-ins.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        // Native libraries are NOT staged to the working directory ("the one archive
        // directory left unstaged"); the patcher materialises an entry from the input
        // archive when get() finds it missing (verified in morphe-patcher 1.14.1 bytecode:
        // extractRootEntries is called unconditionally on !exists()).
        // listApkEntries is reached reflectively so the patch also runs on hosts whose
        // patcher predates it.
        val entries = runCatching {
            val m = this::class.java.methods.firstOrNull { it.name == "listApkEntries" }
            (m?.invoke(this) as? Collection<*>)?.map { it.toString() } ?: emptyList<String>()
        }.getOrDefault(emptyList())
        val archiveName = entries.firstOrNull { it == LIB_PATH }
            ?: entries.firstOrNull { it.endsWith("libInjusticeGAU.so") }
            ?: LIB_PATH.takeIf {
                runCatching { get(it, true).let { f -> f.isFile && f.length() > MIN_LIB_BYTES } }
                    .getOrDefault(false)
            }
            ?: throw IllegalStateException(
                "libInjusticeGAU.so not found in the APK being patched. " +
                    "lib/ entries visible to the patcher (${entries.size}): " +
                    entries.take(12).joinToString().ifEmpty { "<none>" } + ". " +
                    "On split installs the native libraries live in a separate split and " +
                    "are not part of the patcher input — patch from the single APK file " +
                    "(e.g. the APKPure download) instead of the installed app."
            )

        val lib = get(archiveName, true)
        check(lib.isFile && lib.length() >= MIN_LIB_BYTES) {
            "Native library not materialised in the patch workspace " +
                "(entry=$archiveName, resolved=${lib.absolutePath}, " +
                "exists=${lib.isFile}, size=${lib.length()})."
        }
        RandomAccessFile(lib, "rw").use { f ->
            fun apply(offset: Long, expected: ByteArray, patch: ByteArray, what: String) {
                val current = ByteArray(expected.size)
                f.seek(offset)
                f.readFully(current)
                check(current.contentEquals(expected)) {
                    "$what: unexpected bytes at 0x${offset.toString(16)} — " +
                        "got ${current.joinToString("") { "%02x".format(it) }}, " +
                        "want ${expected.joinToString("") { "%02x".format(it) }} " +
                        "(binary is not the supported 3.5.1 build?)"
                }
                f.seek(offset)
                f.write(patch)
            }

            apply(SKIPS_GETTER_OFFSET, SKIPS_GETTER_ORIGINAL, SKIPS_GETTER_PATCH, "GetNumSurvivorCooldownSkips")
            apply(COOLDOWN_GATE_OFFSET, COOLDOWN_GATE_ORIGINAL, COOLDOWN_GATE_PATCH, "IsSurvivalModeCooldownInEffect")
        }
    }
}
