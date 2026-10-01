package com.shortsmaker.viral

import android.app.Application

class ShortsMakerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
