import json,time
from http.server import BaseHTTPRequestHandler,HTTPServer
class H(BaseHTTPRequestHandler):
    def log_message(s,*a): print(s.path,flush=True)
    def do_GET(s):
        s.send_response(200);s.send_header("Content-Type","application/json");s.end_headers()
        s.wfile.write(json.dumps({"data":[{"id":"mock"}]}).encode())
    def do_POST(s):
        n=int(s.headers.get("Content-Length",0));s.rfile.read(n)
        s.send_response(200);s.send_header("Content-Type","text/event-stream");s.end_headers()
        for w in "Hello there, this is a slow mock reply from the test server.".split():
            s.wfile.write(("data: "+json.dumps({"choices":[{"delta":{"content":w+" "}}]})+"\n\n").encode());s.wfile.flush();time.sleep(1.2)
        s.wfile.write(b"data: [DONE]\n\n")
HTTPServer(("0.0.0.0",8765),H).serve_forever()
