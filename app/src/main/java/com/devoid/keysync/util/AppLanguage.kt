package com.devoid.keysync.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.devoid.keysync.model.LanguagePreference

fun applyAppLanguage(preference: LanguagePreference) {
    val locales = LocaleListCompat.forLanguageTags(preference.languageTag)
    if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != locales.toLanguageTags()) {
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
