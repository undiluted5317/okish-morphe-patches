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
// PurchaseHPBoost), so a tail match like this catches all of them.
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
 * Injustice: Gods Among Us — Last Laugh Tickets (Menu)
 *
 * Awards $TICKETS_AT_MAIN_MENU Last Laugh ("Joker's Wild") tickets when the main menu is
 * entered, from the Java side of the JNI boundary using the game's own console-command
 * bridge (AddJokerTickets → UInjusticeFrontendCheatManager::AddJokerTickets →
 * UPlayerSaveData::IncrementNumJokersWildTickets, the sole ticket-increment path in
 * libInjusticeGAU.so — see analysis/injustice/notes/survivor-last-laugh.md).
 *
 * Anchor: the START of JavaCallback_AlreadyEnterMainMenu()V (UE3JavaApp.smali:5932) — a
 * native→Java callback the engine invokes at the moment the main menu is entered, so the
 * engine and frontend are provably running (the same lifecycle moment the game's own
 * Swrve command bridge runs; its name is a JNI GetMethodID string and can never be
 * renamed). The first revision granted from ContinueOnCreate and crashed instantly:
 * the command bridge dereferences engine state that does not exist during startup.
 *
 * Register budget: .registers 3 (v0, v1, p0). The block is prepended and uses only v0,
 * which the original first instruction re-defines immediately (`const/4 v0, 0x1`).
 *
 * Split from the per-battle grant so the two triggers can be enabled independently —
 * if anything ever crashes, the enabled half identifies the trigger.
 *
 * Risk: MEDIUM — RUNTIME VERIFY the console-command routing (see the per-battle patch);
 * WBID cloud-save sync is server-authoritative and may revert un-backed grants.
 */
@Suppress("unused")
val injusticeLastLaughMenuPatch = bytecodePatch(
    name = "Injustice Last Laugh Tickets (Menu)",
    description = "Awards 60 Last Laugh tickets every time the main menu is reached.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        MainMenuEnteredFingerprint.method.addInstructions(0, """
            const-string v0, "AddJokerTickets $TICKETS_AT_MAIN_MENU"
            invoke-static {v0}, $DEF_EG_ENGINE_CMD_REF
        """.trimIndent())
    }
}

/**
 * Injustice: Gods Among Us — Last Laugh Tickets (Per Battle)
 *
 * Awards $TICKETS_PER_SURVIVOR_EVENT Last Laugh tickets after every Survivor-mode game
 * event — the "invoke the grant multiple times after every survivor battle" decorator.
 *
 * Seam: JavaCallback_SwrveOnEvent(String, String)V (UE3JavaApp.smali:7830) — the
 * native→Java analytics funnel. Survivor battles flow through native
 * UInjusticeAnalytics::LogSurvivorMatchEnd and friends (MatchStart, LadderStart, Exit,
 * CashOut, ...), which compose event names via UNRSMultiAnalytics::MakeEventName and fan
 * out to the Java bridges through CallJava_SwrveOnEvent(wchar*, wchar*), landing here as
 * (eventName, payload).
 *
 * The original body is two instructions (`Swrve.OnEvent(p1, p2)` + `return-void`), with
 * .registers 3 (p0=this, p1=eventName, p2=payload) and NO locals. The block is APPENDED
 * after the OnEvent invoke, so the analytics payload is already delivered and the
 * parameters are free scratch afterwards.
 *
 * Register/verifier discipline (the previous revision crashed the app by merging p2 as
 * int on one path and object on the other — an ART verifier conflict in UE3JavaApp):
 *   - p1 (eventName) is used as the receiver, then becomes the boolean and stays int on
 *     every path afterwards. It is null-checked first: native analytics may deliver a
 *     null name, and an unguarded invoke-virtual would NPE inside a JNI callback.
 *   - p2 only ever holds objects (payload / "urvivor" / the command string), so the
 *     merge at the branch target is type-consistent.
 *
 * Risk: MEDIUM — RUNTIME VERIFY: assumes `AddJokerTickets` is routed to
 * UInjusticeFrontendCheatManager by the engine's command dispatcher (the class + exec
 * bindings ship in the .so). To verify: install with only this patch, finish a Survivor
 * battle, and check the Last Laugh ticket counter moved by $TICKETS_PER_SURVIVOR_EVENT.
 * WBID cloud-save caveat applies as for the repo's other Injustice patches.
 */
@Suppress("unused")
val injusticeLastLaughBattlePatch = bytecodePatch(
    name = "Injustice Last Laugh Tickets (Per Battle)",
    description = "Awards 60 Last Laugh tickets after every Survivor mode event (match start/end, cash out, ...).",
    default = true
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        val swrve = SwrveEventFingerprint.method
        val anchor = swrve.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_STATIC &&
                getReference<MethodReference>()?.name == "OnEvent"
        }
        swrve.addInstructions(anchor + 1, """
            if-eqz p1, :cond_done
            const-string p2, "$SURVIVOR_EVENT_MARKER"
            invoke-virtual {p1, p2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z
            move-result p1
            if-eqz p1, :cond_done
            const-string p2, "AddJokerTickets $TICKETS_PER_SURVIVOR_EVENT"
            invoke-static {p2}, $DEF_EG_ENGINE_CMD_REF
            :cond_done
        """.trimIndent())
    }
}
