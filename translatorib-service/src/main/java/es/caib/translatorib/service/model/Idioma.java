package es.caib.translatorib.service.model;

import java.io.Serializable;

/***
 * Enumerado
 *
 * @author Indra
 *
 */
public enum Idioma implements Serializable {
	CASTELLANO("es", "es")/*, CASTELLANO_ESPANYA("es", "es_ES")*/, CATALAN("ca", "ca"),
	CATALAN_BALEAR("ca", "ca_ES");

	private final String idioma;
	private final String locale;

	/** Constructor. **/
	Idioma(final String iIdioma, final String ivalor) {
		this.idioma = iIdioma;
		this.locale = ivalor;
	}

	public String getIdioma() {
		return this.idioma;
	}

	public String getLocale() {
		return this.locale;
	}

	@Override
	public String toString() {
		return this.idioma + " " + this.locale;
	}

	/** From string. **/
	public static Idioma fromString(final String iIdioma, final String iValor) {
		Idioma idioma = null;
		for (final Idioma idi : Idioma.values()) {
			if (idi.getIdioma().equals(iIdioma) && idi.getLocale().equals(iValor)) {
				idioma = idi;
				break;
			}
		}
		return idioma;
	}

	/** From string. **/
	public static Idioma fromString(final String eIdioma) {
		Idioma idioma = null;
		String idioma1 = eIdioma.split(" ")[0];
		String idioma2 = eIdioma.split(" ")[1];
		for (final Idioma idi : Idioma.values()) {
			if (idi.getIdioma().equals(idioma1) && idi.getLocale().equals(idioma2)) {
				idioma = idi;
				break;
			}
		}
		return idioma;
	}

	public String getLiteral() {
		return "Idioma." + this.idioma + "__" + this.locale;
	}
}
