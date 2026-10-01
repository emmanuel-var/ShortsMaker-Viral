package com.shortsmaker.viral.data

import android.content.Context
import androidx.annotation.StringRes

/** Error con mensaje localizable para mostrar al usuario. */
class AppException(
    @StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
    cause: Throwable? = null,
) : Exception("app error $messageRes", cause) {
    fun localized(context: Context): String = context.getString(messageRes, *formatArgs.toTypedArray())
}
