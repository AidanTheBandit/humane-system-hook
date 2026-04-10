#!/usr/bin/env python3
"""
Mock gRPC server for Humane AI Pin.

Implements:
  - AIBusService/Understand   — echoes the user's utterance back as a Respond action
  - PushRelayService/Subscribe — no-op bidi stream (holds connection open, prevents retry storm)
  - FeatureFlagsService/GetFlags — returns empty flag list

All other RPCs return UNIMPLEMENTED (grpcio default).
"""

import json
import logging
import signal
import sys
import time
import uuid
from concurrent import futures

import grpc

# Add generated stubs to path
sys.path.insert(0, "generated")

from humane.aibus import aibus_pb2, aibus_pb2_grpc
from humane.pushrelay import pushrelay_pb2, pushrelay_pb2_grpc
from humane.featureflags import featureflags_pb2, featureflags_pb2_grpc

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("mock-server")

# ---------------------------------------------------------------------------
# AIBusService
# ---------------------------------------------------------------------------

class AIBusServiceServicer(aibus_pb2_grpc.AIBusServiceServicer):

    def Understand(self, request, context):
        """
        Server-streaming RPC. Client sends one SynapseUnderstandingRequest,
        we stream back SynapseUnderstandingResponse messages.

        For the PoC, we extract the utterance and echo it back as a Respond action.
        The client only collects turns with action or observation content.
        After yielding, the stream completes (triggers onCompleted on the client).
        """
        utterance = request.utterance
        run_id = ""

        # Try to extract run ID from metadata
        metadata = dict(context.invocation_metadata())
        run_id = metadata.get("x-ai-mic-run-id", "unknown")

        log.info(">>> Understand  run_id=%s  utterance=%r", run_id, utterance)

        # Also log device context if present
        if request.HasField("device_context"):
            ctx = request.device_context
            log.info("    device_context: %d turns, is_locked=%s", len(ctx.turns), ctx.is_locked)

        if request.HasField("location"):
            loc = request.location
            log.info("    location: lat=%.6f lon=%.6f", loc.latitude, loc.longitude)

        # Build Respond action
        response_text = f"Echo: {utterance}"
        turn_id = str(uuid.uuid4())

        action_content = aibus_pb2.SynapseActionContent(
            thought="I should respond to the user",
            action="Respond",
            input=json.dumps({"Response": response_text}),
        )

        turn = aibus_pb2.SynapseChatTurn(
            user=aibus_pb2.ASSISTANT,
            identifier=turn_id,
            parent_identifier=run_id,
            action=action_content,
        )

        response = aibus_pb2.SynapseUnderstandingResponse(turn=turn)

        log.info("<<< Understand  responding with: %r", response_text)
        yield response
        # Stream completes here — client's onCompleted() fires


# ---------------------------------------------------------------------------
# PushRelayService
# ---------------------------------------------------------------------------

class PushRelayServiceServicer(pushrelay_pb2_grpc.PushRelayServiceServicer):

    def Subscribe(self, request_iterator, context):
        """
        Bidi streaming RPC. The client opens this at boot and keeps it alive.
        We accept the stream and hold it open without sending anything.
        This prevents the client from entering an infinite retry loop.
        """
        log.info(">>> PushRelay.Subscribe — connection opened")

        # Read client messages in the background (acks, subscription updates)
        try:
            for request in request_iterator:
                experiences = [e.experience_name for e in request.subscribed_experiences]
                log.info("    PushRelay.Subscribe received: acks=%s experiences=%s conn_type=%s",
                         list(request.acks), experiences, request.conn_type)
        except Exception as e:
            log.info("    PushRelay.Subscribe client disconnected: %s", e)

        log.info("<<< PushRelay.Subscribe — connection closed")

    def GetPushTokens(self, request, context):
        """Return empty token list."""
        log.info(">>> PushRelay.GetPushTokens  app_names=%s", list(request.app_names))
        return pushrelay_pb2.PushTokenResponse()


# ---------------------------------------------------------------------------
# FeatureFlagsService
# ---------------------------------------------------------------------------

class FeatureFlagsServiceServicer(featureflags_pb2_grpc.FeatureFlagsServiceServicer):

    def GetFlags(self, request, context):
        """Return empty feature flag list (all flags default to off/0)."""
        log.info(">>> FeatureFlags.GetFlags")
        return featureflags_pb2.DeviceFeatureFlagResponse()


# ---------------------------------------------------------------------------
# Server setup
# ---------------------------------------------------------------------------

def serve(port=9090):
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=10),
        options=[
            # Allow large messages (some requests include image data)
            ("grpc.max_receive_message_length", 50 * 1024 * 1024),
            ("grpc.max_send_message_length", 50 * 1024 * 1024),
        ],
    )

    aibus_pb2_grpc.add_AIBusServiceServicer_to_server(
        AIBusServiceServicer(), server
    )
    pushrelay_pb2_grpc.add_PushRelayServiceServicer_to_server(
        PushRelayServiceServicer(), server
    )
    featureflags_pb2_grpc.add_FeatureFlagsServiceServicer_to_server(
        FeatureFlagsServiceServicer(), server
    )

    listen_addr = f"0.0.0.0:{port}"
    server.add_insecure_port(listen_addr)
    server.start()

    log.info("=" * 60)
    log.info("Mock gRPC server listening on %s (plaintext)", listen_addr)
    log.info("Services:")
    log.info("  - humane.aibus.AIBusService/Understand       (echo)")
    log.info("  - humane.pushrelay.PushRelayService/Subscribe (no-op)")
    log.info("  - humane.pushrelay.PushRelayService/GetPushTokens (empty)")
    log.info("  - humane.featureflags.FeatureFlagsService/GetFlags (empty)")
    log.info("  - All other RPCs: UNIMPLEMENTED")
    log.info("=" * 60)

    def shutdown(signum, frame):
        log.info("Shutting down...")
        server.stop(grace=2)
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    server.wait_for_termination()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9090
    serve(port)
