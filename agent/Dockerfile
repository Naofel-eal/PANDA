FROM node:22-trixie

ENV HOME=/root
ENV PATH="/root/.bun/bin:/root/.local/bin:${PATH}"

WORKDIR /agent

RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    git \
    ripgrep \
    ca-certificates \
    curl \
    unzip \
  && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://bun.sh/install | bash \
  && ln -s /root/.bun/bin/bun /usr/local/bin/bun

RUN npm install -g opencode-ai

RUN curl -fsSL https://raw.githubusercontent.com/rtk-ai/rtk/master/install.sh | sh \
  && rtk --version \
  && rtk gain \
  && mkdir -p /root/.config/opencode/plugins \
  && rtk init -g --opencode \
  && rtk init --show

COPY package.json ./
COPY src/ src/
COPY opencode/ opencode/

EXPOSE 8081

CMD ["npm", "start"]
