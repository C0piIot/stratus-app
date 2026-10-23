# A DLNA renderer for the conformance suite (stratus-app#153): gmrender-resurrect
# as Debian packages it, playing into sinks that discard what they are given, so
# a runner with no screen and no sound card can still be asked to play.
#
# Not a television, and not a claim about any of them -- what it proves is the
# chain: that a renderer finds nothing wrong with what this app says over
# SSDP and SOAP, and that the signed link it is handed is one it can fetch.
FROM debian:trixie-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        gmediarender gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 49494
# sync=true, or a sink that discards plays twenty seconds in a moment and the
# track is over before anybody can pause it.
ENTRYPOINT ["gmediarender", "--friendly-name", "Conformance", "--port", "49494", \
    "--gstout-audiosink", "fakesink sync=true", "--gstout-videosink", "fakesink sync=true", \
    "--logfile", "/dev/stdout"]
