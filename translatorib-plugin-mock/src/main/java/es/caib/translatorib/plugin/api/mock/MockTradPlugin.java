package es.caib.translatorib.plugin.api.mock;

import es.caib.translatorib.service.model.*;
import es.caib.translatorib.plugin.api.ITraduccionPlugin;
import org.fundaciobit.pluginsib.core.utils.AbstractPluginProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Implementacion Mockup.
 *
 * @author Indra
 *
 */
public class MockTradPlugin extends AbstractPluginProperties implements ITraduccionPlugin {

	/**
	 * Constructor
	 * @param prefijoPropiedades Prefijo propiedades
	 * @param properties Propiedades
	 */
	public MockTradPlugin(final String prefijoPropiedades, final Properties properties) {
		super(prefijoPropiedades, properties);
	}

	/** Prefix. */
	public static final String IMPLEMENTATION_BASE_PROPERTY = "mock.";

	@Override
	public ResultadoTraduccionTexto realizarTraduccion(final String textoEntrada, final TipoEntrada tipoEntrada,
			final Idioma idiomaEntrada, final Idioma idiomaSalida, final Opciones opciones) {
		final ResultadoTraduccionTexto resultado = new ResultadoTraduccionTexto();
		resultado.setError(false);
		String textoTraducido = textoEntrada + " (MOCKUP) ";
		if (getProperty("mock.SYSTEM_PRUEBA") != null) {
			textoTraducido+=" SYSTEM_PRUEBA:" + getProperty("mock.SYSTEM_PRUEBA");
		}
		if (getProperty("mock.CONFIG_PRUEBA") != null) {
			textoTraducido+=" CONFIG_PRUEBA:" + getProperty("mock.CONFIG_PRUEBA");
		}
		resultado.setTextoTraducido(textoTraducido);
		return resultado;
	}

	@Override
	public ResultadoTraduccionDocumento realizarTraduccionDocumento(final String documentoEntradaB64,
			final TipoDocumento tipoDocumento, final Idioma idiomaEntrada, final Idioma idiomaSalida,
			final Opciones opciones) {
		final ResultadoTraduccionDocumento resultado = new ResultadoTraduccionDocumento();
		resultado.setError(false);
		resultado.setTextoTraducido("Hola");
		return resultado;
	}

	@Override
	public List<ParejaIdiomas> getParesIdiomasPermitidos() {

		List<ParejaIdiomas> parejas = new ArrayList<ParejaIdiomas>();

		for (Idioma origen : Idioma.values()) {
			for (Idioma destino : Idioma.values()) {
				if (origen != destino) {
					parejas.add(new ParejaIdiomas(origen, destino));
				}
			}
		}

		return parejas;
	}
}
