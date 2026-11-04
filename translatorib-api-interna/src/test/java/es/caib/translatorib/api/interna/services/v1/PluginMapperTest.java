package es.caib.translatorib.api.interna.services.v1;

import es.caib.translatorib.api.v1.model.PluginDto;
import es.caib.translatorib.service.model.Plugin;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginMapperTest {

    @Test
    public void convierteLosCamposPublicosSinExponerDatosInternos() {
        Plugin origen = new Plugin();
        origen.setCodigo(42L);
        origen.setIdentificador("mock");
        origen.setDescripcion("Plugin de prueba");
        origen.setIdiomasPermitidos("es,ca");
        origen.setIdiomasFrontal("ca");
        origen.setPorDefecto(true);
        origen.setClassname("secreto.ClaseInterna");
        origen.setPrefijo("configuracion_interna");

        PluginDto dto = PluginMapper.toDto(origen);
        assertEquals(Long.valueOf(42L), dto.getCodigo());
        assertEquals("mock", dto.getIdentificador());
        assertEquals("Plugin de prueba", dto.getDescripcion());
        assertEquals("es,ca", dto.getIdiomasPermitidos());
        assertEquals("ca", dto.getIdiomasFrontal());
        assertTrue(dto.isPorDefecto());
        assertFalse(tieneGetter("getClassname"));
        assertFalse(tieneGetter("getPrefijo"));
        assertFalse(tieneGetter("getPropiedades"));
    }

    private boolean tieneGetter(String nombre) {
        for (java.lang.reflect.Method method : PluginDto.class.getMethods()) {
            if (method.getName().equals(nombre)) {
                return true;
            }
        }
        return false;
    }
}
