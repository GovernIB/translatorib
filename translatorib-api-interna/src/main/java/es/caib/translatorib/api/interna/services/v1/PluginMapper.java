package es.caib.translatorib.api.interna.services.v1;

import es.caib.translatorib.api.v1.model.PluginDto;
import es.caib.translatorib.service.model.Plugin;

/** Adapta el modelo de negocio al contrato de la API, sin exponer configuracion interna. */
final class PluginMapper {

    private PluginMapper() {
    }

    static PluginDto toDto(final Plugin plugin) {
        PluginDto dto = new PluginDto();
        dto.setCodigo(plugin.getCodigo());
        dto.setIdentificador(plugin.getIdentificador());
        dto.setDescripcion(plugin.getDescripcion());
        dto.setIdiomasPermitidos(plugin.getIdiomasPermitidos());
        dto.setIdiomasFrontal(plugin.getIdiomasFrontal());
        dto.setPorDefecto(plugin.isPorDefecto());
        return dto;
    }
}
