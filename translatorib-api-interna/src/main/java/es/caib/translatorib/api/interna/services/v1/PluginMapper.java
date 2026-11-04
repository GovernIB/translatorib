package es.caib.translatorib.api.interna.services.v1;

import es.caib.translatorib.api.v1.model.PluginDto;
import es.caib.translatorib.service.model.Idioma;
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
        // Solo cambia la representacion expuesta por REST; no modifica el modelo persistido.
        dto.setIdiomasPermitidos(formatearParejasIdiomas(plugin.getIdiomasPermitidos()));
        dto.setIdiomasFrontal(formatearParejasIdiomas(plugin.getIdiomasFrontal()));
        dto.setPorDefecto(plugin.isPorDefecto());
        return dto;
    }

    /** Convierte 'es es->ca ca_ES,es es->en en' a 'CASTELLANO:CATALAN_BALEAR,CASTELLANO:INGLES'. */
    private static String formatearParejasIdiomas(final String parejas) {
        if (parejas == null || parejas.trim().isEmpty()) {
            return parejas;
        }

        final String[] elementos = parejas.split(",", -1);
        final StringBuilder resultado = new StringBuilder();
        for (int i = 0; i < elementos.length; i++) {
            if (i > 0) {
                resultado.append(',');
            }

            final String pareja = elementos[i].trim();
            final String[] partes = pareja.split("->", -1);
            if (partes.length != 2) {
                resultado.append(pareja);
                continue;
            }

            final String origen = nombreIdioma(partes[0]);
            final String destino = nombreIdioma(partes[1]);
            if (origen == null || destino == null) {
                // Un valor desconocido no debe desaparecer ni convertirse incorrectamente.
                resultado.append(pareja);
            } else {
                resultado.append(origen).append(':').append(destino);
            }
        }
        return resultado.toString();
    }

    private static String nombreIdioma(final String valor) {
        final String normalizado = valor.trim().replaceAll("\\s+", " ");
        for (final Idioma idioma : Idioma.values()) {
            if (idioma.toString().equalsIgnoreCase(normalizado)
                    || idioma.name().equalsIgnoreCase(normalizado)) {
                return idioma.name();
            }
        }
        return null;
    }
}
