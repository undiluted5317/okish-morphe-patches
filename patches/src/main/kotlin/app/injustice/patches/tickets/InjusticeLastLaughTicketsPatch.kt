package app.injustice.patches.tickets

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Last Laugh ("Joker's Wild") tickets granted per game launch. Change this to tune the
// amount — it is interpolated verbatim into the injected console command below.
private const val TICKETS_PER_LAUNCH = 60

// Smali reference to the JNI console-command bridge. Verified against classes.dex:
//   UE3JavaApp.NativeCallback_DefeGEngineCmd(Ljava/lang/String;)V (public static native,
//   UE3JavaApp.smali:2239) — implemented natively as
//   NativeCallback_DefeGEngineCmd(JNIEnv*, jobject, jstring) in libInjusticeGAU.so
//   (dynsym 0x1341f68), which forwards the string into the engine command line.
private const val DEF_EG_ENGINE_CMD_REF =
    "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;->NativeCallback_DefeGEngineCmd(Ljava/lang/String;)V"

/**
 * Injustice: Gods Among Us — Last Laugh Tickets
 *
 * Awards $TICKETS_PER_LAUNCH Last Laugh ("Joker's Wild") tickets on every game launch by
 * running the native console command `AddJokerTickets <n>` through the engine's own
 * command bridge.
 *
 * Background (from native analysis of libInjusticeGAU.so, see
 * analysis/injustice/notes/survivor-last-laugh.md): the Last Laugh minigame consumes
 * "Joker tickets" (UPlayerSaveData.GetNumJokersWildTickets / Increment / Decrement); the
 * ticket balance has no Java surface at all — awarding happens natively, so a DEX patch
 * cannot scale each award. Instead this patch uses the shipping cheat console:
 * UInjusticeFrontendCheatManager.AddJokerTickets(int) (exported dynsym, exec command name
 * "AddJokerTickets") feeds UPlayerSaveData.IncrementNumJokersWildTickets(int) — the sole
 * ticket-increment path in the binary.
 *
 * Injection point: immediately AFTER the game's own
 * `UE3JavaApp.NativeCallback_OnAppBecameActive()V` call inside ContinueOnCreate(Z)V
 * (UE3JavaApp.smali:1427) — the exact instruction where the game notifies the native
 * engine that startup completed, so the engine is provably initialized and accepting
 * native calls when the grant fires (no early-resume crash window). ContinueOnCreate runs
 * once per launch (it bumps "LAUNCH_TIMES_COUNT", UE3JavaApp.smali:1436-1440).
 *
 * Register budget: ContinueOnCreate declares .registers 6 (v0-v3 + p0/p1). The injected
 * block uses only v2, which is dead at the anchor (its next definition is
 * `const-string v2, "LAUNCH_TIMES_COUNT"` at UE3JavaApp.smali:1437); v0 stays live for
 * getSharedPreferences's mode arg and v1 feeds the launch-counter add — both untouched.
 * p0/p1 untouched, .registers unchanged.
 *
 * Risk: MEDIUM —
 *   1. RUNTIME VERIFY: this assumes `AddJokerTickets` is routed by the engine's
 *      console-command dispatcher to UInjusticeFrontendCheatManager in this shipping
 *      build. The class + exec bindings ship in the .so (also
 *      AddPotentialSurvivorRewards / SurvivorMode cheats), so routing is likely, but it
 *      must be confirmed on-device once: launch, open the Last Laugh screen, and check
 *      the ticket counter moved by $TICKETS_PER_LAUNCH.
 *   2. Same caveat as the repo's other Injustice patches: WBID cloud-save sync is
 *      server-authoritative; un-backed grants may be reverted on a cloud-save load.
 */
@Suppress("unused")
val injusticeLastLaughTicketsPatch = bytecodePatch(
    name = "Injustice Last Laugh Tickets",
    description = "Awards 60 Last Laugh tickets every time the game launches.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        // Anchor on the NativeCallback_OnAppBecameActive() call and inject right after it.
        val anchorIndex = ContinueOnCreateFingerprint.method.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_STATIC &&
                getReference<MethodReference>()?.name == "NativeCallback_OnAppBecameActive"
        }

        ContinueOnCreateFingerprint.method.addInstructions(anchorIndex + 1, """
            const-string v2, "AddJokerTickets $TICKETS_PER_LAUNCH"
            invoke-static {v2}, $DEF_EG_ENGINE_CMD_REF
        """.trimIndent())
    }
}
