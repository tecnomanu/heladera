# Imagen con todo adentro: el núcleo Node y los motores locales (whisper + piper).
# Si vas a usar STT/TTS remotos, borrá el bloque de python y la imagen baja a ~120 MB.
FROM node:22-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-venv python3-pip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json ./
COPY src ./src
COPY web ./web
COPY engines-py ./engines-py

ENV HELADERA_HOME=/data
RUN python3 -m venv /data/engines/venv \
    && /data/engines/venv/bin/pip install --no-cache-dir -q -r engines-py/requirements.txt

VOLUME /data
EXPOSE 8790
ENV HELADERA_PORT=8790 HELADERA_HOST=0.0.0.0
CMD ["node", "src/cli.js", "server"]
