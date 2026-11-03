package es.caib.translatorib.backend.controller;

import es.caib.translatorib.backend.model.DialogResult;
import es.caib.translatorib.backend.util.UtilJSF;

import es.caib.translatorib.service.model.Idioma;
import es.caib.translatorib.service.model.ParejaIdiomas;
import es.caib.translatorib.service.model.Plugin;
import es.caib.translatorib.service.model.comun.Propiedad;
import es.caib.translatorib.service.model.types.TypeModoAcceso;
import es.caib.translatorib.service.model.types.TypeNivelGravedad;
import es.caib.translatorib.service.model.types.TypeParametroVentana;
import es.caib.translatorib.service.service.PluginService;
import es.caib.translatorib.service.util.UtilJSON;
import org.primefaces.PrimeFaces;
import org.primefaces.event.SelectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.EJB;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

@ManagedBean
@ViewScoped
public class DialogPlugins extends ViewControllerBase  {

    @EJB
    private PluginService pluginService;

    private static final Logger LOG = LoggerFactory.getLogger(DialogPlugins.class);

    private static final String TIPO_BOLETIN_PROPIEDAD = "tipoBoletin";

    private String id;

    private Plugin data;

    private Plugin dataOriginal;

    // Lista de todas las posibles combinaciones de idiomas (origen, destino)
    private List<ParejaIdiomas> todasLasCombinaciones;

    // Lista de combinaciones seleccionadas
    private List<ParejaIdiomas> combinacionesSeleccionadas;

    /**
     * Propiedad seleccionada.
     */
    private Propiedad propiedadSeleccionada;


    public void load() {
        LOG.debug("init");

        // Inicializar lista vacía para las combinaciones seleccionadas
        combinacionesSeleccionadas = new ArrayList<>();

        data = new Plugin();
        if (this.isAlta()) {
            data = new Plugin();
            data.setPropiedades(new ArrayList<>());
            data.setPorDefecto(false);
            dataOriginal = data.clone();
        } else if (this.isEdicion() || this.isConsulta()) {
            data = pluginService.getPluginByCodigo(Long.valueOf(id));
            dataOriginal = data.clone();
            if (data.getPropiedades() != null) {
                dataOriginal.setPropiedades(new ArrayList<>(data.getPropiedades()));
            }
        }

        //Cargamos todas las posibles combinaciones de parejas. (ahora viene por defecto en los plugins))
        if (data.getCodigo() != null) {
            todasLasCombinaciones =
                    pluginService.getParesIdiomasPermitidos(
                            data.getCodigo());
        } else {
            //Se pondrá vacía porque realmente, hay que implementar su XXXPLugin.java
            todasLasCombinaciones = new ArrayList<>();
        }

        if (data.getIdiomasPermitidos() != null
                && !data.getIdiomasPermitidos().isEmpty()) {

            cargarCombinacionesDesdeString(data.getIdiomasPermitidos());

            // Mantener únicamente los pares soportados por el plugin
            combinacionesSeleccionadas.retainAll(todasLasCombinaciones);
        }



    }



    public void guardar() {

        // Mantener únicamente los pares admitidos por el plugin
        combinacionesSeleccionadas.retainAll(todasLasCombinaciones);

        // IMPORTANTE: convertir la selección y guardarla en el DTO
        data.setIdiomasPermitidos(guardarCombinacionesComoString());

        // Ajustar los idiomas disponibles en el frontal
        if (data.getIdiomasFrontal() == null
                || data.getIdiomasFrontal().isEmpty()) {

            data.setIdiomasFrontal(data.getIdiomasPermitidos());

        } else {
            data.setIdiomasFrontal(
                    filtrarIdiomas(
                            data.getIdiomasFrontal(),
                            data.getIdiomasPermitidos()
                    )
            );
        }

        if (this.data.getCodigo() == null) {
            pluginService.guardarPlugin(this.data);
        } else {
            pluginService.updatePlugin(this.data);
        }

        final DialogResult result = new DialogResult();

        if (this.getModoAcceso() != null) {
            result.setModoAcceso(
                    TypeModoAcceso.valueOf(this.getModoAcceso())
            );
        } else {
            result.setModoAcceso(TypeModoAcceso.CONSULTA);
        }

        result.setResult(data);
        UtilJSF.closeDialog(result);
    }



    /**
     * Método para filtrar los idiomas de idiomasFrontal que no estén en idiomasPermitidos.
     *
     * @param idiomasFrontal       Los idiomas en el frontal (separados por comas).
     * @param idiomasPermitidos    Los idiomas permitidos (separados por comas).
     * @return Una cadena con los idiomas filtrados, separados por comas.
     */
    public static String filtrarIdiomas(String idiomasFrontal, String idiomasPermitidos) {
        // Convertimos ambas cadenas a listas
        List<String> listaIdiomasFrontal = Arrays.asList(idiomasFrontal.split(","));
        List<String> listaIdiomasPermitidos = Arrays.asList(idiomasPermitidos.split(","));

        // Filtramos los idiomas de idiomasFrontal que no estén en idiomasPermitidos
        List<String> idiomasFiltrados = listaIdiomasFrontal.stream()
                .filter(listaIdiomasPermitidos::contains)
                .collect(Collectors.toList());

        // Devolvemos los idiomas filtrados como una cadena separada por comas
        return String.join(",", idiomasFiltrados);
    }

    /**
     * Crea nueva propiedad.
     */
    public void nuevaPropiedad() {
        abrirPropiedad(TypeModoAcceso.ALTA, null);
    }

    /**
     * Edita una propiedad.
     */
    public void editarPropiedad() {

        if (!verificarFilaSeleccionada()) {
            return;
        }
        abrirPropiedad(TypeModoAcceso.EDICION, propiedadSeleccionada);
    }

    private void abrirPropiedad(TypeModoAcceso modo, Propiedad propiedad) {
        final Map<String, String> params = new HashMap<>();
        if (propiedad != null) {
            params.put(TypeParametroVentana.DATO.toString(), UtilJSON.toJSON(propiedad));
        }
        UtilJSF.openDialog(DialogPropiedad.class, modo, params, true, 730, 400);
    }

    /**
     * Quita una propiedad.
     */
    public void quitarPropiedad() {
        if (!verificarFilaSeleccionada()) {
            return;
        }

        this.data.getPropiedades().remove(this.propiedadSeleccionada);
    }

    /**
     * Baja la propiedad de posición.
     */
    public void bajarPropiedad() {
        if (!verificarFilaSeleccionada()) return;

        final int posicion = this.data.getPropiedades().indexOf(this.propiedadSeleccionada);
        if (posicion >= this.data.getPropiedades().size() - 1) {
            UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, "error mover abajo");
            return;
        }

        final Propiedad propiedad = this.data.getPropiedades().remove(posicion);
        this.data.getPropiedades().add(posicion + 1, propiedad);
    }

    /**
     * Sube la propiedad de posición.
     */
    public void subirPropiedad() {
        if (!verificarFilaSeleccionada()) return;

        final int posicion = this.data.getPropiedades().indexOf(this.propiedadSeleccionada);
        if (posicion <= 0) {
            UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, "error mover arriba");
            return;
        }

        final Propiedad propiedad = this.data.getPropiedades().remove(posicion);
        this.data.getPropiedades().add(posicion - 1, propiedad);
    }

    /**
     * Verifica si hay fila seleccionada.
     *
     * @return
     */
    private boolean verificarFilaSeleccionada() {
        boolean filaSeleccionada = true;

        if (this.propiedadSeleccionada == null) {
            UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, "error fila no seleccionada");
            filaSeleccionada = false;
        }
        return filaSeleccionada;
    }

    /**
     * Retorno dialogo de los botones de propiedades.
     *
     * @param event respuesta dialogo
     */
    public void returnDialogo(final SelectEvent event) {
        final DialogResult respuesta = (DialogResult) event.getObject();

        if (!respuesta.isCanceled()) {
            switch (respuesta.getModoAcceso()) {
                case ALTA:
                    // Refrescamos datos
                    final Propiedad propiedad = (Propiedad) respuesta.getResult();

                    boolean duplicado = false;

                    if (data.getPropiedades() == null) {
                        data.setPropiedades(new ArrayList<>());
                    }
                    for (final Propiedad prop : data.getPropiedades()) {
                        if (prop.getCodigo().equals(propiedad.getCodigo())) {
                            duplicado = true;
                            break;
                        }
                    }

                    if (duplicado) {
                        UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, "error duplicado");
                    } else {
                        this.data.getPropiedades().add(propiedad);
                    }

                    break;
                case EDICION:
                    // Actualizamos fila actual
                    final Propiedad propiedadEdicion = (Propiedad) respuesta.getResult();
                    // Muestra dialogo
                    final int posicion = this.data.getPropiedades().indexOf(this.propiedadSeleccionada);

                    boolean duplicadoEdicion = false;

                    for (final Propiedad prop : data.getPropiedades()) {
                        if (prop.getCodigo().equals(propiedadEdicion.getCodigo())) {
                            duplicadoEdicion = true;
                            break;
                        }
                    }

                    if (duplicadoEdicion && !propiedadSeleccionada.getCodigo().equals(propiedadEdicion.getCodigo())) {
                        UtilJSF.addMessageContext(TypeNivelGravedad.WARNING, "error duplicado");
                    } else {
                        this.data.getPropiedades().remove(posicion);
                        this.data.getPropiedades().add(posicion, propiedadEdicion);
                        this.propiedadSeleccionada = propiedadEdicion;
                    }
                    break;
                case CONSULTA:
                    // No hay que hacer nada
                    break;
            }
        }
    }



    public void cerrar() {
        if (data != null && dataOriginal != null && comprobarModificacion()) {
            PrimeFaces.current().executeScript("PF('confirmCerrar').show();");
        } else {
            cerrarDefinitivo();
        }
    }

    public boolean comprobarModificacion() {
        return false;
       // return UtilComparador.compareTo(data.getCodigo(), dataOriginal.getCodigo()) != 0 || UtilComparador.compareTo(data.getEntidad().getCodigo(), dataOriginal.getEntidad().getCodigo()) != 0 || UtilComparador.compareTo(data.getTipo(), dataOriginal.getTipo()) != 0 || UtilComparador.compareTo(data.getClassname(), dataOriginal.getClassname()) != 0 || UtilComparador.compareTo(data.getDescripcion(), dataOriginal.getDescripcion()) != 0 || !data.getPropiedades().equals(dataOriginal.getPropiedades()) || UtilComparador.compareTo(data.getPrefijoPropiedades(), dataOriginal.getPrefijoPropiedades()) != 0;

    }

    public void cerrarDefinitivo() {
        final DialogResult result = new DialogResult();
        if (Objects.isNull(this.getModoAcceso())) {
            this.setModoAcceso(TypeModoAcceso.CONSULTA.name());
        } else {
            result.setModoAcceso(TypeModoAcceso.valueOf(this.getModoAcceso()));
        }
        result.setCanceled(true);
        UtilJSF.closeDialog(result);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Plugin getData() {
        return data;
    }

    public void setData(Plugin data) {
        this.data = data;
    }

    public Propiedad getPropiedadSeleccionada() {
        return propiedadSeleccionada;
    }

    public void setPropiedadSeleccionada(Propiedad propiedadSeleccionada) {
        this.propiedadSeleccionada = propiedadSeleccionada;
    }

    public List<ParejaIdiomas> getCombinacionesSeleccionadas() {
        return combinacionesSeleccionadas;
    }

    public void setCombinacionesSeleccionadas(List<ParejaIdiomas> combinacionesSeleccionadas) {
        this.combinacionesSeleccionadas = combinacionesSeleccionadas;
    }

    public List<ParejaIdiomas> getTodasLasCombinaciones() {
        return todasLasCombinaciones;
    }

    public void setTodasLasCombinaciones(List<ParejaIdiomas> todasLasCombinaciones) {
        this.todasLasCombinaciones = todasLasCombinaciones;
    }

    // Método para convertir las combinaciones seleccionadas a un String
    public String guardarCombinacionesComoString() {
        return combinacionesSeleccionadas.stream()
                .map(pareja -> pareja.getOrigen().getIdioma() + " " + pareja.getOrigen().getLocale()
                        + "->" + pareja.getDestino().getIdioma() + " " + pareja.getDestino().getLocale())
                .collect(Collectors.joining(","));
    }

    // Método para cargar las combinaciones seleccionadas a partir de un String
    public void cargarCombinacionesDesdeString(String combinacionesString) {
        String[] combinacionesArray = combinacionesString.split(",");
        combinacionesSeleccionadas.clear(); // Limpiar la lista de seleccionados
        for (String combinacionStr : combinacionesArray) {
            String[] partes = combinacionStr.split("->");
            if (partes.length == 2) {
                String[] origenParts = partes[0].trim().split(" ");
                String[] destinoParts = partes[1].trim().split(" ");
                if (origenParts.length == 2 && destinoParts.length == 2) {
                    Idioma origen = Idioma.fromString(origenParts[0], origenParts[1]);
                    Idioma destino = Idioma.fromString(destinoParts[0], destinoParts[1]);
                    if (origen != null && destino != null) {
                        combinacionesSeleccionadas.add(new ParejaIdiomas(origen, destino));
                    }
                }
            }
        }
    }
}
