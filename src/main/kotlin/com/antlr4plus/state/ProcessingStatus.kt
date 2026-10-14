package com.antlr4plus.state

sealed class ProcessingStatus {
    object Idle : ProcessingStatus()

    data object Loading : ProcessingStatus()

    data object WaitingForVerification : ProcessingStatus()

    data object Success : ProcessingStatus()

    data object Failed : ProcessingStatus()

    data object Cancelled : ProcessingStatus()
}
