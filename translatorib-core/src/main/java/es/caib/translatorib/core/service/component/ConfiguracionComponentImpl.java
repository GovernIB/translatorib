package es.caib.translatorib.core.service.component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.PostConstruct;

import es.caib.translatorib.core.api.exception.ErrorNoControladoException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import es.caib.translatorib.core.api.exception.ConfiguracionException;

@Component("configuracionComponent")
public class ConfiguracionComponentImpl implements ConfiguracionComponent {

    /** LOG **/
    private static final Logger LOG = LoggerFactory.getLogger(ConfiguracionComponentImpl.class);

    /**
     * Propiedades configuración especificadas en properties.
     */
    private Properties propiedadesLocales;

    /**
     * Directorio configuración.
     */
    private String directorioConf;

    @PostConstruct
    public void init() {
        final String pathProperties = System
                .getProperty("es.caib.translatorib.properties.path");
        propiedadesLocales = new Properties();

        // Carga fichero de propiedades
        if (pathProperties != null && !pathProperties.isEmpty()) {
            try (FileInputStream fis = new FileInputStream(pathProperties);) {
                propiedadesLocales.load(fis);
            } catch (final IOException e) {
                throw new ConfiguracionException(e);
            }
        }

        final String pathPropertiesSystem = System.getProperty("es.caib.translatorib.system.properties.path");
        final Properties propSystem = new Properties();
        if (pathPropertiesSystem != null && !pathPropertiesSystem.isEmpty()) {
            try (FileInputStream fis = new FileInputStream(pathPropertiesSystem);) {
                propSystem.load(fis);
            } catch (final IOException e) {
                throw new ConfiguracionException(e);
            }
        }
        propiedadesLocales.putAll(propSystem);

        // Obtiene directorio configuracion
        final File f = new File(
                System.getProperty("es.caib.translatorib.properties.path"));
        directorioConf = f.getParentFile().getAbsolutePath();
    }

    @Override
    public String obtenerDirectorioConfiguracion() {
        return directorioConf;
    }

    @Override
    public Set<String> getPropertiesNames() {
        return propiedadesLocales.stringPropertyNames();
    }

    @Override
    public String obtenerPropiedadConfiguracion(final String propiedad) {
        return readPropiedad(propiedad);
    }

    // ----------------------------------------------------------------------
    // FUNCIONES PRIVADAS
    // ----------------------------------------------------------------------

    private String createPlugin(String id) {

        return "";
    }

    /**
     * Lee propiedad.
     *
     * @param propiedad
     *            propiedad
     * @return valor propiedad (nulo si no existe)
     */
    @Override
    public String readPropiedad(final String propiedad) {
        // Busca primero en propiedades locales
        return propiedadesLocales.getProperty(propiedad);
    }

    /**
     * Se le pasa un valor y lo reemplaza si tiene placeholders.
     * @param valor Valor
     * @return Valor con placeholders reemplazados
     */
    @Override
    public String replacePlaceholders(final String valor) {
        String res = replacePlaceHolders(valor, false);
        res = replacePlaceHolders(res, true);
        return res;
    }

    /**
     * Reemplaza placeholders: sistema (${system.propiedad}) o configuración
     * (${config.propiedad).
     *
     * @param valor
     *                   Valor
     * @param system
     *                   Indica si es de sistema
     * @return Valor reemplazado.
     */
    private String replacePlaceHolders(final String valor, final boolean system) {
        String placeholder;

        if (system) {
            placeholder = "${system.";
        } else {
            placeholder = "${config.";
        }

        String res = valor;
        if (res != null) {
            int pos = valor.indexOf(placeholder);
            while (pos >= 0) {
                final int pos2 = res.indexOf("}", pos + 1);
                if (pos2 >= 0) {
                    final String propPlaceholder = res.substring(pos + placeholder.length(), pos2);
                    String valuePlaceholder = "";
                    if (system) {
                        valuePlaceholder = System.getProperty(propPlaceholder);
                    } else {
                        valuePlaceholder = readPropiedad(propPlaceholder);
                    }
                    valuePlaceholder = StringUtils.defaultString(valuePlaceholder);
                    if (valuePlaceholder.contains(placeholder)) {
                        throw new ErrorNoControladoException(
                                "Valor no válido para propiedad " + propPlaceholder + ": " + valuePlaceholder);
                    }
                    if (StringUtils.isBlank(valuePlaceholder)) {
                        LOG.warn("Placeholder {} tiene valor vacío", propPlaceholder);
                    }
                    res = StringUtils.replace(res, placeholder + propPlaceholder + "}", valuePlaceholder);
                }
                pos = res.indexOf(placeholder);
            }
        }
        return res;
    }

    /**
     * Lee propiedad.
     *
     * @param prefix Prefix
     * @return valor propiedad (nulo si no existe)
     */
    private Map<String, String> readPropiedades(final String prefix) {
        // Busca primero en propiedades locales
        final Map<String, String> props = new HashMap<>();
        for (final Object key : propiedadesLocales.keySet()) {
            if (key.toString().startsWith(prefix)) {
                props.put(key.toString().substring(prefix.length()),
                        propiedadesLocales.getProperty(key.toString()));
            }
        }
        return props;
    }

}
