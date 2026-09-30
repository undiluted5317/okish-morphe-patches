package app.injustice.patches.tickets

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// Last Laugh ("Joker's Wild") tickets granted per trigger. Change these to tune the
// amounts — they are interpolated verbatim into the injected console commands.
private const val TICKETS_AT_MAIN_MENU = 60
private const val TICKETS_PER_SURVIVOR_EVENT = 60

// Substring matched against native analytics event names. Survivor-mode events are
// composed by UNRSMultiAnalytics::MakeEventName from names that contain "Survivor"
// (LogSurvivorMatchEnd/MatchStart/LadderStart/Exit/CashOut/ModeClicked/PurchaseCooldown/
// PurchaseHPBoost), so a case-insensitive tail match like this catches all of them.
private const val SURVIVOR_EVENT_MARKER = "urvivor"

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
 * Awards Last Laugh ("Joker's Wild") tickets from the Java side of the JNI boundary,
 * using the game's own console-command bridge — twice:
 *
 *   1. $TICKETS_AT_MAIN_MENU tickets when the main menu is entered
 *      (JavaCallback_AlreadyEnterMainMenu — the native→Java callback the engine invokes
 *      when the frontend is up).
 *   2. $TICKETS_PER_SURVIVOR_EVENT tickets after every Survivor-mode game event
 *      (JavaCallback_SwrveOnEvent — the native analytics funnel). Survivor battles flow
 *      through UInjusticeAnalytics::LogSurvivorMatchEnd and friends, so each battle
 *      start/end, ladder start, cash-out etc. fires a grant. This is the "invoke the
 *      grant multiple times after every survivor battle" decorator: the hook wraps the
 *      native→Java event call, matches "urvivor" in the event name, and re-enters the
 *      native grant.
 *
 * Background (from native analysis of libInjusticeGAU.so, see
 * analysis/injustice/notes/survivor-last-laugh.md): the Last Laugh minigame consumes
 * "Joker tickets" (UPlayerSaveData.GetNumJokersWildTickets / Increment / Decrement); the
 * ticket balance has no Java surface at all — awarding happens natively. The shipping
 * cheat console provides the one Java-reachable grant: the exec command
 * "AddJokerTickets" (UInjusticeFrontendCheatManager::AddJokerTickets →
 * UPlayerSaveData::IncrementNumJokersWildTickets, the sole ticket-increment path).
 *
 * Register budget:
 *   - JavaCallback_AlreadyEnterMainMenu: .registers 3 (v0, v1, p0). The block is
 *     prepended and uses only v0, which the original first instruction re-defines
 *     immediately (`const/4 v0, 0x1`).
 *   - JavaCallback_SwrveOnEvent: .registers 3 (p0, p1, p2) — no locals. The block is
 *     APPENDED after the original `Swrve.OnEvent(p1, p2)` call, so the analytics payload
 *     is already delivered and p1/p2 are free scratch (the event name is consumed first,
 *     the payload last). No register expansion, no clobbered live values.
 *
 * Risk: MEDIUM —
 *   1. RUNTIME VERIFY: assumes `AddJokerTickets` is routed by the engine's
 *      console-command dispatcher to UInjusticeFrontendCheatManager in this shipping
 *      build (the class + exec bindings ship in the .so). To verify: install, reach the
 *      main menu, and check the Last Laugh ticket counter moved by $TICKETS_AT_MAIN_MENU;
 *      finish a Survivor battle and check it moved again by $TICKETS_PER_SURVIVOR_EVENT.
 *   2. Same caveat as the repo's other Injustice patches: WBID cloud-save sync is
 *      server-authoritative; un-backed grants may be reverted on a cloud-save load.
 */
@Suppress("unused")
val injusticeLastLaughTicketsPatch = bytecodePatch(
    name = "Injustice Last Laugh Tickets",
    description = "Awards 60 Last Laugh tickets at the main menu and 60 more after every Survivor mode event (match start/end, cash out, ...).",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        // (1) Main-menu entry grant.
        MainMenuEnteredFingerprint.method.addInstructions(0, """
            const-string v0, "AddJokerTickets $TICKETS_AT_MAIN_MENU"
            invoke-static {v0}, $DEF_EG_ENGINE_CMD_REF
        """.trimIndent())

        // (2) Per-Survivor-event grant, appended after the analytics fan-out call.
        val swrve = SwrveEventFingerprint.method
        val anchor = swrve.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_STATIC &&
                getReference<MethodReference>()?.name == "OnEvent"
        }
        swrve.addInstructions(anchor + 1, """
            const-string p2, "$SURVIVOR_EVENT_MARKER"
            invoke-virtual {p1, p2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
            move-result p2
            if-eqz p2, :cond_done
            const-string p2, "AddJokerTickets $TICKETS_PER_SURVIVOR_EVENT"
            invoke-static {p2}, $DEF_EG_ENGINE_CMD_REF
            :cond_done
        """.trimIndent())
    }
}
