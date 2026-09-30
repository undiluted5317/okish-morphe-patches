package app.injustice.patches.tickets

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * com.epicgames.virtuos.UnrealEngine3.UE3JavaApp.JavaCallback_AlreadyEnterMainMenu()V —
 * package-private, called BY the native engine (JNI GetMethodID) at the moment the main
 * menu is entered.
 *
 * This is the crash fix for the first revision of this patch, which granted from
 * ContinueOnCreate(Z)V and instantly crashed the app: the console-command bridge
 * (NativeCallback_DefeGEngineCmd) forwards into the engine's command path, which
 * dereferences its engine/player-controller state unguarded — state that does not exist
 * yet during ContinueOnCreate (startup), but always exists once the main menu is up.
 *
 * Why this anchor is safe and stable:
 *   - native→Java callback: the engine calls this Java method, so by construction the
 *     engine and frontend are fully running when it executes (same lifecycle moment the
 *     game's own Swrve command bridge — Swrve$2.onAction → NativeCallback_DefeGEngineCmd
 *     — is used at runtime, see Swrve$2.smali:60).
 *   - the method name is referenced by the .so's JNI GetMethodID strings
 *     (libInjusticeGAU.so, "JavaCallback_AlreadyEnterMainMenu"), so it can never be
 *     renamed/obfuscated.
 *   - the body's first two instructions re-define v0 immediately, so prepending a block
 *     that uses only v0 is register-safe (verified smali below).
 *
 * Confirmed smali: classes.dex
 * com/epicgames/virtuos/UnrealEngine3/UE3JavaApp.smali:5932
 * (`.method JavaCallback_AlreadyEnterMainMenu()V`, .registers 3):
 *
 *   const/4 v0, 0x1
 *   iput-boolean v0, p0, UE3JavaApp;->isAlreadyEnterMainMenu:Z
 *   iput-boolean v0, p0, UE3JavaApp;->FlagTOUIFirstTime:Z
 *   ...
 *   invoke-static {}, Lcom/epicgames/virtuos/UnrealEngine3/MicroTransaction;->resolvePendingTransaction()V
 *   return-void
 *
 * Filters are in exact instruction order (verified against smali): the literal 1
 * (const/4 v0, 0x1) and the trailing resolvePendingTransaction() call. The class +
 * non-obfuscated method name + ()V signature pin the method uniquely.
 */
object MainMenuEnteredFingerprint : Fingerprint(
    definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;",
    name = "JavaCallback_AlreadyEnterMainMenu",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        literal(1),
        methodCall(
            definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/MicroTransaction;",
            name = "resolvePendingTransaction",
        ),
    )
)

/**
 * com.epicgames.virtuos.UnrealEngine3.UE3JavaApp.JavaCallback_SwrveOnEvent(String, String)V —
 * public, called BY the native engine for every analytics event.
 *
 * This is the per-battle seam: the native UInjusticeAnalytics::Log* family (which
 * includes LogSurvivorMatchEnd, LogSurvivorMatchStart, LogSurvivorLadderStart,
 * LogSurvivorExit, LogSurvivorCashOut — see analysis/injustice/notes/survivor-last-laugh.md)
 * composes event names via UNRSMultiAnalytics::MakeEventName and fans them out to the
 * Java SDK bridges through CallJava_SwrveOnEvent(wchar*, wchar*) (libInjusticeGAU.so),
 * which lands here as (eventName, payload).
 *
 * The whole body is two instructions — the Swrve fan-out and the return:
 *
 *   invoke-static {p1, p2}, Lcom/epicgames/virtuos/UnrealEngine3/Swrve;->OnEvent(Ljava/lang/String;Ljava/lang/String;)V
 *   return-void
 *
 * .registers 3 (p0=this, p1=eventName, p2=payload) — no locals. The patch APPENDS its
 * block after the OnEvent invoke (see InjusticeLastLaughTicketsPatch), so the analytics
 * payload has already been delivered and p1/p2 can be reused as scratch — no register
 * expansion needed. Filter "urvivor" is matched as a substring of the event name, which
 * is robust to MakeEventName composition ("Survivor" contains "urvivor").
 *
 * Filters in exact instruction order: the Swrve OnEvent call, then RETURN_VOID.
 * The class + non-obfuscated method name + (String, String)V signature pin the method
 * uniquely; the name is referenced by the .so's JNI GetMethodID strings.
 */
object SwrveEventFingerprint : Fingerprint(
    definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;",
    name = "JavaCallback_SwrveOnEvent",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/Swrve;",
            name = "OnEvent",
        ),
        opcode(Opcode.RETURN_VOID),
    )
)
