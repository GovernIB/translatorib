package es.caib.translatorib.backend.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

import javax.faces.application.FacesMessage;
import javax.faces.application.FacesMessage.Severity;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.servlet.ServletContext;

import es.caib.translatorib.backend.controller.SessionBean;
import es.caib.translatorib.backend.controller.ViewTraducir;
import org.apache.commons.lang3.StringUtils;
import org.primefaces.PrimeFaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.caib.translatorib.backend.model.DialogResult;
import es.caib.translatorib.backend.model.types.TypeParametroVentana;
import es.caib.translatorib.service.exception.ErrorBackException;
import es.caib.translatorib.service.model.comun.ConstantesNumero;
import es.caib.translatorib.service.model.types.TypeEntorno;
import es.caib.translatorib.service.model.types.TypeModoAcceso;
import es.caib.translatorib.service.model.types.TypeRoleAcceso;
import es.caib.translatorib.service.model.types.TypeNivelGravedad;
import es.caib.translatorib.backend.controller.DialogAyuda;

/**
 * Utilidades.
 *
 * @author Indra
 *
 */
public final class UtilJSF {

	private static final String ERROR_APERTURA_DIALOG = "Se han modificado parametros de apertura dialog";

	private static final String MESSAGES = "i18n.messages";

	/** Log. */
	private static final Logger LOG = LoggerFactory.getLogger(UtilJSF.class);

	/** Parametro de sesion para securizar apertura dialogs. */
	private static final String SEC_OPEN_DIALOG = "SEC_OPEN_DIALOG";

	/** Constructor privado para evitar problema. */
	private UtilJSF() {
		// not called
	}

	/** Path views. */
	public static final String PATH_VIEWS = "/secure/app/";
	/** Extensión .html **/
	private static final String EXTENSION_XHTML = ".xhtml";
	/** Url sin implementar. **/
	private static final String URL_SIN_IMPLEMENTAR = "/error/errorCode.xhtml?errorCode=404";

	/**
	 * Abre pantalla de dialogo
	 *
	 * @param clase     Clase dialogo
	 * @param modoAcceso Modo de acceso
	 * @param params     parametros
	 * @param modal      si se abre en forma modal
	 * @param width      anchura
	 * @param heigth     altura
	 */
	public static void openDialog(final Class<?> clase, final TypeModoAcceso modoAcceso,
			final Map<String, String> params, final boolean modal, final int width, final int heigth) {
		openDialog(UtilJSF.getViewNameFromClass(clase), modoAcceso, params, modal, width, heigth);
	}

	/**
	 * Abre pantalla de dialogo
	 *
	 * @param dialog     Nombre pantalla dialogo (dialogo.xhtml o id navegacion)
	 * @param modoAcceso Modo de acceso
	 * @param params     parametros
	 * @param modal      si se abre en forma modal
	 * @param width      anchura
	 * @param heigth     altura
	 */
	public static void openDialog(final String dialog, final TypeModoAcceso modoAcceso,
			final Map<String, String> params, final boolean modal, final int width, final int heigth) {
		// Opciones dialogo
		final Map<String, Object> options = new HashMap<String, Object>();
		options.put("modal", modal);
		options.put("width", width);
		options.put("height", heigth);
		options.put("contentWidth", "100%");
		options.put("contentHeight", "100%");
		options.put("headerElement", "customheader");
		options.put("styleClass", "custom-dialog");

		// Parametros
		String idParam = "";
		final Map<String, List<String>> paramsDialog = new HashMap<String, List<String>>();
		paramsDialog.put(TypeParametroVentana.MODO_ACCESO.toString(), Collections.singletonList(modoAcceso.toString()));
		if (params != null) {
			for (final String key : params.keySet()) {
				paramsDialog.put(key, Collections.singletonList(params.get(key)));
				if (TypeParametroVentana.ID.toString().equals(key)) {
					idParam = params.get(key);
				}
			}
		}

		// Metemos en sessionbean un parámetro de seguridad para evitar que se
		// pueda cambiar el modo de acceso
		final String secOpenDialog = modoAcceso.toString() + "-" + idParam + "-" + System.currentTimeMillis();
		getSessionBean().getMochilaDatos().put(SEC_OPEN_DIALOG, secOpenDialog);

		// Abre dialogo
		PrimeFaces.current().dialog().openDynamic(dialog, options, paramsDialog);
	}

	/**
	 * Chequea que no se ha cambiado modo de acceso en apertura dialog.
	 *
	 * @param modoAcceso modo acceso
	 * @param id         id
	 */
	public static void checkSecOpenDialog(final TypeModoAcceso modoAcceso, final String id) {
		// Buscamos si existe token
		final String secOpenDialog = (String) getSessionBean().getMochilaDatos().get(SEC_OPEN_DIALOG);
		if (secOpenDialog == null) {
			throw new ErrorBackException(ERROR_APERTURA_DIALOG);
		}
		// Verificamos que coincida modo acceso e id
		final String[] items = secOpenDialog.split("-");
		final String paramId = StringUtils.defaultString(id);
		if (items.length != 3 || !modoAcceso.toString().equals(items[0]) || !paramId.equals(items[1])) {
			throw new ErrorBackException(ERROR_APERTURA_DIALOG);
		}
		// Verificamos que no haya pasado mas de 1 min
		final Date tiempo = new Date(Long.parseLong(items[2]));
		final Date ahora = new Date();
		final long diffInMillies = Math.abs(ahora.getTime() - tiempo.getTime());
		if (diffInMillies > (ConstantesNumero.N60 * ConstantesNumero.N1000)) {
			throw new ErrorBackException(ERROR_APERTURA_DIALOG);
		}
		// Eliminamos token de la sesion para que se pueda reusar
		getSessionBean().getMochilaDatos().remove(SEC_OPEN_DIALOG);
	}

	/**
	 * Cierra dialog
	 *
	 * @param result
	 */
	public static void closeDialog(final DialogResult result) {
		PrimeFaces.current().dialog().closeDynamic(result);
	}

	/**
	 * Muestra mensaje como ventana de dialogo.
	 *
	 * @param nivel   Nivel gravedad
	 * @param title   Titulo
	 * @param message Mensaje
	 */
	public static void showMessageDialog(final TypeNivelGravedad nivel, final String title, final String message) {
		final Severity severity = getSeverity(nivel);
		PrimeFaces.current().dialog().showMessageDynamic(new FacesMessage(severity, title, message));
	}

	/**
	 * Añade mensaje al contexto para que lo trate la aplicación (growl,
	 * messages,...).
	 *
	 * @param nivel   Nivel gravedad
	 * @param message Mensaje
	 */
	public static void addMessageContext(final TypeNivelGravedad nivel, final String message) {
		addMessageContext(nivel, message, message);
	}

	/**
	 * Añade mensaje al contexto para que lo trate la aplicación (growl,
	 * messages,...).
	 *
	 * @param nivel   Nivel gravedad
	 * @param message Mensaje
	 * @param detail  Detalle
	 */
	public static void addMessageContext(final TypeNivelGravedad nivel, final String message, final String detail) {
		final Severity severity = getSeverity(nivel);
		final FacesContext context = FacesContext.getCurrentInstance();
		context.addMessage(null, new FacesMessage(severity, message, detail));
	}

	/**
	 * Añade mensaje al contexto para que lo trate la aplicación (growl,
	 * messages,...).
	 *
	 * @param nivel            Nivel gravedad
	 * @param message          Mensaje
	 * @param validationFailed añade la marca de error de validacion
	 */
	public static void addMessageContext(final TypeNivelGravedad nivel, final String message,
			final boolean validationFailed) {
		addMessageContext(nivel, message, message, validationFailed);

	}

	/**
	 * Añade mensaje al contexto para que lo trate la aplicación (growl,
	 * messages,...).
	 *
	 * @param nivel            Nivel gravedad
	 * @param message          Mensaje
	 * @param detail           Detalle
	 * @param validationFailed añade la marca de error de validacion
	 */
	public static void addMessageContext(final TypeNivelGravedad nivel, final String message, final String detail,
			final boolean validationFailed) {
		addMessageContext(nivel, message, detail);

		if (validationFailed) {
			FacesContext.getCurrentInstance().validationFailed();
		}
	}

	/**
	 * Obtiene literal.
	 *
	 * @param key key
	 * @return literal
	 */
	public static String getLiteral(final String key) {
		final ResourceBundle text = ResourceBundle.getBundle(MESSAGES, getSessionBean().getLocale());
		return text.getString(key);
	}

	/**
	 * Obtiene el valor de literal.
	 *
	 * @param key        key
	 * @param parametros parametros para sustituir en el literal
	 * @return el valor de literal
	 */
	public static String getLiteral(final String key, final Object[] parametros) {
		final ResourceBundle text = ResourceBundle.getBundle(MESSAGES, getSessionBean().getLocale());
		return MessageFormat.format(text.getString(key), parametros);
	}

	/**
	 * Obtiene el valor de literal.
	 *
	 * @param key  key
	 * @param lang lang
	 * @return el valor de literal
	 */
	public static String getLiteral(final String key, final String lang) {
		final ResourceBundle text = ResourceBundle.getBundle(MESSAGES, new Locale(lang));
		return text.getString(key);
	}

	/**
	 * Obtiene bean de sesión.
	 *
	 * @return bean de sesión
	 */
	public static SessionBean getSessionBean() {
		return (SessionBean) FacesContext.getCurrentInstance().getExternalContext().getSessionMap().get("sessionBean");
	}

	/**
	 * Devuelve la version.
	 *
	 * @return
	 */
	public static String getVersion() {
		return FacesContext
				.getCurrentInstance().getApplication().evaluateExpressionGet(FacesContext.getCurrentInstance(),
						"#{negocioModuleConfig}", es.caib.translatorib.backend.model.comun.ModuleConfig.class)
				.getVersion();
	}

	/**
	 * Redirige pagina JSF.
	 *
	 * @param jsfPage path JSF page
	 */
	public static void redirectJsfPage(final String jsfPage) {
		try {
			final ServletContext servletContext = (ServletContext) FacesContext.getCurrentInstance()
					.getExternalContext().getContext();
			final String contextPath = servletContext.getContextPath();
			FacesContext.getCurrentInstance().getExternalContext().redirect(contextPath + jsfPage);
		} catch (final IOException e) {
			UtilJSF.LOG.error("Error redirigiendo", e);
		}
	}

	/**
	 * Redirect jsf page.
	 *
	 * @param jsfPage the jsf page
	 * @param params  the params
	 */
	public static void redirectJsfPage(final String jsfPage, final Map<String, List<String>> params) {
		try {
			final ServletContext servletContext = (ServletContext) FacesContext.getCurrentInstance()
					.getExternalContext().getContext();
			final String contextPath = servletContext.getContextPath();
			final ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
			FacesContext.getCurrentInstance().getExternalContext()
					.redirect(ec.encodeRedirectURL(contextPath + jsfPage, params));
			FacesContext.getCurrentInstance().responseComplete();
		} catch (final IOException e) {
			UtilJSF.LOG.error("Error redirigiendo", e);
		}
	}

	// FacesContext.getCurrentInstance().getExternalContext().encodeRedirectURL(baseUrl,
	// parameters)

	private static Severity getSeverity(final TypeNivelGravedad nivel) {
		Severity severity;
		switch (nivel) {
		case INFO:
			severity = FacesMessage.SEVERITY_INFO;
			break;
		case WARNING:
			severity = FacesMessage.SEVERITY_WARN;
			break;
		case ERROR:
			severity = FacesMessage.SEVERITY_ERROR;
			break;
		case FATAL:
			severity = FacesMessage.SEVERITY_FATAL;
			break;
		default:
			severity = FacesMessage.SEVERITY_INFO;
			break;
		}
		return severity;
	}

	/**
	 * Devuelve view name suponiendo que se llama igual que la clase.
	 *
	 * @param clase clase
	 * @return view name
	 */
	public static String getViewNameFromClass(final Class<?> clase) {
		final String className = clase.getSimpleName();
		return className.substring(0, 1).toLowerCase() + className.substring(1);
	}

	/**
	 * Devuelve el title name de la clase.
	 *
	 * @param clase
	 * @return
	 */
	public static String getTitleViewNameFromClass(final Class<?> clase) {
		return getViewNameFromClass(clase) + ".titulo";
	}


	/**
	 * Obtiene context path.
	 *
	 * @return context path
	 */
	public static String getContextPath() {
		return FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath();
	}

	/**
	 * Realiza el update desde ajax del componente como desde el xhtml.
	 *
	 * @param pIdComponente identificador del componente
	 */
	public static void doUpdateComponent(final String pIdComponente) {
		PrimeFaces.current().ajax().update(pIdComponente);
	}

	/**
	 * Pone el estado validation failed.
	 */
	public static void doValidationFailed() {
		FacesContext.getCurrentInstance().validationFailed();
	}

	/**
	 * Comprueba si el entorno es el mismo (desarrollo, preproduccion y produccion).
	 *
	 * @return
	 */
	public static boolean checkEntorno(final TypeEntorno tipoEntorno) {
		final boolean mismo = false;
		if (tipoEntorno != null) {
			final String entorno = getEntorno();
			return tipoEntorno.toString().equals(entorno);
		}
		return mismo;
	}

	/**
	 * Devuelve el entorno.
	 *
	 * @return
	 */
	public static String getEntorno() {
		return FacesContext
				.getCurrentInstance().getApplication().evaluateExpressionGet(FacesContext.getCurrentInstance(),
						"#{negocioModuleConfig}", es.caib.translatorib.backend.model.comun.ModuleConfig.class)
				.getEntorno();
	}

	/**
	 * Devuelve la AyudaExternatranslatorib.
	 *
	 * @return
	 */
	public static String getAyudaExternatranslatorib() {
		String ruta = System.getProperty("es.caib.translatorib.properties", null);
		if (ruta == null || ruta.isEmpty()) {
			return null;
		}

		try (InputStream input = new FileInputStream(ruta)) {
			Properties prop = new Properties();
			prop.load(input);
			String rutaAyudaExterna = prop.getProperty("ayuda.translatorib.path");
			return rutaAyudaExterna;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Redirige pagina JSF por defecto para role.
	 *
	 * @param role role
	 */
	public static void redirectJsfDefaultPageRole(final TypeRoleAcceso role) {
		redirectJsfPage(getDefaultUrlRole(role));
	}

	/**
	 * Devuelve url por defecto segun role.
	 *
	 * @param role role
	 * @return url
	 */
	public static String getDefaultUrlRole(final TypeRoleAcceso role) {
		String url = null;
		if (role == null) {
			url = paginaViewTraducir();
		} else {
			switch (role) {
			case SUPER_ADMIN:
				url = getUrlOpcionMenuSuperadministrador();
				break;
			default:
				//url = "/error/errorUsuarioSinRol.xhtml";
				//break;
				url = getUrlOpcionMenuNoRol();
				break;

			}
		}
		return url;
	}

	/**
	 * Devuelve url opcion menu super administrador.
	 *
	 * @return url
	 */
	public static String getUrlOpcionMenuSuperadministrador() {
		return paginaViewTraducir();
	}

	/**
	 * Pagina view Traducir.
	 * @return url
	 */
	public static String paginaViewTraducir() {
		return	"/" + UtilJSF.getViewNameFromClass(ViewTraducir.class) + EXTENSION_XHTML;
	}

	/**
	 * Devuelve url opcion menu super administrador.
	 *
	 * @return url
	 */
	public static String getUrlOpcionMenuNoRol() {
		return paginaViewTraducir();
	}


	/**
	 * Verifica si accede el superadministrador generando excepción en caso
	 * contrario.
	 *
	 */
	public static void verificarAccesoSuperAdministrador() {
		final SessionBean sb = (SessionBean) FacesContext.getCurrentInstance().getExternalContext().getSessionMap()
				.get("sessionBean");
		if (sb.getActiveRole() != TypeRoleAcceso.SUPER_ADMIN) {
			throw new ErrorBackException("No se está accediendo con perfil SuperAdministrador");
		}
	}



	public static void openHelp(final String id) {
		final Map<String, String> params = new HashMap<>();
		if (StringUtils.isBlank(id)) {
			throw new ErrorBackException("No existe identificador");
		}

		params.put(TypeParametroVentana.ID.toString(), id);

		UtilJSF.openDialog(DialogAyuda.class, TypeModoAcceso.CONSULTA, params, true, 900, 550);
	}

	public static void openHelp(final String id, Map<String, String> params) {
		if (StringUtils.isBlank(id)) {
			throw new ErrorBackException("No existe identificador");
		}

		params.put(TypeParametroVentana.ID.toString(), id);

		UtilJSF.openDialog(DialogAyuda.class, TypeModoAcceso.CONSULTA, params, true, 900, 550);
	}

	/**
	 * Verifica si accede el superadministrador generando excepción en caso
	 * contrario.
	 *
	 */
	public static void verificarAccesoOld() {
		final SessionBean sb = (SessionBean) FacesContext.getCurrentInstance().getExternalContext().getSessionMap()
				.get("sessionBean");
		if (sb.getActiveRole() != TypeRoleAcceso.SUPER_ADMIN ) {
			throw new ErrorBackException("No se está accediendo con perfil SuperAdministrador");
		}
	}


	public static void verificarAcceso(SessionBean sb) {
		if (sb.isAnonimo()) {
			sb.actualizar();
		}
		if (sb.getActiveRole() != TypeRoleAcceso.SUPER_ADMIN ) {
			throw new ErrorBackException("No se está accediendo con perfil SuperAdministrador");
		}
	}
	/**
	 * Verifica el perfil pero comprueba si sigue siendo anónimo.
	 **/
	public static void verificarAcceso() {
		final SessionBean sb = (SessionBean) FacesContext.getCurrentInstance().getExternalContext().getSessionMap()
				.get("sessionBean");
		if (sb.isAnonimo()) {
			sb.actualizar();
		}
		if (sb.getActiveRole() != TypeRoleAcceso.SUPER_ADMIN ) {
			throw new ErrorBackException("No se está accediendo con perfil SuperAdministrador");
		}
	}

	/**
	 * Obtiene idioma.
	 *
	 * @return idioma
	 */
	public static String getIdioma() {
		return getSessionBean().getLang();
	}
}
