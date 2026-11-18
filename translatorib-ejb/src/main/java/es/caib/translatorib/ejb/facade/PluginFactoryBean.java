package es.caib.translatorib.ejb.facade;

import es.caib.translatorib.ejb.service.component.ConfiguracionComponent;
import es.caib.translatorib.persistence.repository.PluginRepository;
import es.caib.translatorib.plugin.api.ITraduccionPlugin;
import es.caib.translatorib.service.model.Plugin;
import es.caib.translatorib.service.model.comun.Propiedad;
import org.fundaciobit.pluginsib.core.IPlugin;
import org.fundaciobit.pluginsib.core.utils.PluginsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.LocalBean;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import java.util.Properties;

/**
 * Bean auxiliar para cargar la configuracion del plugin dentro de una transaccion
 * corta y devolver una instancia lista para ser usada fuera de ella.
 */
@Stateless
@LocalBean
@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class PluginFactoryBean {

    private static final Logger LOG = LoggerFactory.getLogger(PluginFactoryBean.class);

    @Inject
    ConfiguracionComponent configuracionComponent;

    @Inject
    private PluginRepository pluginDao;

    public ITraduccionPlugin createPlugin(final String identificador) {
        IPlugin plg = null;
        Plugin plugin = null;
        try {
            final Properties prop = new Properties();
            final String classname;
            if (identificador == null) {
                plugin = pluginDao.getPluginDefault();
            } else {
                plugin = pluginDao.getPluginByIdentificador(identificador);
            }
            classname = plugin.getClassname();
            if (plugin.getPropiedades() != null) {
                for (final Propiedad key : plugin.getPropiedades()) {
                    final String valor = configuracionComponent.replacePlaceholders(key.getValor());
                    prop.put(ITraduccionPlugin.TRANSLATOR_BASE_PROPERTY + plugin.getPrefijo() + key.getCodigo(), valor);
                }
            }

            plg = (IPlugin) PluginsManager.instancePluginByClassName(
                    classname,
                    ITraduccionPlugin.TRANSLATOR_BASE_PROPERTY,
                    prop);
        } catch (final Exception e) {
            LOG.error("Error obteniendo el plugin", e);
            if (identificador == null) {
                LOG.error("El identificador es nulo");
            } else if (plugin == null) {
                LOG.error("El jplugin es nulo");
            } else {
                LOG.error("El identificador NO es nulo {} {}", identificador, " ni el plugin ni el jplugin");
            }
        }
        return (ITraduccionPlugin) plg;
    }
}
