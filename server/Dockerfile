FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Install base dependencies, OpenJDK 17, and Node.js
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    git \
    unzip \
    openjdk-17-jdk \
    nodejs \
    npm \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Detect JAVA_HOME path dynamically or set default
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Install Android SDK Command-line Tools
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d $ANDROID_SDK_ROOT/cmdline-tools && \
    mv $ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install basic platforms and build-tools
RUN yes | sdkmanager --licenses || true && \
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" || true

# Hardened security: SDK is read-only for build processes
RUN chmod -R a+rX $ANDROID_SDK_ROOT

# Create dedicated unprivileged 'builder' user (UID 1001)
RUN useradd -m -u 1001 -s /bin/bash builder && \
    mkdir -p /tmp/maya-builds /tmp/maya-artifacts /app && \
    chown -R builder:builder /tmp/maya-builds /tmp/maya-artifacts /app

WORKDIR /app
COPY --chown=builder:builder index.js /app/index.js

# Switch to unprivileged builder user - never run builds as root!
USER builder

EXPOSE 8080
ENV PORT=8080

CMD ["node", "/app/index.js"]
