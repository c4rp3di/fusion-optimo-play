import java.io.*; import java.net.*; import javax.net.ssl.*; import java.security.cert.X509Certificate;

public class Prueba {
    // --- Réplica de Red.abrir() (ListaM3u.kt): redirecciones a mano, también entre http y https
    static HttpURLConnection conexion(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua == null ? "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/124.0" : ua);
        return c;
    }
    static HttpURLConnection abrir(String url, String ua) throws Exception {
        String actual = url; int saltos = 0;
        while (true) {
            HttpURLConnection c = conexion(actual, ua);
            c.setInstanceFollowRedirects(false);
            int cod = c.getResponseCode();
            if (cod == 301 || cod == 302 || cod == 303 || cod == 307 || cod == 308) {
                String d = c.getHeaderField("Location"); c.disconnect();
                if (d == null || d.trim().isEmpty() || ++saltos > 8) throw new IOException("Demasiadas redirecciones");
                actual = new URL(new URL(actual), d.trim()).toString(); continue;
            }
            return c;
        }
    }
    static HttpURLConnection abrirLista(String url, String ua) throws Exception {
        HttpURLConnection c = abrir(url, ua);
        if ((c.getResponseCode() == 403 || c.getResponseCode() == 406) && ua == null) { c.disconnect(); return abrir(url, "VLC/3.0.20 LibVLC/3.0.20"); }
        return c;
    }
    static String leer(HttpURLConnection c) throws Exception { try (InputStream i = c.getInputStream()) { return new String(i.readAllBytes(), "UTF-8"); } }

    public static void main(String[] a) throws Exception {
        // Solo para la prueba: confiar en el certificado autofirmado del servidor local.
        TrustManager[] todo = { new X509TrustManager() { public void checkClientTrusted(X509Certificate[] x, String s) {} public void checkServerTrusted(X509Certificate[] x, String s) {} public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } } };
        SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, todo, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory()); HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

        String url = "http://127.0.0.1:8080/php/regioflix.m3u?usuario=regio36&clave=regio36";

        // 1) Comportamiento ANTERIOR: Red.conexion() con instanceFollowRedirects = true
        HttpURLConnection viejo = conexion(url, null);
        int cod = viejo.getResponseCode();
        String cuerpo = leer(viejo);
        boolean esLista = cuerpo.contains("#EXTINF");
        System.out.println("ANTES  (HttpURLConnection sigue solo): código=" + cod + "  ¿es lista? " + esLista + "  Location=" + viejo.getHeaderField("Location"));

        // 2) Comportamiento NUEVO: Red.abrir()
        HttpURLConnection nuevo = abrirLista(url, null);
        String txt = leer(nuevo);
        System.out.println("AHORA  (Red.abrir a mano):            código=" + nuevo.getResponseCode() + "  ¿es lista? " + txt.contains("#EXTINF") + "  URL final=" + nuevo.getURL());

        // 3) Servidor que solo atiende a VLC (403 con navegador): reintento como VLC
        String solo = "http://127.0.0.1:8080/php/regioflix.m3u?solo-vlc=1";
        HttpURLConnection c3 = abrirLista(solo, null);
        System.out.println("403→VLC: código=" + c3.getResponseCode() + "  ¿es lista? " + leer(c3).contains("#EXTINF"));

        if (esLista || !txt.contains("#EXTINF")) System.exit(1);
        System.out.println("OK");
    }
}
