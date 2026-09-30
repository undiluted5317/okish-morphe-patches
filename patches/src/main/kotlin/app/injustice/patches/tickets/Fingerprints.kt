package app.injustice.patches.tickets

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.epicgames.virtuos.UnrealEngine3.UE3JavaApp.ContinueOnCreate(Z)V — private.
 *
 * The one-shot startup continuation (it even increments the "LAUNCH_TIMES_COUNT"
 * shared-preference counter inside its body). It is the game's post-startup init point:
 * the tail of the method configures Swrve and then calls
 *
 *   UE3JavaApp.NativeCallback_OnAppBecameActive()V
 *
 * which notifies the native engine that the app is active — proof the engine side is
 * initialized and accepting native calls at that exact instruction. The patch anchors
 * its grant right after that call (see InjusticeLastLaughTicketsPatch).
 *
 * Confirmed smali: classes.dex
 * com/epicgames/virtuos/UnrealEngine3/UE3JavaApp.smali:1191
 * (`.method private ContinueOnCreate(Z)V`, .registers 6). Evidence for the anchors:
 *   Swrve;->Configure(String, String) at UE3JavaApp.smali:1423
 *   NativeCallback_OnAppBecameActive()V at UE3JavaApp.smali:1427
 *
 * The "ERROR SWRVE NOT AVAILABLE" skip path (UE3JavaApp.smali:1412-1414) cannot be taken
 * on this build: getSwrveAPIKey() (UE3JavaApp.smali:4297) always returns "854"/"856",
 * so the anchor call runs on every launch.
 *
 * Filters are in exact instruction order (verified against smali): the Swrve Configure
 * call, then the NativeCallback_OnAppBecameActive call. The non-obfuscated class +
 * method name + (Z)V signature pin the method uniquely.
 */
object ContinueOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;",
    name = "ContinueOnCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/Swrve;",
            name = "Configure",
        ),
        methodCall(
            definingClass = "Lcom/epicgames/virtuos/UnrealEngine3/UE3JavaApp;",
            name = "NativeCallback_OnAppBecameActive",
        ),
    )
)
