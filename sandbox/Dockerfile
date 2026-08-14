# SkillHub forkprobe sandbox runtime.
#
# A hardened, non-root image that runs the Claude Code CLI for skill
# comparisons. The Java-side DockerSandboxSkillExecutor launches this image via
# `docker run --read-only --cap-drop=ALL --user=65534:65534 ...`; the CLI inside
# only sees a read-only /skill mount plus a tmpfs /tmp, so a skill's tools
# (Bash/Read/Write/Edit) cannot touch the host.
FROM node:22-bookworm-slim

# Minimal runtime deps the CLI (and the skills it runs) may shell out to.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# Install the Claude Code CLI globally (provides the `claude` binary).
RUN npm install -g @anthropic-ai/claude-code

# The executor passes --user=65534:65534 at runtime; declare it here too so the
# image never runs as root even if launched outside the executor.
USER 65534:65534

WORKDIR /skill
