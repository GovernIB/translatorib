package es.caib.translatorib.backend.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;

/**
 * Transporte REST compartido por el backoffice. No tiene estado de usuario.
 * Toda respuesta SOAP/REST se maneja en el backend; este cliente solo realiza
 * HTTP a las rutas existentes, no sustituye el servicio REST por un EJB.
 *
 * OkHttp 3.14.x: Java 8+ y sin runtime Kotlin. Version fijada para este
 * parche, pendiente de revisión de dependencias/vulnerabilidades en el proyecto.
 */
public final class RestHttpCliente implements ServletContextListener {

    public static final int TIMEOUT_TOTAL_MS = 10_000;

    private static final ConnectionPool POOL =
            new ConnectionPool(2, 30, TimeUnit.SECONDS);
    private static final AtomicBoolean CERRADO = new AtomicBoolean(false);


    // Prueba de compatibilidad: evitar reanudacion TLS 1.3 en OpenJDK 11.0.2.
    // Se mantiene la validacion normal del certificado y del hostname.
    // CLEARTEXT solo mantiene compatibilidad con URLs http:// existentes;
    // las conexiones https:// quedan limitadas a TLS 1.2.
    private static final ConnectionSpec TLS_12 = new ConnectionSpec.Builder(
            ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build();

    private static final OkHttpClient CLIENTE = new OkHttpClient.Builder()
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectionSpecs(Arrays.asList(TLS_12, ConnectionSpec.CLEARTEXT))
            .connectTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_TOTAL_MS, TimeUnit.MILLISECONDS)
            .connectionPool(POOL)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build();

    public RestHttpCliente() { }

    /** Datos ya leídos antes de cerrar la respuesta y devolver el socket al pool. */
    public static final class Respuesta {
        private final int status;
        private final String body;
        private final String contentType;

        private Respuesta(int status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }

        public int statusCode() { return status; }
        public String body() { return body; }
        public String contentType() { return contentType; }
    }

    /**
     * Limite del ciclo completo DNS + conexión + petición + lectura de respuesta.
     * Las pausas globales de la JVM (GC) pueden retrasar también los timeouts.
     */
    public static Respuesta post(String url, String json, String authHeader,
                                 String trace, String capa) throws IOException {
        if (CERRADO.get()) {
            throw new IOException("Cliente HTTP cerrado por parada de aplicación");
        }

        RequestBody contenido = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"), json);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(contenido)
                .header("X-Translatorib-Trace", trace)
                .header("Content-Type", "application/json; charset=utf-8");
        if (authHeader != null && !authHeader.isEmpty()) {
            builder.header("Authorization", authHeader);
        }
        Request request = builder.build();
        Call call = CLIENTE.newCall(request);

        try (Response respuesta = call.execute()) {
            ResponseBody cuerpo = respuesta.body();
            String texto = cuerpo == null ? null : cuerpo.string();
            return new Respuesta(respuesta.code(), texto,
                    respuesta.header("Content-Type", "(no indicado)"));
        } catch (InterruptedIOException e) {
            call.cancel();
            HttpTimeoutException error = new HttpTimeoutException(
                    "Sin respuesta de la REST API en 10 segundos, trace=" + trace);
            error.initCause(e);
            throw error;
        } catch (IOException e) {
            call.cancel();
            throw e;
        }
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (CERRADO.compareAndSet(false, true)) {
            CLIENTE.dispatcher().cancelAll();
            POOL.evictAll();
            CLIENTE.dispatcher().executorService().shutdown();
        }
    }
}
