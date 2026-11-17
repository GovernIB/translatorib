package es.caib.translatorib.ejb.facade;
import es.caib.translatorib.ejb.interceptor.ExceptionTranslate;
import es.caib.translatorib.ejb.interceptor.Logged;
import es.caib.translatorib.ejb.interceptor.NegocioInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import es.caib.translatorib.service.model.*;
import es.caib.translatorib.service.service.TraduccionService;

import javax.annotation.security.PermitAll;
import javax.ejb.Local;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;

import es.caib.translatorib.plugin.api.ITraduccionPlugin;
import es.caib.translatorib.plugin.api.TraduccionException;

@Logged
@ExceptionTranslate
@Stateless
@Local(TraduccionService.class)
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class TraduccionServiceBean implements TraduccionService {

	private static final Logger LOG = LoggerFactory.getLogger(TraduccionServiceBean.class);

	@Inject
	private PluginFactoryBean pluginFactoryBean;

	@Override
	@PermitAll
	@NegocioInterceptor
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public ResultadoTraduccionTexto realizarTraduccion(String textoEntrada, TipoEntrada tipoEntrada, Idioma idiomaEntrada, Idioma idiomaSalidad, String plugin, Opciones opciones) {
		final ITraduccionPlugin plg  = pluginFactoryBean.createPlugin(plugin);
		ResultadoTraduccionTexto res;
		if (plg == null) {
			res = new ResultadoTraduccionTexto();
			res.setError(true);
			res.setDescripcionError("No se ha podido cargar el plugin de traducción");
			return res;
		}
		try {
			res = plg.realizarTraduccion(textoEntrada, tipoEntrada, idiomaEntrada, idiomaSalidad, opciones);
		} catch (final TraduccionException exp) {
			LOG.error("Error realizando realizarTraduccion", exp);
			res = new ResultadoTraduccionTexto();
			res.setError(true);
			res.setDescripcionError(exp.getMessage());
		}
		return res;
	}

	@Override
	@PermitAll
	@NegocioInterceptor
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public ResultadoTraduccionDocumento realizarTraduccionDocumento(String contenidoDocumentoB64, TipoDocumento tipoDocumento, Idioma idiomaEntrada, Idioma idiomaSalidad, String plugin, Opciones opciones) {
		final ITraduccionPlugin plg = pluginFactoryBean.createPlugin(plugin);
		ResultadoTraduccionDocumento res;
		if (plg == null) {
			res = new ResultadoTraduccionDocumento();
			res.setError(true);
			res.setDescripcionError("No se ha podido cargar el plugin de traducción");
			return res;
		}
		try {
			res = plg.realizarTraduccionDocumento(contenidoDocumentoB64, tipoDocumento, idiomaEntrada, idiomaSalidad,
					opciones);
		} catch (final TraduccionException exp) {
			LOG.error("Error realizando realizarTraduccionDocumento", exp);
			res = new ResultadoTraduccionDocumento();
			res.setError(true);
			res.setDescripcionError(exp.getMessage());
		}
		return res;
	}


}