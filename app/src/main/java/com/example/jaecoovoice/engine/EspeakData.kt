package com.example.jaecoovoice.engine

import java.io.File

/**
 * The one espeak-ng-data every Piper model uses, at `filesDir/espeak-ng-data`. Each Piper archive
 * carries its own copy: the first one installed is kept, later copies are dropped, and deleting
 * a model never touches it. Models installed by JaecooVoice 1.3.x still hold their copy in the
 * model folder; [adopt] moves it the first time the model is checked.
 */
object EspeakData {

    const val DIR_NAME = "espeak-ng-data"
    private const val PHONTAB = "phontab"

    fun dir(filesDir: File): File = File(filesDir, DIR_NAME)

    fun isPresent(dir: File): Boolean = File(dir, PHONTAB).length() > 0L

    /** Fails the install when a Piper archive is the first and has no espeak-ng-data. */
    fun checkArchive(model: TtsModel, filesDir: File, staging: File) {
        if (model.frontend != TtsFrontend.ESPEAK || isPresent(dir(filesDir))) return
        check(isPresent(File(staging, DIR_NAME))) { "Gói tải về thiếu espeak-ng-data" }
    }

    /** Moves `<modelDir>/espeak-ng-data` to the shared folder, or drops it when that exists. */
    @Synchronized
    fun adopt(model: TtsModel, filesDir: File, modelDir: File) {
        val own = File(modelDir, DIR_NAME)
        if (model.frontend != TtsFrontend.ESPEAK || !own.exists()) return
        val shared = dir(filesDir)
        if (!isPresent(shared) && isPresent(own)) {
            shared.deleteRecursively()
            if (!own.renameTo(shared)) {
                // Not on the same file system: copy beside, then swap in whole.
                val copy = File(filesDir, "$DIR_NAME.staging").apply { deleteRecursively() }
                own.copyRecursively(copy, overwrite = true)
                if (!copy.renameTo(shared)) copy.deleteRecursively()
            }
        }
        own.deleteRecursively()
    }
}
