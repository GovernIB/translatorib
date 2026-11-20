package es.caib.translatorib.backend.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puente REST asíncrono exclusivo del frontal JSF.
 * Mantiene REST hacia la API existente y nunca espera la respuesta en hilos Undertow.
 * Limita los trabajos pendientes del frontal; la API REST externa no cambia.
 */
@Singleton
@Startup
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class TraduccionRestAsincronaService {

    private static final Logger LOG = LoggerFactory.getLogger(TraduccionRestAsincronaService.class);
    private static final int MAX_PETICIONES = Integer.getInteger("translatorib.front.maxRestPending", 8);
    private static final int MAX_TRABAJOS_REGISTRADOS = Integer.getInteger("translatorib.front.maxRestStored", 32);
    private static final long CADUCIDAD_MS = TimeUnit.MINUTES.toMillis(4);
    private static final int MAX_RESPUESTA_CARACTERES = 1_000_000;

    private final HttpClient cliente = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Semaphore plazas = new Semaphore(MAX_PETICIONES, true);
    // También acotamos los resultados completados pendientes de recoger por JSF.
    private final Semaphore plazasRegistro = new Semaphore(MAX_TRABAJOS_REGISTRADOS, true);
    private final Map<String, Trabajo> trabajos = new ConcurrentHashMap<>();

    @PostConstruct
    public void inicializarDiagnostico() {
        LOG.info("[TRADUCIR REST] SINGLETON_INICIADO instancia={} maxEnVuelo={} maxRegistrados={} hilo={}",
                System.identityHashCode(this), MAX_PETICIONES, MAX_TRABAJOS_REGISTRADOS,
                Thread.currentThread().getName());
    }

    public String iniciar(String url, String authorization, String cuerpoJson) {
        if (!plazasRegistro.tryAcquire()) {
            LOG.warn("[TRADUCIR REST] RECHAZADO_REGISTRO registrados={} max={} enVuelo={}",
                    trabajos.size(), MAX_TRABAJOS_REGISTRADOS, MAX_PETICIONES - plazas.availablePermits());
            LOG.warn("[TRADUCIR FRONT REST] Resultados pendientes sin consumir; límite={}", MAX_TRABAJOS_REGISTRADOS);
            throw new IllegalStateException("Registro de resultados REST lleno");
        }
        if (!plazas.tryAcquire()) {
            plazasRegistro.release();
            LOG.warn("[TRADUCIR REST] RECHAZADO_ACTIVOS enVuelo={} max={} registrados={}",
                    MAX_PETICIONES - plazas.availablePermits(), MAX_PETICIONES, trabajos.size());
            LOG.warn("[TRADUCIR FRONT REST] Capacidad agotada; activos={} máximo={}",
                    MAX_PETICIONES - plazas.availablePermits(), MAX_PETICIONES);
            throw new IllegalStateException("Límite de peticiones REST del frontal alcanzado");
        }

        final Trabajo trabajo = new Trabajo(UUID.randomUUID().toString());
        trabajos.put(trabajo.id, trabajo);
        LOG.info("[TRADUCIR REST] ACEPTADO trabajo={} instancia={} enVuelo={} trabajosRegistrados={} jsonChars={} hilo={}",
                trabajo.id, System.identityHashCode(this), MAX_PETICIONES - plazas.availablePermits(),
                trabajos.size(), cuerpoJson == null ? 0 : cuerpoJson.length(),
                Thread.currentThread().getName());
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpoJson, StandardCharsets.UTF_8));
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }

            final long inicioNanos = System.nanoTime();
            CompletableFuture<HttpResponse<String>> futuro =
                    cliente.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            trabajo.futuro = futuro;
            futuro.whenComplete((respuesta, fallo) -> {
                try {
                    final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicioNanos);
                    if (fallo != null) {
                        trabajo.estado = Estado.error("Error de transporte REST: " + fallo.getClass().getSimpleName());
                        LOG.warn("[TRADUCIR REST] FINAL_ERROR trabajo={} duracionMs={} enVuelo={} causa={}",
                                trabajo.id, ms, MAX_PETICIONES - plazas.availablePermits(),
                                fallo.getClass().getSimpleName());
                        LOG.warn("[TRADUCIR FRONT REST] Finalizó con error en {} ms", ms, fallo);
                    } else if (respuesta.body() != null && respuesta.body().length() > MAX_RESPUESTA_CARACTERES) {
                        trabajo.estado = Estado.error("Respuesta REST demasiado grande");
                        LOG.warn("[TRADUCIR REST] FINAL_EXCESO_TAMANO trabajo={} duracionMs={} respuestaChars={}",
                                trabajo.id, ms, respuesta.body().length());
                        LOG.warn("[TRADUCIR FRONT REST] Respuesta descartada por tamaño ({} caracteres)", respuesta.body().length());
                    } else {
                        trabajo.estado = Estado.correcto(respuesta.statusCode(), respuesta.body());
                        LOG.info("[TRADUCIR REST] FINAL_OK trabajo={} duracionMs={} http={} respuestaChars={} enVuelo={}",
                                trabajo.id, ms, respuesta.statusCode(),
                                respuesta.body() == null ? 0 : respuesta.body().length(),
                                MAX_PETICIONES - plazas.availablePermits());
                        LOG.info("[TRADUCIR PERF] REST texto: {} ms, HTTP {}, frontal activos={}",
                                ms, respuesta.statusCode(), MAX_PETICIONES - plazas.availablePermits());
                    }
                } catch (Throwable inesperado) {
                    trabajo.estado = Estado.error("Error procesando resultado REST");
                    LOG.error("[TRADUCIR REST] FINAL_ERROR_INTERNO trabajo={} tipo={}",
                            trabajo.id, inesperado.getClass().getSimpleName());
                    LOG.error("[TRADUCIR FRONT REST] Error inesperado al completar", inesperado);
                } finally {
                    plazas.release();
                }
            });
            return trabajo.id;
        } catch (RuntimeException ex) {
            LOG.error("[TRADUCIR REST] ERROR_ENVIO trabajo={} instancia={}",
                    trabajo.id, System.identityHashCode(this), ex);
            if (trabajos.remove(trabajo.id, trabajo)) {
                plazasRegistro.release();
            }
            plazas.release();
            throw ex;
        }
    }

    /**
     * Las descargas de documentos siguen siendo HTTP síncronas por diseño,
     * con concurrencia limitada en ViewTraducir.
     */
    public HttpResponse<String> enviarDocumento(HttpRequest peticion)
            throws java.io.IOException, InterruptedException {
        return cliente.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** El resultado final se consume una sola vez y se elimina del registro. */
    public Estado consultar(String id) {
        if (id == null) {
            return Estado.error("No existe identificador de trabajo");
        }
        Trabajo trabajo = trabajos.get(id);
        if (trabajo == null) {
            LOG.warn("[TRADUCIR REST] CONSULTA_DESCONOCIDA trabajo={} trabajosRegistrados={}",
                    id, trabajos.size());
            return Estado.error("Trabajo caducado o cancelado");
        }
        trabajo.ultimoPollMs = System.currentTimeMillis();
        int consultas = trabajo.consultas.incrementAndGet();
        if (consultas == 1) {
            LOG.info("[TRADUCIR REST] PRIMER_POLL trabajo={} desdeInicioMs={}",
                    id, trabajo.ultimoPollMs - trabajo.creadoEn);
        }
        Estado estado = trabajo.estado;
        if (estado != null) {
            if (trabajos.remove(id, trabajo)) {
                plazasRegistro.release();
            }
            LOG.info("[TRADUCIR REST] ENTREGADO trabajo={} http={} error={} consultas={} duracionTotalMs={} restantes={}",
                    id, estado.getCodigoHttp(), estado.getError(), consultas,
                    System.currentTimeMillis() - trabajo.creadoEn, trabajos.size());
            return estado;
        }
        return Estado.PENDIENTE;
    }

    public void cancelar(String id) {
        Trabajo trabajo = trabajos.remove(id);
        if (trabajo != null) {
            LOG.warn("[TRADUCIR REST] CANCELADO trabajo={} terminado={} consultas={} edadMs={}",
                    id, trabajo.estado != null, trabajo.consultas.get(),
                    System.currentTimeMillis() - trabajo.creadoEn);
            plazasRegistro.release();
            if (trabajo.futuro != null) {
                trabajo.futuro.cancel(true);
            }
        }
    }

    /** Limpieza incluso si el usuario cierra el navegador sin completar el polling. */
    @Schedule(hour = "*", minute = "*", second = "*/30", persistent = false)
    public void limpiarCaducados() {
        long ahora = System.currentTimeMillis();
        if (!trabajos.isEmpty()) {
            LOG.info("[TRADUCIR REST] ESTADO_POOL singleton={} enVuelo={} plazasLibres={} trabajosRegistrados={} registrosLibres={}",
                    System.identityHashCode(this), MAX_PETICIONES - plazas.availablePermits(),
                    plazas.availablePermits(), trabajos.size(), plazasRegistro.availablePermits());
        }
        for (Map.Entry<String, Trabajo> entrada : trabajos.entrySet()) {
            Trabajo t = entrada.getValue();
            long edad = ahora - t.creadoEn;
            if (edad >= 15000L) {
                LOG.warn("[TRADUCIR REST] TRABAJO_DEMORADO trabajo={} edadMs={} terminado={} consultas={} ultimoPollHaceMs={}",
                        t.id, edad, t.estado != null, t.consultas.get(),
                        t.ultimoPollMs == 0L ? -1L : ahora - t.ultimoPollMs);
            }
            if (edad > CADUCIDAD_MS) {
                cancelar(entrada.getKey());
            }
        }
    }

    /**
     * Los @ViewScoped suelen limpiar el temporal en @PreDestroy.
     * Si el navegador se cierra sin cerrar la sesión, eliminamos archivos
     * huérfanos de más de una hora (la petición documental vence en 15 min).
     */
    @Schedule(hour = "*", minute = "*/10", second = "0", persistent = false)
    public void limpiarTemporalesAbandonados() {
        final Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        final long antesDe = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        try (DirectoryStream<Path> archivos =
                     Files.newDirectoryStream(tmp, "translatorib-vista-*.upload")) {
            for (Path archivo : archivos) {
                try {
                    if (Files.isRegularFile(archivo)
                            && Files.getLastModifiedTime(archivo).toMillis() < antesDe) {
                        Files.deleteIfExists(archivo);
                    }
                } catch (IOException ex) {
                    LOG.warn("[TRADUCIR FRONT] No se pudo limpiar un temporal antiguo", ex);
                }
            }
        } catch (IOException ex) {
            LOG.warn("[TRADUCIR FRONT] No se pudo revisar el directorio temporal", ex);
        }
    }

    @PreDestroy
    public void cerrar() {
        LOG.info("[TRADUCIR REST] SINGLETON_CERRANDO instancia={} trabajosRegistrados={}",
                System.identityHashCode(this), trabajos.size());
        for (String id : trabajos.keySet()) {
            cancelar(id);
        }
    }

    private static final class Trabajo {
        final String id;
        final long creadoEn = System.currentTimeMillis();
        volatile CompletableFuture<HttpResponse<String>> futuro;
        volatile Estado estado;
        volatile long ultimoPollMs;
        final AtomicInteger consultas = new AtomicInteger();
        Trabajo(String id) { this.id = id; }
    }

    public static final class Estado {
        static final Estado PENDIENTE = new Estado(false, 0, null, null);
        private final boolean finalizado;
        private final int codigoHttp;
        private final String cuerpo;
        private final String error;

        private Estado(boolean finalizado, int codigoHttp, String cuerpo, String error) {
            this.finalizado = finalizado;
            this.codigoHttp = codigoHttp;
            this.cuerpo = cuerpo;
            this.error = error;
        }

        static Estado correcto(int codigoHttp, String cuerpo) {
            return new Estado(true, codigoHttp, cuerpo, null);
        }

        static Estado error(String mensaje) {
            return new Estado(true, 0, null, mensaje);
        }

        public boolean isFinalizado() { return finalizado; }
        public int getCodigoHttp() { return codigoHttp; }
        public String getCuerpo() { return cuerpo; }
        public String getError() { return error; }
    }
}
