package com.example.jaecoovoice.engine

/**
 * VoiceEngineService answers only JaecooApp. The signature permission alone is not enough:
 * whichever app is installed first defines a permission both declare.
 */
object CallerCheck {
    const val JAECOO_PACKAGE = "com.example.jaecooapp"

    /** [packagesForUid] is `PackageManager.getPackagesForUid(Binder.getCallingUid())`. */
    fun isAllowed(packagesForUid: Array<String>?): Boolean = packagesForUid?.contains(JAECOO_PACKAGE) == true
}
