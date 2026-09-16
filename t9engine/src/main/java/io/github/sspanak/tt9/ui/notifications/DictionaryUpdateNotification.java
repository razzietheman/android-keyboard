package io.github.sspanak.tt9.ui.notifications;

import android.content.Context;
import io.github.sspanak.tt9.languages.Language;

/**
 * T9-i-FUTO-patch: no-op-ersättning (se DictionaryLoadingBar.java för motivering).
 * Originalet visar en Android-notis om att en ordbok bör uppdateras.
 */
public class DictionaryUpdateNotification {
	public DictionaryUpdateNotification(Context context, Language language) {}
	public void show() {}
}
