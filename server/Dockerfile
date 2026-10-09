FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Install base dependencies, OpenJDK 17, Node.js, CMake & Ninja for native llama.cpp compilation
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    unzip \
    openjdk-17-jdk \
    nodejs \
    npm \
    build-essential \
    ninja-build \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Install Android SDK Command-line Tools
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools && \
    mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install Android SDK 34, Build-Tools 34.0.0, CMake 3.22.1, and NDK 26
RUN yes | sdkmanager --licenses > /dev/null || true && \
    sdkmanager --install \
      "platform-tools" \
      "platforms;android-34" \
      "build-tools;34.0.0" \
      "cmake;3.22.1" \
      "ndk;26.1.10909125"

# Hardened security: SDK is read-only for build processes
RUN chmod -R a+rX $ANDROID_SDK_ROOT

# Create dedicated unprivileged 'builder' user (UID 1001)
RUN useradd -m -u 1001 -s /usr/sbin/nologin builder && \
    mkdir -p /tmp/maya-builds /tmp/maya-artifacts /app && \
    chown -R builder:builder /tmp/maya-builds /tmp/maya-artifacts /app

WORKDIR /app
COPY --chown=builder:builder index.js /app/index.js

# Switch to unprivileged builder user - never run builds as root!
USER builder

EXPOSE 8080
ENV PORT=8080
ENV MAX_CONCURRENT_BUILDS=4
ENV BUILD_TIMEOUT_MS=300000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${PORT}/health || exit 1

CMD ["node", "/app/index.js"]
