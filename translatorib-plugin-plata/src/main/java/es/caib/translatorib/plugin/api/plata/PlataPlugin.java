package es.caib.translatorib.plugin.api.plata;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.WebServiceException;

import es.caib.translatorib.service.model.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.fundaciobit.pluginsib.core.utils.AbstractPluginProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.caib.translatorib.plata.rest.cxf.CustomFileResponse;
import es.caib.translatorib.plata.rest.cxf.TranslatorV2;
import es.caib.translatorib.plata.rest.cxf.TranslatorV2Service;
import es.caib.translatorib.plugin.api.ITraduccionPlugin;
import es.caib.translatorib.plugin.api.TraduccionException;

/**
 * Interfaz plata
 *
 * @author Indra
 *
 */
public class PlataPlugin extends AbstractPluginProperties implements ITraduccionPlugin {

	private static final Logger LOG = LoggerFactory.getLogger(PlataPlugin.class);

	/**
	 * Reutilizamos una unica instancia del servicio SOAP para evitar recrear
	 * metadatos y estructura del cliente en cada peticion.
	 */
	private static final TranslatorV2Service SOAP_SERVICE = new TranslatorV2Service();

	/**
	 * Pool compartido por todas las instancias del plugin (PluginFactoryBean crea
	 * un objeto PlataPlugin en cada peticion REST). Cada puerto solo se presta a
	 * una peticion a la vez; los proxies JAX-WS no se comparten concurrentemente.
	 *
	 * Hay un pool por URL/timeout, por lo que RRNN y el endpoint normal no se
	 * mezclan. La configuracion del proxy HTTP de la JVM se asume estable.
	 */
	private static final int MAX_CLIENTES_POR_ENDPOINT = 4;
	private static final long ESPERA_POOL_SEGUNDOS = 30L;
	/** Solo se registra WARN si la espera del pool supera este umbral. */
	private static final long UMBRAL_ESPERA_MS = 500L;
	/** Detecta traducciones que mantienen un puerto ocupado mucho tiempo. */
	private static final long UMBRAL_USO_MS = 5000L;
	private static final ConcurrentMap<String, PoolClientePlata> POOLS =
			new ConcurrentHashMap<String, PoolClientePlata>();

	private static final class PoolClientePlata {
		private final String url;
		private final long timeoutMs;
		private final Semaphore permisos = new Semaphore(MAX_CLIENTES_POR_ENDPOINT, true);
		private final ConcurrentLinkedQueue<TranslatorV2> disponibles =
				new ConcurrentLinkedQueue<TranslatorV2>();
		private boolean cerrado;

		PoolClientePlata(final String url, final long timeoutMs) {
			this.url = url;
			this.timeoutMs = timeoutMs;
		}

		PrestamoPlata adquirir() throws Exception {
			// Medir solo la espera del semaforo, separada del tiempo SOAP.
			final long inicioEsperaNs = System.nanoTime();
			final boolean adquirido;
			try {
				adquirido = permisos.tryAcquire(ESPERA_POOL_SEGUNDOS, TimeUnit.SECONDS);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				LOG.warn("[PLATA POOL] Espera interrumpida; endpoint={}, enCola={}",
						url, permisos.getQueueLength());
				throw e;
			}
			final long esperaMs = TimeUnit.NANOSECONDS.toMillis(
					System.nanoTime() - inicioEsperaNs);

			if (!adquirido) {
				LOG.error("[PLATA POOL] SATURADO: espera={} ms, max={}, "
						+ "disponibles={}, enCola={}, endpoint={}",
						esperaMs, MAX_CLIENTES_POR_ENDPOINT, permisos.availablePermits(),
						permisos.getQueueLength(), url);
				throw new IllegalStateException(
						"Pool SOAP PLATA saturado: mas de " + MAX_CLIENTES_POR_ENDPOINT
								+ " traducciones concurrentes para el mismo endpoint");
			}

			if (esperaMs >= UMBRAL_ESPERA_MS) {
				LOG.warn("[PLATA POOL] Espera elevada: espera={} ms, "
						+ "ocupados={}, disponibles={}, enCola={}, endpoint={}",
						esperaMs, MAX_CLIENTES_POR_ENDPOINT - permisos.availablePermits(),
						permisos.availablePermits(), permisos.getQueueLength(), url);
			}

			try {
				final TranslatorV2 port;
				synchronized (this) {
					if (cerrado) {
						throw new IllegalStateException("Pool SOAP PLATA cerrado");
					}
					port = disponibles.poll();
				}

				if (port != null) {
					LOG.debug("Reutilizando un puerto SOAP PLATA del pool");
					return new PrestamoPlata(this, port);
				}

				final TranslatorV2 creado = crearPuerto(url, timeoutMs);
				LOG.info("Nuevo puerto SOAP PLATA creado (maximo por endpoint: {})",
						MAX_CLIENTES_POR_ENDPOINT);
				return new PrestamoPlata(this, creado);
			} catch (final Exception | Error e) {
				permisos.release();
				throw e;
			}
		}

		void devolver(final TranslatorV2 port, final boolean invalidado) {
			final boolean destruir;
			synchronized (this) {
				destruir = invalidado || cerrado;
				if (!destruir) {
					disponibles.offer(port);
				}
			}
			try {
				if (destruir) {
					destroyClientePlata(port);
				}
			} finally {
				permisos.release();
			}
		}

		void cerrar() {
			final List<TranslatorV2> paraDestruir = new ArrayList<TranslatorV2>();
			synchronized (this) {
				cerrado = true;
				TranslatorV2 port;
				while ((port = disponibles.poll()) != null) {
					paraDestruir.add(port);
				}
			}
			for (final TranslatorV2 port : paraDestruir) {
				destroyClientePlata(port);
			}
			// Un puerto en uso sera destruido cuando su peticion lo devuelva.
		}
	}

	/** Prestamo exclusivo de un proxy JAX-WS; cerrar siempre en finally. */
	private static final class PrestamoPlata implements AutoCloseable {
		private final PoolClientePlata pool;
		private final TranslatorV2 port;
		private final long inicioUsoNs = System.nanoTime();
		private boolean invalidado;
		private boolean devuelto;

		PrestamoPlata(final PoolClientePlata pool, final TranslatorV2 port) {
			this.pool = pool;
			this.port = port;
		}

		TranslatorV2 getPort() {
			return port;
		}

		void invalidar() {
			invalidado = true;
		}

		@Override
		public void close() {
			if (!devuelto) {
				devuelto = true;
				// Tiempo durante el que la peticion ha retenido el puerto (SOAP + preparacion).
				final long usoMs = TimeUnit.NANOSECONDS.toMillis(
						System.nanoTime() - inicioUsoNs);
				try {
					pool.devolver(port, invalidado);
				} finally {
					if (usoMs >= UMBRAL_USO_MS) {
						LOG.warn("[PLATA POOL] Puerto ocupado demasiado tiempo: "
								+ "uso={} ms, invalidado={}, disponibles={}, enCola={}, endpoint={}",
								usoMs, invalidado, pool.permisos.availablePermits(),
								pool.permisos.getQueueLength(), pool.url);
					}
				}
			}
		}
	}


	/** Prefix. */
	public static final String IMPLEMENTATION_BASE_PROPERTY = "plata.";

	public static final List<ParejaIdiomas> PARES_IDIOMAS_PERMITIDOS =
			Collections.unmodifiableList(Arrays.asList(
					new ParejaIdiomas(Idioma.CASTELLANO, Idioma.CATALAN),
					new ParejaIdiomas(Idioma.CASTELLANO, Idioma.CATALAN_BALEAR),
					new ParejaIdiomas(Idioma.CATALAN, Idioma.CASTELLANO),
					new ParejaIdiomas(Idioma.CATALAN, Idioma.CATALAN_BALEAR),
					new ParejaIdiomas(Idioma.CATALAN_BALEAR, Idioma.CASTELLANO),
					new ParejaIdiomas(Idioma.CATALAN_BALEAR, Idioma.CATALAN),
					new ParejaIdiomas(Idioma.CASTELLANO, Idioma.INGLES) /*,
					new ParejaIdiomas(Idioma.INGLES, Idioma.CASTELLANO)*/
			));

	/**
	 * Constructor
	 * @param prefijoPropiedades Prefijo propiedades
	 * @param properties Propiedades
	 */
	public PlataPlugin(final String prefijoPropiedades, final Properties properties) {
		super(prefijoPropiedades, properties);
	}

	/**
	 * Realiza la traduccion con plata
	 * @param codeTranslate Texto de entrada
	 * @param tipoEntrada Tipo de entrada
	 * @param idiomaEntrada Idioma entrada
	 * @param idiomaSalida Idioma salida
	 * @param opciones Opciones
	 * @return Resultado de la traduccion
	 * @throws TraduccionException Excepcion
	 */
	@Override
	public ResultadoTraduccionTexto realizarTraduccion(final String codeTranslate, final TipoEntrada tipoEntrada,
			final Idioma idiomaEntrada, final Idioma idiomaSalida, final Opciones opciones) throws TraduccionException {
		final ResultadoTraduccionTexto resultado = new ResultadoTraduccionTexto();
		final String url , userName, userPass;

		if ((idiomaSalida == Idioma.INGLES && idiomaEntrada == Idioma.CASTELLANO)
		 || (idiomaSalida == Idioma.CASTELLANO && idiomaEntrada == Idioma.INGLES)  )
		{
			url = getPropiedad("rrnn_url");
			userName = getPropiedad("rrnn_user");
			userPass = getPropiedad("rrnn_pass");
		} else {
			url = getPropiedad("url");
			userName = getPropiedad("user");
			userPass = getPropiedad("pass");
		}
		final Long timeout = Long.valueOf(getPropiedad("timeout"));


		long inicioPlata = System.currentTimeMillis();

		PrestamoPlata cliente = null;
		try {
			cliente = getClientePlata(url, timeout);
			final TranslatorV2 port = cliente.getPort();


			final java.lang.Boolean proxyCache = false;
			final java.lang.String translationEngine = "plata";
			final String documentBase64 = "";
			final java.lang.String languagePair = getIdioma(idiomaEntrada, idiomaSalida);

			final java.lang.Boolean ner = getNer(opciones);
			final java.lang.String markUnknown = getMarkunkonw(opciones);
			final java.lang.String checksum = "";
			final java.lang.String urlX = "";
			final java.lang.String dirbase = "";
			final java.lang.String contentType;
			if (tipoEntrada == TipoEntrada.XML) {
				contentType = "WXML";
			} else {
				contentType = tipoEntrada.toString();
			}
			final String textoResultado = port.translateString(proxyCache, translationEngine, null, languagePair, ner,
					contentType, markUnknown, codeTranslate, null, null, null, userName, userPass);

			LOG.info("PLATA - Traducción completada correctamente");
			LOG.info("Tiempo de respuesta PLATA: {} ms",
					System.currentTimeMillis() - inicioPlata);
			resultado.setError(false);
			resultado.setTextoTraducido(textoResultado);

		} catch (final Exception e) {
			if (cliente != null && esErrorDeTransporte(e)) {
				cliente.invalidar();
			}
			LOG.error("ERROR PLATA - URL: {}, idiomas: {} -> {}, tiempo: {} ms",
					url,
					idiomaEntrada,
					idiomaSalida,
					System.currentTimeMillis() - inicioPlata,
					e);
			resultado.setError(true);
			resultado.setDescripcionError(ExceptionUtils.getMessage(e));

		} finally {
			if (cliente != null) {
				cliente.close();
			}
		}

		return resultado;
	}

	@Override
	public ResultadoTraduccionDocumento realizarTraduccionDocumento(final String documentoEntradaB64,
			final TipoDocumento tipoDocumento, final Idioma idiomaEntrada, final Idioma idiomaSalida,
			final Opciones opciones) throws TraduccionException {
		final ResultadoTraduccionDocumento resultado = new ResultadoTraduccionDocumento();
		final String url, user, pass;
		if ((idiomaSalida == Idioma.INGLES && idiomaEntrada == Idioma.CASTELLANO)
				|| (idiomaSalida == Idioma.CASTELLANO && idiomaEntrada == Idioma.INGLES) )
		{
			url = getPropiedad("rrnn_url");
			user = getPropiedad("rrnn_user");
			pass = getPropiedad("rrnn_pass");

		} else {
			url = getPropiedad("url");
			user = getPropiedad("user");
			pass = getPropiedad("pass");
		}
		final Long timeout = Long.valueOf(getPropiedad("timeout"));

		PrestamoPlata cliente = null;
		try {
			cliente = getClientePlata(url, timeout);
			final TranslatorV2 port = cliente.getPort();

			final java.lang.Boolean proxyCache = false;
			final java.lang.String translationEngine = "plata";
			final java.lang.String languagePair = getIdioma(idiomaEntrada, idiomaSalida);

			final java.lang.Boolean ner = getNer(opciones);

			final java.lang.String markUnknown = getMarkunkonw(opciones);
			final java.lang.String urlX = null;
			final java.lang.String dirbase = null;
			final java.lang.String tipoDocumentoStr = getTipoDocumento(tipoDocumento);

			/*** IMPORTANTE, EL CHECKSUM EN MINUSCULAS ***/

			final byte[] targetArray = Base64.getDecoder().decode(documentoEntradaB64);
			final String valorChecksum = (new HexBinaryAdapter())
					.marshal(MessageDigest.getInstance("MD5").digest(targetArray)).toLowerCase();
			final CustomFileResponse textoResultado = port.translateFile(proxyCache, translationEngine,
					documentoEntradaB64, languagePair, ner, tipoDocumentoStr, markUnknown, null, valorChecksum, urlX,
					dirbase, user, pass);
			resultado.setError(false);
			final String textoTrad = textoResultado.getDocumentBase64().replaceAll("\n", "");
			resultado.setTextoTraducido(textoTrad);
			resultado.setDireccion(textoResultado.getDirectionOfTranslate());
			resultado.setChecksum(textoResultado.getDocumentChecksum());
			resultado.setTipo(textoResultado.getDocumentType());
			resultado.setDescripcionError(textoResultado.getError());

		} catch (final Exception e) {
			if (cliente != null && esErrorDeTransporte(e)) {
				cliente.invalidar();
			}
			resultado.setError(true);
			resultado.setDescripcionError(ExceptionUtils.getMessage(e));

		} finally {
			if (cliente != null) {
				cliente.close();
			}
		}

		return resultado;
	}

	/**
	 * Obtiene un prestamo exclusivo de un puerto SOAP reutilizable.
	 * El timeout de la configuracion PLATA se expresa en segundos.
	 */
	private PrestamoPlata getClientePlata(final String url, final Long timeout) throws Exception {
		final long timeoutMs = timeout != null ? Math.multiplyExact(timeout, 1000L) : 60000L;
		final String key = url.length() + ":" + url + ":" + timeoutMs;

		final PoolClientePlata pool = POOLS.computeIfAbsent(key,
				k -> new PoolClientePlata(url, timeoutMs));
		return pool.adquirir();
	}

	/** Se ejecuta al crear un puerto nuevo, nunca por cada reutilizacion. */
	private static TranslatorV2 crearPuerto(final String url, final long timeoutMs) throws Exception {
		// Serializamos la creacion del proxy sobre el Service compartido.
		final TranslatorV2 port;
		synchronized (SOAP_SERVICE) {
			port = SOAP_SERVICE.getTranslatorV2Port();
		}

		try {
			final BindingProvider provider = (BindingProvider) port;
			provider.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, url);

			final Client client = ClientProxy.getClient(port);
			final HTTPConduit conduit = (HTTPConduit) client.getConduit();
			final HTTPClientPolicy policy = conduit.getClient();

			policy.setConnectionTimeout(10000L);
			policy.setReceiveTimeout(timeoutMs);
			policy.setAllowChunking(false);

			// Mantenemos el comportamiento de proxy del codigo original.
			final String proxyHost = System.getProperty("http.proxyHost");
			if (proxyHost != null && !proxyHost.isEmpty() && !validateNonProxyHosts(url)) {
				policy.setProxyServer(proxyHost);
				policy.setProxyServerPort(Integer.parseInt(System.getProperty("http.proxyPort")));
				conduit.getProxyAuthorization().setUserName(System.getProperty("http.proxyUser"));
				conduit.getProxyAuthorization().setPassword(System.getProperty("http.proxyPassword"));
			}
			return port;
		} catch (final Exception | Error e) {
			destroyClientePlata(port);
			throw e;
		}
	}

	/** Descarta puertos ante fallos de CXF, JAX-WS o del transporte HTTP. */
	private static boolean esErrorDeTransporte(final Throwable error) {
		Throwable causa = error;
		while (causa != null) {
			if (causa instanceof WebServiceException
					|| causa instanceof org.apache.cxf.interceptor.Fault
					|| causa instanceof java.io.IOException) {
				return true;
			}
			if (causa.getCause() == causa) {
				break;
			}
			causa = causa.getCause();
		}
		return false;
	}

	private static void destroyClientePlata(final TranslatorV2 port) {
		if (port == null) {
			return;
		}
		try {
			ClientProxy.getClient(port).destroy();
		} catch (final Exception e) {
			LOG.warn("No se ha podido liberar el cliente SOAP de PLATA", e);
		}
	}

	/** Invocado por PlataPoolLifecycleBean durante la parada de TranslatorIB. */
	public static void cerrarPools() {
		for (final PoolClientePlata pool : POOLS.values()) {
			pool.cerrar();
		}
		POOLS.clear();
		LOG.info("Pools SOAP PLATA cerrados");
	}

	private String getTipoDocumento(final TipoDocumento tipoDocumento) {
		String tipo;
		if (tipoDocumento == TipoDocumento.XML) {
			tipo = "WXML";
		} else {
			tipo = tipoDocumento.toString();
		}

		return tipo;
	}

	/**
	 * Busca els host de la url indicada dentro de la propiedad http.nonProxyHosts
	 * de la JVM
	 *
	 * @param url Endpoint del ws
	 * @return true si el host esta dentro de la propiedad, fals en caso contrario
	 */
	private static boolean validateNonProxyHosts(final String url) throws Exception {
		final String nonProxyHosts = System.getProperty("http.nonProxyHosts");
		boolean existe = false;
		URL urlURL;
		try {
			if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
				urlURL = new URL(url);
				final String[] nonProxyHostsArray = nonProxyHosts.split("\\|");
				for (int i = 0; i < nonProxyHostsArray.length; i++) {
					final String a = nonProxyHostsArray[i].replaceAll("\\.", "\\\\.").replaceAll("\\*", ".*");
					if (urlURL.getHost().matches(a)) {
						existe = true;
						break;
					}
				}
			}
		} catch (final MalformedURLException e) {
			LOG.error("Error al validar los nonProxyHost " + e.getCause(), e);
			throw e;
		}
		return existe;
	}

	/**
	 * Obtiene propiedad.
	 *
	 * @param propiedad propiedad
	 * @return El valor de la propiedad
	 */
	private String getPropiedad(final String propiedad) throws TraduccionException {
		final String res = getProperty(IMPLEMENTATION_BASE_PROPERTY + propiedad);
		if (res == null) {
			throw new TraduccionException("No se ha especificado parametro " + propiedad + " en propiedades");
		}
		return res;
	}

	/**
     * Calcula el languagePair. Según la  (<a href="https://administracionelectronica.gob.es/ctt/plata/descargas">documentación oficial</a>), parámetro donde se especifica el par de idiomas que se quiere utilizar en la traducción.
     * <ul>
     * <li> es-ca (traducción castellano - catalán)</li>
     * <li> es-gl (traducción castellano - gallego)</li>
     * <li> val-es-ca (traducción castellano - valenciano)</li>
     * <li> bal-es-ca (traducción castellano - balear)</li>
     * <li> es-pt (traducción castellano - portugués)</li>
     * <li> fr-es (traducción francés - castellano)</li>
     * <li> ca-es (traducción catalán - castellano)</li>
     * <li> gl-es (traducción gallego - castellano)</li>
     * <li> val-ca-es (traducción valenciano - castellano)</li>
     * <li> bal-ca-es (traducción balear - castellano)</li>
     * <li> pt-es (traducción portugués - castellano)</li>
     * <li> es-fr (traducción castellano - francés)</li>
     * </ul>
     *
     * @param idiomaEntrada Idioma entrada
     * @param idiomaSalida Idioma salida
     * @return Idioma para el languagePair de plata
     */
	private String getIdioma(final Idioma idiomaEntrada, final Idioma idiomaSalida) {
		String idioma = idiomaEntrada.getIdioma() + "-" + idiomaSalida.getIdioma();
		// De momento, PLaTa no soporta el balear, seguramente se añadiría como _balear
		// Por tanto, el balear se enviará como catalán.
		if (idiomaEntrada == Idioma.CATALAN_BALEAR || idiomaSalida == Idioma.CATALAN_BALEAR) {
			idioma = "bal-" + idioma;
		}
		return idioma;
	}

	/**
	 * Obtiene la opcion de markunknow si se ha pasado por opciones
	 *
	 * @param opciones Opciones
	 * @return markUnknown
	 */
	private String getMarkunkonw(final Opciones opciones) {
		String markUnknown;
		if (opciones != null && opciones.contains(Opciones.OPENTRAD_MARKUNKNOWN)) {
			markUnknown = opciones.getValor(Opciones.OPENTRAD_MARKUNKNOWN);
		} else {
			markUnknown = Opciones.OPENTRAD_MARKUNKNOWN_VALOR_ACTIVO;
		}
		return markUnknown;
	}

	/**
	 * Obtiene el valor de ner si se ha pasado por opciones
	 *
	 * @param opciones Opciones
	 * @return ner
	 */
	private Boolean getNer(final Opciones opciones) {
		Boolean ner;
		if (opciones != null && opciones.contains(Opciones.OPENTRAD_NER)) {
			ner = Boolean.valueOf(opciones.getValor(Opciones.OPENTRAD_NER));
		} else {
			ner = Boolean.valueOf(Opciones.OPENTRAD_NER_VALOR_INACTIVO);
		}
		return ner;
	}

	@Override
	public List<ParejaIdiomas> getParesIdiomasPermitidos() {
		return PARES_IDIOMAS_PERMITIDOS;
	}
}
