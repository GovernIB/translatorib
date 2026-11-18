package es.caib.translatorib.ejb.facade;

import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.ejb.Startup;

import es.caib.translatorib.plugin.api.plata.PlataPlugin;

/**
 * Cierra los clientes CXF compartidos por PlataPlugin al detener/desplegar
 * de nuevo la aplicacion EAR. La llamada REST existente no cambia.
 */
@Singleton
@Startup
public class PlataPoolLifecycleBean {

    @PreDestroy
    public void detener() {
        PlataPlugin.cerrarPools();
    }
}
