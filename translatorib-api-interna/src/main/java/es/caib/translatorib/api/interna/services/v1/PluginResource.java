package es.caib.translatorib.api.interna.services.v1;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.security.RolesAllowed;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import es.caib.translatorib.api.v1.model.PluginDto;
import es.caib.translatorib.commons.utils.Constants;
import es.caib.translatorib.service.model.Plugin;
import es.caib.translatorib.service.service.PluginService;

/** Recurso REST para consultar los plugins de traduccion disponibles. */
@Stateless
@Path("plugins/v1")
@Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
@RolesAllowed({ Constants.TIB_API })
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class PluginResource {

    @Inject
    private PluginService pluginService;

    /** Lista los plugins configurados; devuelve [] cuando no hay resultados. */
    @GET
    @Operation(operationId = "listarPlugins", summary = "Lista los plugins configurados")
    @APIResponse(
            responseCode = "200",
            description = "Plugins disponibles (puede ser una lista vacia)",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = PluginDto[].class)
            )
    )
    @APIResponse(responseCode = "401", description = "No autenticado")
    @APIResponse(responseCode = "403", description = "Sin permisos para consultar los plugins")
    public Response listar() {
        final List<PluginDto> resultado = new ArrayList<PluginDto>();
        final List<Plugin> plugins = pluginService.lista(null);
        if (plugins != null) {
            for (final Plugin plugin : plugins) {
                if (plugin != null) {
                    resultado.add(PluginMapper.toDto(plugin));
                }
            }
        }
        return Response.ok(resultado).build();
    }
}
