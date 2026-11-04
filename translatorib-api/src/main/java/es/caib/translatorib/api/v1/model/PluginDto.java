package es.caib.translatorib.api.v1.model;

import java.io.Serializable;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Datos de un plugin expuestos a los consumidores de la API REST.
 * No se incluyen la clase de implementacion, credenciales ni propiedades internas.
 */
@Schema(name = "Plugin", description = "Plugin de traduccion disponible")
public class PluginDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "Codigo del plugin", example = "1")
    private Long codigo;

    @Schema(description = "Identificador del plugin", example = "opentrad")
    private String identificador;

    @Schema(description = "Descripcion del plugin")
    private String descripcion;

    @Schema(description = "Idiomas soportados por el plugin")
    private String idiomasPermitidos;

    @Schema(description = "Idiomas habilitados para el frontal")
    private String idiomasFrontal;

    @Schema(description = "Indica si es el plugin predeterminado")
    private boolean porDefecto;

    public PluginDto() {
    }

    public Long getCodigo() {
        return codigo;
    }

    public void setCodigo(Long codigo) {
        this.codigo = codigo;
    }

    public String getIdentificador() {
        return identificador;
    }

    public void setIdentificador(String identificador) {
        this.identificador = identificador;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getIdiomasPermitidos() {
        return idiomasPermitidos;
    }

    public void setIdiomasPermitidos(String idiomasPermitidos) {
        this.idiomasPermitidos = idiomasPermitidos;
    }

    public String getIdiomasFrontal() {
        return idiomasFrontal;
    }

    public void setIdiomasFrontal(String idiomasFrontal) {
        this.idiomasFrontal = idiomasFrontal;
    }

    public boolean isPorDefecto() {
        return porDefecto;
    }

    public void setPorDefecto(boolean porDefecto) {
        this.porDefecto = porDefecto;
    }
}
