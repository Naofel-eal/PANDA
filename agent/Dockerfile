FROM node:22-bookworm-slim

WORKDIR /agent

RUN apt-get update \
  && apt-get install -y --no-install-recommends git ripgrep ca-certificates curl unzip \
  && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://bun.sh/install | bash \
  && ln -s /root/.bun/bin/bun /usr/local/bin/bun

RUN npm install -g opencode-ai

COPY package.json ./
COPY src/ src/
COPY opencode/ opencode/

EXPOSE 8081

CMD ["npm", "start"]
