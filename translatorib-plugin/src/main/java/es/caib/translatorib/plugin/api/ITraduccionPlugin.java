package es.caib.translatorib.plugin.api;

import es.caib.translatorib.service.model.*;
import org.fundaciobit.pluginsib.core.IPlugin;

import java.util.List;

/**
 * Interface pasarela traducción.
 *
 * @author Indra
 *
 */
public interface ITraduccionPlugin extends IPlugin {

	/** Prefix. */
	public static final String TRANSLATOR_BASE_PROPERTY = "es.caib.translatorib.";

	/** PLUGINS **/
	public static final String PLUGIN_OPENTRAD = "opentrad";

	public static final String PLUGIN_PLATA = "plata";

	/**
	 * Realizar traduccion de un texto.
	 *
	 * @param textoEntrada Texto de entrada
	 * @param tipoEntrada Tipo de entrada
	 * @param idiomaEntrada Idioma entrada
	 * @param idiomaSalida Idioma salida
	 * @param opciones Opciones
	 * @return
	 * @throws TraduccionException
	 */
	ResultadoTraduccionTexto realizarTraduccion(String textoEntrada, TipoEntrada tipoEntrada, Idioma idiomaEntrada,
			Idioma idiomaSalida, Opciones opciones) throws TraduccionException;

	/**
	 * Realizar traducción de un documento.
	 *
	 * @param documentoEntradaB64 Documento entrada b64
	 * @param tipoDocumento Tipo Documento
	 * @param idiomaEntrada Idioma entrada
	 * @param idiomaSalida Idioma salida
	 * @param opciones Opciones
	 * @return
	 */
	ResultadoTraduccionDocumento realizarTraduccionDocumento(final String documentoEntradaB64,
			final TipoDocumento tipoDocumento, final Idioma idiomaEntrada, final Idioma idiomaSalida,
			final Opciones opciones) throws TraduccionException;


	/**
	 * Devuelve la lista de  parejas permitidos por idioma.
	 * @return La lista de parejas idoma.
	 */
	List<ParejaIdiomas> getParesIdiomasPermitidos();
}
