#!/usr/bin/env bash
# Runner wrapper for cancel_order.js
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node "$SCRIPT_DIR/cancel_order.js" "$@"
