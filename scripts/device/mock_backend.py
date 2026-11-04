import json,os,time
from http.server import BaseHTTPRequestHandler,HTTPServer
# Set MOCK_LOG=/path to append each POST body (one JSON per line); MOCK_FAST=1 to skip the streaming delay.
LOG=os.environ.get("MOCK_LOG"); DELAY=0 if os.environ.get("MOCK_FAST") else 1.2
TEXT="Hello there, this is a slow mock reply from the test server."
class H(BaseHTTPRequestHandler):
    def log_message(s,*a): print(s.path,flush=True)
    def do_GET(s):
        s.send_response(200);s.send_header("Content-Type","application/json");s.end_headers()
        s.wfile.write(json.dumps({"data":[{"id":"mock"}]}).encode())
    def do_POST(s):
        n=int(s.headers.get("Content-Length",0));body=s.rfile.read(n)
        if LOG: open(LOG,"a").write(body.decode("utf-8","replace").replace("\n"," ")+"\n")
        try: stream=json.loads(body).get("stream",True)
        except Exception: stream=True
        if not stream:
            s.send_response(200);s.send_header("Content-Type","application/json");s.end_headers()
            s.wfile.write(json.dumps({"choices":[{"message":{"role":"assistant","content":TEXT},"finish_reason":"stop"}]}).encode());return
        s.send_response(200);s.send_header("Content-Type","text/event-stream");s.end_headers()
        for w in TEXT.split():
            s.wfile.write(("data: "+json.dumps({"choices":[{"delta":{"content":w+" "}}]})+"\n\n").encode());s.wfile.flush();time.sleep(DELAY)
        s.wfile.write(b"data: [DONE]\n\n")
HTTPServer(("0.0.0.0",8765),H).serve_forever()
