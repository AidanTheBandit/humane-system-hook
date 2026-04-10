#!/usr/bin/env bash
# Generate Python gRPC stubs from .proto files.
# Run from the server/ directory.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV_PYTHON="$SCRIPT_DIR/.venv/bin/python3"
PROTO_DIR="$SCRIPT_DIR/proto"
OUT_DIR="$SCRIPT_DIR/generated"

if [ ! -x "$VENV_PYTHON" ]; then
  echo "Error: virtualenv not found at $SCRIPT_DIR/.venv/"
  echo "Create it with: python3 -m venv $SCRIPT_DIR/.venv && $SCRIPT_DIR/.venv/bin/pip install grpcio grpcio-tools protobuf"
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Find the well-known proto include path from grpc_tools
GRPC_PROTO_PATH="$($VENV_PYTHON -c 'import grpc_tools; import os; print(os.path.join(grpc_tools.__path__[0], "_proto"))')"

$VENV_PYTHON -m grpc_tools.protoc \
  -I "$PROTO_DIR" \
  -I "$GRPC_PROTO_PATH" \
  --python_out="$OUT_DIR" \
  --grpc_python_out="$OUT_DIR" \
  "$PROTO_DIR/humane/aibus/aibus.proto" \
  "$PROTO_DIR/humane/pushrelay/pushrelay.proto" \
  "$PROTO_DIR/humane/featureflags/featureflags.proto"

# Create __init__.py files for the generated package hierarchy
find "$OUT_DIR" -type d -exec touch {}/__init__.py \;

echo "Generated Python stubs in $OUT_DIR/"
echo "  humane/aibus/aibus_pb2.py"
echo "  humane/aibus/aibus_pb2_grpc.py"
echo "  humane/pushrelay/pushrelay_pb2.py"
echo "  humane/pushrelay/pushrelay_pb2_grpc.py"
echo "  humane/featureflags/featureflags_pb2.py"
echo "  humane/featureflags/featureflags_pb2_grpc.py"
