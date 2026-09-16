package io.github.sspanak.tt9.ui.notifications;

import android.content.Context;
import io.github.sspanak.tt9.languages.Language;

/**
 * T9-i-FUTO-patch: no-op-ersättning.
 *
 * Originalet (io.github.sspanak.tt9.ui.notifications.DictionaryLoadingBar) visar
 * TT9:s egen notifikationsdrivna progressbar under ordboksimport – den bygger på
 * TT9:s R-resurser (ikoner/strängar) som inte finns i FUTO:s res-träd.
 *
 * DictionaryLoader.java (db/words/) anropar denna klass för statusuppdateringar
 * under import men den logiska importen fungerar oavsett om progressen visas.
 * Ersätt gärna med en riktig implementation kopplad till FUTO:s egen UI
 * (t.ex. en Compose-baserad progress-indikator i inställningarna) längre fram.
 */
public class DictionaryLoadingBar {
	private static DictionaryLoadingBar instance;

	public static DictionaryLoadingBar getInstance(Context context) {
		if (instance == null) {
			instance = new DictionaryLoadingBar();
		}
		return instance;
	}

	public void showStart(int totalLanguages) {}
	public void showProgress(Language language, long elapsedMs, int currentFile, int progressPercent) {}
	public void showError(String errorType, Language language, long line) {}
	public void showCancelled() {}
}
