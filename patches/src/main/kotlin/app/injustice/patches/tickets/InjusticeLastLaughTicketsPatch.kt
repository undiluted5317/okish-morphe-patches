package app.injustice.patches.tickets

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE

// Last Laugh ("Joker's Wild") tickets granted per main-menu entry. Change this to tune the
// amount — it is interpolated verbatim into the injected console command below.
private const val TICKETS_PER_GRANT = 60

// Smali reference to the JNI console-command bridge. Verified against classes.dex:
//   UE3JavaApp.NativeCallback_DefeGEngineCmd(Ljava/lang/String;)V (public static native,
//   UE3JavaApp.smali:2239) — implemented natively as
//   NativeCallback_DefeGEngineCmd(JNIEnv*, jobject, jstring) in libInjusticeGAU.so
//   (dynsym 0x1341f68), which forwards the string into the engine command line. The game
//   itself uses this exact channel at runtime: Swrve$2.onAction(String) passes Swrve
//   in-app-message button actions straight to it (Swrve$2.smali:60).
private const val DEF_EG_ENGINE_CMD_REF =
    "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;->NativeCallback_DefeGEngineCmd(Ljava/lang/String;)V"

/**
 * Injustice: Gods Among Us — Last Laugh Tickets
 *
 * Awards $TICKETS_PER_GRANT Last Laugh ("Joker's Wild") tickets every time the main menu
 * is entered, by running the native console command `AddJokerTickets <n>` through the
 * engine's own command bridge.
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
 * Injection point: the START of
 * UE3JavaApp.JavaCallback_AlreadyEnterMainMenu()V (UE3JavaApp.smali:5932) — a native→Java
 * callback the engine invokes at the moment the main menu is entered.
 *
 * History / crash fix: the first revision injected into ContinueOnCreate(Z)V right after
 * NativeCallback_OnAppBecameActive() and instantly crashed the app. Root cause: the
 * console-command bridge forwards into the engine command path, which dereferences its
 * engine/player-controller state unguarded (libInjusticeGAU.so: `*global + 0x4c4` call,
 * dynsym 0x1341f68) — that state does not exist yet during startup. JavaCallback_
 * AlreadyEnterMainMenu is called BY the engine itself (its name is a JNI GetMethodID
 * string in the .so, so it can never be renamed), guaranteeing the engine and frontend
 * are fully running at grant time — the same lifecycle moment the game's own Swrve
 * command bridge runs.
 *
 * Register budget: JavaCallback_AlreadyEnterMainMenu declares .registers 3 (v0, v1, p0).
 * The injected block is prepended and uses only v0, which the original first instruction
 * re-defines immediately (`const/4 v0, 0x1`, UE3JavaApp.smali:5935) — nothing is
 * clobbered. p0 untouched, .registers unchanged.
 *
 * Risk: LOW-MEDIUM —
 *   1. RUNTIME VERIFY: assumes `AddJokerTickets` is routed by the engine's
 *      console-command dispatcher to UInjusticeFrontendCheatManager in this shipping
 *      build (the class + exec bindings ship in the .so). To verify: install, reach the
 *      main menu, and check the Last Laugh ticket counter moved by $TICKETS_PER_GRANT.
 *   2. Same caveat as the repo's other Injustice patches: WBID cloud-save sync is
 *      server-authoritative; un-backed grants may be reverted on a cloud-save load.
 */
@Suppress("unused")
val injusticeLastLaughTicketsPatch = bytecodePatch(
    name = "Injustice Last Laugh Tickets",
    description = "Awards 60 Last Laugh tickets every time the main menu is reached.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        MainMenuEnteredFingerprint.method.addInstructions(0, """
            const-string v0, "AddJokerTickets $TICKETS_PER_GRANT"
            invoke-static {v0}, $DEF_EG_ENGINE_CMD_REF
        """.trimIndent())
    }
}
