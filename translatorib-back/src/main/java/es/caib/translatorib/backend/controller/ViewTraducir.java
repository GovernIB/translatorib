package es.caib.translatorib.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.caib.translatorib.api.v1.model.ParametrosTraduccionDocumento;
import es.caib.translatorib.api.v1.model.ParametrosTraduccionTexto;
import es.caib.translatorib.backend.util.UtilJSF;
import es.caib.translatorib.service.model.*;
import es.caib.translatorib.service.model.comun.Constantes;
import es.caib.translatorib.service.model.types.TypeNivelGravedad;
import es.caib.translatorib.service.service.ConfiguracionFrontalService;
import es.caib.translatorib.service.service.ConfiguracionGlobalService;
import es.caib.translatorib.service.service.PluginService;
import es.caib.translatorib.service.service.TraduccionService;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.annotation.PreDestroy;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;
import javax.faces.context.FacesContext;
import javax.faces.context.ExternalContext;
import javax.faces.event.AjaxBehaviorEvent;
import javax.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpTimeoutException;
import es.caib.translatorib.backend.util.RestHttpCliente;
import java.util.concurrent.Semaphore;
import java.net.URLEncoder;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Mantenimiento de entidades.
 *
 * @author Indra
 *
 */
@ManagedBean
@ViewScoped
public class ViewTraducir extends ViewControllerBase {

	private static final Logger LOG = LoggerFactory.getLogger(ViewTraducir.class);

	/** Cliente REST OkHttp compartido en RestHttpCliente (no por vista). */
	/** Limite TOTAL de espera de la respuesta REST (no solo de conexion TCP). */
	private static final Duration TIMEOUT_REST = Duration.ofSeconds(10);
	private static final String ERROR_CONEXION_REST =
			"No ha sido posible conectarse a la REST API (tiempo de espera de 10 segundos agotado).";

	/**
	 * ViewTraducir es @ViewScoped, NO singleton: cada usuario/vista tiene su bean.
	 * El HttpClient y estos semaforos SI se comparten entre vistas en esta JVM.
	 * Limitar los auto-HTTP sin ocupar todos los hilos Undertow esperando al mismo JBoss.
	 */
	private static final int MAX_REST_TEXTO =
			Math.max(1, Integer.getInteger("translatorib.view.maxRestConcurrent", 4));
	private static final Semaphore REST_TEXTO_PLAZAS = new Semaphore(MAX_REST_TEXTO, true);
	private static final int MAX_REST_DOCUMENTO =
			Math.max(1, Integer.getInteger("translatorib.view.maxRestDocumentConcurrent", 2));
	private static final Semaphore REST_DOCUMENTO_PLAZAS =
			new Semaphore(MAX_REST_DOCUMENTO, true);

	private void mostrarErrorRest() {
		UtilJSF.addMessageContext(TypeNivelGravedad.ERROR, ERROR_CONEXION_REST);
	}

	@EJB
	private PluginService	pluginService;

	@EJB
	private TraduccionService traduccionService;

	@EJB
	private ConfiguracionGlobalService configuracionGlobalService;

	@EJB
	private ConfiguracionFrontalService configuracionFrontalService;

	/**
	 * Filtro (puede venir por parametro).
	 */
	private String filtro;

	/**
	 * Lista de datos.
	 */
	private List<Plugin> plugins;
	private String plugin;
	Plugin pluginDTO;

	private List<Idioma> idiomasOrigen;
	private List<Idioma> idiomasDestino;
	private Idioma idiomaOrigen;
	private Idioma idiomaDestino;
	private String textoOrigen;
	private String textoDestino;

	/** Activar boton **/
	private boolean mostrarBotonJava;

	private Idioma idiomaOrigenOld;
	private Idioma idiomaDestinoOld;
	private Integer tipo = 0;


	/**
	 * Inicializacion.
	 */
	public void init() {

		// Obtener el parámetro de la URL manualmente
		// viewTraducir.xhtml?activarBotonJava=true
		String activarBotonJava = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("activarBotonJava");
		if (activarBotonJava != null && activarBotonJava.equals("true")) {
			mostrarBotonJava = true;
		} else {
			mostrarBotonJava = false;
		}

		ConfiguracionFrontal configuracionFrontal = configuracionFrontalService.findConfFrontalByDefault();

		// Control acceso
		if (configuracionFrontal.getPlugin() != null) {
			plugin = configuracionFrontal.getPlugin().getIdentificador();
			pluginDTO = configuracionFrontal.getPlugin();
		}

		// Si es nulo, hay que coger el por defecto
		if (plugin == null) {
			pluginDTO = pluginService.getPluginByDefault();
			plugin = pluginDTO.getIdentificador();
		}

		if (configuracionFrontal.getPluginsSoportados() == null || configuracionFrontal.getPluginsSoportados().isEmpty()) {
			plugins = pluginService.lista(this.filtro);
		} else {
			plugins = new ArrayList<Plugin>();
			for(String plg : configuracionFrontal.getPluginsSoportados().split(",")) {
				Plugin plugin = pluginService.getPluginByCodigo(Long.valueOf(plg));
				if (plugin != null) {
					plugins.add(plugin);
				}
			}
		}

		idiomasOrigen = new ArrayList<Idioma>();
		idiomasDestino = new ArrayList<Idioma>();
		if (plugin != null) {
			for(Plugin plg: plugins) {
				if (plg.getIdentificador().equals(plugin)) {
					setearIdiomas(plg);
					break;
				}
			}
		}

		idiomaOrigen = Idioma.CASTELLANO;
		idiomaOrigenOld = Idioma.CASTELLANO;
		idiomaDestino = Idioma.CATALAN_BALEAR;
		idiomaDestinoOld = Idioma.CATALAN_BALEAR;
	}

	/**
	 * El archivo se conserva como bytes, sin retener UploadedFile ni el buffer
	 * del componente de subida en el estado de vista JSF.
	 */
	private static final long CADUCIDAD_DOCUMENTO_MS = 5L * 60L * 1000L;
	private String fileName;
	private byte[] contenido;
	private long documentoCargadoEn;
	private volatile boolean traduccionDocumentoEnCurso;

	// Getters y setters

	public void handleFileUpload(FileUploadEvent event) {
		guardarDocumento(event == null ? null : event.getFile());
	}

	private void guardarDocumento(final UploadedFile nuevoArchivo) {
		limpiarDocumentoPendiente();
		if (nuevoArchivo != null) {
			final byte[] bytes = nuevoArchivo.getContent();
			if (bytes != null && bytes.length > 0) {
				this.fileName = nuevoArchivo.getFileName();
				this.contenido = bytes;
				this.documentoCargadoEn = System.currentTimeMillis();
			}
		}
	}

	private void limpiarDocumentoPendiente() {
		this.contenido = null;
		this.fileName = null;
		this.documentoCargadoEn = 0L;
	}

	/**
	 * Caducidad pasiva: tambien se comprueba al usar la vista, sin crear
	 * un hilo ni una tarea programada por usuario.
	 */
	private void limpiarDocumentoSiCaducado() {
		if (!traduccionDocumentoEnCurso && documentoCargadoEn > 0L
				&& System.currentTimeMillis() - documentoCargadoEn >= CADUCIDAD_DOCUMENTO_MS) {
			limpiarDocumentoPendiente();
		}
	}

	/**
	 * El idleMonitor JSF invoca este metodo una vez tras cinco minutos
	 * sin interaccion del usuario en la vista de documentos.
	 */
	public void limpiarDocumentoInactivo() {
		limpiarDocumentoSiCaducado();
	}

	public boolean isDocumentoDisponible() {
		limpiarDocumentoSiCaducado();
		return contenido != null && contenido.length > 0 && fileName != null;
	}

	@PreDestroy
	public void destruirVista() {
		limpiarDocumentoPendiente();
	}

	/**
	 * Prepara la traduccion documental y devuelve los bytes directamente al navegador.
	 * Se invoca desde el boton no AJAX (NO desde un getter JSF).
	 */
	public void descargarArchivo() {

		if (!isDocumentoDisponible()) {
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
					UtilJSF.getLiteral("viewTraducir.ArchivoNoSeleccionado"));
			return;
		}

		if (plugin == null || idiomaOrigen == null || idiomaDestino == null) {
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
					UtilJSF.getLiteral("viewTraducir.faltandatos"));
			return;
		}

		final String trace = UUID.randomUUID().toString();

		if (!REST_DOCUMENTO_PLAZAS.tryAcquire()) {
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
					"Hay demasiadas traducciones de documentos en curso. Inténtalo de nuevo.");
			return;
		}

		boolean respuestaBinariaIniciada = false;
		traduccionDocumentoEnCurso = true;

		try {
			ParametrosTraduccionDocumento parametros = new ParametrosTraduccionDocumento();
			parametros.setPlugin(plugin);
			parametros.setTipoDocumento(getTipoDocumento(fileName));
			parametros.setIdiomaEntrada(es.caib.translatorib.api.v1.model.Idioma.fromString(idiomaOrigen.toString()));
			parametros.setIdiomaSalida(es.caib.translatorib.api.v1.model.Idioma.fromString(idiomaDestino.toString()));
			parametros.setContenidoDocumento(Base64.getEncoder().encodeToString(contenido));

			String user = configuracionGlobalService.valorByPropiedad(Constantes.PROPIEDAD_GLOBAL_FRONTAL_USER);
			String pwd = configuracionGlobalService.valorByPropiedad(Constantes.PROPIEDAD_GLOBAL_FRONTAL_PWD);
			String json = new ObjectMapper().writeValueAsString(parametros);
			String url = configuracionGlobalService.valorByPropiedad(Constantes.PROPIEDAD_GLOBAL_FRONTAL_URL_DOC);
			String authHeader = null;
			if (user != null && pwd != null) {
				String auth = Base64.getEncoder().encodeToString(
						(user + ":" + pwd).getBytes(StandardCharsets.UTF_8));
				authHeader = "Basic " + auth;
			}

			RestHttpCliente.Respuesta respuesta = RestHttpCliente.post(url, json, authHeader, trace, "VIEW_DOC");
			String contentType = respuesta.contentType();

			if (respuesta.statusCode() < 200 || respuesta.statusCode() >= 300) {
				UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
						UtilJSF.getLiteral("viewTraducir.errorDescarga"));
				return;
			}

			if (respuesta.body() == null || respuesta.body().trim().isEmpty()
					|| contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
				UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
						UtilJSF.getLiteral("viewTraducir.errorDescarga"));
				return;
			}

			ResultadoTraduccionDocumento resultado = new ObjectMapper()
					.readValue(respuesta.body(), ResultadoTraduccionDocumento.class);

			if (resultado == null || resultado.isError()) {
				UtilJSF.addMessageContext(TypeNivelGravedad.WARNING,
						UtilJSF.getLiteral("viewTraducir.errorTraduccion")
								+ (resultado == null ? "Respuesta nula" : String.valueOf(resultado.getDescripcionError())));
				return;
			}

			if (resultado.getTextoTraducido() == null || resultado.getTextoTraducido().isEmpty()) {
				UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
						UtilJSF.getLiteral("viewTraducir.errorDescarga"));
				return;
			}

			byte[] bytesTraducidos = Base64.getMimeDecoder().decode(resultado.getTextoTraducido());

			respuestaBinariaIniciada = true;
			enviarDocumento(bytesTraducidos, fileName);
			// La respuesta binaria se ha enviado: no mantener el original en JSF.
			limpiarDocumentoPendiente();

		} catch (HttpTimeoutException e) {
			LOG.warn("Se ha agotado el tiempo de espera de la REST API (10 segundos)");
			mostrarErrorRest();
		} catch (IOException e) {
			LOG.error("Error de comunicación con la REST API", e);
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
					"No ha sido posible conectarse a la REST API.");
		} catch (Exception e) {
			LOG.error("Error inesperado al realizar la traducción", e);
			if (respuestaBinariaIniciada) {
				FacesContext.getCurrentInstance().responseComplete();
			} else {
				UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
						UtilJSF.getLiteral("viewTraducir.errorDescarga"));
			}
		} finally {
			traduccionDocumentoEnCurso = false;
			REST_DOCUMENTO_PLAZAS.release();
		}
	}

	/**
	 * Envia el documento al navegador evitando usar p:fileDownload/getFileDownload.
	 * Solo se invoca cuando la traduccion y la decodificacion han terminado bien.
	 */
	private void enviarDocumento(byte[] documento, String nombreArchivo) throws IOException {
		String nombre = nombreArchivo == null ? "traduccion" : nombreArchivo.replace('\\', '/');
		nombre = nombre.substring(nombre.lastIndexOf('/') + 1)
				.replaceAll("[\r\n\"]", "_");
		if (nombre.trim().isEmpty()) {
			nombre = "traduccion";
		}

		String nombreASCII = nombre.replaceAll("[^ -~]", "_");
		String nombreUTF8 = URLEncoder.encode(nombre, StandardCharsets.UTF_8.name())
				.replace("+", "%20");

		FacesContext faces = FacesContext.getCurrentInstance();
		ExternalContext external = faces.getExternalContext();
		external.responseReset();
		external.setResponseContentType("application/octet-stream");
		external.setResponseContentLength(documento.length);
		external.setResponseHeader("Cache-Control", "no-store");
		external.setResponseHeader("X-Content-Type-Options", "nosniff");
		external.setResponseHeader("Content-Disposition",
				"attachment; filename=\"" + nombreASCII + "\"; filename*=UTF-8''" + nombreUTF8);

		try (OutputStream salida = external.getResponseOutputStream()) {
			salida.write(documento);
			salida.flush();
		}
		faces.responseComplete();
	}

	private es.caib.translatorib.api.v1.model.TipoDocumento getTipoDocumento(String fileName) {
		switch (fileName.substring(fileName.lastIndexOf(".")).toLowerCase()) {
			case ".pdf":
				return es.caib.translatorib.api.v1.model.TipoDocumento.PDF;
			case ".doc":
				return es.caib.translatorib.api.v1.model.TipoDocumento.DOC;
			case ".docx":
				return es.caib.translatorib.api.v1.model.TipoDocumento.DOCX;
			case ".xlx":
			case ".xlsx":
				return es.caib.translatorib.api.v1.model.TipoDocumento.XLSX;
			case ".xml":
			case ".wxml":
				return es.caib.translatorib.api.v1.model.TipoDocumento.XML;
			case ".odp":
				return es.caib.translatorib.api.v1.model.TipoDocumento.ODP;
			case ".ods":
				return es.caib.translatorib.api.v1.model.TipoDocumento.ODS;
			case ".odt":
				return es.caib.translatorib.api.v1.model.TipoDocumento.ODT;
			case ".ppt":
			case ".pptx":
				return es.caib.translatorib.api.v1.model.TipoDocumento.PPTX;
			case ".html":
				return es.caib.translatorib.api.v1.model.TipoDocumento.HTML;
			case ".txt":
			default:
				return  es.caib.translatorib.api.v1.model.TipoDocumento.TXT;

		}

	}

	/**
	 * Intercambia los valores de idioma origen y destino.

	 */
	public void cambioValores() {
		Idioma previoIdiomaOrigen = idiomaOrigen;
		idiomaOrigen = idiomaDestino;
		idiomaDestino = previoIdiomaOrigen;
	}

	/**
	 * Cambiar idioma origen.
	 *
	 * @param event the event
	 */
	public void cambiarIdiomaOrigenAux(AjaxBehaviorEvent event) {

		// Si el destino actual no es válido para el nuevo origen,
		// seleccionar automáticamente el primer destino permitido.
		if (idiomaDestino == null || esDisabledDestino(idiomaDestino)) {

			idiomaDestino = null;

			for (Idioma candidato : idiomasDestino) {
				if (!esDisabledDestino(candidato)) {
					idiomaDestino = candidato;
					break;
				}
			}

			idiomaDestinoOld = idiomaDestino;
		}

		idiomaOrigenOld = idiomaOrigen;
	}

	/**
	 * Cambiar idioma destino.
	 *
	 * @param event the event
	 */
	public void cambiarIdiomaDestinoAux(AjaxBehaviorEvent event) {
		if (idiomaOrigen.equals(idiomaDestino)) {
			idiomaOrigen = idiomaDestinoOld;
		}
		idiomaDestinoOld = idiomaDestino;
	}

	/**
	 * Realizar traduccion.
	 */
	public void traducir() {
		final String trace = UUID.randomUUID().toString();


		if (plugin == null || idiomaDestino == null || idiomaOrigen == null) {
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
					UtilJSF.getLiteral("viewTraducir.faltandatos"));
			return;
		}
		// No esperar en un hilo JSF por un hueco: ante sobrecarga se informa al usuario.
		if (!REST_TEXTO_PLAZAS.tryAcquire()) {
			textoDestino = "Demasiadas traducciones en curso. Inténtalo de nuevo en unos segundos.";
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR, textoDestino);
			return;
		}

		try {
			ParametrosTraduccionTexto parametros = new ParametrosTraduccionTexto();
			parametros.setPlugin(plugin);
			parametros.setTipoEntrada(es.caib.translatorib.api.v1.model.TipoEntrada.TEXTO_PLANO);
			parametros.setIdiomaEntrada(es.caib.translatorib.api.v1.model.Idioma.fromString(idiomaOrigen.toString()));
			parametros.setIdiomaSalida(es.caib.translatorib.api.v1.model.Idioma.fromString(idiomaDestino.toString()));
			parametros.setTextoEntrada(textoOrigen);

			String authHeader = null;
			String user = configuracionGlobalService.valorByPropiedad(Constantes.PROPIEDAD_GLOBAL_FRONTAL_USER);
			String pwd = configuracionGlobalService.valorByPropiedad(Constantes.PROPIEDAD_GLOBAL_FRONTAL_PWD);
			if (user != null && pwd != null) {
				String auth = user + ":" + pwd;
				authHeader = "Basic " + Base64.getEncoder()
						.encodeToString(auth.getBytes(StandardCharsets.UTF_8));
			}

			ObjectMapper objectMapper = new ObjectMapper();
			String json = objectMapper.writeValueAsString(parametros);
			String url = configuracionGlobalService.valorByPropiedad(Constantes.PROPIEDAD_GLOBAL_FRONTAL_URL_TEXT);


			RestHttpCliente.Respuesta respuesta =
					RestHttpCliente.post(url, json, authHeader, trace, "VIEW");


			if (respuesta.statusCode() < 200 || respuesta.statusCode() >= 300
					|| respuesta.body() == null || respuesta.body().trim().isEmpty()) {
				textoDestino = "Error de la REST API (HTTP " + respuesta.statusCode() + ").";
				UtilJSF.addMessageContext(TypeNivelGravedad.ERROR, textoDestino);
				return;
			}

			ResultadoTraduccionTexto resultado =
					objectMapper.readValue(respuesta.body(), ResultadoTraduccionTexto.class);
			if (resultado == null) {
				textoDestino = "La REST API ha devuelto una respuesta vacía.";
				UtilJSF.addMessageContext(TypeNivelGravedad.ERROR, textoDestino);
			} else if (resultado.isError()) {
				textoDestino = "Error en la traduccion:" + resultado.getDescripcionError();
			} else {
				textoDestino = resultado.getTextoTraducido();
			}

		} catch (HttpTimeoutException e) {
			LOG.warn("Se ha agotado el tiempo de espera de la REST API (10 segundos)");
			textoDestino = ERROR_CONEXION_REST;
			mostrarErrorRest();
		} catch (IOException e) {
			LOG.error("Error de comunicación con la REST API", e);
			textoDestino = "No ha sido posible conectarse a la REST API.";
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR, textoDestino);
		} catch (Exception e) {
			LOG.error("Error inesperado al realizar la traducción", e);
			UtilJSF.addMessageContext(TypeNivelGravedad.ERROR,
					UtilJSF.getLiteral("viewTraducir.errorTraduccion"));
		} finally {
			REST_TEXTO_PLAZAS.release();
		}
	}

	/**
	 * Cambiar plugin.
	 */
	public void cambiarPlugin() {
		for(Plugin plg: plugins) {
			if (plg.getIdentificador().equals(plugin)) {
				pluginDTO = plg;
				setearIdiomas(plg);
				if (idiomaOrigen == null || idiomaOrigen.equals(Idioma.CASTELLANO)) {
					idiomaOrigen = idiomasOrigen.get(0);
				}
				if (idiomaDestino == null || idiomaDestino.equals(Idioma.CATALAN_BALEAR)) {
					idiomaDestino = idiomasDestino.get(0);
				}
				break;
			}
		}
	}

	/**
	 * Comprobar si el idioma destino esta deshabilitado.
	 * @param idiomaDestino Idioma destino
	 * @return the boolean (true lo desactiva, false lo activa)
	 */
	public boolean esDisabledDestino(Idioma idiomaDestino) {
		if (idiomaOrigen == null || idiomaDestino == null || pluginDTO == null) {
			return true;
		}
		if (pluginDTO.getIdiomasFrontal() == null || pluginDTO.getIdiomasFrontal().isEmpty()) {
			return true;
		}

		for(String idioma : pluginDTO.getIdiomasFrontal().split(",")) {
			String[] idiomaArray = idioma.split("->");
			if (Idioma.fromString(idiomaArray[0]) == idiomaOrigen && Idioma.fromString(idiomaArray[1]) == idiomaDestino) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Setear idiomas segun plugin
	 * @param plg Plugin
	 */
	private void setearIdiomas(Plugin plg) {
		if (plg.getIdiomasFrontal() == null || plg.getIdiomasFrontal().isEmpty()) {
			idiomasDestino = new ArrayList<Idioma>();
			idiomasOrigen = new ArrayList<Idioma>();
			return;
		} else {
			setearIdiomasOrigen(plg.getIdiomasFrontal());
			setearIdiomasDestino(plg.getIdiomasFrontal());
		}
	}

	/**
	 * Setear idiomas destino.
	 * @param idiomasFrontal Idiomas del frontal
	 */
	private void setearIdiomasDestino(String idiomasFrontal) {
		//Teniendo en cuenta que idiomasFronta tiene un valor asi : es es->ca ca_ES,ca ca_ES->es es
		//La separación entre pares es la coma (',')
		//Quiero extraer la segunda parte ants de la -> y convertirlo a una lista de idiomas y setearlo a idiomas
		idiomasDestino = new ArrayList<Idioma>();
		String[] idiomasFrontalArray = idiomasFrontal.split(",");
		for (String idioma : idiomasFrontalArray) {
			String[] idiomaArray = idioma.split("->");
			if(idiomasDestino.contains(Idioma.fromString(idiomaArray[1]))){
				continue;
			}
			idiomasDestino.add(Idioma.fromString(idiomaArray[1]));
		}
	}

	/**
	 * Setear idiomas origen.
	 * @param idiomasFrontal Idiomas del frontal
	 */
	private void setearIdiomasOrigen(String idiomasFrontal) {
		//Teniendo en cuenta que idiomasFronta tiene un valor asi : es es->ca ca_ES,ca ca_ES->es es
		//La separación entre pares es la coma (',')
		//Quiero extraer la primera parte ants de la -> y convertirlo a una lista de idiomas y setearlo a idiomas
		idiomasOrigen = new ArrayList<Idioma>();
		String[] idiomasFrontalArray = idiomasFrontal.split(",");
		for (String idioma : idiomasFrontalArray) {
			String[] idiomaArray = idioma.split("->");
			if(idiomasOrigen.contains(Idioma.fromString(idiomaArray[0]))){
				continue;
			}
			idiomasOrigen.add(Idioma.fromString(idiomaArray[0]));
		}
	}

	/**
	 * Realizar traduccion mediante JAVA sin realizar un restapi.
	 */
	public void traducirJAVA() {
		if (plugin != null) {

			// Realizar traduccion
			ResultadoTraduccionTexto resultado =
					traduccionService.realizarTraduccion(
							textoOrigen,
							TipoEntrada.TEXTO_PLANO,
							idiomaOrigen,
							idiomaDestino,
							plugin,
							null
					);
			textoDestino = resultado.getTextoTraducido();
		}
	}

	public String getFiltro() {
		return filtro;
	}

	public void setFiltro(String filtro) {
		this.filtro = filtro;
	}

	public List<Plugin> getPlugins() {
		return plugins;
	}

	public void setPlugins(List<Plugin> plugins) {
		this.plugins = plugins;
	}

	public String getPlugin() {
		return plugin;
	}

	public void setPlugin(String plugin) {
		this.plugin = plugin;
	}

	public List<Idioma> getIdiomasOrigen() {
		return idiomasOrigen;
	}

	public void setIdiomasOrigen(List<Idioma> idiomasOrigen) {
		this.idiomasOrigen = idiomasOrigen;
	}

	public List<Idioma> getIdiomasDestino() {
		return idiomasDestino;
	}

	public void setIdiomasDestino(List<Idioma> idiomasDestino) {
		this.idiomasDestino = idiomasDestino;
	}

	public Idioma getIdiomaOrigen() {
		return idiomaOrigen;
	}

	public void setIdiomaOrigen(Idioma idiomaOrigen) {
		this.idiomaOrigen = idiomaOrigen;
	}

	public Idioma getIdiomaDestino() {
		return idiomaDestino;
	}

	public void setIdiomaDestino(Idioma idiomaDestino) {
		this.idiomaDestino = idiomaDestino;
	}

	public String getTextoOrigen() {
		return textoOrigen;
	}

	public void setTextoOrigen(String textoOrigen) {
		this.textoOrigen = textoOrigen;
	}

	public String getTextoDestino() {
		return textoDestino;
	}

	public void setTextoDestino(String textoDestino) {
		this.textoDestino = textoDestino;
	}

	public boolean isMostrarBotonJava() {
		return mostrarBotonJava;
	}

	public void setMostrarBotonJava(boolean mostrarBotonJava) {
		this.mostrarBotonJava = mostrarBotonJava;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public void file(FileUploadEvent event) {
		guardarDocumento(event == null ? null : event.getFile());
	}

	public void setFile(UploadedFile archivo) {
		guardarDocumento(archivo);
	}

	public Integer getTipo() {
		return tipo;
	}

	public void setTipo(Integer tipo) {
		this.tipo = tipo;
	}

	/**
	 * Desactivar boton cambio valores.
	 * @return the boolean
	 */
	public boolean desactivarBotonCambioValores() {
		if (idiomaOrigen == null || idiomaDestino == null || plugin == null) {
			return true;
		}

		return comprobarIntercambiarIdiomas(pluginDTO.getIdiomasFrontal(), idiomaOrigen, idiomaDestino);
	}

	/**
	 * Tiene que comportar si existe en idiomasFrontal si existe la tupla idiomaDestino con idiomaOrigen
	 * @param idiomasFrontal Idiomas del frontal del plugin
	 * @param idiomaOrigen Idioma origen
	 * @param idiomaDestino Idioma destino
	 * @return the boolean (true lo desactiva, false lo activa)
	 */
	private boolean comprobarIntercambiarIdiomas(String idiomasFrontal, Idioma idiomaOrigen, Idioma idiomaDestino) {
		//Tiene que comportar si existe en idiomasFrontal si existe la tupla idiomaDestino con idiomaOrigen
		if (idiomasFrontal == null || idiomasFrontal.isEmpty()) {
			return true;
		}
		String[] idiomasFrontalArray = idiomasFrontal.split(",");
		for (String idioma : idiomasFrontalArray) {
			String[] idiomaArray = idioma.split("->");
			if (idiomaArray[0].equals(idiomaDestino.toString()) && idiomaArray[1].equals(idiomaOrigen.toString())) {
				return false;
			}
		}
		return true;
	}
}

