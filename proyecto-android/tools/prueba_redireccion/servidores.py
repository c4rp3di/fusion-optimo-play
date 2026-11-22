import http.server, ssl, threading, sys
LISTA = b"#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"Cine\",Canal A\nhttp://x/a.ts\n#EXTINF:-1,Canal B\nhttp://x/b.ts\n"
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        ua = self.headers.get("User-Agent", "")
        if self.server.server_port == 8080:                      # http: redirige a https (y una vez más con ruta relativa)
            self.send_response(301); self.send_header("Location", "https://127.0.0.1:8443/php/salto?" + self.path.split("?",1)[-1]); self.end_headers(); return
        if self.path.startswith("/php/salto"):                   # https: otra redirección con ruta RELATIVA
            self.send_response(302); self.send_header("Location", "regioflix.m3u?" + self.path.split("?",1)[-1]); self.end_headers(); return
        if "VLC" not in ua and "solo-vlc" in self.path:          # simula un servidor que solo atiende a VLC
            self.send_response(403); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Type", "audio/x-mpegurl; charset=utf-8"); self.end_headers(); self.wfile.write(LISTA)
a = http.server.HTTPServer(("127.0.0.1", 8080), H)
b = http.server.HTTPServer(("127.0.0.1", 8443), H)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain("c.pem", "k.pem"); b.socket = ctx.wrap_socket(b.socket, server_side=True)
threading.Thread(target=a.serve_forever, daemon=True).start(); b.serve_forever()
