package com.shortsmaker.viral.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestiona el consentimiento (Google UMP, obligatorio en EEE/Reino Unido) y la inicialización de AdMob.
 * Los anuncios NO se solicitan hasta que el consentimiento lo permite (`canRequestAds`).
 */
class AdsManager(private val appContext: Context) {
    private val initialized = AtomicBoolean(false)
    private val _canShowAds = MutableStateFlow(false)
    val canShowAds: StateFlow<Boolean> = _canShowAds.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private val consentInfo: ConsentInformation get() = UserMessagingPlatform.getConsentInformation(appContext)

    /** Llamar en `onCreate` de la actividad: actualiza el estado de consentimiento y muestra el formulario si hace falta. */
    fun gatherConsent(activity: Activity) {
        // Si ya hay consentimiento de una sesión anterior se pueden pedir anuncios de inmediato.
        refreshState()
        consentInfo.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { refreshState() }
            },
            { refreshState() },
        )
    }

    /** Formulario "Opciones de privacidad de anuncios" (debe ofrecerse en Ajustes cuando sea requerido). */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { refreshState() }
    }

    private fun refreshState() {
        val info = consentInfo
        _privacyOptionsRequired.value =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (info.canRequestAds()) {
            initializeSdk()
            _canShowAds.value = true
        }
    }

    private fun initializeSdk() {
        if (!initialized.compareAndSet(false, true)) return
        // Fuera del hilo principal, como recomienda AdMob.
        CoroutineScope(Dispatchers.IO).launch { MobileAds.initialize(appContext) { } }
    }
}
