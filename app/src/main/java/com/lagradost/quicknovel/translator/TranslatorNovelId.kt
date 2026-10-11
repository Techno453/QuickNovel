package com.lagradost.quicknovel.translator

/**
 * Computes a stable novel ID for use as a primary key in the translator database.
 * Both the result screen (which has the URL) and the reader (which has meta)
 * use the same key: "${apiName}/${novelName}".
 */
object TranslatorNovelId {
    fun from(apiName: String, novelName: String): String = "$apiName/$novelName"
}
