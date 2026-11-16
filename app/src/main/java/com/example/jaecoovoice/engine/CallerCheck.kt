package com.example.jaecoovoice.engine

/**
 * VoiceEngineService answers only JaecooApp and JaecooAssistant. The signature permission alone is not enough:
 * whichever app is installed first defines a permission both declare.
 */
object CallerCheck {
    const val JAECOO_PACKAGE = "com.example.jaecooapp"
    const val ASSISTANT_PACKAGE = "com.example.jaecooassistant"

    /** [packagesForUid] is `PackageManager.getPackagesForUid(Binder.getCallingUid())`. */
    fun isAllowed(packagesForUid: Array<String>?): Boolean = packagesForUid?.any { it == JAECOO_PACKAGE || it == ASSISTANT_PACKAGE } == true
}
