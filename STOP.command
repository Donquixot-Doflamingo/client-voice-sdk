#!/bin/bash
# Stops the Indic Voice laptop test server.
pkill -f indicvoice-proxy.jar && echo "Indic Voice server stopped." || echo "Server was not running."
sleep 2
