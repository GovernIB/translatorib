package es.caib.translatorib.ejb.service.component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.security.PermitAll;
import javax.ejb.Local;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import es.caib.translatorib.service.exception.ErrorNoControladoException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.caib.translatorib.service.exception.ConfiguracionException;


@Stateless(name = "ConfiguracionComponent")
@Local(ConfiguracionComponent.class)
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
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
                .getProperty("es.caib.translatorib.properties");
        propiedadesLocales = new Properties();

        // Carga fichero de propiedades
        if (pathProperties != null && !pathProperties.isEmpty()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(pathProperties);
                propiedadesLocales.load(fis);
            } catch (final IOException e) {
                throw new ConfiguracionException(e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        // Manejo opcional del error de cierre
                        e.printStackTrace();
                    }
                }
            }
        }

        final String pathPropertiesSystem = System.getProperty("es.caib.translatorib.system.properties");
        final Properties propSystem = new Properties();
        if (pathPropertiesSystem != null && !pathPropertiesSystem.isEmpty()) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(pathPropertiesSystem);
                propSystem.load(fis);
            } catch (final IOException e) {
                throw new ConfiguracionException(e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException e) {
                        // Manejo opcional del error de cierre
                        e.printStackTrace();
                    }
                }
            }
        }
        propiedadesLocales.putAll(propSystem);

        // Obtiene directorio configuracion
        final File f = new File(
                System.getProperty("es.caib.translatorib.properties"));
        directorioConf = f.getParentFile().getAbsolutePath();
    }

    @Override
    @PermitAll
    public String obtenerDirectorioConfiguracion() {
        return directorioConf;
    }

    @Override
    @PermitAll
    public Set<String> getPropertiesNames() {
        return propiedadesLocales.stringPropertyNames();
    }

    @Override
    @PermitAll
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
    @PermitAll
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
    @PermitAll
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
        final Map<String, String> props = new HashMap<String, String>();
        for (final Object key : propiedadesLocales.keySet()) {
            if (key.toString().startsWith(prefix)) {
                props.put(key.toString().substring(prefix.length()),
                        propiedadesLocales.getProperty(key.toString()));
            }
        }
        return props;
    }

}
