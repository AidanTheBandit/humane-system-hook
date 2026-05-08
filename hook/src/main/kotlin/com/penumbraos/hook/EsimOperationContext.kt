package com.penumbraos.hook

object EsimOperationContext {

    @Volatile
    var currentAction: String? = null

    @Volatile
    var currentRequestId: String? = null

    @Volatile
    var currentIccid: String? = null

    @Volatile
    var currentActivationCode: String? = null

    @Volatile
    var currentNickname: String? = null

    @Volatile
    var currentSource: String? = null

    @Volatile
    var currentDownloadIccid: String? = null

    fun clear() {
        currentAction = null
        currentRequestId = null
        currentIccid = null
        currentActivationCode = null
        currentNickname = null
        currentSource = null
        currentDownloadIccid = null
    }
}
