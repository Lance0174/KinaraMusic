"""Serve only this self-contained preview on the local machine. No extra dependencies."""
from argparse import ArgumentParser
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import webbrowser

parser = ArgumentParser(description="Open the local Kinara HTML design preview.")
parser.add_argument("--port", type=int, default=8766)
parser.add_argument("--open", action="store_true", help="Open the preview in the default browser")
args = parser.parse_args()
root = Path(__file__).resolve().parent
server = ThreadingHTTPServer(("127.0.0.1", args.port), partial(SimpleHTTPRequestHandler, directory=str(root)))
url = f"http://127.0.0.1:{args.port}/index.html"
print(f"Kinara HTML preview: {url}", flush=True)
if args.open:
    webbrowser.open(url)
try:
    server.serve_forever()
except KeyboardInterrupt:
    server.server_close()
