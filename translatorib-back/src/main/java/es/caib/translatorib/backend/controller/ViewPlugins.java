package es.caib.translatorib.backend.controller;

import es.caib.translatorib.backend.model.DialogResult;
import es.caib.translatorib.backend.model.types.TypeParametroVentana;
import es.caib.translatorib.backend.util.UtilJSF;
import es.caib.translatorib.service.exception.CargaConfiguracionException;
import es.caib.translatorib.service.exception.MaxNumFilasException;
import es.caib.translatorib.service.model.Plugin;
import es.caib.translatorib.service.model.types.TypeModoAcceso;
import es.caib.translatorib.service.model.types.TypeNivelGravedad;
import es.caib.translatorib.service.service.PluginService;
import es.caib.translatorib.service.service.SessionService;
import org.primefaces.PrimeFaces;
import org.primefaces.event.SelectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.ejb.EJBException;
import javax.ejb.SessionBean;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;
import javax.inject.Inject;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

/**
 * Mantenimiento de entidades.
 *
 * @author Indra
 *
 */
@ManagedBean
@ViewScoped
public class ViewPlugins extends ViewControllerBase {

	private static final Logger LOG = LoggerFactory.getLogger(ViewPlugins.class);

	@EJB
	private PluginService	pluginService;


	/**
	 * Filtro (puede venir por parametro).
	 */
	private String filtro;

	/**
	 * Lista de datos.
	 */
	private List<Plugin> listaDatos;

	/**
	 * Dato seleccionado en la lista.
	 */
	private Plugin datoSeleccionado;



	/**
	 * Inicializacion.
	 */
	public void init() {
		// Control acceso
		UtilJSF.verificarAcceso();
		listaDatos = pluginService.lista(this.filtro);
	}

	/**
	 * Abre dialogo para consultar dato.
	 */
	public void consultar() {
		// Verifica si no hay fila seleccionada
		if (!verificarFilaSeleccionada())
			return;

		// Muestra dialogo
		final Map<String, String> params = new HashMap<String, String>();
		params.put(TypeParametroVentana.ID.toString(), String.valueOf(this.datoSeleccionado.getCodigo()));
		UtilJSF.openDialog(DialogPlugins.class, TypeModoAcceso.CONSULTA, params, true, 900, 650);
	}

	/**
	 * Abre dialogo para crear un nuevo plugin
	 */
	public void nuevo() {
		// Muestra dialogo
		final Map<String, String> params = new HashMap<String, String>();
		UtilJSF.openDialog(DialogPlugins.class, TypeModoAcceso.ALTA, params, true, 900, 650);
	}

	/**
	 * Abre dialogo para consultar dato.
	 */
	public void editar() {
		// Verifica si no hay fila seleccionada
		if (!verificarFilaSeleccionada()) {
			return;
		}

		// Muestra dialogo
		final Map<String, String> params = new HashMap<>();
		params.put(TypeParametroVentana.ID.toString(), String.valueOf(this.datoSeleccionado.getCodigo()));
		UtilJSF.openDialog(DialogPlugins.class, TypeModoAcceso.EDICION, params, true, 900, 650);
	}


	/**
	 * Abre dialogo para consultar dato.
	 */
	public void borrar() {

		// Verifica si no hay fila seleccionada
		if (!verificarFilaSeleccionada()) {
			return;
		}

		if (datoSeleccionado.isPorDefecto()) {
			UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, UtilJSF.getLiteral("warning.pluginPorDefecto"));
			return ;
		}
		pluginService.borrar(datoSeleccionado);
		datoSeleccionado = null;
		buscar();
		PrimeFaces.current().ajax().update("form:panelBotones");
		UtilJSF.addMessageContext(TypeNivelGravedad.INFO, UtilJSF.getLiteral("msg.eliminadoCorrecto"));
	}

	/**
	 * Retorno dialogo de editar/crear
	 *
	 * @param event respuesta dialogo
	 ***/
	public void returnDialogo(final SelectEvent event) {
		final DialogResult respuesta = (DialogResult) event.getObject();
		if (!respuesta.isCanceled()) {
			this.buscar();

			if(respuesta.getResult() != null) {
				this.datoSeleccionado = (Plugin) respuesta.getResult();
				final int indice = listaDatos.indexOf(datoSeleccionado);

				if(indice>=0) {
					listaDatos.set(indice, datoSeleccionado);
				}

				if(respuesta.getModoAcceso()==TypeModoAcceso.ALTA || respuesta.getModoAcceso()==TypeModoAcceso.EDICION) {
					UtilJSF.addMessageContext(TypeNivelGravedad.INFO, UtilJSF.getLiteral("msg.guardadoCorrecto"));
				}else if(respuesta.getModoAcceso()==TypeModoAcceso.CONSULTA) {
					UtilJSF.addMessageContext(TypeNivelGravedad.INFO, UtilJSF.getLiteral("msg.guardadoCorrecto"));
				}
			}
		}

	}


	/**
	 * Retorno dialogo confirmar
	 *
	 * @param event respuesta dialogo
	 ***/
	public void returnDialogoConfirmar(final SelectEvent event) {
		final DialogResult respuesta = (DialogResult) event.getObject();
		if (!respuesta.isCanceled()) {
			final Plugin plg = (Plugin) respuesta.getResult();
			final int indice = listaDatos.indexOf(datoSeleccionado);
			listaDatos.set(indice, plg);
			datoSeleccionado = plg;
		}
	}

	public void verificar() {
		// Verifica si no hay fila seleccionada
		if (!verificarFilaSeleccionada()) {
			return;
		}

		UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, UtilJSF.getLiteral("info.estado.noCambio"));

	}

	/**
	 * Método final que se encarga de realizar la búsqueda
	 */
	public void buscar() {
		// Filtra

		try {
			listaDatos = pluginService.lista(this.filtro);
		} catch (final EJBException e) {
			if (e.getCause() instanceof MaxNumFilasException) {
				UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, UtilJSF.getLiteral("warning.maxnumfilas"));
				return;
			} else {
				throw e;
			}

		}

		// Quitamos seleccion de dato
		datoSeleccionado = null;
	}

	private boolean verificarFilaSeleccionada() {
		boolean filaSeleccionada = true;
		if (this.datoSeleccionado == null) {
			UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, UtilJSF.getLiteral("warning.noseleccionadofila"));
			filaSeleccionada = false;
		}
		return filaSeleccionada;
	}

	/**
	 * Ayuda.
	 */
	public void ayuda() {
		UtilJSF.openHelp("plugins");
	}

	private Properties recuperarConfiguracionProperties() {
		final String pathProperties = System.getProperty("es.caib.translatorib.properties");
		try (FileInputStream fis = new FileInputStream(pathProperties);) {
			final Properties props = new Properties();
			props.load(fis);
			return props;
		} catch (final IOException e) {
			throw new CargaConfiguracionException(
					"Error al cargar la configuracion del properties '" + pathProperties + "' : " + e.getMessage(), e);
		}
	}

	private Date getToday() {
		final Calendar calendar = Calendar.getInstance();
		final int year = calendar.get(Calendar.YEAR);
		final int month = calendar.get(Calendar.MONTH);
		final int day = calendar.get(Calendar.DATE);
		calendar.set(year, month, day, 0, 0, 0);
		return calendar.getTime();
	}

	/**
	 * @return the filtro
	 */
	public String getFiltro() {
		return filtro;
	}

	/**
	 * @param filtro the filtro to set
	 */
	public void setFiltro(final String filtro) {
		this.filtro = filtro;
	}

	/**
	 * @return the listaDatos
	 */
	public List<Plugin> getListaDatos() {
		return listaDatos;
	}

	/**
	 * @param listaDatos the listaDatos to set
	 */
	public void setListaDatos(final List<Plugin> listaDatos) {
		this.listaDatos = listaDatos;
	}

	/**
	 * @return the datoSeleccionado
	 */
	public Plugin getDatoSeleccionado() {
		return datoSeleccionado;
	}

	/**
	 * @param datoSeleccionado the datoSeleccionado to set
	 */
	public void setDatoSeleccionado(final Plugin datoSeleccionado) {
		this.datoSeleccionado = datoSeleccionado;
	}


}
