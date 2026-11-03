package es.caib.translatorib.service.service;

import es.caib.translatorib.service.model.ParejaIdiomas;
import es.caib.translatorib.service.model.Plugin;

import java.util.List;

/**
 * Acceso a funciones invocadas desde el view de plugins.
 *
 * @author Indra
 *
 */
public interface PluginService {

	/**
	 * Lista plugins.
	 * @param filtro
	 */
	List<Plugin> lista(String filtro);

	/**
	 * Recupera plugin por Codigo.
	 *
	 * @param codigo codigo
	 * @return plugin
	 */
	Plugin getPluginByCodigo(Long codigo);

	/**
	 * Guardar plugin.
	 * @param plugin plugin
	 */
	void guardarPlugin(Plugin plugin);

	/**
	 * Actualizar plugin
	 * @param plugin plugin
	 */
	void updatePlugin(Plugin plugin);

	/**
	 * Obtener plugin por defecto
	 * @return El plugin
	 */
	Plugin getPluginByDefault();

	/**
	 * Actualizar codigo
	 * @param codigo Codigo plugin
	 * @param idiomasFrontal Idiomas del frontal
	 */
	void actualizarPlg(Long codigo, String idiomasFrontal);

	/**
	 * Borrar plugin
	 * @param plugin el plugin
	 */
    void borrar(Plugin plugin);

	/**
	 * Obtiene los pares de idiomas soportados por un plugin.
	 *
	 * @param codigo Código del plugin
	 * @return Pares de idiomas admitidos
	 */
	List<ParejaIdiomas> getParesIdiomasPermitidos(Long codigo);
}
